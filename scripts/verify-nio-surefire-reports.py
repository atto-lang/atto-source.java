#!/usr/bin/env python3
"""Fail-closed source-local evidence check for mandatory targeted JUnit suites.

This is a report integrity check, not a substitute for runner/build provenance.
"""
from __future__ import annotations

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED = (
    "VmNioTcpListenerTest",
    "NativeNetStdlibTest",
    "NioReadinessGroupTest",
    "NativeNioFutureReactorTest",
    "OresSchedulerTest",
    "OresCarrierPermitPoolTest",
    "OresFuturesTest",
    "OresFutureCallbackTest",
)


def verify(directory: Path) -> tuple[int, int]:
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("Surefire reports directory missing or is a symlink")
    found: dict[str, tuple[int, int]] = {}
    total_run = 0
    report_count = 0
    for path in sorted(directory.glob("TEST-*.xml")):
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"unsafe Surefire XML report: {path.name}")
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            raise ValueError(f"invalid Surefire XML report {path.name}: {exc}") from exc
        if root.tag != "testsuite":
            raise ValueError(f"unexpected Surefire root in {path.name}")
        name = root.attrib.get("name", "").rsplit(".", 1)[-1]
        if not name or name in found:
            raise ValueError(f"empty or duplicate Surefire suite: {name}")
        try:
            cases = int(root.attrib["tests"])
            skipped = int(root.attrib.get("skipped", "0"))
            failures = int(root.attrib.get("failures", "0"))
            errors = int(root.attrib.get("errors", "0"))
        except (KeyError, ValueError) as exc:
            raise ValueError(f"invalid Surefire counters for {name}") from exc
        if min(cases, skipped, failures, errors) < 0 or (
            skipped + failures + errors > cases
        ):
            raise ValueError(f"inconsistent Surefire counters for {name}")
        testcase_nodes = root.findall("testcase")
        if len(testcase_nodes) != cases:
            raise ValueError(f"Surefire testcase count mismatch for {name}")
        actual_skipped = sum(bool(case.findall("skipped")) for case in testcase_nodes)
        if actual_skipped != skipped:
            raise ValueError(f"Surefire skipped-case count mismatch for {name}")
        if failures or errors or root.findall(".//failure") or root.findall(".//error"):
            raise ValueError(f"failing Surefire test suite: {name}")
        ran = cases - skipped
        found[name] = (cases, ran)
        total_run += ran
        report_count += 1
    missing = [name for name in REQUIRED if name not in found]
    if missing:
        raise ValueError("missing required JUnit suites: " + ", ".join(missing))
    unexecuted = [name for name in REQUIRED if found[name][1] < 1]
    if unexecuted:
        raise ValueError("zero executed tests for: " + ", ".join(unexecuted))
    if total_run < len(REQUIRED):
        raise ValueError("no meaningful targeted JUnit execution")
    return report_count, total_run


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify-nio-surefire-reports.py <surefire-reports>", file=sys.stderr)
        return 64
    try:
        reports, executed = verify(Path(sys.argv[1]))
    except ValueError as exc:
        print("FAIL: " + str(exc), file=sys.stderr)
        return 74
    print(f"PASS: {reports} Surefire suites, {executed} non-skipped test cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
