#!/usr/bin/env python3
"""Render with real Compose in a disposable project; never start containers."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class ComposeIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="death-swap-compose-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = Path(__file__).resolve().parent.parent
        (self.root / "docker").mkdir()
        shutil.copy(source / "docker-compose.yml", self.root / "docker-compose.yml")
        shutil.copy(source / "docker" / "compose.py", self.root / "docker" / "compose.py")
        self.secret = "fixture$literal-" + "1" * 40
        self.env = {
            key: value for key, value in os.environ.items()
            if not key.startswith("COMPOSE_") and key not in (
                "SERVER_COUNT", "MAX_PLAYERS", "FORWARDING_SECRET",
            )
        }
        self.settings = f"FORWARDING_SECRET='{self.secret}'\n"

    def render(self, **overrides):
        (self.root / ".env").write_text(self.settings, encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(self.root / "docker" / "compose.py"), "config", "--format", "json"],
            cwd=self.root, env={**self.env, **overrides}, capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, "Real Compose rendering failed (Compose v2 required)")
        return json.loads(result.stdout)["services"]

    def check_services(self, services, ids, capacity):
        self.assertEqual({"proxy", *(f"server-{server_id}" for server_id in ids)}, set(services))
        self.assertEqual(",".join(ids), services["proxy"]["environment"]["BACKEND_SERVER_IDS"])
        self.assertNotIn("SERVER_COUNT", services["proxy"]["environment"])
        for service in services.values():
            self.assertEqual(str(capacity), service["environment"]["MAX_PLAYERS"])
            # Normalized Compose output keeps literals escaped for another read.
            self.assertEqual(self.secret.replace("$", "$$"), service["environment"]["FORWARDING_SECRET"])

    def test_defaults(self):
        self.check_services(self.render(), ["a", "b"], 100)

    def test_dotenv_count_and_capacity(self):
        self.settings += "SERVER_COUNT=3\nMAX_PLAYERS=64\n"
        self.check_services(self.render(), ["a", "b", "c"], 64)

    def test_shell_overrides_dotenv(self):
        self.settings += "SERVER_COUNT=3\nMAX_PLAYERS=64\n"
        self.check_services(self.render(SERVER_COUNT="4", MAX_PLAYERS="80"), ["a", "b", "c", "d"], 80)


if __name__ == "__main__":
    unittest.main()
