#!/usr/bin/env python3
"""Verify the exact Fabric, Velocity, and Docker artifacts for a release."""

import hashlib
import os
from pathlib import Path
import sys

from deployment_archive import verify_archive
from release_version import parse_version, properties
from verify_mod_jar import verify as verify_mod
from verify_proxy_jar import verify as verify_velocity


def verify_checksums(directory):
    directory = Path(directory)
    manifest = directory / "SHA256SUMS"
    expected = {path.name for path in directory.iterdir()} - {"SHA256SUMS"}
    entries = {}
    for line in manifest.read_text().splitlines():
        digest, name = line.split("  ", 1)
        if name in entries or name not in expected or len(digest) != 64:
            raise ValueError("Invalid checksum manifest entries")
        entries[name] = digest
    if entries.keys() != expected:
        raise ValueError("Checksum manifest does not cover all release assets")
    for name, digest in entries.items():
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch: {name}")


def verify(directory, version=None):
    props = properties()
    version = version or os.environ.get("DEATHSWAP_VERSION", props["mod_version"])
    parse_version(version)
    full_version = f"{version}+mc{props['minecraft_version']}"
    directory = Path(directory)
    mod_name = f"death-swap-{full_version}.jar"
    plugin_name = f"death-swap-proxy-{version}.jar"
    docker_name = f"death-swap-docker-{full_version}.zip"
    mod263_name = f"death-swap-{version}+mc26.3.jar"
    expected = {mod_name, mod263_name, plugin_name, docker_name}
    present = {path.name for path in directory.iterdir()}
    if not expected <= present or present - expected - {"CHANGELOG.md", "SHA256SUMS"}:
        raise ValueError(f"Expected exact release assets {sorted(expected)}; found {sorted(present)}")
    if present & {"CHANGELOG.md", "SHA256SUMS"}:
        if not {"CHANGELOG.md", "SHA256SUMS"} <= present or not (directory / "CHANGELOG.md").stat().st_size:
            raise ValueError("Missing release notes or checksums")
        verify_checksums(directory)
    mod, metadata = verify_mod(directory, version, minecraft="26.2")
    mod263, _ = verify_mod(directory, version, minecraft="26.3")
    plugin, _ = verify_velocity(directory, version)
    deployment = verify_archive(directory / docker_name, version)
    return [mod, plugin, deployment, mod263], metadata


def main():
    assets, metadata = verify(sys.argv[1] if len(sys.argv) > 1 else "build/release")
    print(f"Verified {metadata['version']}: " + ", ".join(str(asset) for asset in assets))


if __name__ == "__main__":
    main()
