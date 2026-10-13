#!/usr/bin/env python3
"""Check matching backend admission and proxy display capacity in rendered files."""

import re
import tempfile
import unittest
from pathlib import Path

import runtime


class CapacityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="death-swap-capacity-test-")
        self.root = Path(self.temp.name)
        install = self.root / "install"
        install.mkdir()
        (install / "fabric-server-launch.jar").write_text("fixture")
        velocity = self.root / "velocity.jar"
        plugin = self.root / "plugin.jar"
        velocity.write_text("fixture")
        plugin.write_text("fixture")
        self.env = {
            "EULA": "TRUE", "SERVER_ID": "a",
            "FORWARDING_SECRET": "private-test-capacity-secret-" + "1" * 40,
            "SHARED_DIR": str(self.root / "data"), "INSTALL_DIR": str(install),
            "VELOCITY_JAR": str(velocity), "PLUGIN_JAR": str(plugin),
        }

    def tearDown(self):
        self.temp.cleanup()

    def rendered_capacities(self, maximum=None):
        env = dict(self.env)
        if maximum is not None:
            env["MAX_PLAYERS"] = str(maximum)
        server_config = runtime.RuntimeConfig.from_env({**env, "MODE": "server"})
        supervisor = runtime.BackendSupervisor(server_config)
        supervisor.data_dir.mkdir(parents=True, exist_ok=True)
        # Replace even duplicate old backend limits, while retaining unrelated settings.
        properties_file = supervisor.data_dir / "server.properties"
        properties_file.write_text("max-players=20\nmax-players=12\nmotd=Retained setting\n")
        supervisor.prepare_server(supervisor.identity())
        properties = properties_file.read_text()
        backend_limits = re.findall(r"^max-players=(\d+)$", properties, re.MULTILINE)
        self.assertEqual(1, len(backend_limits))
        self.assertIn("motd=Retained setting\n", properties)

        proxy_config = runtime.RuntimeConfig.from_env({**env, "MODE": "proxy"})
        proxy_dir = runtime.prepare_proxy(proxy_config)
        proxy_limits = re.findall(
            r"^show-max-players\s*=\s*(\d+)\s*$",
            (proxy_dir / "velocity.toml").read_text(), re.MULTILINE,
        )
        self.assertEqual(1, len(proxy_limits))
        return int(backend_limits[0]), int(proxy_limits[0])

    def test_default_capacity_is_100_in_both_rendered_configs(self):
        self.assertEqual((100, 100), self.rendered_capacities())

    def test_configured_capacity_matches_in_both_rendered_configs(self):
        self.assertEqual((64, 64), self.rendered_capacities(64))

    def test_capacity_bounds_are_accepted_in_both_rendered_configs(self):
        for maximum in (1, 1_024):
            with self.subTest(maximum=maximum):
                self.assertEqual((maximum, maximum), self.rendered_capacities(maximum))

    def test_invalid_capacity_is_rejected_for_proxy_and_backend(self):
        for mode in ("server", "proxy"):
            for maximum in ("", "0", "1025", "-1", "1.5", "many", "100 extra", "true"):
                with self.subTest(mode=mode, maximum=maximum), self.assertRaisesRegex(ValueError, "MAX_PLAYERS"):
                    runtime.RuntimeConfig.from_env({**self.env, "MODE": mode, "MAX_PLAYERS": maximum})


if __name__ == "__main__":
    unittest.main()
