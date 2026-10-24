# Death Swap with fresh-seed rounds

This deployment uses the same shape as Linked Hardcore: one Velocity proxy and
2–64 Fabric 26.2 servers in Docker. Players use one address. The first
participant death ends that round and moves **everyone on that server** to an
empty, prepared backend with a different seed. Everyone starts the next round
with fresh world/player data, health, hunger, and inventory. The vacated backend
is reset while players build their next traps.

At least two players are required. The initial lobby starts automatically after
the configured countdown. Later rounds use the same countdown after the expected
participants arrive. Players still swap locations every five minutes by default. A disconnect
alone pauses the active match rather than generating another seed. Late joiners
observe until the next round. Ordinary server restarts preserve each world's
seed; only an authorized round reset changes it.

The standalone mod also has a 26.3 build. This deployment bundle and its
forwarding dependencies remain pinned to 26.2; see [COMPATIBILITY.md](COMPATIBILITY.md).

## Start

Install Docker Engine with the Compose v2 plugin (or Docker Desktop) and Python 3
on the host. The helper checks for `docker compose` v2 before rendering settings.
The image includes Java 25, Fabric Loader 0.19.3, Fabric API 0.154.2+26.2,
FabricProxy-Lite v2.12.0, the mod, and Velocity 4.0.0-6 with its proxy plugin.
The FabricProxy-Lite and Velocity downloads are checksum verified.

The [release downloads](https://github.com/evan6509/death-swap/releases) include
`death-swap-docker-VERSION+mc26.2.zip` with all sources needed by this setup.
Extract it before following these instructions. Its `.env.example` selects the
matching `DEATHSWAP_VERSION`; keep that value when copying to `.env` so the mod,
proxy, and Docker image use the same release version.

```sh
cp .env.example .env
python3 -c 'import secrets; print(secrets.token_hex(32))'
```

Edit `.env`: paste that random value into `FORWARDING_SECRET`, read the
[Minecraft EULA](https://aka.ms/MinecraftEULA), and set `EULA=TRUE` if you accept
it. The checked-in example does not accept it for you. Keep `.env` private.

```sh
./docker/compose.py up --build -d
./docker/compose.py logs -f proxy server-a server-b
```

Connect a vanilla **Minecraft 26.2** client to `localhost:25577`, or use the
Docker host's IP address and that port from another computer. Clients need no
Death Swap or proxy mod. Use an authenticated Minecraft account. Wait for a
backend to report ready, then have at least two players connect; the first
round starts automatically.

Use `./docker/compose.py` for every Compose command. It reads Compose's resolved
`.env` settings and expands `SERVER_COUNT` while retaining server IDs `a`, `b`,
through `z`, then `s27`, `s28`, and so on. Plain `docker compose` starts only the
checked-in default pair.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `SERVER_COUNT` | `2` | 2–64 Fabric backends; more add ready capacity |
| `PROXY_MEMORY` | `512M` | Proxy Java heap |
| `BACKEND_MEMORY` | `3G` | Java heap per backend |
| `MAX_PLAYERS` | `100` | 1–1024 players; same backend admission and proxy display limit |
| `SWAP_INTERVAL_SECONDS` | `300` | Location swap interval, 10–86400 seconds |
| `START_COUNTDOWN_SECONDS` | `5` | Initial and transferred-round countdown, 0–60 seconds |
| `VELOCITY_PORT` | `25577` | Published host port |
| `FORWARDING_SECRET` | Required | Same private random secret for proxy and backends |
| `EULA` | `FALSE` | Explicit acceptance required before a backend starts |

Two default backends reserve up to 6 GB of Java heap plus the proxy, world
generation, Docker, and native JVM overhead. Budget more than 6.5 GB of available
RAM. Increasing `SERVER_COUNT` adds another backend heap per server. Set the
interval to `10` for a quick trial, then recreate services with `up -d`.

Only the proxy publishes a host port. Backends stay on the private Compose
network and require Velocity modern forwarding with the shared secret. Proxy
authentication is enabled. Automatic fallback to an arbitrary backend is
disabled so failed transfers cannot send players to a used or resetting world.

## Data and reset behavior

The Compose volume `death-swap_deathswap-data` retains all service data:

- `server-a/`, `server-b/`, … contain each backend's world and config.
- `server-ID/world-generation.json` records the generation UUID and seed.
- `server-ID/config/deathswap/` holds the backend heartbeat and reset handshake.
- `proxy/plugins/deathswap-network/` holds the proxy's persisted transfer state.

The reset supervisor deletes only the owning backend's `world/`,
`world_nether/`, `world_the_end/`, `playerdata/`, `stats/`, and `advancements/`
directories, plus the old round's protocol state. It retains configuration,
operators, whitelist, bans, and every other service's data. Vanilla dimension
and player data nested in `world/` are deleted with that directory. A new random
seed is written explicitly and must differ from the previous seed.

A pending reset file is insufficient to delete anything. The current generation
must acknowledge the exact request while empty and rejecting new joins. The
supervisor saves a durable authorization receipt, stops the JVM, confirms its
exit, and then resets. A crash can resume that authorized reset; a stale request
or ordinary restart cannot wipe a world. Existing worlds without generation
metadata are refused rather than silently adopted. Full fields and state rules
are in [NETWORK_CONTRACT.md](NETWORK_CONTRACT.md).

```sh
./docker/compose.py ps
./docker/compose.py logs -f
./docker/compose.py down
./docker/compose.py up -d
```

`down` preserves the volume. Do not add `-v` unless you intend to delete **all**
the deployment's worlds and retained configuration. Stop the deployment before
backing up its volume. Keep at least one independent backup of anything you
want to retain; completed rounds are deliberately discarded.

If no empty ready backend exists, the round remains ended and the source world
is retained while the proxy waits and retries. A partial transfer likewise
retains the source until everyone has moved. Inspect both proxy and backend
logs if progress stops. Startup errors identify missing EULA acceptance,
invalid secrets/settings, or reset metadata that needs operator review.

## Verification

```sh
python3 docker/test_runtime.py
python3 docker/test_compose.py # Requires Compose v2; renders only, no containers
sh -n docker/entrypoint.sh
./gradlew --no-daemon build runGametest
python3 scripts/verify_mod_jar.py
python3 scripts/verify_proxy_jar.py
```

The Python checks simulate reset handshakes, stale/mismatched requests, crash
recovery, seed changes, retained config, symlink refusal, process shutdown, and
Compose expansion in disposable temporary directories. The separate Compose
check renders the real helper with default, `.env`, and shell settings, without
starting containers. These checks never reset a real server. GameTests validate
backend gameplay, and the proxy tests validate
its coordination rules. These are distinct from building/running containers
and joining them with real clients. On a Docker host, also check rendered
services with `./docker/compose.py config --services`, start the deployment,
join with two clients, die once, and confirm the group reaches a different seed
before the old backend returns ready.
