#!/usr/bin/env python3
"""Build and independently re-verify a portable JVM CLI *receipt*, not a provenance signature.

Source must remain inside an authorized same-org runner. Never export private
source or binaries to another org without separate review. Linux/POSIX only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import zipfile

SCHEMA = 'oreslang-compiler-bundle/v1'
REPO = 'ores-truffle-oreslang/oreslang-source.java'
SHA40 = re.compile(r'[0-9a-f]{40}\Z')
SHA256 = re.compile(r'[0-9a-f]{64}\Z')
NAME = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]*\.jar\Z')
MODES = {'jvm': ('jit', 'runtime-selected')}
MAX_JARS = 256
MAX_FILE = 128 * 1024 * 1024
MAX_JSON = 128 * 1024
MAX_JAR_MEMBER = 64 * 1024 * 1024
MAX_JAR_EXPANDED = 256 * 1024 * 1024
LAUNCHER = '''#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
set -- "$@"
MODULE_PATH="$ROOT/app.jar"
for dep in "$ROOT"/deps/*.jar; do
    test -f "$dep" || exit 72
    MODULE_PATH="$MODULE_PATH:$dep"
done
exec java --enable-native-access=ALL-UNNAMED -p "$MODULE_PATH" -m dev.oreslang/dev.oreslang.launcher.OresMain "$@"
'''


def deny(msg):
    raise ValueError('COMPILER_BUNDLE_DENIED: ' + msg)


def check(test, msg):
    if not test:
        deny(msg)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def no_symlink_parents(path):
    """Deny symlinks in existing path components, not just at the leaf."""
    path = Path(path).absolute()
    check(path.is_absolute(), 'nonabsolute input path')
    for part in (path, *path.parents):
        check(not part.is_symlink(), 'symlink in path: ' + str(part))


def regular(path, max_size=MAX_FILE):
    no_symlink_parents(path)
    check(not path.is_symlink(), 'symlink: ' + str(path))
    check(path.is_file(), 'missing, linked, empty or oversized file: ' + str(path))
    st = path.stat()
    check(stat.S_ISREG(st.st_mode) and st.st_nlink == 1 and 0 < st.st_size <= max_size,
          'missing, linked, empty or oversized file: ' + str(path))


def jar_structure(path, *, main=False):
    """Bounded, passive validation of complete archive contents and metadata."""
    regular(path)
    try:
        # A ZIP reader can accept a prefixed shell/polyglot executable. Require
        # JAR bytes to begin at offset zero, not after executable script text.
        with path.open('rb') as stream:
            check(stream.read(4) == b'PK\x03\x04', 'not a valid JAR: executable prefix or invalid header')
        with zipfile.ZipFile(path, 'r') as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            check(0 < len(names) <= 32768 and len(names) == len(set(names)),
                  'invalid or duplicate JAR entries')
            expanded = 0
            for info in infos:
                name = info.filename
                parts = name.split('/')
                check(bool(name) and not name.startswith('/') and
                      not name.startswith('\\') and '\\' not in name and
                      ':' not in name and '\x00' not in name and
                      all(part not in ('', '.', '..') for part in
                          (parts[:-1] if info.is_dir() else parts)) and
                      (not info.is_dir() or parts[-1] == ''),
                      'unsafe JAR member path')
                # ZIP extra attributes encode POSIX symlinks. No ZIP member
                # needs to point outside the archive or carry a hidden payload.
                filetype = (info.external_attr >> 16) & 0o170000
                check(filetype not in (stat.S_IFLNK, stat.S_IFCHR, stat.S_IFBLK,
                                       stat.S_IFIFO, stat.S_IFSOCK),
                      'non-regular JAR member')
                check(info.flag_bits & 1 == 0, 'encrypted JAR member')
                check(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                      'unsupported JAR compression')
                check(0 <= info.file_size <= MAX_JAR_MEMBER and
                      0 <= info.compress_size <= MAX_FILE,
                      'oversized JAR member')
                expanded += info.file_size
                check(expanded <= MAX_JAR_EXPANDED, 'JAR expanded-content bound exceeded')
                if info.is_dir():
                    check(info.file_size == 0, 'directory with data in JAR')
                else:
                    # Reading to EOF validates CRC and catches truncated data.
                    # Do not extract anything, even to a temporary directory.
                    with archive.open(info, 'r') as reader:
                        while reader.read(65536):
                            pass
            if main:
                check('module-info.class' in names and
                      'dev/oreslang/launcher/OresMain.class' in names,
                      'compiler JAR missing modular OresMain entry point')
    except (zipfile.BadZipFile, zipfile.LargeZipFile, RuntimeError,
            NotImplementedError, EOFError):
        deny('not a valid JAR archive: ' + str(path))


def read_json(path):
    regular(path, MAX_JSON)
    def unique(pairs):
        obj = {}
        for k, v in pairs:
            check(k not in obj, 'duplicate JSON key')
            obj[k] = v
        return obj
    def invalid(value):
        deny('nonfinite JSON value: ' + value)
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique,
                      parse_constant=invalid)


def git(repo, *args):
    env = {'PATH': os.defpath, 'LC_ALL': 'C', 'GIT_CONFIG_NOSYSTEM': '1',
           'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_NO_REPLACE_OBJECTS': '1'}
    proc = subprocess.run(['git', '-c', 'core.fsmonitor=false', '-C', str(repo), *args],
                          check=True, capture_output=True, text=True, timeout=20, env=env)
    return proc.stdout.strip()


def check_source(repo, commit, tree):
    check(SHA40.fullmatch(commit) is not None and SHA40.fullmatch(tree) is not None,
          'invalid source commit/tree SHA')
    no_symlink_parents(repo)
    check(not repo.is_symlink() and repo.is_dir() and repo.resolve() == repo,
          'unsafe source directory')
    check(Path(git(repo, 'rev-parse', '--show-toplevel')).resolve() == repo,
          'source Git root differs')
    check(git(repo, 'rev-parse', '--verify', 'HEAD') == commit, 'wrong source commit')
    check(git(repo, 'show', '-s', '--format=%T', 'HEAD') == tree, 'wrong source tree')
    check(not git(repo, 'status', '--porcelain', '--untracked-files=all',
                  '--ignore-submodules=none'),
          'dirty source checkout')
    for row in git(repo, 'ls-files', '-v', '-z').split('\0'):
        check(not row or row.startswith('H '), 'masked Git index entry')


def validate_info(obj):
    check(type(obj) is dict and set(obj) == {'build_mode', 'default_execution_mode',
                                            'guest_execution'}, 'bad build-info schema')
    mode = obj['build_mode']
    check(type(mode) is str and mode in MODES and
          (obj['default_execution_mode'], obj['guest_execution']) == MODES[mode],
          'unsupported launcher build mode/capabilities')
    return obj


def probe_info(launcher, cwd):
    # The standard host PATH often points to a different (older) JDK than the
    # GraalVM JDK installed by CI. Preserve only the reviewed JDK bin path;
    # never inherit an arbitrary runner PATH, tokens or JAVA_TOOL_OPTIONS.
    home = os.environ.get('JAVA_HOME')
    java_path = os.defpath
    if home:
        root = Path(home)
        check(root.is_absolute() and (root / 'bin' / 'java').is_file(),
              'JAVA_HOME does not identify a Java runtime')
        java_path = str(root / 'bin') + os.pathsep + os.defpath
    env = {'PATH': java_path, 'LC_ALL': 'C', 'LANG': 'C', 'TZ': 'UTC'}
    run = subprocess.run([str(launcher), '--build-info'], cwd=cwd, env=env,
                         capture_output=True, timeout=40)
    check(run.returncode == 0 and not run.stderr and len(run.stdout) <= 4096,
          'build-info failed or noisy')
    # JSON duplicate-key and NaN protections also apply to command output.
    def pairs(items):
        x = {}
        for k, v in items:
            check(k not in x, 'duplicate CLI JSON field')
            x[k] = v
        return x
    def bad(x):
        deny('nonfinite CLI JSON')
    try:
        obj = json.loads(run.stdout.decode('utf-8', 'strict'), object_pairs_hook=pairs,
                         parse_constant=bad)
    except (UnicodeError, json.JSONDecodeError):
        deny('malformed build-info JSON')
    return validate_info(obj)


def file_entry(base, path):
    target = base / path
    regular(target)
    return {'path': path, 'sha256': digest(target), 'bytes': target.stat().st_size}


def canonical_dependencies(bundle):
    deps = bundle / 'deps'
    check(deps.is_dir() and not deps.is_symlink(), 'missing dependency directory')
    names = sorted(p.name for p in deps.iterdir())
    check(0 < len(names) <= MAX_JARS and all(NAME.fullmatch(n) for n in names),
          'empty or invalid dependency set')
    return [file_entry(bundle, 'deps/' + name) for name in names]


def create(args):
    source = Path(args.source_dir).absolute()
    out = Path(args.out_dir).absolute()
    check_source(source, args.source_commit, args.source_tree)
    jar = Path(args.main_jar).absolute()
    depdir = Path(args.dependency_dir).absolute()
    jar_structure(jar, main=True)
    check(jar.suffix == '.jar', 'main artifact not a JAR')
    no_symlink_parents(depdir)
    check(depdir.is_dir() and not depdir.is_symlink(), 'missing dependency directory')
    no_symlink_parents(out)
    check(not out.exists(), 'output already exists; refuse overwrite')
    check(source != out and source not in out.parents, 'bundle may not contain source')
    check(out != source and out not in source.parents, 'bundle cannot sit inside source')
    deps = sorted(depdir.iterdir(), key=lambda p: p.name)
    check(0 < len(deps) <= MAX_JARS and all(NAME.fullmatch(p.name) for p in deps),
          'missing/invalid dependency JAR names')
    for dep in deps:
        jar_structure(dep)
    check(jar.name not in {x.name for x in deps}, 'duplicate app/dependency name')
    # Do not allow an existing destination to acquire incomplete artifacts.
    out.mkdir(parents=True, mode=0o700)
    try:
        (out / 'deps').mkdir(mode=0o700)
        shutil.copyfile(jar, out / 'app.jar', follow_symlinks=False)
        for dep in deps:
            shutil.copyfile(dep, out / 'deps' / dep.name, follow_symlinks=False)
        (out / 'run-oreslang').write_text(LAUNCHER, encoding='utf-8')
        (out / 'run-oreslang').chmod(0o700)
        # Record the same JDK that probe_info will execute via the launcher.
        # Never silently report a different JDK's version as artifact provenance.
        home = os.environ.get('JAVA_HOME')
        java_bin = str(Path(home) / 'bin' / 'java') if home else 'java'
        java = subprocess.run([java_bin, '--version'], capture_output=True, text=True,
                              timeout=20, check=True).stdout.strip()
        maven = subprocess.run(['mvn', '--version'], capture_output=True, text=True,
                               timeout=20, check=True).stdout.strip()
        info = probe_info(out / 'run-oreslang', out)
        files = [file_entry(out, 'app.jar'), file_entry(out, 'run-oreslang')]
        deps_manifest = canonical_dependencies(out)
        manifest = {'schema_version': SCHEMA, 'source_repository': REPO,
                    'source_commit': args.source_commit, 'source_tree': args.source_tree,
                    'maven_pom_sha256': digest(source / 'pom.xml'),
                    'module_main': 'dev.oreslang/dev.oreslang.launcher.OresMain',
                    'build_info': info, 'toolchain': {'java_version': java, 'maven_version': maven},
                    'files': files + deps_manifest,
                    'provenance_independently_attested': False,
                    'oreslang_ml_guest_executed': False}
        (out / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
        check_source(source, args.source_commit, args.source_tree)
        verify_bundle(out, args.source_commit, args.source_tree)
        return manifest
    except BaseException:
        shutil.rmtree(out)
        raise


def verify_bundle(bundle, commit, tree, *, expected_manifest_sha256=None,
                  run_untrusted_launcher=False):
    """Verify bytes *without executing* the bundle unless explicitly requested.

    A self-reported manifest is not trusted provenance. For an out-of-band pinned
    receipt provide expected_manifest_sha256. Running the JVM is a separate
    explicitly opted-in step, only appropriate for a trusted executable.
    """
    bundle = Path(bundle).absolute()
    no_symlink_parents(bundle)
    check(bundle.is_dir() and not bundle.is_symlink(), 'unsafe bundle directory')
    if expected_manifest_sha256 is not None:
        check(type(expected_manifest_sha256) is str and
              SHA256.fullmatch(expected_manifest_sha256),
              'invalid pinned manifest SHA-256')
        regular(bundle / 'manifest.json', MAX_JSON)
        check(digest(bundle / 'manifest.json') == expected_manifest_sha256,
              'pinned manifest SHA-256 mismatch')
    doc = read_json(bundle / 'manifest.json')
    check(type(doc) is dict and set(doc) == {'schema_version', 'source_repository',
          'source_commit', 'source_tree', 'maven_pom_sha256', 'module_main',
          'build_info', 'toolchain', 'files', 'provenance_independently_attested',
          'oreslang_ml_guest_executed'}, 'noncanonical receipt schema')
    check(doc['schema_version'] == SCHEMA and doc['source_repository'] == REPO,
          'unknown bundle schema/source repository')
    check(doc['source_commit'] == commit and doc['source_tree'] == tree and
          SHA40.fullmatch(commit) and SHA40.fullmatch(tree), 'wrong source commit/tree')
    check(type(doc['maven_pom_sha256']) is str and SHA256.fullmatch(doc['maven_pom_sha256']),
          'missing Maven source digest')
    check(doc['module_main'] == 'dev.oreslang/dev.oreslang.launcher.OresMain',
          'unexpected compiler entry point')
    check(doc['provenance_independently_attested'] is False and
          doc['oreslang_ml_guest_executed'] is False, 'forged attestation claim')
    check(type(doc['toolchain']) is dict and set(doc['toolchain']) ==
          {'java_version', 'maven_version'} and all(type(v) is str and v.strip()
          for v in doc['toolchain'].values()), 'missing toolchain metadata')
    validate_info(doc['build_info'])
    check(set(p.name for p in bundle.iterdir()) ==
          {'app.jar', 'deps', 'run-oreslang', 'manifest.json'},
          'unexpected bundle root file or directory')
    files = doc['files']
    check(type(files) is list and 3 <= len(files) <= MAX_JARS + 2,
          'invalid dependency manifest')
    jar_structure(bundle / 'app.jar', main=True)
    expected = [file_entry(bundle, 'app.jar'), file_entry(bundle, 'run-oreslang')]
    expected.extend(canonical_dependencies(bundle))
    for entry in expected[2:]:
        jar_structure(bundle / entry['path'])
    check(files == expected, 'dependency/artifact bytes or inventory changed')
    check((bundle / 'run-oreslang').read_text(encoding='utf-8') == LAUNCHER,
          'unknown launcher shell script')
    check((bundle / 'run-oreslang').stat().st_mode & stat.S_IXUSR,
          'launcher is not executable')
    if run_untrusted_launcher:
        check(doc['build_info'] == probe_info(bundle / 'run-oreslang', bundle),
              'build-info mismatches manifest')
    return doc


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sp = p.add_subparsers(dest='command', required=True)
    create_p = sp.add_parser('create')
    create_p.add_argument('--source-dir', required=True)
    create_p.add_argument('--source-commit', required=True)
    create_p.add_argument('--source-tree', required=True)
    create_p.add_argument('--main-jar', required=True)
    create_p.add_argument('--dependency-dir', required=True)
    create_p.add_argument('--out-dir', required=True)
    verify_p = sp.add_parser('verify')
    verify_p.add_argument('--bundle', required=True)
    verify_p.add_argument('--source-commit', required=True)
    verify_p.add_argument('--source-tree', required=True)
    verify_p.add_argument('--expected-manifest-sha256', required=True,
                          help='Out-of-band trusted digest; the bundle manifest cannot attest itself')
    verify_p.add_argument('--probe-launcher', action='store_true',
                          help='EXPLICITLY execute bundled code AFTER static/hash verification')
    args = p.parse_args()
    try:
        if args.command == 'create':
            result = create(args)
        else:
            result = verify_bundle(Path(args.bundle).absolute(), args.source_commit, args.source_tree,
                                   expected_manifest_sha256=args.expected_manifest_sha256,
                                   run_untrusted_launcher=args.probe_launcher)
        print(json.dumps({'status': 'BUNDLE_BYTES_CHECKED_NOT_ATTESTED',
                          'source_commit': result['source_commit'],
                          'files_checked': len(result['files']),
                          'provenance_independently_attested': False}, sort_keys=True))
    except (OSError, ValueError, subprocess.SubprocessError, UnicodeError) as e:
        print('UNVERIFIED: ' + str(e), file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
