import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from verify_compiler_build import capture, strict_json


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()


class BundleReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.repo = root / 'source'
        self.repo.mkdir()
        git(self.repo, 'init', '-q')
        git(self.repo, 'config', 'user.email', 'test@example.invalid')
        git(self.repo, 'config', 'user.name', 'Test')
        (self.repo / 'examples').mkdir()
        (self.repo / 'examples' / 'together.ores').write_text('synthetic source\n')
        git(self.repo, 'add', '.')
        git(self.repo, 'commit', '-qm', 'snapshot')
        self.commit = git(self.repo, 'rev-parse', 'HEAD')
        self.tree = git(self.repo, 'show', '-s', '--format=%T', 'HEAD')
        self.bundle = root / 'bundle'
        (self.bundle / 'lib').mkdir(parents=True)
        (self.bundle / 'native').mkdir()
        (self.bundle / 'native' / 'liboresthread.so').write_bytes(b'synthetic native library')
        with ZipFile(self.bundle / 'oreslang-source.jar', 'w') as jar:
            jar.writestr('dev/oreslang/launcher/OresMain.class', b'synthetic')
        with ZipFile(self.bundle / 'lib' / 'dependency.jar', 'w') as jar:
            jar.writestr('synthetic.txt', b'one')
        self.report = root / 'versions.txt'
        self.report.write_text('GraalVM JDK 25\nApache Maven 3.9\nNative Image 25.3\n')
        self.launcher = self.bundle / 'launcher'
        self.launcher.write_text('''#!/bin/sh
case "$1" in
--build-info) printf '%s\\n' '{"build_mode":"jvm","default_execution_mode":"jit","guest_execution":"runtime-selected"}'; exit 0;;
--check) case "$2" in */invalid.ores) printf '%s:1:1: error: syntax error\\n' "$2" >&2; exit 1;; *) exit 0;; esac;;
esac
exit 9
''')
        self.launcher.chmod(0o700)

    def capture(self, **kw):
        return capture(self.repo, self.bundle, kw.get('sha', self.commit),
                       kw.get('tree', self.tree), kw.get('mode', 'jvm'),
                       kw.get('report'))

    def test_positive_bundle(self):
        result = self.capture()
        self.assertTrue(result['negative_check'])
        self.assertFalse(result['independent_provenance_attested'])
        self.assertEqual(len(result['bundle_files']), 4)

    def test_source_commit_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'wrong source commit'):
            self.capture(sha='0' * 40)

    def test_tree_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'wrong source Git tree'):
            self.capture(tree='0' * 40)

    def test_missing_dependency(self):
        (self.bundle / 'lib' / 'dependency.jar').unlink()
        with self.assertRaisesRegex(ValueError, 'missing or extra dependency'):
            self.capture()

    def test_symlink_dependency(self):
        link = self.bundle / 'lib' / 'dependency.jar'
        link.rename(self.bundle / 'lib' / 'real-dep.jar')
        link.symlink_to('real-dep.jar')
        with self.assertRaisesRegex(ValueError, 'missing or symlinked'):
            self.capture()

    def test_no_launcher_class(self):
        with ZipFile(self.bundle / 'oreslang-source.jar', 'w') as jar:
            jar.writestr('wrong.class', b'synthetic')
        with self.assertRaisesRegex(ValueError, 'real compiled Java launcher class missing'):
            self.capture()

    def test_unsupported_requested_mode(self):
        with self.assertRaisesRegex(ValueError, 'unsupported expected build mode'):
            self.capture(mode='native')

    def test_incorrect_runtime_mode(self):
        with self.assertRaisesRegex(ValueError, 'unsupported launcher mode or capabilities'):
            self.capture(mode='aot')

    def test_negative_source_accepted_fails(self):
        source = self.launcher.read_text()
        self.launcher.write_text(source.replace("printf '%s:1:1: error: syntax error\\n' \"$2\" >&2; exit 1", 'exit 0'))
        with self.assertRaisesRegex(ValueError, 'negative real compiler check failed'):
            self.capture()

    def test_duplicate_build_info_fails(self):
        source = self.launcher.read_text().replace('"build_mode":"jvm",', '"build_mode":"jvm","build_mode":"jvm",')
        self.launcher.write_text(source)
        with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
            self.capture()

    def test_dirty_repo_denied(self):
        (self.repo / 'examples' / 'together.ores').write_text('tampered\n')
        with self.assertRaisesRegex(ValueError, 'dirty source checkout'):
            self.capture()

    def test_wrong_artifact_hash_detected(self):
        receipt = self.capture()
        with ZipFile(self.bundle / 'lib' / 'dependency.jar', 'w') as jar:
            jar.writestr('synthetic.txt', b'tampered')
        self.assertNotEqual(self.capture()['bundle_files'], receipt['bundle_files'])

    def test_masked_git_entry_denied(self):
        git(self.repo, 'update-index', '--skip-worktree', 'examples/together.ores')
        with self.assertRaisesRegex(ValueError, 'masked Git index entry'):
            self.capture()

    def test_verify_cli_rejects_mutated_dependency_after_receipt(self):
        script = Path(__file__).resolve().parents[1] / 'scripts' / 'verify_compiler_build.py'
        receipt = self.bundle.parent / 'receipt.json'
        args = [sys.executable, str(script), '--source-root', str(self.repo),
                '--bundle', str(self.bundle), '--expected-source-sha', self.commit,
                '--expected-tree', self.tree, '--receipt', str(receipt)]
        creation = subprocess.run(args, text=True, capture_output=True)
        self.assertEqual(creation.returncode, 0, creation.stderr)
        with ZipFile(self.bundle / 'lib' / 'dependency.jar', 'w') as jar:
            jar.writestr('synthetic.txt', b'new contents')
        verification = subprocess.run(args + ['--verify'], text=True, capture_output=True)
        self.assertNotEqual(verification.returncode, 0)
        self.assertIn('receipt mismatch', verification.stderr)

    def test_launcher_creating_extra_module_jar_is_denied(self):
        body = self.launcher.read_text().replace(
            'exit 0;;',
            'exit 0;;',
        )
        body = body.replace('case "$1" in',
            'touch "' + str(self.bundle / 'lib' / 'surprise.jar') + '"\ncase "$1" in')
        self.launcher.write_text(body)
        with self.assertRaisesRegex(ValueError, 'bundle file set mutated'):
            self.capture()

    def test_toolchain_report_is_hash_bound(self):
        first = self.capture(report=self.report)
        self.report.write_text('changed compiler toolchain report\n')
        second = self.capture(report=self.report)
        self.assertNotEqual(first['toolchain_report'], second['toolchain_report'])

    def test_missing_toolchain_report_denied(self):
        with self.assertRaisesRegex(ValueError, 'toolchain report missing'):
            self.capture(report=self.bundle.parent / 'missing-version-file')

    def test_cannot_reuse_different_bundle(self):
        self.assertEqual(self.capture()['source_commit'], self.commit)
        self.assertEqual(self.capture()['source_tree'], self.tree)


if __name__ == '__main__':
    unittest.main()
