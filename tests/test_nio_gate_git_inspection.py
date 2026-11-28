"""Source-free exact-tree gate regression: Git inspection failures must never PASS.

Copies ONLY the qualification shell helper and XML verifier into disposable Git
fixtures. Does not require or transfer any private Oreslang compiler sources.
"""
from __future__ import annotations
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SRC = Path(__file__).resolve().parents[1] / 'scripts'
NAMES = ('VmNioTcpListenerTest', 'NativeNetStdlibTest', 'NioReadinessGroupTest',
         'NativeNioFutureReactorTest', 'OresSchedulerTest',
         'OresCarrierPermitPoolTest', 'OresFuturesTest', 'OresFutureCallbackTest')


def call(argv, **kwargs):
    return subprocess.run(argv, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=30, **kwargs)


class GitInspectionFailureTests(unittest.TestCase):
    def run_fixture(self, mode, phase='preflight', *, expected=75):
        with tempfile.TemporaryDirectory(prefix='ores-nio-git-probe-') as tmp:
            root = Path(tmp) / 'repo'
            root.mkdir()
            bin_path = Path(tmp) / 'bin'
            bin_path.mkdir()
            (root / 'scripts').mkdir()
            for name in ('test-nio-exact-tree-source-local.sh',
                         'nio_verify_surefire_evidence.py',
                         'nio_verify_raw_worktree.py'):
                shutil.copy2(SRC / name, root / 'scripts' / name)
            (root / '.gitignore').write_text('target/\n')
            (root / 'README.md').write_text('source-free fixture\n')
            for argv in (['git', 'init', '-q'],
                         ['git', 'config', 'user.email', 'fixture@example.invalid'],
                         ['git', 'config', 'user.name', 'Source-free CI'],
                         ['git', 'add', '.'], ['git', 'commit', '-qm', 'fixture']):
                subprocess.run(argv, cwd=root, check=True, capture_output=True)
            sha = call(['git', 'rev-parse', 'HEAD'], cwd=root).stdout.strip()
            tree = call(['git', 'rev-parse', 'HEAD^{tree}'], cwd=root).stdout.strip()
            git_shim = r'''#!/bin/sh
case " $* " in
  *" %MODE% "*)
    if [ "${NIO_FAKE_FAIL_PHASE:-preflight}" = preflight ] || [ -e target/nio-exact-tree-gate/full-maven-verify.log ]; then
      echo 'injected git inspection failure' >&2; exit 128
    fi ;;
esac
exec /usr/bin/git "$@"
'''.replace('%MODE%', mode)
            (bin_path / 'git').write_text(git_shim)
            (bin_path / 'git').chmod(0o755)
            fixtures = {
                'java': '#!/bin/sh\necho \'openjdk version "25.0.1"\' >&2\n',
                'javac': '#!/bin/sh\necho "javac 25.0.1"\n',
                'mvn': r'''#!/bin/sh
if [ "$1" = -version ]; then printf 'Maven 3.9.9\nJava version: 25.0.1\n'; exit 0; fi
case " $* " in *" clean "*) rm -rf target; exit 0;; esac
mkdir -p target/surefire-reports
for suite in VmNioTcpListenerTest NativeNetStdlibTest NioReadinessGroupTest NativeNioFutureReactorTest OresSchedulerTest OresCarrierPermitPoolTest OresFuturesTest OresFutureCallbackTest; do
printf '<testsuite tests="1" skipped="0" failures="0" errors="0"><testcase classname="dev.oreslang.%s" name="synthetic"/></testsuite>\n' "$suite" > "target/surefire-reports/TEST-dev.oreslang.$suite.xml"
done
touch -t 203501010101 target/surefire-reports/TEST-*.xml
exit 0
''',
            }
            for name, body in fixtures.items():
                f = bin_path / name
                f.write_text(body)
                f.chmod(0o755)
            env = os.environ.copy()
            env['PATH'] = str(bin_path) + ':' + env['PATH']
            env['NIO_FAKE_FAIL_PHASE'] = phase
            result = call(['bash', 'scripts/test-nio-exact-tree-source-local.sh', sha, tree],
                          cwd=root, env=env)
            receipt = root / 'target/nio-exact-tree-gate/receipt.txt'
            self.assertEqual(expected, result.returncode, result.stderr + result.stdout)
            self.assertTrue(receipt.exists())
            body = receipt.read_text()
            self.assertIn('result=FAIL' if expected else 'result=PASS', body)
            if expected:
                self.assertIn('exit_code=75', body)
                self.assertIn('stage=' + ('source-identity' if phase == 'preflight'
                                                 else 'postflight-source-identity'), body)
            if phase == 'preflight' and expected:
                self.assertFalse((root / 'target/nio-exact-tree-gate/targeted-junit.log').exists())
            else:
                self.assertTrue((root / 'target/nio-exact-tree-gate/full-maven-verify.log').exists())

    def test_git_untracked_scan_failure_before_maven_fails_closed(self):
        self.run_fixture('ls-files --others --exclude-standard')

    def test_git_ignored_scan_failure_before_maven_fails_closed(self):
        self.run_fixture('ls-files --others')

    def test_git_untracked_scan_failure_after_maven_fails_closed(self):
        self.run_fixture('ls-files --others --exclude-standard', 'postflight')

    def test_git_ignored_scan_failure_after_maven_fails_closed(self):
        self.run_fixture('ls-files --others', 'postflight')

    def test_git_index_inspection_failure_before_maven_fails_closed(self):
        self.run_fixture('ls-files -v', 'preflight')

    def test_git_index_inspection_failure_after_maven_fails_closed(self):
        self.run_fixture('ls-files -v', 'postflight')

    def test_source_free_clean_probe_still_passes(self):
        # Deliberately no matching Git operation: all scans complete normally.
        self.run_fixture('not-a-real-git-subcommand', expected=0)


class RawWorktreeSecurityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ores-nio-raw-fixture-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'repo'
        self.root.mkdir()
        (self.root / 'scripts').mkdir()
        shutil.copy2(SRC / 'nio_verify_raw_worktree.py',
                     self.root / 'scripts' / 'nio_verify_raw_worktree.py')
        (self.root / 'source.txt').write_text('trusted source\n')
        (self.root / 'nested').mkdir()
        (self.root / 'nested' / 'source.txt').write_text('nested trusted source\n')
        (self.root / 'linkdir').mkdir()
        (self.root / 'linkdir' / 'tracked-link').symlink_to('../source.txt')
        for argv in (['git', 'init', '-q'],
                     ['git', 'config', 'user.email', 'fixture@example.invalid'],
                     ['git', 'config', 'user.name', 'Source-free CI'],
                     ['git', 'add', '.'], ['git', 'commit', '-qm', 'fixture']):
            subprocess.run(argv, cwd=self.root, check=True, capture_output=True)
        self.sha = call(['git', 'rev-parse', 'HEAD'], cwd=self.root).stdout.strip()
        self.tree = call(['git', 'rev-parse', 'HEAD^{tree}'], cwd=self.root).stdout.strip()

    def gate(self):
        return call(['python3', '-I', '-B', 'scripts/nio_verify_raw_worktree.py',
                     self.sha, self.tree], cwd=self.root)

    def test_clean_raw_worktree_passes(self):
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)

    def test_fifo_substitution_fails_without_parking(self):
        tracked = self.root / 'source.txt'
        tracked.unlink()
        os.mkfifo(tracked)
        result = self.gate()
        self.assertEqual(77, result.returncode, result.stderr + result.stdout)
        self.assertIn('tracked input is not regular', result.stderr)

    def test_hardlinked_tracked_input_is_rejected(self):
        external = Path(self.tmp.name) / 'external-source-alias.txt'
        os.link(self.root / 'source.txt', external)
        status = call(['git', 'status', '--porcelain=v1'], cwd=self.root)
        self.assertEqual('', status.stdout, status.stderr)
        result = self.gate()
        self.assertEqual(77, result.returncode, result.stderr + result.stdout)
        self.assertIn('multiply-linked tracked input', result.stderr)

    def test_hardlink_removal_restores_admission(self):
        external = Path(self.tmp.name) / 'temporary-source-alias.txt'
        os.link(self.root / 'source.txt', external)
        self.assertEqual(77, self.gate().returncode)
        external.unlink()
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)


    def test_parent_directory_symlink_is_rejected(self):
        original = self.root / 'nested'
        external = Path(self.tmp.name) / 'external-nested'
        original.rename(external)
        original.symlink_to(external, target_is_directory=True)
        # The tracked leaf bytes remain reachable through the symlink. Git
        # porcelain also notices this topology change, but the raw verifier
        # must independently reject it without relying on porcelain status.
        result = self.gate()
        self.assertEqual(77, result.returncode, result.stderr + result.stdout)


    def test_tracked_symlink_parent_directory_symlink_is_rejected(self):
        original = self.root / 'linkdir'
        external = Path(self.tmp.name) / 'external-linkdir'
        original.rename(external)
        original.symlink_to(external, target_is_directory=True)
        result = self.gate()
        self.assertEqual(77, result.returncode, result.stderr + result.stdout)


if __name__ == '__main__':
    unittest.main()
