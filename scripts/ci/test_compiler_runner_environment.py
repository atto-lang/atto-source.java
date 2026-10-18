"""Source-free tests for the approved compiler runner's process-environment admission."""
from pathlib import Path
import os
import re
import subprocess
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
