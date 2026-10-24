#!/usr/bin/env python3
"""Stage both Minecraft JARs, the proxy JAR, and a clean Docker source deployment ZIP."""

import os
from pathlib import Path
import shutil

from deployment_archive import create_archive
from release_version import properties
from verify_mod_jar import verify as verify_mod
from verify_proxy_jar import verify as verify_velocity
from verify_release_assets import verify


def stage(root=".", destination="build/release", version=None):
    props = properties()
    version = version or os.environ.get("DEATHSWAP_VERSION", props["mod_version"])
    root = Path(root)
    destination = Path(destination)
    mod, _ = verify_mod(root / "build/libs", version, minecraft="26.2")
    mod263, _ = verify_mod(root / "build/26.3/libs", version, minecraft="26.3")
    plugin, _ = verify_velocity(root / "velocity-plugin/build/libs", version)
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError(f"Release staging directory must be empty: {destination}")
    for source in (mod, mod263, plugin):
        shutil.copyfile(source, destination / source.name)
    create_archive(root, destination / f"death-swap-docker-{version}+mc{props['minecraft_version']}.zip", version)
    return verify(destination, version)[0]


def main():
    assets = stage()
    print("Staged release assets: " + ", ".join(str(asset) for asset in assets))


if __name__ == "__main__":
    main()
