#!/usr/bin/env python3
"""Verify the separately installed Velocity controller artifact."""
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line)
version = properties["mod_version"]
jar = root / "velocity-plugin" / "build" / "libs" / f"death-swap-proxy-{version}.jar"
assert jar.is_file(), f"Missing proxy artifact: {jar}"
with zipfile.ZipFile(jar) as archive:
    names = set(archive.namelist())
    metadata = json.loads(archive.read("velocity-plugin.json"))
    assert metadata["id"] == "deathswap-network"
    assert metadata["version"] == version
    entrypoint = metadata["main"].replace(".", "/") + ".class"
    assert entrypoint in names
    assert "com/deathswap/proxy/RoundController.class" in names
    assert not any(name.endswith("Test.class") or name.endswith("Tests.class") for name in names)
    assert not any(name.startswith("net/minecraft/") for name in names)
    assert int.from_bytes(archive.read(entrypoint)[6:8], "big") == 69, "Expected Java 25 bytecode"
print(f"Verified {jar.name}: Velocity metadata, Java 25, controller classes, and no test code.")
