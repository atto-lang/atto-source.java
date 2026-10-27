#!/usr/bin/env python3
"""Fail closed unless the four expected focused JUnit suites actually executed."""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXPECTED = {
    "dev.oreslang.runtime.OresFutureObserverTest": 10,
    "dev.oreslang.runtime.TaskFutureAdaptersTest": 11,
    "dev.oreslang.runtime.OneShotObservableTest": 14,
    "dev.oreslang.FutureFromInlineTaskLanguageTest": 7,
}

def verify(report_dir: Path) -> dict:
    if report_dir.is_symlink() or not report_dir.is_dir():
        raise ValueError("report directory absent or symbolic link")
    summary = {}
    for suite, expected in EXPECTED.items():
        path = report_dir / f"TEST-{suite}.xml"
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"missing or symlinked focused report: {suite}")
        root = ET.parse(path).getroot()
        if root.tag != "testsuite" or root.attrib.get("name") != suite:
            raise ValueError(f"wrong JUnit suite identity: {suite}")
        counts = {}
        for key in ("tests", "errors", "failures", "skipped"):
            raw = root.attrib.get(key, "")
            if not raw.isascii() or not raw.isdecimal():
                raise ValueError(f"missing or invalid {key} in {suite}")
            counts[key] = int(raw)
        if counts != dict(tests=expected, errors=0, failures=0, skipped=0):
            raise ValueError(f"incorrect execution counts for {suite}: {counts}")
        cases = root.findall("testcase")
        if len(cases) != expected or any(
            c.attrib.get("classname") != suite or not c.attrib.get("name")
            or c.find("skipped") is not None
            or c.find("failure") is not None
            or c.find("error") is not None
            for c in cases
        ):
            raise ValueError(f"missing, misattributed, or failed individual cases in {suite}")
        names = [c.attrib["name"] for c in cases]
        if len(names) != len(set(names)):
            raise ValueError(f"duplicate JUnit testcase names: {suite}")
        summary[suite] = expected
    if sum(summary.values()) != 42:
        raise ValueError("focused test total does not equal 42")
    return {"suites": summary, "executed": 42,
            "failures": 0, "errors": 0, "skipped": 0}

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("reports", type=Path)
    args = p.parse_args()
    try:
        result = verify(args.reports)
    except (ValueError, ET.ParseError, OSError) as exc:
        print(f"ASYNC_INTEROP_JUNIT_DENIED: {exc}", file=sys.stderr)
        return 1
    print("ASYNC_INTEROP_JUNIT_PROOF=" + json.dumps(result, sort_keys=True))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
