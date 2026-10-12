#!/usr/bin/env python3
"""Check the distributable JAR, including its entrypoint and target versions."""
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines() if "=" in line)
expected_version = f"{properties['mod_version']}+mc{properties['minecraft_version']}"
jar = root / "build" / "libs" / f"death-swap-{expected_version}.jar"
assert jar.is_file(), f"Missing distributable: {jar}"
with zipfile.ZipFile(jar) as archive:
    names = set(archive.namelist())
    metadata = json.loads(archive.read("fabric.mod.json"))
    assert metadata["id"] == "deathswap"
    assert metadata["version"] == expected_version
    assert metadata["depends"]["minecraft"] == "26.2"
    assert metadata["entrypoints"]["main"] == ["com.deathswap.DeathSwapMod"]
    assert "com/deathswap/DeathSwapMod.class" in names
    assert "com/deathswap/DeathSwapGame.class" in names
    assert "LICENSE" in names
    assert not any("GameTests" in name or "RulesTests" in name for name in names), "Test code leaked into mod"
    assert b"net/minecraft/client/" not in b"".join(archive.read(name) for name in names if name.endswith(".class")), "Client classes in server mod"
    assert int.from_bytes(archive.read("com/deathswap/DeathSwapMod.class")[6:8], "big") == 69, "Expected Java 25 bytecode"
print(f"Verified {jar.name}: metadata, Java 25, server-only code, and production classes.")
