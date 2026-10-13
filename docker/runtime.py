#!/usr/bin/env python3
"""Container bootstrap and generation-correlated, crash-safe world reset.

Only this supervisor deletes worlds. A request alone is never authorization.
The Fabric process must acknowledge the same generation/request while empty
and terminally rejecting new joins, then the JVM must finish shutting down.
"""

import fcntl
import json
import os
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

SCHEMA = 1
STATUS_STALE_MS = 5_000
RESETTABLE_DIRS = ("world", "world_nether", "world_the_end", "playerdata", "stats", "advancements")
RESETTABLE_PROTOCOL = ("used.json", "control.json", "status.json")
ID_PATTERN = re.compile(r"[a-z][a-z0-9-]*")


def log(message):
    print(f"[deathswap] {message}", flush=True)


def read_json(path):
    if path.is_symlink():
        raise ValueError(f"Refusing protocol symlink: {path}")
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value


def sync_directory(path):
    directory = os.open(path, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def atomic_text(path, text, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError(f"Refusing output symlink: {path}")
    descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            os.fchmod(output.fileno(), mode)
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        # The receipt and generation file must survive a host/container crash.
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_json(path, value):
    atomic_text(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def valid_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        return False


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def valid_identity(identity, server_id):
    return (
        integer(identity.get("schemaVersion")) and identity.get("schemaVersion") == SCHEMA
        and identity.get("serverId") == server_id
        and valid_uuid(identity.get("generationId"))
        and integer(identity.get("seed"))
        and -(2 ** 63) <= identity["seed"] < 2 ** 63
    )


def fresh_identity(server_id, previous_seed=None):
    seed = secrets.randbits(64) - 2 ** 63
    while seed == previous_seed:
        seed = secrets.randbits(64) - 2 ** 63
    return {"schemaVersion": SCHEMA, "serverId": server_id,
            "generationId": str(uuid.uuid4()), "seed": seed}


def env_integer(env, key, default, minimum, maximum):
    value = env.get(key, str(default))
    if not re.fullmatch(r"[0-9]+", value) or not minimum <= int(value) <= maximum:
        raise ValueError(f"{key} must be {minimum}–{maximum}")
    return int(value)


@dataclass(frozen=True)
class RuntimeConfig:
    mode: str
    server_id: str
    backend_ids: tuple
    shared_root: Path
    install_dir: Path
    velocity_jar: Path
    plugin_jar: Path
    secret: str
    memory: str
    swap_interval: int
    start_countdown: int
    velocity_port: int
    java_bin: str
    shutdown_seconds: float = 30.0
    max_players: int = 100

    @classmethod
    def from_env(cls, env=None):
        env = os.environ if env is None else env
        mode = env.get("MODE", "server")
        if mode not in ("server", "proxy"):
            raise ValueError("MODE must be server or proxy")
        server_id = env.get("SERVER_ID", "a")
        ids = tuple(env.get("BACKEND_SERVER_IDS", "a,b").split(","))
        if not ID_PATTERN.fullmatch(server_id) or server_id == "try":
            raise ValueError("SERVER_ID must be a lowercase ID; 'try' is reserved")
        if not 2 <= len(ids) <= 64 or len(set(ids)) != len(ids) or any(
                not ID_PATTERN.fullmatch(item) or item == "try" for item in ids):
            raise ValueError("BACKEND_SERVER_IDS needs 2–64 unique lowercase IDs")
        secret = env.get("FORWARDING_SECRET", "")
        if len(secret) < 32 or any(character.isspace() for character in secret):
            raise ValueError("Set FORWARDING_SECRET to a private random string of at least 32 characters")
        if mode == "server" and env.get("EULA", "FALSE").upper() != "TRUE":
            raise ValueError("Read https://aka.ms/MinecraftEULA and set EULA=TRUE to accept it")
        memory = env.get("MC_MEMORY", "512M" if mode == "proxy" else "3G")
        if not re.fullmatch(r"[1-9][0-9]*[MmGg]", memory):
            raise ValueError("MC_MEMORY must be a JVM heap size such as 512M or 3G")
        shared_root = Path(env.get("SHARED_DIR", "/deathswap"))
        if not shared_root.is_absolute():
            raise ValueError("SHARED_DIR must be absolute")
        return cls(
            mode, server_id, ids, shared_root,
            Path(env.get("INSTALL_DIR", "/opt/deathswap/server")),
            Path(env.get("VELOCITY_JAR", "/opt/deathswap/velocity.jar")),
            Path(env.get("PLUGIN_JAR", "/opt/deathswap/death-swap-proxy.jar")),
            secret, memory,
            env_integer(env, "SWAP_INTERVAL_SECONDS", 300, 10, 86_400),
            env_integer(env, "START_COUNTDOWN_SECONDS", 5, 0, 60),
            env_integer(env, "VELOCITY_PORT", 25_577, 1, 65_535),
            env.get("JAVA_BIN", "java"),
            max_players=env_integer(env, "MAX_PLAYERS", 100, 1, 1_024),
        )


class BackendSupervisor:
    def __init__(self, config):
        self.config = config
        self.data_dir = config.shared_root / f"server-{config.server_id}"
        self.protocol_dir = self.data_dir / "config" / "deathswap"
        self.identity_file = self.data_dir / "world-generation.json"
        self.request_file = self.protocol_dir / "reset.request.json"
        self.receipt_file = self.protocol_dir / "reset.authorized.json"
        self.status_file = self.protocol_dir / "status.json"
        self.stop_requested = False
        self.process = None
        self.lock_file = None

    def acquire_lock(self):
        if self.data_dir.is_symlink():
            raise ValueError(f"Backend data directory must not be a symlink: {self.data_dir}")
        self.data_dir.mkdir(parents=True, exist_ok=True)
        if (self.data_dir / "config").is_symlink() or self.protocol_dir.is_symlink():
            raise ValueError("Backend config directory must not be a symlink")
        path = self.data_dir / ".supervisor.lock"
        if path.is_symlink():
            raise ValueError("Supervisor lock must not be a symlink")
        self.lock_file = path.open("a+")
        try:
            fcntl.flock(self.lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock_file.close()
            self.lock_file = None
            raise ValueError(f"Another supervisor already owns {self.data_dir}") from None

    def release_lock(self):
        if self.lock_file is not None:
            self.lock_file.close()
            self.lock_file = None

    def identity(self):
        if self.identity_file.exists():
            identity = read_json(self.identity_file)
            if not valid_identity(identity, self.config.server_id):
                raise ValueError(f"Invalid world identity: {self.identity_file}")
            return identity
        # Existing worlds without provenance are never silently adopted/reset.
        if any((self.data_dir / name).exists() or (self.data_dir / name).is_symlink()
               for name in RESETTABLE_DIRS):
            raise ValueError("Existing world has no world-generation.json; use a fresh dedicated volume")
        identity = fresh_identity(self.config.server_id)
        atomic_json(self.identity_file, identity)
        return identity

    def matching_request(self, request, identity):
        return (
            integer(request.get("schemaVersion")) and request.get("schemaVersion") == SCHEMA
            and request.get("serverId") == self.config.server_id
            and request.get("generationId") == identity["generationId"]
            and valid_uuid(request.get("requestId"))
            and integer(request.get("requestedAt"))
            and 0 <= request["requestedAt"] <= int(time.time() * 1000)
        )

    def reset_authorization(self, identity, now_ms=None):
        """Return a receipt only for the current JVM's fresh empty acknowledgment."""
        now_ms = int(time.time() * 1000) if now_ms is None else now_ms
        try:
            request = read_json(self.request_file)
            status = read_json(self.status_file)
        except (OSError, ValueError):
            return None
        updated_at = status.get("updatedAt")
        if not self.matching_request(request, identity) or not (
            integer(status.get("schemaVersion")) and status.get("schemaVersion") == SCHEMA
            and status.get("serverId") == self.config.server_id
            and status.get("generationId") == identity["generationId"]
            and valid_uuid(status.get("bootId"))
            and status.get("state") == "resetting"
            and integer(status.get("playerCount")) and status["playerCount"] == 0
            and status.get("onlinePlayers") == []
            and status.get("resetRequestId") == request["requestId"]
            and integer(updated_at)
            and 0 <= now_ms - updated_at <= STATUS_STALE_MS
            and request["requestedAt"] <= updated_at
        ):
            return None
        next_identity = fresh_identity(self.config.server_id, identity["seed"])
        return {
            **request, "seed": identity["seed"], "bootId": status["bootId"],
            "authorizedAt": now_ms, "nextGenerationId": next_identity["generationId"],
            "nextSeed": next_identity["seed"],
        }

    def validate_receipt(self, receipt):
        old = {key: receipt.get(key) for key in ("schemaVersion", "serverId", "generationId", "seed")}
        new = {**old, "generationId": receipt.get("nextGenerationId"), "seed": receipt.get("nextSeed")}
        if not (valid_identity(old, self.config.server_id) and valid_identity(new, self.config.server_id)
                and old["generationId"] != new["generationId"] and old["seed"] != new["seed"]
                and valid_uuid(receipt.get("requestId")) and valid_uuid(receipt.get("bootId"))
                and integer(receipt.get("requestedAt")) and integer(receipt.get("authorizedAt"))
                and 0 <= receipt["requestedAt"] <= receipt["authorizedAt"] <= int(time.time() * 1000)):
            raise ValueError(f"Invalid durable reset authorization: {self.receipt_file}")
        return old, new

    def wipe_world(self):
        # Validate every path first so a symlink cannot cause a partial deletion.
        paths = [self.data_dir / name for name in RESETTABLE_DIRS]
        for path in paths:
            if path.is_symlink() or (path.exists() and not path.is_dir()):
                raise ValueError(f"Resettable world path must be a real directory: {path}")
        for path in paths:
            if path.exists():
                log(f"resetting {self.config.server_id}: removing {path.name}")
                shutil.rmtree(path)

    def complete_reset(self, receipt, identity):
        """Idempotently finish an already authorized reset with no running JVM."""
        if self.process is not None and self.process.poll() is None:
            raise ValueError("Refusing to reset while the server process is running")
        old, new = self.validate_receipt(receipt)
        if identity == old:
            request = read_json(self.request_file)
            if any(request.get(field) != receipt.get(field) for field in
                   ("schemaVersion", "serverId", "generationId", "requestId", "requestedAt")):
                raise ValueError("Durable reset authorization does not match the pending request")
            self.wipe_world()
            # Used generation state lives outside world/. It must be removed
            # before new identity is committed, while no JVM can rewrite it.
            for name in RESETTABLE_PROTOCOL:
                path = self.protocol_dir / name
                if path.is_symlink():
                    raise ValueError(f"Refusing protocol symlink: {path}")
                path.unlink(missing_ok=True)
            sync_directory(self.protocol_dir)
            atomic_json(self.identity_file, new)
        elif identity != new:
            raise ValueError("Durable reset authorization belongs to another generation")
        # If identity already equals new, deletion has completed. Never wipe the
        # new world's files again after a crash between commit and cleanup.
        if self.request_file.exists():
            request = read_json(self.request_file)
            if request.get("requestId") != receipt["requestId"] or request.get("generationId") != old["generationId"]:
                raise ValueError("Pending reset request changed while completing reset")
            self.request_file.unlink()
        self.receipt_file.unlink(missing_ok=True)
        sync_directory(self.protocol_dir)
        log(f"backend {self.config.server_id}: fresh generation {new['generationId']}, seed {new['seed']}")
        return new

    def recover_reset(self, identity):
        if self.receipt_file.exists():
            return self.complete_reset(read_json(self.receipt_file), identity)
        # Deliberately leave an unacknowledged pending request for the backend.
        return identity

    def prepare_server(self, identity):
        if not self.config.install_dir.is_dir():
            raise ValueError(f"Missing baked Fabric install: {self.config.install_dir}")
        shutil.copytree(self.config.install_dir, self.data_dir, dirs_exist_ok=True)
        self.protocol_dir.mkdir(parents=True, exist_ok=True)
        atomic_text(self.data_dir / "eula.txt", "eula=true\n")
        properties = self.data_dir / "server.properties"
        lines = properties.read_text(encoding="utf-8").splitlines() if properties.exists() else []
        overrides = {
            "online-mode": "false", "server-port": "25565", "server-ip": "",
            "level-name": "world", "level-seed": str(identity["seed"]),
            "gamemode": "survival", "hardcore": "false", "force-gamemode": "true",
            "enforce-secure-profile": "false", "enable-rcon": "false",
            "max-players": str(self.config.max_players),
        }
        preserved = [line for line in lines if line.split("=", 1)[0].strip() not in overrides]
        atomic_text(properties, "\n".join(preserved + [f"{key}={value}" for key, value in overrides.items()]) + "\n")
        atomic_json(self.protocol_dir / "network.json", {
            "schemaVersion": SCHEMA, "enabled": True, "serverId": self.config.server_id,
            "generationId": identity["generationId"], "swapIntervalSeconds": self.config.swap_interval,
            "startCountdownSeconds": self.config.start_countdown,
        })
        if self.status_file.is_symlink():
            raise ValueError(f"Refusing protocol symlink: {self.status_file}")
        self.status_file.unlink(missing_ok=True)

    def request_stop(self, signum, frame):
        self.stop_requested = True
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()

    def stop_process(self):
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=self.config.shutdown_seconds)
        except subprocess.TimeoutExpired:
            log("server did not exit after TERM; sending KILL and waiting for confirmed exit")
            self.process.kill()
            # Wait is required even after KILL: deleting first races the JVM.
            self.process.wait(timeout=self.config.shutdown_seconds)

    def run(self):
        self.acquire_lock()
        previous_handlers = {number: signal.getsignal(number) for number in (signal.SIGTERM, signal.SIGINT)}
        try:
            identity = self.recover_reset(self.identity())
            self.prepare_server(identity)
            env = dict(os.environ)
            env.update({"FABRIC_PROXY_SECRET": self.config.secret,
                        "FABRIC_PROXY_HACK_ONLINE_MODE": "true", "FABRIC_PROXY_HACK_MESSAGE_CHAIN": "true"})
            for number in previous_handlers:
                signal.signal(number, self.request_stop)
            log(f"starting Fabric backend {self.config.server_id}, generation {identity['generationId']}")
            self.process = subprocess.Popen(
                [self.config.java_bin, f"-Xmx{self.config.memory}", "-jar", "fabric-server-launch.jar", "nogui"],
                cwd=self.data_dir, env=env,
            )
            while not self.stop_requested:
                code = self.process.poll()
                if code is not None:
                    return code
                receipt = self.reset_authorization(identity)
                if receipt is not None:
                    atomic_json(self.receipt_file, receipt)
                    log(f"backend {self.config.server_id}: acknowledged empty reset {receipt['requestId']}")
                    self.stop_process()
                    if not self.stop_requested:
                        self.complete_reset(receipt, identity)
                    # Compose restarts the same backend into its fresh seed.
                    return 0
                time.sleep(0.5)
            self.stop_process()
            return 0
        finally:
            if self.process is not None and self.process.poll() is None:
                self.stop_process()
            for number, previous in previous_handlers.items():
                signal.signal(number, previous)
            self.release_lock()


def prepare_proxy(config):
    proxy_dir = config.shared_root / "proxy"
    if proxy_dir.is_symlink():
        raise ValueError("Proxy data directory must not be a symlink")
    plugins = proxy_dir / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    shutil.copy2(config.velocity_jar, proxy_dir / "velocity.jar")
    shutil.copy2(config.plugin_jar, plugins / "death-swap-proxy.jar")
    atomic_text(proxy_dir / "forwarding.secret", config.secret + "\n")
    servers = "\n".join(f'{server_id} = "server-{server_id}:25565"' for server_id in config.backend_ids)
    fallback = json.dumps(list(config.backend_ids))
    atomic_text(proxy_dir / "velocity.toml", f'''# Generated by docker/runtime.py.
config-version = "2.8"
bind = "0.0.0.0:{config.velocity_port}"
motd = "<#09add3>Death Swap — fresh seeds every round"
show-max-players = {config.max_players}
online-mode = true
force-key-authentication = true
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
sample-players-in-ping = false
enable-player-address-logging = false

[servers]
{servers}
try = {fallback}

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = false
show-ping-requests = false
failover-on-unexpected-server-disconnect = false
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false
enable-reuse-port = false

[query]
enabled = false
port = {config.velocity_port}
map = "Death Swap"
show-plugins = false
''')
    atomic_json(plugins / "deathswap-network" / "config.json", {
        "sharedRoot": str(config.shared_root),
        "backendServers": {server_id: {
            "serverId": server_id,
            "statusFile": str(config.shared_root / f"server-{server_id}" / "config" / "deathswap" / "status.json"),
        } for server_id in config.backend_ids},
        "swapIntervalSeconds": config.swap_interval, "statusStaleSeconds": STATUS_STALE_MS // 1000,
    })
    return proxy_dir


def main():
    try:
        config = RuntimeConfig.from_env()
        if config.mode == "server":
            return BackendSupervisor(config).run()
        proxy_dir = prepare_proxy(config)
        log(f"starting online-authenticated Velocity on port {config.velocity_port}")
        os.chdir(proxy_dir)
        os.execvp(config.java_bin, [config.java_bin, f"-Xmx{config.memory}", "-jar", "velocity.jar"])
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        log(f"startup/reset refused: {error}")
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
