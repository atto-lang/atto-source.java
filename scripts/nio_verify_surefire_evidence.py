#!/usr/bin/env python3
"""Fail closed on stale, skipped, inconsistent or missing Surefire JUnit evidence.

Source-local test evidence only; neither an attestation nor execution authority.
"""
from __future__ import annotations

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

MAX_XML_BYTES = 32 * 1024 * 1024

EXPECTED = (
    'VmNioTcpListenerTest', 'NativeNetStdlibTest', 'NioReadinessGroupTest',
    'NativeNioFutureReactorTest', 'OresSchedulerTest',
    'OresCarrierPermitPoolTest', 'OresFuturesTest', 'OresFutureCallbackTest',
)


def local(tag: str) -> str:
    return tag.rsplit('}', 1)[-1]


def count(suite: ET.Element, field: str) -> int:
    try:
        value = int(suite.attrib[field])
    except (KeyError, ValueError) as exc:
        raise ValueError(f'missing/invalid {field} counter') from exc
    if value < 0:
        raise ValueError(f'negative {field} counter')
    return value


def check_report(path: Path) -> tuple[int, set[str]]:
    try:
        size = path.stat().st_size
        if size > MAX_XML_BYTES:
            raise ValueError('Surefire XML exceeds bounded parse limit')
        payload = path.read_bytes()
        # Surefire XML must be UTF-8. A UTF-16/32-encoded DTD contains
        # NULs and evades an ASCII byte scan while ElementTree expands it.
        # Decode first, then forbid declarations on the decoded Unicode text.
        try:
            decoded = payload.decode('utf-8-sig')
        except UnicodeDecodeError as exc:
            raise ValueError('non-UTF-8 Surefire evidence is forbidden') from exc
        if '<!DOCTYPE' in decoded.upper() or '<!ENTITY' in decoded.upper():
            raise ValueError('DOCTYPE/ENTITY is forbidden in Surefire evidence')
        root = ET.fromstring(decoded)
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f'invalid XML: {exc}') from exc
    tag = local(root.tag)
    if tag == 'testsuite':
        suites = [root]
    elif tag == 'testsuites':
        suites = [child for child in root if local(child.tag) == 'testsuite']
    else:
        raise ValueError('unexpected JUnit XML root')
    if not suites:
        raise ValueError('no JUnit suites')
    # A malformed XML file may embed failures/errors outside testcase nodes,
    # even if suite summary counters falsely claim zero failures.
    if any(local(node.tag) in ('failure', 'error') for node in root.iter()):
        raise ValueError('JUnit XML contains a failure/error element')
    executed = 0
    names: set[str] = set()
    for suite in suites:
        cases = [child for child in suite if local(child.tag) == 'testcase']
        skipped = failed = errors = 0
        for case in cases:
            children = {local(child.tag) for child in case}
            skipped += 'skipped' in children
            failed += 'failure' in children
            errors += 'error' in children
            if 'skipped' in children:
                continue
            # A classname without a named testcase is not execution evidence.
            if not case.attrib.get('name', '').strip():
                raise ValueError('executed JUnit testcase has no name')
            # A report filename is not evidence of the class actually run.
            identity = case.attrib.get('classname', '')
            name = identity.rsplit('.', 1)[-1].split('$', 1)[0]
            if name in EXPECTED:
                names.add(name)
            executed += 1
        if (count(suite, 'tests') != len(cases) or
            count(suite, 'skipped') != skipped or
            count(suite, 'failures') != failed or
            count(suite, 'errors') != errors):
            raise ValueError('JUnit counters contradict testcase elements')
        if failed or errors:
            raise ValueError('JUnit suite contains failures/errors')
    # A <testsuites> wrapper may publish authoritative aggregate counters.
    # Do not let failed/skipped results hide behind clean child suites.
    if tag == 'testsuites':
        for field in ('tests', 'skipped', 'failures', 'errors'):
            if field in root.attrib:
                expected = sum(count(suite, field) for suite in suites)
                if count(root, field) != expected:
                    raise ValueError(f'JUnit wrapper {field} counter contradicts suites')
    return executed, names


def verify(marker: Path, phase: str) -> tuple[int, int]:
    if phase not in ('targeted', 'full'):
        raise ValueError('unknown test phase')
    if marker.is_symlink() or not marker.is_file():
        raise ValueError('missing/unsafe phase-start marker')
    reports = Path('target/surefire-reports')
    if reports.is_symlink() or not reports.is_dir():
        raise ValueError('missing/unsafe Surefire reports directory')
    fresh = []
    for path in sorted(reports.glob('TEST-*.xml')):
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'unsafe report path: {path}')
        if path.stat().st_mtime_ns > marker.stat().st_mtime_ns:
            fresh.append(path)
    if not fresh:
        raise ValueError('no fresh Surefire XML after Maven phase')
    total = 0
    names: set[str] = set()
    for path in fresh:
        ran, present = check_report(path)
        total += ran
        names.update(present)
    missing = sorted(set(EXPECTED) - names)
    if total == 0 or missing:
        raise ValueError(f'executed={total}; mandatory suites absent: {missing}')
    return len(fresh), total


def main() -> int:
    if len(sys.argv) != 3:
        print('usage: nio_verify_surefire_evidence.py MARKER targeted|full', file=sys.stderr)
        return 64
    try:
        reports, tests = verify(Path(sys.argv[1]), sys.argv[2])
    except (ValueError, OSError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        return 76
    print(f'PASS: {sys.argv[2]} has {tests} fresh non-skipped cases in {reports} JUnit XML reports')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
