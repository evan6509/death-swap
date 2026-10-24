"""Failure paths and artifact checks use only temporary files and mocked GitHub."""
import hashlib
from functools import partial
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from package_release import stage
import publish_release
import release_version
import test_release as fixtures
from test_release import COMMIT, OTHER_COMMIT, release
from verify_mod_jar import verify as verify_mod
from verify_proxy_jar import verify as verify_proxy
from verify_release_assets import verify, verify_checksums


class ReleaseSafetyTests(unittest.TestCase):
    def test_tag_created_before_draft_failure_is_reused(self):
        self.assertEqual(release_version.select_version([], COMMIT, {"v1.0.3": COMMIT}, "1.0.0"), "1.0.3")

    def test_existing_release_takes_precedence_over_an_unrelated_tag(self):
        self.assertEqual(release_version.select_version([release("v1.0.0", COMMIT)], COMMIT,
                                                       {"v1.0.0": COMMIT, "v1.0.2": COMMIT}, "1.0.0"), "1.0.0")

    def test_version_cli_reads_every_page_and_rejects_missing_published_tags(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "releases.json"
            output = Path(folder) / "output"
            source.write_text(json.dumps([[release("v1.0.1", draft=True)],
                                          [release("v1.0.9", draft=True)]]))
            with patch.dict(os.environ, GITHUB_SHA=COMMIT, GITHUB_OUTPUT=str(output)), \
                    patch("sys.argv", ["release_version.py", str(source)]), \
                    patch.object(release_version.subprocess, "check_output", return_value=""), \
                    patch.object(release_version, "properties", return_value={"mod_version": "1.0.0"}):
                release_version.main()
                self.assertEqual(output.read_text(), "version=1.0.10\ntag=v1.0.10\n")
                source.write_text(json.dumps([[release("v1.0.9")]]))
                with self.assertRaisesRegex(ValueError, "Cannot resolve published tag"):
                    release_version.main()
                source.write_text("unauthorized")
                with self.assertRaises(json.JSONDecodeError):
                    release_version.main()

    def test_publish_gate_rejects_all_other_events_before_api_use(self):
        cases = [("push", "refs/heads/codex/development"), ("push", "refs/tags/v1.0.0"),
                 ("pull_request", "refs/pull/1/merge"), ("workflow_dispatch", "refs/heads/main")]
        for event, ref in cases:
            with self.subTest(event=event, ref=ref), \
                    patch.dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_REF=ref), \
                    patch.object(publish_release, "gh") as api:
                with self.assertRaisesRegex(ValueError, "requires a main push"):
                    publish_release.main()
                api.assert_not_called()

    def test_mismatched_checkout_fails_before_api_use(self):
        env = dict(GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main", DEATHSWAP_VERSION="1.0.0",
                   RELEASE_TAG="v1.0.0", GITHUB_REPOSITORY="example/death-swap", GITHUB_SHA=COMMIT)
        with patch.dict(os.environ, env), \
                patch.object(publish_release.subprocess, "check_output", return_value=OTHER_COMMIT), \
                patch.object(publish_release, "gh") as api:
            with self.assertRaisesRegex(ValueError, "Checkout differs"):
                publish_release.main()
            api.assert_not_called()

    def test_remote_tag_conflict_never_moves_tag(self):
        with patch.object(publish_release, "remote_tag_commit", return_value=OTHER_COMMIT), \
                patch.object(publish_release, "gh") as api:
            with self.assertRaisesRegex(ValueError, "different commit"):
                publish_release.ensure_tag("example/death-swap", "v1.0.0", COMMIT)
            api.assert_not_called()

    def test_new_tag_is_create_only_and_existing_matching_tag_is_untouched(self):
        for actual in (None, COMMIT):
            with self.subTest(actual=actual), \
                    patch.object(publish_release, "remote_tag_commit", return_value=actual), \
                    patch.object(publish_release, "gh") as api:
                publish_release.ensure_tag("example/death-swap", "v1.0.0", COMMIT)
                if actual is None:
                    self.assertEqual(api.call_args.args[:3], ("api", "--method", "POST"))
                    self.assertIn("sha=" + COMMIT, api.call_args.args)
                else:
                    api.assert_not_called()

    def test_annotated_tag_is_peeled_and_prefix_matches_ignored(self):
        refs = [dict(ref="refs/tags/v1.0.0-other", object=dict(type="commit", sha=OTHER_COMMIT)),
                dict(ref="refs/tags/v1.0.0", object=dict(type="tag", sha=OTHER_COMMIT))]
        with patch.object(publish_release, "gh", side_effect=[json.dumps(refs),
                          json.dumps(dict(object=dict(type="commit", sha=COMMIT)))]):
            self.assertEqual(publish_release.remote_tag_commit("example/death-swap", "v1.0.0"), COMMIT)

    def test_older_main_push_cannot_request_latest(self):
        with tempfile.TemporaryDirectory() as folder:
            calls = fixtures.PublicationTests().run_publication(folder, main_commit=OTHER_COMMIT)
            publish = next(call for call in calls if "--draft=false" in call)
            self.assertIn("--latest=false", publish)


