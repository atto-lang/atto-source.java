#!/usr/bin/env python3
"""Exact-head Java ML guest evidence; only callable after real build/run steps."""
import argparse
import hashlib
import os
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

FIXTURE = Path('tests/fixtures/ml-actor-state-scalar.ores')
FIXTURE_BLOB = '555dfe5602e28c93b02c0b398a428ef412c13b58'
STDOUT = b'-6\n20\n-7\n30\n2\n-4\n-2\n5\n5\n2\n-1\n10\n'
SHA40 = re.compile(r'[a-f0-9]{40}\Z')
EXPECTED_METHODS = {
    'compiledActorStageHasOneStateSchemaAndDeclaredEffects',
    'actualGuestForwardBackwardDuplicateAndCommitMatchPinnedOutput',
    'readEffectsDoNotGrantStateWriteAndLoanCannotEscape',
    'undeclaredWriteEffectCannotBeSmuggledThroughCritical',
    'oneStateOnlyAndDirectStateFieldAccessAreSealed',
    'liveLoanCannotCooperateOrAwait',
}


def require(ok, message):
    if not ok:
        raise ValueError('ML_GUEST_RECEIPT_DENIED: ' + message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(
        ['git', *args], text=True, timeout=10,
        env={'PATH': os.defpath, 'LC_ALL': 'C', 'GIT_CONFIG_NOSYSTEM': '1',
             'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_NO_REPLACE_OBJECTS': '1'},
    ).strip()


def make_receipt(expected_sha, report, output_file, build_info, jar):
    require(SHA40.fullmatch(expected_sha), 'invalid expected SHA')
    head = git('rev-parse', 'HEAD')
    tree = git('rev-parse', 'HEAD^{tree}')
    require(head == expected_sha and SHA40.fullmatch(tree), 'wrong checked-out commit/tree')
    require(not git('status', '--porcelain', '--untracked-files=no'), 'tracked source dirty')
    fixture = FIXTURE.read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(fixture)).encode() + b'\0' + fixture).hexdigest()
    require(blob == FIXTURE_BLOB, 'fixture differs from pinned blob')
    require(git('rev-parse', 'HEAD:' + FIXTURE.as_posix()) == blob, 'fixture not tracked at this head')
    observed = Path(output_file).read_bytes()
    require(observed == STDOUT, 'real Java CLI stdout differs from exact golden')
    report_bytes = Path(report).read_bytes()
    require(0 < len(report_bytes) <= 4_000_000, 'JUnit XML size outside bound')
    suite = ET.fromstring(report_bytes)
    require(suite.tag == 'testsuite', 'invalid JUnit XML root')
    cases = suite.findall('testcase')
    actual_methods = {c.attrib.get('name') for c in cases}
    require(all(c.attrib.get('classname') == 'dev.oreslang.MlActorStateScalarGuestTest'
                for c in cases), 'wrong JUnit testcase class')
    require(len(cases) == len(EXPECTED_METHODS) and actual_methods == EXPECTED_METHODS,
            'missing, duplicate, or wrong ML JUnit test methods')
    for key in ('tests', 'failures', 'errors', 'skipped'):
        count = int(suite.attrib.get(key, '-1'))
        require(count == (len(EXPECTED_METHODS) if key == 'tests' else 0), 'JUnit ' + key + ' not admitted')
    require(all(len(c) == 0 for c in cases), 'unexpected failed/skipped child in JUnit')
    info_bytes = Path(build_info).read_bytes()
    require(0 < len(info_bytes) <= 4096, 'build-info size outside bound')
    build = json.loads(info_bytes)
    require(isinstance(build, dict) and all(k in build for k in
            ('build_mode', 'default_execution_mode', 'guest_execution')), 'CLI build-info missing fields')
    require(all(isinstance(build[k], str) and 0 < len(build[k]) <= 128
                and '\n' not in build[k] and '\r' not in build[k]
                for k in ('build_mode', 'default_execution_mode', 'guest_execution')),
            'invalid CLI build-info values')
    artifact = Path(jar)
    with zipfile.ZipFile(artifact) as z:
        require(len(z.namelist()) == len(set(z.namelist())), 'duplicate jar archive entries')
        require('dev/oreslang/launcher/OresMain.class' in z.namelist(), 'jar lacks OresMain launcher')
        require('module-info.class' in z.namelist(), 'jar lacks Java module-info')
    return {
        'schema_version': 'oreslang-java-ml-guest-evidence/v1',
        'source_commit': head, 'source_tree': tree,
        'fixture_git_blob': blob,
        'java_junit_test_count': len(cases),
        'java_guest_cli_stdout_sha256': sha256(observed),
        'build_info': build,
        'compiler_modular_jar_sha256': sha256(artifact.read_bytes()),
        'compiler_artifact_kind': 'MODULAR_JAR_NEEDS_DEPENDENCIES_NOT_STANDALONE',
        'compilation_and_guest_execution_observed_in_job': True,
        'native_aot_verified': False,
        'source_binary_provenance_attested': False,
        'full_suite_verified': False,
        'release_ready': False,
    }


def main():
    p = argparse.ArgumentParser()
    for name in ('expected-sha', 'junit-xml', 'cli-stdout', 'build-info', 'jar', 'output'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    receipt = make_receipt(a.expected_sha, a.junit_xml, a.cli_stdout, a.build_info, a.jar)
    path = Path(a.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print('ML_GUEST_EVIDENCE_LOCAL_ONLY ' + receipt['source_commit'])


if __name__ == '__main__':
    main()
