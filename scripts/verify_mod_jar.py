#!/usr/bin/env python3
"""Check the installable Fabric JAR, including target, version, and server code."""
import json
import os
from pathlib import Path
import sys
import zipfile

from release_version import parse_version, properties


def verify(directory="build/libs", version=None):
    props = properties()
    version = version or os.environ.get("DEATHSWAP_VERSION", props["mod_version"])
    parse_version(version)
    expected = f"{version}+mc{props['minecraft_version']}"
    jar = Path(directory) / f"death-swap-{expected}.jar"
    if not jar.is_file() or not jar.stat().st_size:
        raise ValueError(f"Missing or empty distributable: {jar}")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if archive.testzip() or len(names) != len(set(names)):
            raise ValueError("Corrupt or duplicate JAR entries")
        metadata = json.loads(archive.read("fabric.mod.json"))
        if (metadata["id"] != "deathswap" or metadata["version"] != expected
                or metadata["depends"]["minecraft"] != "26.2"
                or metadata["depends"]["java"] != ">=25"
                or metadata["entrypoints"]["main"] != ["com.deathswap.DeathSwapMod"]):
            raise ValueError("Incorrect Fabric identity, embedded version, entrypoint, or target")
        required = {"LICENSE", "com/deathswap/DeathSwapMod.class", "com/deathswap/DeathSwapGame.class"}
        if not required <= set(names) or any(not archive.read(name) for name in required):
            raise ValueError("Missing or empty production files")
        for name in names:
            if name.endswith(("Test.class", "Tests.class")):
                raise ValueError("Test code leaked into mod")
            if name.endswith(".class"):
                data = archive.read(name)
                if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 69:
                    raise ValueError(f"Expected Java 25 bytecode: {name}")
                if b"net/minecraft/client/" in data:
                    raise ValueError("Client classes in server mod")
    return jar, metadata


if __name__ == "__main__":
    jar, _ = verify(sys.argv[1] if len(sys.argv) > 1 else "build/libs")
    print(f"Verified {jar.name}: metadata, Java 25, server-only code, and production classes.")
