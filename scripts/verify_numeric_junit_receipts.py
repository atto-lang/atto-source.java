#!/usr/bin/env python3
"""Fail-closed receipt gate for AttoLang's exact-head numerical JVM guest suite.

Validates emitted Surefire XML only; cannot authenticate a runner, prove
source provenance or certify full guest AOT on its own.
"""

import os
from pathlib import Path
import stat
import sys
import xml.etree.ElementTree as ET

REQUIRED = (
    "NumericValueOrderingTest",
    "NumericExactIndependentOracleTest",
    "NumericValueEqualityExactTest",
    "NumericGuestOrderingV1Test",
    "TreeShakerNumericEqualityTest",
    "NumericCheckedI64CurrentMainTest",
    "NumericIntegerDivisionV1Test",
    "NumericTrapResultV1Test",
    "NumericErrorAbiTest",
)
MAX_XML_BYTES = 4 * 1024 * 1024


class ReceiptFailure(ValueError):
    pass


def require_safe_directory(root):
    original = Path(root)
    if not original.parts or ".." in original.parts:
        raise ReceiptFailure("invalid report directory")
    probe = Path(original.anchor) if original.is_absolute() else Path.cwd()
    for part in original.parts:
        if part in ("", ".", original.anchor):
            continue
        probe = probe / part
        if probe.is_symlink():
            raise ReceiptFailure("symlinked report directory ancestor")
        if not probe.is_dir():
            raise ReceiptFailure("missing report directory")
    return probe


def read_regular_report(path):
    if path.is_symlink():
        raise ReceiptFailure("symlinked Surefire report")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as exc:
        raise ReceiptFailure("missing or inaccessible numerical Surefire report") from exc
    with os.fdopen(fd, "rb") as fh:
        meta = os.fstat(fh.fileno())
        if (not stat.S_ISREG(meta.st_mode) or meta.st_nlink != 1
                or meta.st_size > MAX_XML_BYTES):
            raise ReceiptFailure("nonregular, hardlinked or oversized Surefire report")
        raw = fh.read(MAX_XML_BYTES + 1)
    if not raw or len(raw) > MAX_XML_BYTES:
        raise ReceiptFailure("empty or oversized Surefire report")
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise ReceiptFailure("XML document type/entity forbidden")
    return raw


def exact_nonnegative_int(source, field):
    value = source.get(field)
    if value is None or not value.isascii() or not value.isdigit():
        raise ReceiptFailure("missing or malformed numeric Surefire attribute: " + field)
    return int(value)


def verify(root, required=REQUIRED):
    if len(required) != len(set(required)) or not required:
        raise ReceiptFailure("ambiguous required class set")
    directory = require_safe_directory(root)
    total = 0
    for name in required:
        if not name.isidentifier():
            raise ReceiptFailure("invalid required test class")
        qualified = "dev.oreslang." + name
        path = directory / ("TEST-" + qualified + ".xml")
        raw = read_regular_report(path)
        try:
            xml = ET.fromstring(raw)
        except ET.ParseError as exc:
            raise ReceiptFailure("malformed Surefire XML for " + name) from exc
        if xml.tag != "testsuite" or xml.get("name") != qualified:
            raise ReceiptFailure("unexpected Surefire suite identity for " + name)
        declared = exact_nonnegative_int(xml, "tests")
        if declared <= 0:
            raise ReceiptFailure("no tests ran for " + name)
        for field in ("errors", "failures", "skipped"):
            if exact_nonnegative_int(xml, field) != 0:
                raise ReceiptFailure("failed/skipped numerical suite: " + name)
        cases = xml.findall("testcase")
        if len(cases) != declared:
            raise ReceiptFailure("Surefire testcase count does not match: " + name)
        seen = set()
        for case in cases:
            method = case.get("name")
            if not method or not method.strip() or case.get("classname") != qualified:
                raise ReceiptFailure("invalid numerical testcase identity: " + name)
            if method in seen:
                raise ReceiptFailure("duplicate testcase: " + name + "." + method)
            seen.add(method)
            if case.find("failure") is not None or case.find("error") is not None \
                    or case.find("skipped") is not None:
                raise ReceiptFailure("testcase contains failed/skipped outcome")
        total += declared
    return total


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("NUMERIC_JUNIT_RECEIPT_DENIED: expected report directory", file=sys.stderr)
        return 2
    try:
        count = verify(args[0])
    except (ReceiptFailure, OSError, ValueError) as exc:
        print("NUMERIC_JUNIT_RECEIPT_DENIED: " + str(exc), file=sys.stderr)
        return 1
    print("NUMERIC_JVM_GUEST_SUITES=" + str(len(REQUIRED)))
    print("NUMERIC_JVM_JUNIT_CASES=" + str(count))
    print("NUMERIC_JVM_JUNIT_RECEIPT=PASS")
    print("NUMERIC_FULL_GUEST_AOT=NOT_VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
