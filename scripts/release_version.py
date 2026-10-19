#!/usr/bin/env python3
"""Choose a unique patch release, reusing a version when its commit is rerun."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")


def parse_version(value):
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError(f"Invalid mod version: {value}")
    return tuple(map(int, match.groups()))


def properties():
    return dict(line.split("=", 1) for line in (
        line.strip() for line in Path("gradle.properties").read_text().splitlines())
        if "=" in line and not line.startswith("#"))


def stable_tag(tag):
    return tag.startswith("v") and VERSION.fullmatch(tag[1:]) is not None


def stable_releases(releases):
    return [release for release in releases
            if stable_tag(release["tag_name"]) and not release.get("prerelease")]


def tag_commit(tag):
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}"],
        text=True, capture_output=True, check=False)
    if result.returncode not in (0, 1):
        raise ValueError(f"Cannot resolve tag {tag}: {result.stderr.strip()}")
    return result.stdout.strip() if result.returncode == 0 else None


def select_version(releases, commit, tag_commits, base_version):
    base = parse_version(base_version)
    stable = stable_releases(releases)
    existing = [release for release in stable
                if tag_commits.get(release["tag_name"]) == commit
                or (release["tag_name"] not in tag_commits
                    and release.get("target_commitish") == commit)]
    orphaned = [tag[1:] for tag, sha in tag_commits.items()
                if stable_tag(tag) and sha == commit
                and not any(r["tag_name"] == tag for r in releases)]
    if existing:
        return max((release["tag_name"][1:] for release in existing), key=parse_version)
    if orphaned:
        return max(orphaned, key=parse_version)

    # Drafts and standalone tags reserve versions, even before publication.
    reserved = {parse_version(release["tag_name"][1:]) for release in releases if stable_tag(release["tag_name"])}
    reserved.update(parse_version(tag[1:]) for tag in tag_commits if stable_tag(tag))
    if not reserved or base > max(reserved):
        return base_version
    major, minor, patch = max(reserved)
    return f"{major}.{minor}.{patch + 1}"


def main():
    pages = json.loads(Path(sys.argv[1]).read_text())
    releases = [release for page in pages for release in page]
    tags = subprocess.check_output(
        ["git", "tag", "--list"], text=True).splitlines()
    tag_commits = {tag: tag_commit(tag) for tag in tags if stable_tag(tag)}
    for release in stable_releases(releases):
        if not release["draft"] and not tag_commits.get(release["tag_name"]):
            raise ValueError(f"Cannot resolve published tag {release['tag_name']}; refusing to reset history")
    version = select_version(releases, os.environ["GITHUB_SHA"], tag_commits,
                             properties()["mod_version"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"version={version}\ntag=v{version}\n")
    print(f"Release version: {version}")


if __name__ == "__main__":
    main()
