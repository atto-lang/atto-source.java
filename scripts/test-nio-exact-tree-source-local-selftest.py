#!/usr/bin/env python3
"""Run exact-tree gate failure injection on a synthetic Git repository only."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

GATE = Path(__file__).with_name('test-nio-exact-tree-source-local.sh')

SHIM = '''#!/usr/bin/env python3
import os, pathlib, shutil, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
version = os.environ.get('NIO_TEST_JDK', '25')
if name == 'java':
    print('openjdk version "' + version + '.0.2"', file=sys.stderr)
elif name == 'javac':
    print('javac ' + version + '.0.2')
elif name == 'native-image':
    pass
elif name == 'mvn':
    if '-version' in args:
        print('Apache Maven 3.9.9\\nJava version: ' + version + '.0.2')
    else:
        step = args[-1]
        with open(os.environ['NIO_TEST_TRACE'], 'a') as log:
            print(step, *args, file=log)
        if step == 'clean':
            if os.environ.get('NIO_TEST_FAIL') == 'clean': sys.exit(88)
            shutil.rmtree('target', ignore_errors=True)
        elif step == 'test':
            if pathlib.Path('target/stale.class').exists(): sys.exit(99)
            if not any('NioReadinessGroupTest' in s for s in args): sys.exit(99)
            if not any('OresFuturesTest' in s for s in args): sys.exit(99)
            if os.environ.get('NIO_TEST_FAIL') == 'test': sys.exit(89)
        elif step == 'verify':
            if os.environ.get('NIO_TEST_FAIL') == 'verify': sys.exit(90)
            if os.environ.get('NIO_TEST_FAIL') == 'mutate':
                with open('README.md', 'a') as f: print('MUTATED', file=f)
        elif step == 'package':
            if '-Pnative-aot' not in args: sys.exit(99)
        else: sys.exit(98)
        if step in ('test', 'verify'):
            suites = ('VmNioTcpListenerTest', 'NativeNetStdlibTest',
                      'NioReadinessGroupTest', 'NativeNioFutureReactorTest',
                      'OresSchedulerTest', 'OresCarrierPermitPoolTest',
                      'OresFuturesTest', 'OresFutureCallbackTest')
            reports = pathlib.Path('target/surefire-reports')
            reports.mkdir(parents=True, exist_ok=True)
            for suite in suites:
                (reports / ('TEST-dev.oreslang.' + suite + '.xml')).write_text(
                    '<testsuite><testcase classname="dev.oreslang.' + suite +
                    '" name="synthetic"/></testsuite>\\n')
else: sys.exit(98)
'''


def invoke(args, cwd, env=None, check=True):
    return subprocess.run(args, cwd=cwd, env=env, text=True,
                          capture_output=True, check=check, timeout=15)


def scenario(name, code, setup=None, *, aot=False):
    with tempfile.TemporaryDirectory(prefix='oreslang-source-free-ci-') as td:
        root = Path(td)
        repo, fakebin = root / 'repo', root / 'bin'
        repo.mkdir(); fakebin.mkdir(); (repo / 'scripts').mkdir()
        shutil.copy2(GATE, repo / 'scripts/gate.sh')
        (repo / '.gitignore').write_text('target/\n')
        (repo / 'README.md').write_text('synthetic fixture\n')
        for tool in ('java', 'javac', 'mvn', 'native-image'):
            shim = fakebin / tool
            shim.write_text(SHIM); shim.chmod(0o755)
        invoke(['git', 'init', '-q'], repo)
        invoke(['git', 'config', 'user.email', 'fixture@example.invalid'], repo)
        invoke(['git', 'config', 'user.name', 'Synthetic Gate'], repo)
        invoke(['git', 'add', '.'], repo)
        invoke(['git', 'commit', '-qm', 'synthetic source'], repo)
        head = invoke(['git', 'rev-parse', 'HEAD'], repo).stdout.strip()
        tree = invoke(['git', 'rev-parse', 'HEAD^{tree}'], repo).stdout.strip()
        env = os.environ.copy()
        env.update(PATH=str(fakebin) + os.pathsep + env['PATH'],
                   NIO_TEST_TRACE=str(root / 'trace'), NIO_TEST_JDK='25')
        outside = root / 'outside'; outside.mkdir()
        cmd = ['bash', 'scripts/gate.sh', head, tree]
        if setup == 'wrong-commit': cmd[2] = '0' * 40
        elif setup == 'wrong-tree': cmd[3] = '0' * 40
        elif setup == 'dirty': (repo / 'README.md').write_text('changed\n')
        elif setup == 'target-symlink': (repo / 'target').symlink_to(outside, target_is_directory=True)
        elif setup == 'receipt-symlink':
            (repo / 'target').mkdir()
            (repo / 'target/nio-exact-tree-gate').symlink_to(outside, target_is_directory=True)
        elif setup == 'stale':
            (repo / 'target').mkdir()
            (repo / 'target/stale.class').write_text('stale build')
        elif setup == 'wrong-jdk': env['NIO_TEST_JDK'] = '21'
        elif setup in ('clean', 'test', 'verify', 'mutate'): env['NIO_TEST_FAIL'] = setup
        if aot: cmd.append('--native-aot')
        result = invoke(cmd, repo, env, check=False)
        assert result.returncode == code, (name, result.returncode, result.stderr)
        if setup in ('target-symlink', 'receipt-symlink'):
            assert not list(outside.iterdir()), (name, 'wrote outside checkout')
        else:
            receipt = repo / 'target/nio-exact-tree-gate/receipt.txt'
            assert receipt.is_file(), (name, 'missing receipt')
            body = receipt.read_text()
            assert f'exit_code={code}\n' in body, (name, body)
            assert ('result=PASS' in body) == (code == 0), name
            if setup in ('clean', 'test', 'verify', 'mutate'):
                stage = dict(clean='clean-stale-build', test='targeted-junit',
                             verify='full-maven-verify', mutate='postflight-source-identity')[setup]
                assert f'stage={stage}\n' in body, (name, body)
        trace = root / 'trace'
        calls = [row.split()[0] for row in trace.read_text().splitlines()] if trace.exists() else []
        if code == 0:
            assert calls == (['clean', 'test', 'verify', 'package'] if aot
                             else ['clean', 'test', 'verify']), (name, calls)
            assert not (repo / 'target/stale.class').exists()
            assert (repo / 'target/nio-exact-tree-gate/targeted-junit.log').exists()
        if setup in ('wrong-commit', 'wrong-tree', 'dirty', 'target-symlink',
                     'receipt-symlink', 'wrong-jdk'):
            assert not calls, (name, 'Maven ran before gate admission')
        print(f'PASS {name} (exit={code})')


if __name__ == '__main__':
    tests = [('clean-stale-class-output', 0, 'stale', False),
             ('native-image-option', 0, None, True),
             ('wrong-commit', 66, 'wrong-commit', False),
             ('wrong-tree', 66, 'wrong-tree', False),
             ('dirty-checkout', 67, 'dirty', False),
             ('target-symlink', 68, 'target-symlink', False),
             ('receipt-symlink', 68, 'receipt-symlink', False),
             ('wrong-jdk', 69, 'wrong-jdk', False),
             ('clean-failure', 88, 'clean', False),
             ('targeted-failure', 89, 'test', False),
             ('verify-failure', 90, 'verify', False),
             ('postflight-mutation', 70, 'mutate', False)]
    for name, code, fixture, aot in tests:
        scenario(name, code, fixture, aot=aot)
    print(f'PASS: {len(tests)}/{len(tests)} source-free gate scenarios')
