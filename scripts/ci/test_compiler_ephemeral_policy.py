"""Source-free policy regression for compiler ephemeral workflow; no GitHub Actions/JVM invocation."""
from __future__ import annotations

from pathlib import Path
import ast
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest

PATH = Path(__file__).resolve().parents[2] / '.github/workflows/compiler-bundle-private-ephemeral.yml'
REPO = 'ores-truffle-oreslang/oreslang-source.java'
BRANCH = 'ci/immutable-jvm-compiler-bundle-20261018'


def allowed(state: dict[str, object]) -> bool:
    """Independent abstract admission model, not GitHub expression evaluation."""
    return all((
        state['event'] == 'push',
        state['repository'] == REPO,
        state['repository_id'] == '1400792274',
        state['private'] is True,
        state['fork'] is False,
        state['ref'] == 'refs/heads/' + BRANCH,
        state['ref_protected'] is True,
        state['runner_approval'] == 'true',
        state['sha'] == state['approved_sha'],
        bool(re.fullmatch(r'[0-9a-f]{40}', str(state['sha']))),
        bool(re.fullmatch(r'[0-9a-f]{40}', str(state['approved_tree']))),
    ))


class BundleEphemeralPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = PATH.read_text(encoding='utf8')
        cls.valid = dict(event='push', repository=REPO,
                         repository_id='1400792274', private=True, fork=False,
                         ref='refs/heads/' + BRANCH, ref_protected=True,
                         runner_approval='true', sha='a' * 40,
                         approved_sha='a' * 40, approved_tree='b' * 40)

    def test_only_fixed_branch_push_can_trigger(self):
        start = self.source.split('permissions:', 1)[0]
        self.assertIn('  push:\n', start)
        self.assertIn('      - ' + BRANCH, start)
        for event in ('pull_request:', 'pull_request_target:', 'workflow_dispatch:',
                      'schedule:', 'workflow_call:', 'repository_dispatch:'):
            self.assertNotIn(event, start)

    def test_no_credentials_or_source_export(self):
        source = self.source
        self.assertIn('permissions:\n  contents: read', source)
        self.assertIn('persist-credentials: false', source)
        self.assertNotIn('secrets.', source)
        self.assertNotIn('cache: maven', source)
        self.assertNotIn('workflow_run:', source)
        self.assertIn('private-source-local-jvm-bundle-unattested', source)
        self.assertIn('retention-days: 1', source)
        self.assertNotIn('repository: ', source)

    def test_all_external_actions_pinned(self):
        uses = re.findall(r'^\s*(?:-\s*)?uses:\s+([^\s#]+)',
                          self.source, re.M)
        self.assertEqual(len(uses), 3)
        for use in uses:
            with self.subTest(use=use):
                self.assertRegex(use, r'^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$')

    def test_required_both_positive_and_negative_guard_jobs(self):
        for name in ('deny-unapproved:', 'approved-compiler-build:'):
            self.assertIn('  ' + name, self.source)
        self.assertIn('exit 1', self.source)
        self.assertIn('runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]', self.source)
        self.assertIn("github.ref_protected != true", self.source)
        self.assertIn("github.ref_protected == true", self.source)
        self.assertIn("vars.ORESLANG_COMPILER_APPROVED_SHA == github.sha", self.source)
        self.assertIn("vars.ORESLANG_COMPILER_APPROVED_SHA != github.sha", self.source)
        self.assertIn("vars.ORESLANG_COMPILER_APPROVED_TREE == ''", self.source)
        self.assertIn("vars.ORESLANG_COMPILER_APPROVED_TREE != ''", self.source)

    def test_full_real_compile_and_cli_evidence_not_shortcut(self):
        for text in ('clean verify', 'copy-dependencies',
                     '--expected-manifest-sha256', '--probe-launcher',
                     'positive and negative OresMain source checks',
                     '--check', 'JAVA_HOME', 'status --porcelain', 'ls-files -v',
                     "'HEAD^{tree}'", 'test_compiler_bundle.py',
                     'test_compiler_ephemeral_policy.py'):
            self.assertIn(text, self.source)

    def test_approved_only_for_exact_protected_source_private_ref(self):
        self.assertTrue(allowed(self.valid))
        negative = {
            'event': 'pull_request', 'repository': 'foreign/source',
            'repository_id': '1', 'private': False, 'fork': True,
            'ref': 'refs/heads/foreign', 'ref_protected': False,
            'runner_approval': 'false', 'approved_sha': 'c' * 40,
            'approved_tree': '', 'sha': 'not-a-sha'
        }
        for key, changed in negative.items():
            with self.subTest(key=key):
                state = dict(self.valid, **{key: changed})
                self.assertFalse(allowed(state))

    def test_real_cli_gate_disables_ambient_imports_and_assert_optimization(self):
        """Parse the actual embedded runner program, not a pasted equivalent."""
        m = re.search(r"          python3 -I - <<'PY'\n(.*?)\n          PY\n",
                      self.source, re.S)
        self.assertIsNotNone(m)
        program = textwrap.dedent(m.group(1))
        syntax = ast.parse(program)
        self.assertFalse(any(isinstance(node, ast.Assert)
                             for node in ast.walk(syntax)),
                         'optimized Python removes assert-based CI gates')
        self.assertIn('raise SystemExit', program)
        self.assertIn('negative compiler diagnostic check failed', program)
        self.assertIn('positive compiler check failed', program)

    def test_real_cli_rejects_fake_compiler_success_and_failure_under_python_o(self):
        """A fake CLI always exiting 0 or 1 must not pass optimized CI."""
        m = re.search(r"          python3 -I - <<'PY'\n(.*?)\n          PY\n",
                      self.source, re.S)
        self.assertIsNotNone(m)
        code = textwrap.dedent(m.group(1))
        with tempfile.TemporaryDirectory(prefix='sourcefree-ephemeral-oracle-') as td:
            root = Path(td)
            (root / 'examples').mkdir()
            (root / 'examples' / 'together.ores').write_text('source-free fixture')
            (root / 'jdk' / 'bin').mkdir(parents=True)
            (root / 'jdk' / 'bin' / 'java').write_text('synthetic not executed')
            bundle = root / 'oreslang-compiler-bundle'
            bundle.mkdir()
            exe = bundle / 'run-oreslang'
            env = {
                'PATH': os.defpath, 'JAVA_HOME': str(root / 'jdk'),
                'GITHUB_WORKSPACE': str(root), 'RUNNER_TEMP': str(root),
                'PYTHONOPTIMIZE': '2',
            }
            for exit_code, expected in (
                (1, 'positive compiler check failed'),
                (0, 'negative compiler diagnostic check failed'),
            ):
                with self.subTest(always_returns=exit_code):
                    exe.write_text(f'#!/bin/sh\nexit {exit_code}\n')
                    exe.chmod(0o700)
                    result = subprocess.run(
                        [sys.executable, '-O', '-I', '-c', code],
                        cwd=root, env=env, text=True, capture_output=True,
                        timeout=20,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(expected, result.stderr)


    def test_trust_fields_present_in_both_gates(self):
        deny = self.source.split('  deny-unapproved:\n', 1)[1].split('  approved-compiler-build:\n')[0]
        grant = self.source.split('  approved-compiler-build:\n', 1)[1]
        fields = ['github.repository', 'github.repository_id',
                  'github.event.repository.private', 'github.event.repository.fork',
                  'github.ref', 'github.ref_protected',
                  'vars.ORESLANG_COMPILER_PRIVATE_RUNNER',
                  'vars.ORESLANG_COMPILER_APPROVED_SHA',
                  'vars.ORESLANG_COMPILER_APPROVED_TREE']
        for field in fields:
            with self.subTest(field=field):
                self.assertIn(field, deny)
                self.assertIn(field, grant)


if __name__ == '__main__':
    unittest.main()
