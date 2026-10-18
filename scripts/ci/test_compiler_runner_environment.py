"""Source-free tests for the approved compiler runner's process-environment admission."""
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import unittest

WORKFLOW = (Path(__file__).resolve().parents[2] /
            '.github/workflows/compiler-bundle-private-ephemeral.yml')
STEP = 'Refuse ambient compiler and Git process overrides'
FORBIDDEN = ('MAVEN_OPTS', 'MAVEN_ARGS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
             'JDK_JAVA_OPTIONS', 'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE',
             'GIT_CONFIG_COUNT', 'PYTHONPATH', 'PYTHONHOME')
CONFIG_FILES = ('.mvn/maven.config', '.mvn/jvm.config', '.mvn/extensions.xml')


def policy_script():
    workflow = WORKFLOW.read_text(encoding='utf-8')
    match = re.search(r'      - name: ' + re.escape(STEP) +
                      r'\n        shell: bash\n        run: \|\n((?:          .*\n)+)',
                      workflow)
    if not match:
        raise ValueError('runner environment policy step missing or not literal bash')
    return ''.join(line[10:] + '\n' for line in match.group(1).splitlines())


class CompilerRunnerEnvironmentTests(unittest.TestCase):
    def test_python_bootstrap_precedes_bash_and_checkout(self):
        source = WORKFLOW.read_text(encoding='utf-8')
        boot = source.index(
            '      - name: Reject exported Bash functions before any Bash step')
        approved = source.index(
            '      - name: Verify protected source authorization before checkout')
        checkout = source.index(
            '      - name: Checkout approved SHA from original private repository only')
        self.assertLess(boot, approved)
        self.assertLess(approved, checkout)
        section = source[boot:approved]
        self.assertIn('        shell: python -I {0}\n', section)
        self.assertIn("k.startswith('BASH_FUNC_')", section)
        job_env = source.split('  approved-compiler-build:\n', 1)[1].split(
            '\n    steps:\n', 1)[0]
        self.assertIn("      LD_AUDIT: ''", job_env)
        self.assertIn("      LD_DEBUG: ''", job_env)

    def test_exported_bash_functions_and_options_rejected(self):
        source = WORKFLOW.read_text(encoding='utf-8')
        section = source.split(
            '      - name: Reject exported Bash functions before any Bash step\n',
            1)[1].split(
            '      - name: Verify protected source authorization before checkout\n',
            1)[0]
        match = re.search(r'        run: \|\n((?:          .*\n)+)', section)
        self.assertIsNotNone(match)
        script = ''.join(line[10:] + '\n'
                         for line in match.group(1).splitlines())
        baseline = {'PATH': os.defpath, 'HOME': '/tmp', 'LC_ALL': 'C'}
        def execute(env):
            return subprocess.run(
                [sys.executable, '-I', '-c', script], env=env,
                capture_output=True, text=True, timeout=10)
        clean = execute(baseline)
        self.assertEqual(clean.returncode, 0, clean.stderr)
        scenarios = (
            ('BASH_FUNC_git%%', '() { echo injected; }'),
            ('BASH_FUNC_test%%', '() { echo injected; }'),
            ('BASH_FUNC_builtin%%', '() { echo injected; }'),
            ('SHELLOPTS', 'xtrace'),
            ('BASHOPTS', 'extdebug'),
        )
        for key, value in scenarios:
            with self.subTest(variable=key):
                result = execute(dict(baseline, **{key: value}))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)


    def test_job_env_disables_shell_startup_injection(self):
        """Runner BASH_ENV must not execute code before a policy guard starts."""
        source = WORKFLOW.read_text(encoding='utf-8')
        job_env = source.split('  approved-compiler-build:\n', 1)[1].split(
            '\n    steps:\n', 1)[0]
        for key in ('BASH_ENV', 'ENV', 'NODE_OPTIONS', 'NODE_PATH', 'LD_PRELOAD'):
            with self.subTest(name=key):
                self.assertIn("      " + key + ": ''", job_env)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trace = root / 'trace'
            hook = root / 'hook.sh'
            hook.write_text('printf "HOOK_EXECUTED\n" >> "$TRACE_FILE"\n')
            env = {'PATH': os.defpath, 'TRACE_FILE': str(trace),
                   'BASH_ENV': str(hook)}
            command = ['bash', '--noprofile', '--norc', '-e', '-o',
                       'pipefail', '-c', 'printf "REAL_STEP\n"']
            unsafe = subprocess.run(command, env=env, capture_output=True,
                                    text=True, timeout=10)
            self.assertEqual(unsafe.returncode, 0)
            self.assertIn('HOOK_EXECUTED', trace.read_text())
            trace.unlink()
            env['BASH_ENV'] = ''
            clean = subprocess.run(command, env=env, capture_output=True,
                                   text=True, timeout=10)
            self.assertEqual(clean.returncode, 0)
            self.assertFalse(trace.exists(), 'Bash executed a pre-step hook')

    def test_unknown_git_environment_overrides_fail_closed(self):
        for key in ('GIT_CONFIG_GLOBAL', 'GIT_OBJECT_DIRECTORY',
                    'GIT_ALTERNATE_OBJECT_DIRECTORIES',
                    'GIT_TRACE2_EVENT', 'GIT_SSH_COMMAND'):
            with self.subTest(key=key):
                result = self.execute(overrides={key: 'injected'})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)

    def test_unknown_git_guard_before_and_after_checkout(self):
        source = WORKFLOW.read_text(encoding='utf-8')
        before = source.split(
            '      - name: Reject ambient launch overrides before checkout\n', 1)[1].split(
            '      - name: Checkout approved SHA from original private repository only\n', 1)[0]
        after = source.split(
            '      - name: Refuse ambient compiler and Git process overrides\n', 1)[1].split(
            '      - name: Build and run full actual Java compiler tests\n', 1)[0]
        marker = 'for key in "${!GIT_@}"; do'
        self.assertIn(marker, before)
        self.assertIn(marker, after)

    def execute(self, *, overrides=None, config=None):
        with tempfile.TemporaryDirectory(prefix='oreslang-runner-policy-') as temp:
            root = Path(temp)
            (root / '.mvn').mkdir()
            if config:
                target = root / config
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('unreviewed override', encoding='utf-8')
            env = {'PATH': os.defpath, 'HOME': temp, 'LC_ALL': 'C'}
            env.update(overrides or {})
            return subprocess.run(['bash', '--noprofile', '--norc', '-e', '-o',
                                   'pipefail', '-c', policy_script()], cwd=root,
                                  env=env, capture_output=True, text=True, timeout=10)

    def test_second_environment_check_runs_after_setup_before_maven(self):
        workflow = WORKFLOW.read_text(encoding='utf-8')
        setup = workflow.index('      - name: Install existing GraalVM JDK25')
        guard = workflow.index('      - name: Refuse ambient compiler and Git process overrides')
        build = workflow.index('      - name: Build and run full actual Java compiler tests')
        self.assertLess(setup, guard)
        self.assertLess(guard, build)

    def test_bootstrap_gate_runs_before_checkout(self):
        workflow = WORKFLOW.read_text(encoding='utf-8')
        start = workflow.index('      - name: Reject ambient launch overrides before checkout')
        checkout = workflow.index('      - name: Checkout approved SHA from original private repository only')
        check_source = workflow.index('      - name: Verify exact SHA and tree')
        self.assertLess(start, checkout)
        self.assertLess(checkout, check_source)
        bootstrap = workflow[start:checkout]
        for key in FORBIDDEN:
            self.assertIn(key, bootstrap)
        self.assertIn('exit 1', bootstrap)

    def test_clean_is_accepted(self):
        result = self.execute()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_every_ambient_override_is_denied(self):
        for key in FORBIDDEN:
            with self.subTest(key=key):
                result = self.execute(overrides={key: 'unreviewed-value'})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)

    def test_repository_maven_overrides_are_denied(self):
        for config in CONFIG_FILES:
            with self.subTest(config=config):
                result = self.execute(config=config)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(config, result.stderr)

    def test_empty_environment_value_is_allowed(self):
        self.assertEqual(self.execute(overrides={'MAVEN_OPTS': ''}).returncode, 0)


if __name__ == '__main__':
    unittest.main()
