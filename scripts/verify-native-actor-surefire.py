#!/usr/bin/env python3
"""Verify executed Surefire testcases, not merely successful Maven exit status.

The source-local exact-tree gate runs two explicit test selections. A skipped,
absent or stale XML report must never substitute for proof of guest/JNI tests.
This does not attest runner provenance; exact Git SHA and trusted runner gates
are separately mandatory.
"""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

PREFIX = "dev.oreslang.runtime."
REQUIRED = {
    "jni": {"NativeActorTurnSchedulerJniTest": 15},
    "policy": {
        "NativeActorRuntimePolicyTest": 1,
        "ActorEndCleanupTest": 1,
        "ActorAsyncFinalizationBarrierTest": 1,
        "ActorReadinessLifecycleTest": 1,
        "ActorStructuredCancellationTest": 1,
        "ActorStopMailboxRaceTest": 1,
    },
}
MAX_REPORT_SIZE = 8 * 1024 * 1024
MAX_REPORTS = 512


class VerificationFailure(ValueError):
    pass


def positive_integer(value, label):
    try:
        number = int(value)
    except (ValueError, TypeError) as exc:
        raise VerificationFailure(f"invalid {label} counter") from exc
    if number < 0:
        raise VerificationFailure(f"negative {label} counter")
    return number


def verify(report_dir, group):
    if group not in REQUIRED:
        raise VerificationFailure("unknown required test group")
    if report_dir.is_symlink() or not report_dir.is_dir():
        raise VerificationFailure("Surefire report directory missing or symlinked")
    expected = REQUIRED[group]
    seen = set()
    results = {}
    paths = sorted(report_dir.glob("TEST-*.xml"))
    if not paths or len(paths) > MAX_REPORTS:
        raise VerificationFailure("Surefire XML report count invalid")
    for path in paths:
        if path.is_symlink() or not path.is_file():
            raise VerificationFailure("Surefire report must be a regular file")
        if path.stat().st_size > MAX_REPORT_SIZE:
            raise VerificationFailure("Surefire XML report size limit exceeded")
        # XML parser only consumes bounded local test artifacts; it does not
        # load remote DTDs or write external files.
        try:
            root = ET.fromstring(path.read_bytes())
        except ET.ParseError as exc:
            raise VerificationFailure("invalid Surefire XML report") from exc
        if root.tag != "testsuite":
            raise VerificationFailure("unexpected Surefire XML root")
        name = root.get("name", "")
        if not name.startswith(PREFIX):
            continue
        short_name = name[len(PREFIX):]
        if short_name not in expected:
            continue
        if short_name in seen:
            raise VerificationFailure(f"duplicate Surefire report for {short_name}")
        seen.add(short_name)
        executed = positive_integer(root.get("tests"), "tests")
        errors = positive_integer(root.get("errors"), "errors")
        failures = positive_integer(root.get("failures"), "failures")
        skipped = positive_integer(root.get("skipped"), "skipped")
        cases = root.findall("testcase")
        if (executed < expected[short_name] or len(cases) != executed
                or errors or failures or skipped
                or any(any(case.find(tag) is not None for tag in
                           ("error", "failure", "skipped")) for case in cases)):
            raise VerificationFailure(f"missing, failed or skipped testcases: {short_name}")
        results[short_name] = executed

    if seen != set(expected):
        missing = ", ".join(sorted(set(expected) - seen))
        raise VerificationFailure(f"required Surefire class reports absent: {missing}")
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, choices=sorted(REQUIRED))
    parser.add_argument("--reports", type=Path, default=Path("target/surefire-reports"))
    args = parser.parse_args(argv)
    try:
        results = verify(args.reports, args.suite)
    except (VerificationFailure, OSError) as exc:
        print(f"ORES_NATIVE_SUREFIRE_DENIED: {exc}", file=sys.stderr)
        return 1
    print("ORES_NATIVE_SUREFIRE_VERIFIED suite=" + args.suite
          + " classes=" + str(len(results))
          + " executed_tests=" + str(sum(results.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
