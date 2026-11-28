#!/usr/bin/env python3
"""Exact-source, full-runtime-bundle *local* evidence; NOT independent attestation.

Only use with an authorized, isolated compiler build environment. An executable
may lie about its behavior and can access resources permitted to the OS user.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import signal
import stat
import subprocess
import tempfile
import zipfile

SHA40 = re.compile(r'[0-9a-f]{40}\Z')
SHA256 = re.compile(r'[0-9a-f]{64}\Z')
MODES = {'jvm': ('jit', 'runtime-selected'),
         'aot': ('aot', 'interpreter-no-jit'),
         'hybrid': ('hybrid', 'runtime-selected')}
ENV = {'PATH': os.defpath, 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC',
       'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
       'GIT_NO_REPLACE_OBJECTS': '1'}


def deny(ok, reason):
    if not ok:
        raise ValueError('JAVA_COMPILER_RECEIPT_DENIED: ' + reason)


def git(repo, *args):
    return subprocess.run(['git', '-c', 'core.fsmonitor=false', '-C', str(repo), *args],
                          env=ENV, text=True, capture_output=True, timeout=15,
                          check=True).stdout.strip()


def file_identity(path):
    deny(not path.is_symlink() and path.is_file(), 'missing or symlinked bundle file')
    meta = path.stat()
    deny(stat.S_ISREG(meta.st_mode) and meta.st_nlink == 1,
         'bundle file must be singly linked regular file')
    h = hashlib.sha256()
    with path.open('rb') as inp:
        for block in iter(lambda: inp.read(65536), b''):
            h.update(block)
    return {'sha256': h.hexdigest(), 'bytes': meta.st_size}


def _bound_child_output():
    resource.setrlimit(resource.RLIMIT_FSIZE, (65536, 65536))


def safe_run(argv, cwd, timeout=30):
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        proc = subprocess.Popen([str(x) for x in argv], cwd=cwd, env=ENV,
                                stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                                start_new_session=True, preexec_fn=_bound_child_output)
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
            raise ValueError('JAVA_COMPILER_RECEIPT_DENIED: subprocess timed out') from None
        deny(out.tell() <= 65536 and err.tell() <= 65536, 'unbounded subprocess output')
        out.seek(0); err.seek(0)
        return proc.returncode, out.read(), err.read()


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        deny(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def strict_json(payload):
    return json.loads(payload, object_pairs_hook=unique_pairs,
                      parse_constant=lambda x: (_ for _ in ()).throw(ValueError('nonfinite JSON')))


def capture(repo, bundle, expected_sha, expected_tree, mode, toolchain_report=None):
    deny(not Path(repo).is_symlink() and not Path(bundle).is_symlink(),
         'symlinked root forbidden')
    repo, bundle = Path(repo).resolve(strict=True), Path(bundle).resolve(strict=True)
    deny(repo.is_dir() and bundle.is_dir() and repo != bundle and repo not in bundle.parents,
         'bundle must be outside source checkout')
    deny(SHA40.fullmatch(expected_sha or '') is not None and
         SHA40.fullmatch(expected_tree or '') is not None, 'invalid source identity')
    deny(Path(git(repo, 'rev-parse', '--show-toplevel')).resolve() == repo,
         'source root not an exact Git checkout')
    deny(git(repo, 'rev-parse', 'HEAD') == expected_sha, 'wrong source commit')
    deny(git(repo, 'show', '-s', '--format=%T', 'HEAD') == expected_tree,
         'wrong source Git tree')
    deny(not git(repo, 'status', '--porcelain', '--untracked-files=all'),
         'dirty source checkout')
    index = git(repo, 'ls-files', '-v', '-z')
    deny(all(x.startswith('H ') for x in index.split('\x00') if x),
         'masked Git index entry')
    deny(mode in MODES, 'unsupported expected build mode')
    deny({p.name for p in bundle.iterdir()} == {'launcher', 'oreslang-source.jar', 'lib', 'native'},
         'unexpected bundle root entries')
    launcher = bundle / 'launcher'
    jar = bundle / 'oreslang-source.jar'
    library = bundle / 'lib'
    native = bundle / 'native' / 'liboresthread.so'
    deny((bundle / 'native').is_dir() and not (bundle / 'native').is_symlink() and
         {p.name for p in (bundle / 'native').iterdir()} == {'liboresthread.so'},
         'missing or extra native runtime artifact')
    deny(library.is_dir() and not library.is_symlink(), 'missing dependency directory')
    deps = sorted(library.iterdir())
    deny(bool(deps) and all(p.suffix == '.jar' and p.name not in ('.', '..') for p in deps),
         'missing or extra dependency artifact')
    files = {'launcher': file_identity(launcher),
             'oreslang-source.jar': file_identity(jar),
             'native/liboresthread.so': file_identity(native)}
    deny(os.access(launcher, os.X_OK), 'non-executable launcher')
    for dep in deps:
        files['lib/' + dep.name] = file_identity(dep)
    for p in [jar, *deps]:
        with zipfile.ZipFile(p) as archive:
            deny(archive.testzip() is None, 'corrupt runtime JAR')
            if p == jar:
                deny('dev/oreslang/launcher/OresMain.class' in archive.namelist(),
                     'real compiled Java launcher class missing')
    code, stdout, stderr = safe_run([launcher, '--build-info'], repo)
    deny(code == 0 and not stderr and len(stdout) <= 4096,
         'real launcher build-info failed')
    info = strict_json(stdout)
    deny(type(info) is dict and set(info) == {'build_mode','default_execution_mode','guest_execution'},
         'build-info schema invalid')
    actual_mode = info['build_mode']
    deny(type(actual_mode) is str and actual_mode == mode and
         (info['default_execution_mode'], info['guest_execution']) == MODES[mode],
         'unsupported launcher mode or capabilities')
    positive = repo / 'examples' / 'together.ores'
    deny(positive.is_file() and not positive.is_symlink(), 'missing positive source fixture')
    code, stdout, stderr = safe_run([launcher, '--check', str(positive)], repo)
    deny(code == 0 and not stdout, 'positive real compiler check failed')
    with tempfile.TemporaryDirectory(prefix='ores-compiler-negative-') as t:
        bad = Path(t) / 'invalid.ores'
        bad.write_bytes(b'pub routine main( {\n')
        code, stdout, stderr = safe_run([launcher, '--check', str(bad)], Path(t))
        expected = re.compile(rb'^' + re.escape(os.fsencode(bad)) +
                              rb':[1-9][0-9]*:[1-9][0-9]*: error: ', re.M)
        deny(code == 1 and not stdout and expected.search(stderr) is not None,
             'negative real compiler check failed')
    # A launcher must not silently add extra module-path/JNI artifacts.
    deny({p.name for p in bundle.iterdir()} == {'launcher', 'oreslang-source.jar', 'lib', 'native'} and
         sorted(library.iterdir()) == deps and
         {p.name for p in (bundle / 'native').iterdir()} == {'liboresthread.so'},
         'bundle file set mutated during compiler CLI checks')
    # Fail closed if execution modified anything whose identity we admitted.
    deny(all(file_identity(bundle / name) == identity for name, identity in files.items()),
         'bundle mutated during compiler CLI checks')
    deny(git(repo, 'rev-parse', 'HEAD') == expected_sha and
         git(repo, 'show', '-s', '--format=%T', 'HEAD') == expected_tree and
         not git(repo, 'status', '--porcelain', '--untracked-files=all'),
         'source mutated during CLI checks')
    toolchain = None
    if toolchain_report is not None:
        report = Path(toolchain_report)
        deny(report.is_file() and not report.is_symlink() and
             bundle not in report.resolve().parents and report.resolve() != bundle,
             'toolchain report missing or inside bundle')
        data = report.read_bytes()
        deny(0 < len(data) <= 8192 and b'\0' not in data,
             'toolchain report empty, oversized, or invalid')
        toolchain = {'sha256': hashlib.sha256(data).hexdigest(),
                     'captured_versions': data.decode('utf-8', 'strict')}
    return {'schema_version': 'oreslang-java-compiler-local-evidence/v1',
            'source_commit': expected_sha, 'source_tree': expected_tree,
            'mode': mode, 'launcher_build_info': info, 'bundle_files': files,
            'toolchain_report': toolchain,
            'positive_check': True, 'negative_check': True,
            'independent_provenance_attested': False,
            'guest_ml_executed': False}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--source-root', required=True)
    p.add_argument('--bundle', required=True)
    p.add_argument('--expected-source-sha', required=True)
    p.add_argument('--expected-tree', required=True)
    p.add_argument('--expected-mode', default='jvm')
    p.add_argument('--receipt', required=True)
    p.add_argument('--toolchain-report')
    p.add_argument('--verify', action='store_true')
    args = p.parse_args()
    observed = capture(args.source_root, args.bundle, args.expected_source_sha,
                       args.expected_tree, args.expected_mode, args.toolchain_report)
    if args.verify:
        expected = strict_json(Path(args.receipt).read_bytes())
        deny(expected == observed, 'receipt mismatch / dependency or compiler drift')
    else:
        output = Path(args.receipt)
        deny(not output.exists() and output.resolve().parent != Path(args.bundle).resolve(),
             'receipt must be new and outside bundle')
        with output.open('x', encoding='utf-8') as stream:
            json.dump(observed, stream, sort_keys=True, indent=2)
            stream.write('\n')
    print(json.dumps({'result': 'VERIFIED_LOCAL_ONLY', 'source_commit': observed['source_commit'],
                      'mode': observed['mode'], 'files': len(observed['bundle_files']),
                      'independent_provenance_attested': False}))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        raise SystemExit('UNVERIFIED: ' + str(exc)) from None
