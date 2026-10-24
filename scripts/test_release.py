"""Release regression tests; GitHub mutations are simulated, never performed."""

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from deployment_archive import REQUIRED_FILES, create_archive, verify_archive
import publish_release
from release_version import parse_version, select_version
from verify_release_assets import verify_checksums


COMMIT = "a" * 40
OTHER_COMMIT = "b" * 40


def release(tag, target=OTHER_COMMIT, draft=False, prerelease=False):
    return dict(tag_name=tag, target_commitish=target, draft=draft, prerelease=prerelease,
                html_url="https://example.invalid/release", assets=[])


class VersionTests(unittest.TestCase):
    def test_first_release_and_patch_increment(self):
        self.assertEqual(select_version([], COMMIT, {}, "1.0.0"), "1.0.0")
        self.assertEqual(select_version([release("v1.0.0")], COMMIT, {}, "1.0.0"), "1.0.1")

    def test_highest_version_not_api_order(self):
        releases = [release("v1.0.3"), release("v1.0.1"), release("v1.0.2")]
        self.assertEqual(select_version(releases, COMMIT, {}, "1.0.0"), "1.0.4")

    def test_higher_configured_version(self):
        self.assertEqual(select_version([release("v1.0.3")], COMMIT, {}, "2.0.0"), "2.0.0")

    def test_rerun_uses_tag_commit_not_mutable_branch(self):
        releases = [release("v1.0.0", "main"), release("v1.0.1")]
        self.assertEqual(select_version(releases, COMMIT, {"v1.0.0": COMMIT}, "1.0.0"), "1.0.0")
        self.assertEqual(select_version(releases, COMMIT, {}, "1.0.0"), "1.0.2")

    def test_draft_retry_and_reserved_versions(self):
        self.assertEqual(select_version([release("v1.0.0", COMMIT, draft=True)], COMMIT, {}, "1.0.0"), "1.0.0")
        self.assertEqual(select_version([release("v1.0.0", draft=True)], COMMIT, {}, "1.0.0"), "1.0.1")
        self.assertEqual(select_version([], COMMIT, {"v1.0.4": OTHER_COMMIT}, "1.0.0"), "1.0.5")

    def test_prereleases_and_unrelated_tags(self):
        releases = [release("v2.0.0-beta.1", prerelease=True), release("old-release"),
                    release("v2.0.0", prerelease=True)]
        self.assertEqual(select_version(releases, COMMIT, {}, "1.0.0"), "2.0.1")
        # A real tag still reserves its name even if its release is marked prerelease.
        self.assertEqual(select_version(releases, COMMIT, {"v2.0.0": OTHER_COMMIT}, "1.0.0"), "2.0.1")

    def test_wrong_tag_overrides_release_target(self):
        self.assertEqual(select_version([release("v1.0.0", COMMIT)], COMMIT,
                                        {"v1.0.0": OTHER_COMMIT}, "1.0.0"), "1.0.1")

    def test_invalid_versions(self):
        for value in ["1.0", "v1.0.0", "1.0.0-beta", "01.0.0", "1.0.0\n", "", "../1.0.0"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_version(value)


class DeploymentArchiveTests(unittest.TestCase):
    def fixture(self, folder):
        root = Path(folder)
        for name in REQUIRED_FILES:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            content = {
                ".env.example": "EULA=false\n",
                "gradle.properties": "mod_version=1.0.0\n",
                "Dockerfile": "ARG DEATHSWAP_VERSION=1.0.0\n",
                "docker-compose.yml": "image: death-swap:${DEATHSWAP_VERSION:-1.0.0}\n",
            }
            path.write_text(content.get(name, "deployment source fixture\n"))
        return root

    def test_version_and_executable_permissions_without_runtime_data(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            for name in [".env", "docker/state.json", "docker/private.json", "world/level.dat",
                         "velocity-plugin/build/libs/private.jar", ".git/config"]:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("private runtime fixture")
            source = root / "src/main/java/com/deathswap/Extra.java"
            source.write_text("package test;")
            archive = create_archive(root, root / "deployment.zip", "1.0.1")
            with ZipFile(archive) as zipped:
                self.assertEqual(zipped.read("RELEASE_VERSION"), b"1.0.1\n")
                self.assertIn(b"DEATHSWAP_VERSION=1.0.1", zipped.read(".env.example"))
                self.assertIn(source.relative_to(root).as_posix(), zipped.namelist())
                for name in [".env", "docker/state.json", "docker/private.json", "world/level.dat",
                             "velocity-plugin/build/libs/private.jar", ".git/config"]:
                    self.assertNotIn(name, zipped.namelist())
                self.assertEqual((zipped.getinfo("gradlew").external_attr >> 16) & 0o777, 0o755)
                self.assertEqual((zipped.getinfo("docker/entrypoint.sh").external_attr >> 16) & 0o777, 0o755)

    def test_missing_sources_symlinks_and_wrong_version_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            (root / "gradlew").unlink()
            with self.assertRaisesRegex(ValueError, "Missing Docker deployment sources"):
                create_archive(root, root / "deployment.zip", "1.0.1")
            (root / "gradlew").symlink_to(root / "README.md")
            with self.assertRaisesRegex(ValueError, "symlink"):
                create_archive(root, root / "deployment.zip", "1.0.1")
            (root / "gradlew").unlink()
            (root / "gradlew").write_text("wrapper")
            archive = create_archive(root, root / "deployment.zip", "1.0.1")
            with self.assertRaisesRegex(ValueError, "version does not match"):
                verify_archive(archive, "1.0.2")

    def test_unexpected_runtime_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            archive = create_archive(root, root / "deployment.zip", "1.0.1")
            with ZipFile(archive, "a") as zipped:
                zipped.writestr(".env", "private fixture")
            with self.assertRaisesRegex(ValueError, "Unexpected file"):
                verify_archive(archive, "1.0.1")


class PublicationTests(unittest.TestCase):
    def run_publication(self, folder, existing=None, corrupt=False, api_failure=False, releases=(),
                        version="1.0.0", unexpected_download=False, main_commit=COMMIT,
                        missing_download=None, download_failure=False):
        directory = Path(folder)
        jar = directory / f"death-swap-{version}+mc26.2.jar"
        jar.write_bytes(b"verified installable jar fixture")
        plugin = directory / f"death-swap-proxy-{version}.jar"
        plugin.write_bytes(b"verified velocity plugin fixture")
        deployment = directory / f"death-swap-docker-{version}+mc26.2.zip"
        deployment.write_bytes(b"verified docker deployment fixture")
        mod263 = directory / f"death-swap-{version}+mc26.3.jar"
        mod263.write_bytes(b"verified 26.3 jar fixture")
        build_assets = [jar, plugin, deployment, mod263]
        metadata = dict(version=f"{version}+mc26.2", depends={"minecraft": "26.2", "fabricloader": ">=0.19.3",
                                                      "fabric-api": ">=0.154.2", "java": ">=25"})
        calls = []
        verified_directories = []

        def fake_verify(directory, version):
            verified_directories.append(Path(directory))
            if (Path(directory) / "SHA256SUMS").exists():
                verify_checksums(directory)
            return build_assets, metadata

        def fake_gh(*args):
            calls.append(args)
            if args[0] == "api" and "releases?" in args[1]:
                if api_failure:
                    raise subprocess.CalledProcessError(1, ["gh", *args])
                return json.dumps([([existing] if existing else []) + list(releases)])
            if args[0] == "api" and "matching-refs" in args[1]:
                refs = [] if existing is None or existing["draft"] else [
                    dict(ref="refs/tags/v" + version, object=dict(type="commit", sha=COMMIT))]
                return json.dumps(refs)
            if args[0] == "api" and "commits/main" in args[1]:
                return json.dumps(dict(sha=main_commit))
            if args[0] == "api" and "generate-notes" in args[3]:
                return json.dumps(dict(body="## What's Changed\n- Test change in #1"))
            if args[:2] == ("release", "download"):
                if download_failure:
                    raise subprocess.CalledProcessError(1, ["gh", *args])
                downloaded = Path(args[args.index("--dir") + 1])
                for asset in build_assets:
                    (downloaded / asset.name).write_bytes(asset.read_bytes())
                if existing and not existing["draft"]:
                    # Published notes stay original, even after newer releases.
                    notes = downloaded / "CHANGELOG.md"
                    notes.write_text("Original published release notes\n")
                    (downloaded / "SHA256SUMS").write_text("".join(
                        f"{hashlib.sha256(asset.read_bytes()).hexdigest()}  {asset.name}\n"
                        for asset in [*(downloaded / asset.name for asset in build_assets), notes]))
                else:
                    for name in ["CHANGELOG.md", "SHA256SUMS"]:
                        (downloaded / name).write_bytes((directory / name).read_bytes())
                if corrupt:
                    names = [asset.name for asset in build_assets] + ["CHANGELOG.md", "SHA256SUMS"]
                    (downloaded / names[int(corrupt) - 1]).write_bytes(b"corrupted download")
                if missing_download:
                    (downloaded / missing_download).unlink()
                if unexpected_download:
                    (downloaded / "unexpected.jar").write_bytes(b"unexpected download")
            if args[:2] == ("release", "view"):
                return json.dumps(dict(url="https://example.invalid/release"))
            return ""

        env = dict(DEATHSWAP_VERSION=version, RELEASE_TAG=f"v{version}",
                   GITHUB_REPOSITORY="example/death-swap", GITHUB_SHA=COMMIT,
                   GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main")
        with patch.dict(os.environ, env, clear=True), patch("sys.argv", ["publish_release.py", folder]), \
                patch.object(publish_release.subprocess, "check_output", return_value=COMMIT + "\n"), \
                patch.object(publish_release, "verify", side_effect=fake_verify), \
                patch.object(publish_release, "commit_notes", return_value="- Direct commit change"), \
                patch.object(publish_release, "tag_commit", return_value=COMMIT if existing and not existing["draft"] else None), \
                patch.object(publish_release, "gh", side_effect=fake_gh), contextlib.redirect_stdout(io.StringIO()):
            try:
                publish_release.main()
            finally:
                self.calls = calls
                self.verified_directories = verified_directories
        return calls

    def test_draft_download_verification_then_publication(self):
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder)
            creation = next(call for call in calls if call[:2] == ("release", "create"))
            self.assertIn("--draft", creation)
            self.assertIn(COMMIT, creation)
            download = next(i for i, call in enumerate(calls) if call[:2] == ("release", "download"))
            publish = next(i for i, call in enumerate(calls) if "--draft=false" in call)
            self.assertLess(download, publish)
            self.assertIn("--latest", calls[publish])
            self.assertIn("Test change in #1", (Path(folder) / "CHANGELOG.md").read_text())
            self.assertIn("Direct commit change", (Path(folder) / "CHANGELOG.md").read_text())
            self.assertIn("SHA256SUMS", " ".join(creation))
            self.assertIn("death-swap-proxy-1.0.0.jar", " ".join(creation))
            self.assertIn("death-swap-docker-1.0.0+mc26.2.zip", " ".join(creation))
            checksums = (Path(folder) / "SHA256SUMS").read_text()
            self.assertEqual(len(checksums.splitlines()), 5)
            self.assertIn("death-swap-proxy", checksums)
            self.assertIn("death-swap-docker", checksums)
            self.assertIn("death-swap-1.0.0+mc26.3.jar", (Path(folder) / "CHANGELOG.md").read_text())
            self.assertIn("0.165.0+26.3", (Path(folder) / "CHANGELOG.md").read_text())

    def test_corrupt_download_is_not_published(self):
        for asset in (1, 2, 3, 4):
            with self.subTest(asset=asset), tempfile.TemporaryDirectory() as folder:
                with self.assertRaisesRegex(ValueError, "differs from the build"):
                    self.run_publication(folder, corrupt=asset)
                self.assertFalse(any("--draft=false" in call for call in self.calls))

    def test_unexpected_downloaded_asset_is_not_published(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "asset set"):
                self.run_publication(folder, unexpected_download=True)
            self.assertFalse(any("--draft=false" in call for call in self.calls))

    def test_api_failure_is_not_treated_as_missing_release(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_publication(folder, api_failure=True)
            self.assertFalse(any(call[0] == "release" for call in self.calls))

    def test_retry_resumes_only_a_matching_draft(self):
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, existing=release("v1.0.0", COMMIT, draft=True))
            self.assertFalse(any(call[:2] == ("release", "create") for call in calls))
            self.assertTrue(any(call[:2] == ("release", "upload") for call in calls))
            self.assertTrue(any("--draft=false" in call for call in calls))

    def test_older_draft_retry_does_not_replace_newer_latest_release(self):
        existing = release("v1.0.0", COMMIT, draft=True)
        newer = release("v1.0.1")
        self.assertEqual(select_version([existing, newer], COMMIT, {}, "1.0.0"), "1.0.0")
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, existing=existing, releases=[newer])
            publication = next(call for call in calls if "--draft=false" in call)
            self.assertIn("--latest=false", publication)

    def test_unpublished_and_prerelease_versions_do_not_block_latest(self):
        releases = [release("v1.0.1", draft=True), release("v2.0.0", prerelease=True),
                    release("v3.0.0-beta.1", prerelease=True), release("nightly")]
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, releases=releases)
            publication = next(call for call in calls if "--draft=false" in call)
            self.assertIn("--latest", publication)

    def test_latest_uses_numeric_version_order(self):
        for version, other_version, flag in [("1.0.9", "1.0.10", "--latest=false"),
                                             ("1.0.10", "1.0.9", "--latest")]:
            with self.subTest(version=version), tempfile.TemporaryDirectory() as folder:
                calls = self.run_publication(folder, releases=[release(f"v{other_version}")], version=version)
                publication = next(call for call in calls if "--draft=false" in call)
                self.assertIn(flag, publication)

    def test_published_rerun_does_not_modify_assets(self):
        existing = release("v1.0.0", COMMIT)
        existing["assets"] = [dict(name=name, state="uploaded") for name in
                              ["death-swap-1.0.0+mc26.2.jar", "death-swap-proxy-1.0.0.jar",
                               "death-swap-docker-1.0.0+mc26.2.zip", "death-swap-1.0.0+mc26.3.jar", "CHANGELOG.md", "SHA256SUMS"]]
        with tempfile.TemporaryDirectory() as folder:
            calls = self.run_publication(folder, existing=existing, releases=[release("v1.0.1")])
            self.assertEqual([call[:2] for call in calls if call[0] == "release"],
                             [("release", "download")])
            self.assertEqual(len(self.verified_directories), 2)
            self.assertNotEqual(self.verified_directories[0], self.verified_directories[1])
            self.assertFalse(any("generate-notes" in " ".join(call) for call in calls))

    def test_published_rerun_rejects_corruption_missing_and_extra_downloads(self):
        existing = release("v1.0.0", COMMIT)
        names = ["death-swap-1.0.0+mc26.2.jar", "death-swap-proxy-1.0.0.jar",
                 "death-swap-docker-1.0.0+mc26.2.zip", "death-swap-1.0.0+mc26.3.jar", "CHANGELOG.md", "SHA256SUMS"]
        existing["assets"] = [dict(name=name, state="uploaded") for name in names]
        cases = [dict(corrupt=index) for index in range(1, 7)]
        cases += [dict(missing_download=name) for name in names]
        cases += [dict(unexpected_download=True)]
        for options in cases:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):
                    self.run_publication(folder, existing=existing, **options)
                self.assertEqual([call[:2] for call in self.calls if call[0] == "release"],
                                 [("release", "download")])

    def test_published_rerun_download_failure_fails_without_mutation(self):
        existing = release("v1.0.0", COMMIT)
        existing["assets"] = [dict(name=name, state="uploaded") for name in
                              ["death-swap-1.0.0+mc26.2.jar", "death-swap-proxy-1.0.0.jar",
                               "death-swap-docker-1.0.0+mc26.2.zip", "death-swap-1.0.0+mc26.3.jar", "CHANGELOG.md", "SHA256SUMS"]]
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_publication(folder, existing=existing, download_failure=True)
            self.assertEqual([call[:2] for call in self.calls if call[0] == "release"],
                             [("release", "download")])

    def test_published_release_missing_plugin_is_left_unchanged(self):
        existing = release("v1.0.0", COMMIT)
        existing["assets"] = [dict(name=name, state="uploaded") for name in
                              ["death-swap-1.0.0+mc26.2.jar", "CHANGELOG.md", "SHA256SUMS"]]
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "missing expected assets"):
                self.run_publication(folder, existing=existing)
            self.assertFalse(any(call[0] == "release" for call in self.calls))

    def test_conflicting_tag_or_mutable_draft_target_is_rejected(self):
        with self.assertRaises(ValueError):
            publish_release.validate_target(release("v1.0.0", COMMIT), OTHER_COMMIT, COMMIT)
        with self.assertRaises(ValueError):
            publish_release.validate_target(release("v1.0.0", "main", draft=True), None, COMMIT)

    def test_commit_notes_include_direct_changes_and_use_release_range(self):
        output = COMMIT + "\0Fix [CSV] logging\n" + OTHER_COMMIT + "\0Handle commas\n"
        with patch.object(publish_release.subprocess, "check_output", return_value=output) as log:
            notes = publish_release.commit_notes("v1.0.0", COMMIT, "example/death-swap")
            self.assertIn("refs/tags/v1.0.0.." + COMMIT, log.call_args.args[0])
            self.assertIn("Fix \\[CSV\\] logging", notes)
            self.assertIn("Handle commas", notes)
            self.assertIn("/commit/" + OTHER_COMMIT, notes)
            publish_release.commit_notes(None, COMMIT, "example/death-swap")
            self.assertEqual(log.call_args.args[0][-1], COMMIT)


if __name__ == "__main__":
    unittest.main()