class ArtifactSafetyTests(unittest.TestCase):
    def fixture(self, folder):
        root = fixtures.DeploymentArchiveTests().fixture(folder)
        # Small class headers exercise metadata and artifact verification without Java compilation.
        bytecode = b"\xca\xfe\xba\xbe\x00\x00\x00\x45"
        metadata = dict(id="deathswap", version="1.0.1+mc26.2",
                        depends=dict(minecraft="26.2", java=">=25", fabricloader=">=0.19.3", **{"fabric-api": ">=0.154.2"}),
                        entrypoints=dict(main=["com.deathswap.DeathSwapMod"]))
        for directory, filename, resource, info, classes in [
            ("build/libs", "death-swap-1.0.1+mc26.2.jar", "fabric.mod.json", metadata,
             ["com/deathswap/DeathSwapMod.class", "com/deathswap/DeathSwapGame.class"]),
            ("velocity-plugin/build/libs", "death-swap-proxy-1.0.1.jar", "velocity-plugin.json",
             dict(id="deathswap-network", version="1.0.1", main="com.deathswap.proxy.DeathSwapProxyPlugin"),
             ["com/deathswap/proxy/DeathSwapProxyPlugin.class", "com/deathswap/proxy/RoundController.class"])]:
            output = root / directory / filename
            output.parent.mkdir(parents=True, exist_ok=True)
            with ZipFile(output, "w") as archive:
                archive.writestr(resource, json.dumps(info))
                archive.writestr("LICENSE", "MIT fixture")
                for name in classes:
                    archive.writestr(name, bytecode)
        original = root / "build/libs/death-swap-1.0.1+mc26.2.jar"
        destination = root / "build/26.3/libs/death-swap-1.0.1+mc26.3.jar"
        destination.parent.mkdir(parents=True)
        with ZipFile(original) as source, ZipFile(destination, "w") as archive:
            for name in source.namelist():
                content = source.read(name)
                if name == "fabric.mod.json":
                    data = json.loads(content)
                    data["version"] = "1.0.1+mc26.3"
                    data["depends"].update(minecraft="26.3", **{"fabric-api": ">=0.165.0"})
                    content = json.dumps(data)
                archive.writestr(name, content)
        return root

    def test_staging_checks_required_deliverables_and_excludes_sources_and_secrets(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            (root / ".env").write_text("secret fixture")
            (root / "build/libs/death-swap-1.0.1+mc26.2-sources.jar").write_bytes(b"sources fixture")
            output = root / "build/release"
            assets = stage(root, output, "1.0.1")
            self.assertEqual(len(assets), 4)
            self.assertEqual(len(verify(output, "1.0.1")[0]), 4)
            with ZipFile(assets[2]) as archive:
                self.assertNotIn(".env", archive.namelist())
                self.assertIn(b"mod_version=1.0.1", archive.read("gradle.properties"))
            assets[1].unlink()
            with self.assertRaisesRegex(ValueError, "exact release assets"):
                verify(output, "1.0.1")

    def test_wrong_embedded_versions_tests_and_empty_jars_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            mod_dir, proxy_dir = root / "build/libs", root / "velocity-plugin/build/libs"
            for directory, verifier in [
                    (mod_dir, partial(verify_mod, minecraft="26.2")),
                    (root / "build/26.3/libs", partial(verify_mod, minecraft="26.3")),
                    (proxy_dir, verify_proxy)]:
                jar = next(directory.glob("*.jar"))
                original = jar.read_bytes()
                with ZipFile(jar, "a") as archive:
                    archive.writestr("UnexpectedTests.class", b"test")
                with self.assertRaisesRegex(ValueError, "Test code"):
                    verifier(directory, "1.0.1")
                jar.write_bytes(original)
                renamed = jar.with_name(jar.name.replace("1.0.1", "1.0.2"))
                jar.rename(renamed)
                with self.assertRaisesRegex(ValueError, "embedded version"):
                    verifier(directory, "1.0.2")
                renamed.write_bytes(b"")
                with self.assertRaisesRegex(ValueError, "empty"):
                    verifier(directory, "1.0.2")

    def test_missing_artifact_cannot_be_staged(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            next((root / "velocity-plugin/build/libs").glob("*.jar")).unlink()
            with self.assertRaisesRegex(ValueError, "Missing or empty proxy"):
                stage(root, root / "output", "1.0.1")

    def test_checksum_manifest_covers_exact_assets_and_detects_tampering(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            asset = root / "artifact.jar"
            asset.write_bytes(b"fixture")
            manifest = root / "SHA256SUMS"
            digest = hashlib.sha256(asset.read_bytes()).hexdigest()
            manifest.write_text(f"{digest}  artifact.jar\n")
            verify_checksums(root)
            asset.write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                verify_checksums(root)
            manifest.write_text("")
            with self.assertRaisesRegex(ValueError, "cover all"):
                verify_checksums(root)


if __name__ == "__main__":
    unittest.main()
