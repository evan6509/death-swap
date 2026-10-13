#!/usr/bin/env python3
"""All reset checks run against temporary directories and disposable processes."""

import copy
import json
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from dataclasses import replace
from pathlib import Path
from unittest import mock

import runtime
import compose


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="death-swap-runtime-test-")
        self.root = Path(self.temp.name)
        install = self.root / "install"
        (install / "mods").mkdir(parents=True)
        (install / "fabric-server-launch.jar").write_text("fixture")
        (install / "mods" / "death-swap.jar").write_text("fixture")
        self.env = {"MODE": "server", "SERVER_ID": "a", "EULA": "TRUE",
                    "FORWARDING_SECRET": "private-test-secret-" + "1" * 40,
                    "SHARED_DIR": str(self.root / "data"), "INSTALL_DIR": str(install)}
        self.config = runtime.RuntimeConfig.from_env(self.env)
        self.supervisor = runtime.BackendSupervisor(self.config)
        self.supervisor.data_dir.mkdir(parents=True)
        self.identity = self.supervisor.identity()
        self.supervisor.protocol_dir.mkdir(parents=True)

    def tearDown(self):
        if self.supervisor.process is not None and self.supervisor.process.poll() is None:
            self.supervisor.process.kill()
            self.supervisor.process.wait()
        self.supervisor.release_lock()
        self.temp.cleanup()

    def handshake(self, **status_changes):
        now = int(time.time() * 1000)
        request = {"schemaVersion": 1, "serverId": "a", "generationId": self.identity["generationId"],
                   "requestId": str(uuid.uuid4()), "requestedAt": now - 1_000}
        status = {"schemaVersion": 1, "serverId": "a", "generationId": self.identity["generationId"],
                  "bootId": str(uuid.uuid4()), "state": "resetting", "playerCount": 0,
                  "onlinePlayers": [], "updatedAt": now, "resetRequestId": request["requestId"]}
        status.update(status_changes)
        runtime.atomic_json(self.supervisor.request_file, request)
        runtime.atomic_json(self.supervisor.status_file, status)
        return request, status, now

    def world(self):
        (self.supervisor.data_dir / "world" / "playerdata").mkdir(parents=True)
        (self.supervisor.data_dir / "world" / "playerdata" / "player.dat").write_text("old inventory")
        (self.supervisor.data_dir / "ops.json").write_text("operator config")
        (self.supervisor.protocol_dir / "used.json").write_text("old round")

    def test_requires_explicit_eula_and_secret(self):
        for change in ({"EULA": "FALSE"}, {"FORWARDING_SECRET": ""},
                       {"FORWARDING_SECRET": "x" * 31}, {"FORWARDING_SECRET": "x" * 32 + "\n"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                runtime.RuntimeConfig.from_env({**self.env, **change})

    def test_rejects_bad_ids_memory_and_intervals(self):
        for change in ({"SERVER_ID": "../world"}, {"BACKEND_SERVER_IDS": "a,a"},
                       {"BACKEND_SERVER_IDS": "a,try"}, {"BACKEND_SERVER_IDS": "a"},
                       {"BACKEND_SERVER_IDS": ",".join(f"s{index}" for index in range(65))},
                       {"MC_MEMORY": "3G -Dother"}, {"SWAP_INTERVAL_SECONDS": "9"},
                       {"START_COUNTDOWN_SECONDS": "61"}, {"SHARED_DIR": "relative"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                runtime.RuntimeConfig.from_env({**self.env, **change})

    def test_pending_request_never_wipes_at_boot(self):
        self.world()
        self.handshake()
        recovered = self.supervisor.recover_reset(self.identity)
        self.assertEqual(self.identity, recovered)
        self.assertTrue((self.supervisor.data_dir / "world" / "playerdata" / "player.dat").exists())
        self.assertTrue(self.supervisor.request_file.exists())

    def test_identity_survives_ordinary_restarts_and_seed_is_explicit(self):
        self.world()
        self.assertEqual(self.identity, self.supervisor.identity())
        self.supervisor.prepare_server(self.identity)
        properties = (self.supervisor.data_dir / "server.properties").read_text()
        self.assertIn(f"level-seed={self.identity['seed']}\n", properties)
        self.assertIn("online-mode=false\n", properties)
        self.assertIn("gamemode=survival\n", properties)
        network = runtime.read_json(self.supervisor.protocol_dir / "network.json")
        self.assertEqual(self.identity["generationId"], network["generationId"])
        self.assertTrue((self.supervisor.data_dir / "world" / "playerdata" / "player.dat").exists())

    def test_unidentified_existing_world_is_refused(self):
        self.world()
        self.supervisor.identity_file.unlink()
        with self.assertRaisesRegex(ValueError, "Existing world"):
            self.supervisor.identity()
        self.assertTrue((self.supervisor.data_dir / "world").is_dir())

    def test_requires_exact_fresh_empty_acknowledgment(self):
        _, _, now = self.handshake()
        self.assertIsNotNone(self.supervisor.reset_authorization(self.identity, now))
        bad_status = [
            {"playerCount": 1}, {"playerCount": False}, {"onlinePlayers": [str(uuid.uuid4())]},
            {"state": "ended"}, {"state": "ready"}, {"state": "stopped"},
            {"generationId": str(uuid.uuid4())}, {"serverId": "b"}, {"schemaVersion": 2},
            {"schemaVersion": True}, {"updatedAt": now - 5_001}, {"updatedAt": now + 60_000},
            {"resetRequestId": str(uuid.uuid4())}, {"bootId": "not-a-uuid"},
        ]
        for changed in bad_status:
            with self.subTest(changed=changed):
                self.handshake(**changed)
                self.assertIsNone(self.supervisor.reset_authorization(self.identity, int(time.time() * 1000)))

    def test_stale_generation_request_rejected(self):
        request, status, now = self.handshake()
        request["generationId"] = str(uuid.uuid4())
        runtime.atomic_json(self.supervisor.request_file, request)
        self.assertIsNone(self.supervisor.reset_authorization(self.identity, now))

    def test_malformed_missing_or_symlink_protocol_refused(self):
        self.handshake()
        self.supervisor.request_file.write_text("not JSON")
        self.assertIsNone(self.supervisor.reset_authorization(self.identity))
        self.supervisor.request_file.unlink()
        self.assertIsNone(self.supervisor.reset_authorization(self.identity))
        target = self.root / "request-outside.json"
        target.write_text("{}")
        self.supervisor.request_file.symlink_to(target)
        self.assertIsNone(self.supervisor.reset_authorization(self.identity))

    def test_authorized_reset_changes_seed_and_preserves_operator_config(self):
        self.world()
        _, _, now = self.handshake()
        receipt = self.supervisor.reset_authorization(self.identity, now)
        runtime.atomic_json(self.supervisor.receipt_file, receipt)
        new = self.supervisor.recover_reset(self.identity)
        self.assertNotEqual(self.identity["seed"], new["seed"])
        self.assertNotEqual(self.identity["generationId"], new["generationId"])
        self.assertFalse((self.supervisor.data_dir / "world").exists())
        self.assertFalse((self.supervisor.protocol_dir / "used.json").exists())
        self.assertEqual("operator config", (self.supervisor.data_dir / "ops.json").read_text())
        self.assertFalse(self.supervisor.request_file.exists())
        self.assertFalse(self.supervisor.receipt_file.exists())

    def test_reset_refused_while_real_process_alive(self):
        self.world()
        self.handshake()
        receipt = self.supervisor.reset_authorization(self.identity)
        self.supervisor.process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        with self.assertRaisesRegex(ValueError, "process is running"):
            self.supervisor.complete_reset(receipt, self.identity)
        self.assertTrue((self.supervisor.data_dir / "world").exists())
        self.supervisor.stop_process()
        self.assertIsNotNone(self.supervisor.process.poll())
        self.supervisor.complete_reset(receipt, self.identity)
        self.assertFalse((self.supervisor.data_dir / "world").exists())

    def test_hung_process_killed_and_confirmed_before_reset(self):
        self.supervisor.config = replace(self.config, shutdown_seconds=0.05)
        self.supervisor.process = subprocess.Popen([
            sys.executable, "-c",
            "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); print('ready',flush=True); time.sleep(30)",
        ], stdout=subprocess.PIPE, text=True)
        self.assertEqual("ready", self.supervisor.process.stdout.readline().strip())
        self.supervisor.stop_process()
        self.assertEqual(-signal.SIGKILL, self.supervisor.process.returncode)
        self.supervisor.process.stdout.close()

    def test_changed_request_or_foreign_receipt_never_wipes(self):
        self.world()
        request, _, now = self.handshake()
        receipt = self.supervisor.reset_authorization(self.identity, now)
        runtime.atomic_json(self.supervisor.receipt_file, receipt)
        request["requestId"] = str(uuid.uuid4())
        runtime.atomic_json(self.supervisor.request_file, request)
        with self.assertRaisesRegex(ValueError, "pending request"):
            self.supervisor.recover_reset(self.identity)
        foreign = dict(self.identity, generationId=str(uuid.uuid4()))
        with self.assertRaisesRegex(ValueError, "another generation"):
            self.supervisor.complete_reset(receipt, foreign)
        self.assertTrue((self.supervisor.data_dir / "world").exists())

    def test_crash_after_generation_commit_does_not_wipe_new_world(self):
        self.handshake()
        receipt = self.supervisor.reset_authorization(self.identity)
        runtime.atomic_json(self.supervisor.receipt_file, receipt)
        _, new = self.supervisor.validate_receipt(receipt)
        runtime.atomic_json(self.supervisor.identity_file, new)
        self.world()
        recovered = self.supervisor.recover_reset(new)
        self.assertEqual(new, recovered)
        self.assertTrue((self.supervisor.data_dir / "world" / "playerdata" / "player.dat").exists())
        self.assertFalse(self.supervisor.receipt_file.exists())

    def test_interrupted_deletion_recovers_before_new_generation(self):
        self.world()
        (self.supervisor.data_dir / "stats").mkdir()
        self.handshake()
        receipt = self.supervisor.reset_authorization(self.identity)
        runtime.atomic_json(self.supervisor.receipt_file, receipt)
        original = runtime.shutil.rmtree
        calls = 0

        def fail_after_first(path):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("simulated interruption")
            original(path)

        with mock.patch.object(runtime.shutil, "rmtree", side_effect=fail_after_first):
            with self.assertRaises(OSError):
                self.supervisor.recover_reset(self.identity)
        self.assertEqual(self.identity, runtime.read_json(self.supervisor.identity_file))
        self.assertTrue(self.supervisor.receipt_file.exists())
        new = self.supervisor.recover_reset(self.identity)
        self.assertNotEqual(self.identity["generationId"], new["generationId"])
        self.assertFalse((self.supervisor.data_dir / "stats").exists())

    def test_symlink_world_never_deletes_outside_fixture(self):
        external = self.root / "outside-world"
        external.mkdir()
        (external / "keep.dat").write_text("retain")
        (self.supervisor.data_dir / "world").symlink_to(external, target_is_directory=True)
        self.handshake()
        receipt = self.supervisor.reset_authorization(self.identity)
        with self.assertRaisesRegex(ValueError, "real directory"):
            self.supervisor.complete_reset(receipt, self.identity)
        self.assertTrue((external / "keep.dat").exists())

    def test_exclusive_supervisor_lock(self):
        self.supervisor.acquire_lock()
        other = runtime.BackendSupervisor(self.config)
        with self.assertRaisesRegex(ValueError, "Another supervisor"):
            other.acquire_lock()
        self.supervisor.release_lock()
        other.acquire_lock()
        other.release_lock()

    def test_proxy_uses_online_auth_private_routes_and_secret_permissions(self):
        velocity = self.root / "velocity.jar"
        plugin = self.root / "plugin.jar"
        velocity.write_text("fixture")
        plugin.write_text("fixture")
        config = replace(self.config, mode="proxy", velocity_jar=velocity, plugin_jar=plugin)
        directory = runtime.prepare_proxy(config)
        toml = (directory / "velocity.toml").read_text()
        self.assertIn("online-mode = true", toml)
        self.assertIn('player-info-forwarding-mode = "modern"', toml)
        self.assertIn('a = "server-a:25565"', toml)
        self.assertIn("failover-on-unexpected-server-disconnect = false", toml)
        self.assertEqual(0o600, (directory / "forwarding.secret").stat().st_mode & 0o777)
        plugin_config = runtime.read_json(directory / "plugins" / "deathswap-network" / "config.json")
        self.assertEqual(str(config.shared_root), plugin_config["sharedRoot"])
        self.assertEqual({"a", "b"}, set(plugin_config["backendServers"]))

    def test_complete_supervisor_run_persists_receipt_before_process_stop(self):
        self.world()
        fake_java = self.root / "fake-java"
        fake_java.write_text('''#!/usr/bin/env python3
import json,pathlib,signal,time,uuid
root = pathlib.Path.cwd()
protocol = root / 'config' / 'deathswap'
identity = json.loads((root / 'world-generation.json').read_text())
def stopped(signum, frame):
    receipt = json.loads((protocol / 'reset.authorized.json').read_text())
    observed = {'receiptId': receipt['requestId'], 'worldStillPresent': (root / 'world').exists()}
    (root / 'shutdown-observed.json').write_text(json.dumps(observed))
    raise SystemExit(0)
signal.signal(signal.SIGTERM, stopped)
now = int(time.time() * 1000)
request = {'schemaVersion':1,'serverId':'a','generationId':identity['generationId'],
           'requestId':str(uuid.uuid4()),'requestedAt':now}
status = {'schemaVersion':1,'serverId':'a','generationId':identity['generationId'],
          'bootId':str(uuid.uuid4()),'state':'resetting','playerCount':0,'onlinePlayers':[],
          'updatedAt':now,'resetRequestId':request['requestId']}
(protocol / 'reset.request.json').write_text(json.dumps(request))
(protocol / 'status.json').write_text(json.dumps(status))
time.sleep(10)
''')
        fake_java.chmod(0o700)
        self.supervisor.config = replace(self.config, java_bin=str(fake_java), shutdown_seconds=1)
        self.assertEqual(0, self.supervisor.run())
        observed = runtime.read_json(self.supervisor.data_dir / "shutdown-observed.json")
        self.assertTrue(observed["worldStillPresent"])
        self.assertTrue(runtime.valid_uuid(observed["receiptId"]))
        self.assertFalse((self.supervisor.data_dir / "world").exists())
        new = runtime.read_json(self.supervisor.identity_file)
        self.assertNotEqual(self.identity["generationId"], new["generationId"])
        self.assertNotEqual(self.identity["seed"], new["seed"])
        self.assertFalse(self.supervisor.receipt_file.exists())

    def test_server_failure_preserves_existing_world_and_seed(self):
        self.world()
        fake_java = self.root / "failed-java"
        fake_java.write_text("#!/bin/sh\nexit 7\n")
        fake_java.chmod(0o700)
        self.supervisor.config = replace(self.config, java_bin=str(fake_java))
        self.assertEqual(7, self.supervisor.run())
        self.assertEqual(self.identity, runtime.read_json(self.supervisor.identity_file))
        self.assertTrue((self.supervisor.data_dir / "world").exists())


class ComposeTests(unittest.TestCase):
    def fixture(self, count):
        return {"x-server-count": str(count), "services": {
            "proxy": {"environment": {"FORWARDING_SECRET": "private$literal"}},
            "server-a": {"environment": {"SERVER_ID": "a", "FORWARDING_SECRET": "private$literal"},
                         "volumes": ["deathswap-data:/deathswap"]},
            "server-b": {"environment": {"SERVER_ID": "b"}},
        }}

    def test_expansion_preserves_ids_secrets_and_input(self):
        fixture = self.fixture(28)
        before = copy.deepcopy(fixture)
        config = compose.expand_backends(fixture)
        self.assertEqual(before, fixture)
        self.assertEqual(29, len(config["services"]))
        self.assertIn("server-z", config["services"])
        self.assertIn("server-s27", config["services"])
        self.assertEqual("s28", config["services"]["server-s28"]["environment"]["SERVER_ID"])
        self.assertEqual("private$literal", config["services"]["server-b"]["environment"]["FORWARDING_SECRET"])
        self.assertNotIn("ports", config["services"]["server-a"])

    def test_invalid_counts_rejected(self):
        for count in (0, 1, 65, -1, "2.5", "x", ""):
            with self.subTest(count=count), self.assertRaises(ValueError):
                compose.expand_backends(self.fixture(count))

    def test_maximum_supported_backend_count(self):
        config = compose.expand_backends(self.fixture(64))
        self.assertEqual(65, len(config["services"]))
        self.assertIn("server-s64", config["services"])

    def test_compose_resolves_env_once_and_escapes_second_pass_outside_context(self):
        first = subprocess.CompletedProcess([], 0, stdout=json.dumps(self.fixture(3)), stderr="")
        observed = {}

        def launch(command, **kwargs):
            if "--format" in command:
                return first
            path = Path(command[command.index("-f") + 1])
            observed["path"] = path
            observed["config"] = json.loads(path.read_text())
            observed["mode"] = path.stat().st_mode & 0o777
            observed["command"] = command
            return subprocess.CompletedProcess(command, 0)

        with mock.patch.object(compose.shutil, "which", return_value="/fake/docker"), \
                mock.patch.object(compose.subprocess, "run", side_effect=launch):
            self.assertEqual(0, compose.main(["config", "--services"]))
        self.assertEqual(0o600, observed["mode"])
        self.assertEqual("private$$literal", observed["config"]["services"]["proxy"]["environment"]["FORWARDING_SECRET"])
        self.assertFalse(observed["path"].exists())
        self.assertEqual(["config", "--services"], observed["command"][-2:])


if __name__ == "__main__":
    unittest.main()
