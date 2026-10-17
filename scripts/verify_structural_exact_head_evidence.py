#!/usr/bin/env python3
"""Fail-closed exact-head structural JUnit evidence; NOT a substitute for guest CI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from xml.etree import ElementTree as ET

SUITES = (
    "StructuralTuplePolicyTest",
    "PositionalTupleSyntaxTest",
    "StructuralCallableParityTest",
    "FixedProductCallPlanTest",
    "RecordWriteVarianceTest",
    "StructuralCrossSurfaceParityTest",
    "StructuralTuplePatternMatchTest",
)
HEX40 = re.compile(r"[0-9a-f]{40}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect(reports, provenance):
    source = json.loads(provenance.read_text(encoding="utf-8"))
    require(type(source) is dict and set(source) == {
        "source_sha", "source_tree", "scope"
    }, "unexpected provenance schema")
    for name in ("source_sha", "source_tree"):
        require(type(source[name]) is str and HEX40.fullmatch(source[name]),
                "invalid " + name)
    require(source["scope"] == "java-structural-exact-head-guest",
            "unexpected test scope")
    evidence = []
    for name in SUITES:
        path = reports / ("TEST-dev.oreslang." + name + ".xml")
        require(path.is_file() and not path.is_symlink(),
                "missing/unsafe Surefire suite: " + name)
        raw = path.read_bytes()
        root = ET.fromstring(raw)
        require(root.tag == "testsuite", "not a Surefire testsuite: " + name)
        expected_class = "dev.oreslang." + name
        require(root.attrib.get("name") == expected_class,
                "unexpected suite name: " + name)
        tests = root.findall("testcase")
        require(len(tests) > 0, "zero executed testcases: " + name)
        require(len(tests) == int(root.attrib.get("tests", "-1")),
                "test count mismatch: " + name)
        for field in ("errors", "failures", "skipped"):
            require(int(root.attrib.get(field, "-1")) == 0,
                    "nonpassing " + field + " in " + name)
        seen = set()
        for case in tests:
            require(case.attrib.get("classname") == expected_class,
                    "mismatched test class: " + name)
            method = case.attrib.get("name")
            require(bool(method) and method not in seen, "missing/duplicate test method: " + name)
            seen.add(method)
            require(not any(case.iter(tag) for tag in ("failure", "error", "skipped")),
                    "failed/skipped testcase hidden by XML counters: " + name)
        evidence.append({
            "suite": expected_class,
            "tests": len(tests),
            "xml_sha256": hashlib.sha256(raw).hexdigest(),
        })
    return {
        "schema": "oreslang.structural.exact-head-junit.v1",
        **source,
        "github_run_id": os.environ.get("GITHUB_RUN_ID") or None,
        "github_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT") or None,
        "suites": evidence,
        "tests_total": sum(x["tests"] for x in evidence),
        "runtime_evidence": "actual-runner-junit",
    }


def self_test():
    with tempfile.TemporaryDirectory() as temp:
        p = Path(temp)
        reports = p / "reports"
        reports.mkdir()
        provenance = p / "source.json"
        provenance.write_text(json.dumps({
            "source_sha": "a" * 40, "source_tree": "b" * 40,
            "scope": "java-structural-exact-head-guest"
        }), encoding="utf-8")
        for name in SUITES:
            classname = "dev.oreslang." + name
            (reports / ("TEST-" + classname + ".xml")).write_text(
                '<testsuite name="' + classname + '" tests="1" errors="0" failures="0" skipped="0">'
                '<testcase classname="' + classname + '" name="example"/></testsuite>',
                encoding="utf-8")
        require(inspect(reports, provenance)["tests_total"] == len(SUITES),
                "synthetic control unexpectedly rejected")
        first = reports / ("TEST-dev.oreslang." + SUITES[0] + ".xml")
        good = first.read_text(encoding="utf-8")
        bad = (
            good.replace('tests="1"', 'tests="0"'),
            good.replace('<testcase classname=', '<testcase><skipped/></testcase><testcase classname='),
            good.replace('classname="dev.oreslang.', 'classname="forged.', 1),
        )
        for altered in bad:
            first.write_text(altered, encoding="utf-8")
            try:
                inspect(reports, provenance)
            except (ValueError, ET.ParseError):
                continue
            raise ValueError("adversarial JUnit alteration was accepted")
        first.write_text(good, encoding="utf-8")
        first.unlink()
        try:
            inspect(reports, provenance)
        except ValueError:
            pass
        else:
            raise ValueError("omitted JUnit suite was accepted")
    print("structural JUnit evidence self-test: pass")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports", type=Path)
    parser.add_argument("--provenance", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        require(not any((args.reports, args.provenance, args.out)),
                "self-test cannot publish evidence")
        self_test()
        return
    require(all((args.reports, args.provenance, args.out)),
            "reports, provenance and out are required")
    results = inspect(args.reports, args.provenance)
    args.out.write_text(json.dumps(results, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Verified", results["tests_total"], "actual JUnit cases from", len(SUITES), "suites")


if __name__ == "__main__":
    main()
