"""Both Minecraft deliverables must carry exact, matching target metadata."""
import json
import tempfile
import unittest
from zipfile import ZipFile

import test_release_safety
from package_release import stage
from verify_mod_jar import verify


class TargetTests(unittest.TestCase):
    def fixture(self, folder):
        return test_release_safety.ArtifactSafetyTests().fixture(folder)

    def test_both_exact_targets_and_dependency_floors(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            for target, directory in [('26.2', 'build/libs'), ('26.3', 'build/26.3/libs')]:
                with self.subTest(target=target):
                    jar, metadata = verify(root / directory, '1.0.1', minecraft=target)
                    self.assertEqual(metadata['depends']['minecraft'], target)
                    self.assertEqual(jar.name, f'death-swap-1.0.1+mc{target}.jar')
                    data = jar.read_bytes()
                    with ZipFile(jar) as archive:
                        entries = {name: archive.read(name) for name in archive.namelist()}
                    for key, wrong in [('minecraft', '>=26.2'), ('fabric-api', '>=0.1'),
                                       ('fabricloader', '>=0.1')]:
                        altered = json.loads(entries['fabric.mod.json'])
                        altered['depends'][key] = wrong
                        with ZipFile(jar, 'w') as archive:
                            for name, content in entries.items():
                                archive.writestr(name, json.dumps(altered) if name == 'fabric.mod.json' else content)
                        with self.assertRaises(ValueError):
                            verify(root / directory, '1.0.1', minecraft=target)
                        jar.write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'Unsupported Minecraft'):
                verify(root / 'build/libs', '1.0.1', minecraft='26.4')

    def test_release_contains_both_mods_and_original_docker_path(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            assets = stage(root, root / 'release', '1.0.1')
            self.assertEqual({asset.name for asset in assets}, {
                'death-swap-1.0.1+mc26.2.jar', 'death-swap-1.0.1+mc26.3.jar',
                'death-swap-proxy-1.0.1.jar', 'death-swap-docker-1.0.1+mc26.2.zip'})

    def test_missing_second_target_cannot_stage_release(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            (root / 'build/26.3/libs/death-swap-1.0.1+mc26.3.jar').unlink()
            with self.assertRaisesRegex(ValueError, 'Missing or empty distributable'):
                stage(root, root / 'release', '1.0.1')


if __name__ == '__main__':
    unittest.main()
