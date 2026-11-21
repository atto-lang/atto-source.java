"""Source-free synthetic receipt tests; not Java compilation or guest execution."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import ml_guest_receipt as m


class MlGuestReceiptTests(unittest.TestCase):
    def setUp(self):
        self.old = Path.cwd()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, self.old)
        subprocess.run(['git', 'init', '-q'], check=True)
        m.FIXTURE.parent.mkdir(parents=True)
        content = b'synthetic fixture only\n'
        m.FIXTURE.write_bytes(content)
        self.fixture_blob = hashlib.sha1(
            b'blob ' + str(len(content)).encode() + b'\0' + content
        ).hexdigest()
        subprocess.run(['git', 'add', str(m.FIXTURE)], check=True)
        subprocess.run(['git', '-c', 'user.email=test@example.invalid',
                        '-c', 'user.name=Test', 'commit', '-qm', 'fixture'], check=True)
        self.head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        self.xml = Path('report.xml')
        self.stdout = Path('stdout.txt')
        self.build_info = Path('build-info.json')
        self.jar = Path('compiler.jar')
        self.stdout.write_bytes(m.STDOUT)
        self.build_info.write_text(json.dumps({
            'build_mode': 'jvm', 'default_execution_mode': 'jit',
            'guest_execution': 'runtime-selected'
        }))
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('dev/oreslang/launcher/OresMain.class', b'synthetic')
            z.writestr('module-info.class', b'synthetic')
        self.write_xml()

    def write_xml(self, methods=None, skipped=0):
        methods = list(m.EXPECTED_METHODS) if methods is None else methods
        tests = ''.join(
            f'<testcase classname="dev.oreslang.MlActorStateScalarGuestTest" name="{n}"/>'
            for n in methods
        )
        self.xml.write_text(
            f'<testsuite tests="{len(methods)}" failures="0" errors="0" '
            f'skipped="{skipped}">{tests}</testsuite>'
        )

    def receipt(self):
        with patch.object(m, 'FIXTURE_BLOB', self.fixture_blob):
            return m.make_receipt(self.head, self.xml, self.stdout,
                                  self.build_info, self.jar)

    def test_synthetic_receipt_remains_unattested(self):
        r = self.receipt()
        self.assertEqual(r['java_junit_test_count'], 6)
        self.assertFalse(r['source_binary_provenance_attested'])
        self.assertFalse(r['release_ready'])

    def test_wrong_commit_rejected(self):
        self.head = 'f' * 40
        with self.assertRaisesRegex(ValueError, 'wrong checked-out'):
            self.receipt()

    def test_forged_stdout_rejected(self):
        self.stdout.write_bytes(m.STDOUT + b'FAKE_PASS\n')
        with self.assertRaisesRegex(ValueError, 'stdout differs'):
            self.receipt()

    def test_missing_test_method_rejected(self):
        self.write_xml(methods=list(m.EXPECTED_METHODS)[:-1])
        with self.assertRaisesRegex(ValueError, 'missing, duplicate'):
            self.receipt()

    def test_skipped_test_rejected(self):
        self.write_xml(skipped=1)
        with self.assertRaisesRegex(ValueError, 'JUnit skipped'):
            self.receipt()

    def test_skip_worktree_fixture_tamper_rejected(self):
        subprocess.run(['git', 'update-index', '--skip-worktree', str(m.FIXTURE)],
                       check=True)
        m.FIXTURE.write_bytes(b'changed\n')
        with self.assertRaisesRegex(ValueError, 'fixture differs'):
            self.receipt()

    def test_missing_jar_launcher_rejected(self):
        with zipfile.ZipFile(self.jar, 'w') as z:
            z.writestr('module-info.class', b'synthetic')
        with self.assertRaisesRegex(ValueError, 'lacks OresMain'):
            self.receipt()

    def test_wrong_junit_classname_rejected(self):
        self.xml.write_text(
            self.xml.read_text().replace(
                'dev.oreslang.MlActorStateScalarGuestTest', 'UnrelatedSyntheticTest'
            )
        )
        with self.assertRaisesRegex(ValueError, 'wrong JUnit testcase class'):
            self.receipt()

    def test_oversized_build_info_rejected(self):
        self.build_info.write_text('X' * 4097)
        with self.assertRaisesRegex(ValueError, 'build-info size'):
            self.receipt()

    def test_inherited_git_dir_cannot_redirect_identity(self):
        from unittest.mock import patch as mock_patch
        with mock_patch.dict(os.environ, {'GIT_DIR': str(Path.cwd() / 'fake-git'),
                                          'GIT_WORK_TREE': str(Path.cwd()),
                                          'GIT_NO_REPLACE_OBJECTS': '0'}):
            self.assertEqual(self.receipt()['source_commit'], self.head)

    def test_missing_build_info_rejected(self):
        self.build_info.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'build-info'):
            self.receipt()


if __name__ == '__main__':
    unittest.main()
