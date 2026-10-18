#!/usr/bin/env python3
"""Reject zero-test, stale, skipped or incomplete Surefire report evidence.

Source-local only; an unsigned diagnostic, not CI or test certification.
"""
from __future__ import annotations

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

EXPECTED = (
    'VmNioTcpListenerTest', 'NativeNetStdlibTest', 'NioReadinessGroupTest',
    'NativeNioFutureReactorTest', 'OresSchedulerTest',
    'OresCarrierPermitPoolTest', 'OresFuturesTest', 'OresFutureCallbackTest',
)


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] not in ('targeted', 'full'):
        print('usage: nio_verify_surefire_evidence.py MARKER targeted|full', file=sys.stderr)
        return 64
    marker = Path(sys.argv[1])
    if not marker.is_file():
        print('ERROR: missing fresh-test marker', file=sys.stderr)
        return 76
    root = Path('target/surefire-reports')
    fresh = sorted(p for p in root.glob('TEST-*.xml')
                   if p.is_file() and p.stat().st_mtime_ns > marker.stat().st_mtime_ns)
    if not fresh:
        print('ERROR: no newly generated Surefire XML after Maven phase', file=sys.stderr)
        return 76
    executed = 0
    names: set[str] = set()
    for report in fresh:
        try:
            tree = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as exc:
            print(f'ERROR: malformed Surefire report {report}: {exc}', file=sys.stderr)
            return 76
        # Reject reports masquerading as test results; count actual testcases,
        # not only an untrusted tests="N" suite summary attribute.
        if tree.tag.split('}')[-1] not in ('testsuite', 'testsuites'):
            print(f'ERROR: unrecognized test report {report}', file=sys.stderr)
            return 76
        for case in tree.iter():
            if case.tag.split('}')[-1] != 'testcase':
                continue
            name = case.attrib.get('classname', '').rsplit('.', 1)[-1]
            if any(child.tag.split('}')[-1] in ('failure', 'error') for child in case):
                print(f'ERROR: failing test in {report}', file=sys.stderr)
                return 76
            if any(child.tag.split('}')[-1] == 'skipped' for child in case):
                continue
            executed += 1
            if name:
                names.add(name.split('$', 1)[0])
        # Some Surefire providers omit testcase@classname; use suite name
        # only when the report actually recorded at least one executed test.
        if report.stem.startswith('TEST-') and list(tree.iter('testcase')):
            if any(not list(case.iter('skipped')) for case in tree.iter('testcase')):
                names.add(report.stem[5:].rsplit('.', 1)[-1].split('$', 1)[0])
    missing = sorted(set(EXPECTED) - names)
    if executed == 0 or missing:
        print(f'ERROR: executed={executed}, missing suites={missing}', file=sys.stderr)
        return 76
    print(f'PASS: {sys.argv[2]} phase has {executed} fresh executed testcases '
          f'across {len(fresh)} Surefire XML reports; required suites present')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
