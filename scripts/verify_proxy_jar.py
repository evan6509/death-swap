#!/usr/bin/env python3
"""Verify the separately installed Velocity controller artifact."""
import json
import os
from pathlib import Path
import sys
import zipfile

from release_version import parse_version, properties


def verify(directory="velocity-plugin/build/libs", version=None):
    version = version or os.environ.get("DEATHSWAP_VERSION", properties()["mod_version"])
    parse_version(version)
    jar = Path(directory) / f"death-swap-proxy-{version}.jar"
    if not jar.is_file() or not jar.stat().st_size:
        raise ValueError(f"Missing or empty proxy artifact: {jar}")
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if archive.testzip() or len(names) != len(set(names)):
            raise ValueError("Corrupt or duplicate JAR entries")
        metadata = json.loads(archive.read("velocity-plugin.json"))
        if (metadata["id"] != "deathswap-network" or metadata["version"] != version
                or metadata["main"] != "com.deathswap.proxy.DeathSwapProxyPlugin"):
            raise ValueError("Incorrect Velocity identity, embedded version, or entrypoint")
        required = {"LICENSE", "com/deathswap/proxy/DeathSwapProxyPlugin.class",
                    "com/deathswap/proxy/RoundController.class"}
        if not required <= set(names) or any(not archive.read(name) for name in required):
            raise ValueError("Missing or empty proxy production files")
        for name in names:
            if name.endswith(("Test.class", "Tests.class")) or name.startswith("net/minecraft/"):
                raise ValueError("Test code or Minecraft classes in proxy")
            if name.endswith(".class"):
                data = archive.read(name)
                if data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 69:
                    raise ValueError(f"Expected Java 25 bytecode: {name}")
    return jar, metadata


if __name__ == "__main__":
    jar, _ = verify(sys.argv[1] if len(sys.argv) > 1 else "velocity-plugin/build/libs")
    print(f"Verified {jar.name}: Velocity metadata, Java 25, controller classes, and no test code.")
