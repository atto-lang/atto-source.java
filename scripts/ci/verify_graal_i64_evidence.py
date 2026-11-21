#!/usr/bin/env python3
"""Fail-closed, non-attesting admission checks for real Graal i64 CI receipts.

Evidence consistency is not compiler-binary attestation. Reviewed branch
protection, job logs and exact source checkout are separate mandatory gates.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


def no_duplicate_json_pairs(pairs):
    output = {}
    for key, value in pairs:
        require(key not in output, "duplicate JSON key: " + key)
        output[key] = value
    return output


def reject_json_constant(value):
    raise ValueError("non-finite JSON constant: " + value)


def read_bounded(path, limit=1024 * 1024):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(),
            "missing or symlinked evidence: " + str(path))
    for parent in path.parents:
        require(not parent.is_symlink(), "symlinked evidence ancestor: " + str(parent))
    require(0 < path.stat().st_size <= limit, "empty or oversized evidence: " + str(path))
    return path.read_bytes()


# Reviewed Graal JUnit test identity; 17 arbitrary successes are not evidence.
JUNIT_CLASS = "dev.oreslang.GraalI64RuntimeCharacterizationTest"
REQUIRED_JUNIT_NAMES = frozenset(["max","min","max-plus-one-wraps","min-minus-one-wraps","max-times-two-wraps","negate-min-wraps","min-times-negative-one-wraps","overflow-then-cancel","negative-remainder","min-remainder-negative-one","max-minus-one","min-plus-one","min-plus-min-wraps","max-squared-wraps","min-minus-max-wraps","min-remainder-positive-three","zeroDivisorThrowsFromActualGuestEvaluation"])


def check_junit(xml_data):
    root = ET.fromstring(xml_data)
    # The file is the single-class, bounded Surefire XML promised by this job.
    # Never admit a synthetic 17-pass report from unrelated test classes.
    require(root.tag == "testsuite" and root.get("name") == JUNIT_CLASS,
            "wrong JUnit suite or class")
    suites = [root]
    total = 0
    for suite in suites:
        tests = int(suite.attrib.get("tests", "-1"))
        require(tests >= 0, "JUnit test count missing")
        for attr in ("errors", "failures", "skipped"):
            require(int(suite.attrib.get(attr, "-1")) == 0,
                    "JUnit " + attr + " nonzero or missing")
        cases = suite.findall("testcase")
        require(len(cases) == tests, "JUnit test case count disagrees with XML")
        require(all(all(child.tag in {"system-out", "system-err"} for child in case)
                    for case in cases),
                "JUnit test contains failure, skipped or unexpected child evidence")
        require(all(case.get("classname") == JUNIT_CLASS for case in cases),
                "JUnit testcase originates from an unrelated class")
        names = [case.get("name") for case in cases]
        require(len(names) == len(REQUIRED_JUNIT_NAMES)
                and len(set(names)) == len(names)
                and set(names) == REQUIRED_JUNIT_NAMES,
                "missing, substituted or duplicate named Graal JUnit testcase")
        total += tests
    require(total == len(REQUIRED_JUNIT_NAMES),
            "JUnit test count differs from reviewed 16 numeric + 1 divisor cases")
    return total


def check_report(report, suite, suite_digest, expected_commit, schema=None):
    require(isinstance(report, dict), "report must be an object")
    require(set(report) == {"schema_version", "backend", "suite_sha256",
                            "source_revision", "results"},
            "unexpected or absent report root fields")
    require(report["schema_version"] == "1.1.0" and report["backend"] == "graal",
            "wrong backend or schema")
    require(re.fullmatch(r"[a-f0-9]{40}", expected_commit) is not None,
            "invalid approved commit")
    require(report["source_revision"] == expected_commit,
            "unapproved source commit")
    require(re.fullmatch(r"[a-f0-9]{64}", suite_digest) is not None,
            "invalid expected suite digest")
    require(report["suite_sha256"] == suite_digest,
            "mismatched canonical fixture suite digest")
    if schema is not None:
        import jsonschema
        jsonschema.Draft202012Validator(schema).validate(report)
    cases = suite["cases"]
    require(len(cases) == 15, "canonical suite must have exactly 15 cases")
    require(isinstance(report["results"], list) and len(report["results"]) == len(cases),
            "missing or extra semantic results")
    expected = {case["id"]: case for case in cases}
    require(len(expected) == len(cases), "duplicate canonical suite identifier")
    ids = [entry.get("id") for entry in report["results"] if isinstance(entry, dict)]
    require(len(ids) == len(cases) and len(set(ids)) == len(cases) and set(ids) == set(expected),
            "missing, duplicated or substituted semantic fixture")
    for entry in report["results"]:
        name = entry["id"]
        case = expected[name]
        require(case["backends"]["graal"] == "required",
                "pending canonical Graal fixture: " + name)
        require(entry.get("status") == "pass", "pending/failed case: " + name)
        require(not entry.get("detail"), "passing fixture has error details: " + name)
        if case["kind"] == "native_exit":
            require(type(entry.get("observed_exit")) is int and
                    entry["observed_exit"] == case["expected_exit"],
                    "wrong or absent real guest exit: " + name)
            require("diagnostic_class" not in entry,
                    "native result masquerades as diagnostic: " + name)
        elif case["kind"] == "diagnostic":
            require(entry.get("diagnostic_class") == case["diagnostic_class"],
                    "unrelated compiler diagnostic: " + name)
            require("observed_exit" not in entry,
                    "diagnostic result smuggles runtime exit: " + name)
        else:
            raise ValueError("unexpected fixture kind: " + name)
    return len(cases)


def current_git_identity(repo):
    def git(*args):
        return subprocess.check_output(["git", "-c", "core.fsmonitor=false", *args],
                                       cwd=repo, text=True).strip()
    return git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--junit", required=True, type=Path)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument("--expected-tree", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"[a-f0-9]{40}", args.expected_tree) is not None,
            "invalid approved tree")
    head, tree = current_git_identity(args.source_root)
    require((head, tree) == (args.expected_commit, args.expected_tree),
            "checked-out Git commit/tree drift")
    runner = args.source_root / "contracts/v1/semantics/run.py"
    require(runner.is_file() and not runner.is_symlink(),
            "canonical suite runner missing")
    spec = importlib.util.spec_from_file_location("graal_semantic_suite_runner", runner)
    require(spec is not None and spec.loader is not None,
            "canonical suite loader unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    suite, digest = module.validate_suite()
    schema_path = args.source_root / "contracts/v1/semantics/report.schema.json"
    schema = json.loads(read_bounded(schema_path))
    report = json.loads(read_bounded(args.report),
                        object_pairs_hook=no_duplicate_json_pairs,
                        parse_constant=reject_json_constant)
    case_count = check_report(report, suite, digest, args.expected_commit, schema)
    junit_count = check_junit(read_bounded(args.junit))
    require(current_git_identity(args.source_root) ==
            (args.expected_commit, args.expected_tree),
            "source identity changed during admission")
    print(f"GRAAL_I64_EVIDENCE_ADMITTED; JUNIT={junit_count}; "
          f"SEMANTIC_CASES={case_count}; NO_SOURCE_EXPORT; NON_ATTESTING")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError, KeyError, TypeError) as error:
        raise SystemExit("GRAAL_I64_EVIDENCE_DENIED: " + str(error)) from error
