#!/usr/bin/env python3
"""Fail-closed exact-head structural JUnit evidence; NOT a substitute for guest CI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from xml.etree import ElementTree as ET
from unittest.mock import patch

SUITES = (
    "StructuralTuplePolicyTest",
    "PositionalTupleSyntaxTest",
    "StructuralCallableParityTest",
    "FixedProductCallPlanTest",
    "RecordWriteVarianceTest",
    "StructuralCrossSurfaceParityTest",
    "StructuralTuplePatternMatchTest",
    "StructuralContainerPolicyTest",
    "StructuralContainerSourceParityTest",
)
HEX40 = re.compile(r"[0-9a-f]{40}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect(reports, provenance, source_root=None, require_ci=False):
    require(not reports.is_symlink() and not provenance.is_symlink(),
            "unsafe suite directory or provenance symlink")
    source = json.loads(provenance.read_text(encoding="utf-8"))
    require(type(source) is dict and set(source) == {
        "source_sha", "source_tree", "scope"
    }, "unexpected provenance schema")
    for name in ("source_sha", "source_tree"):
        require(type(source[name]) is str and HEX40.fullmatch(source[name]),
                "invalid " + name)
    require(source["scope"] == "java-structural-exact-head-guest",
            "unexpected test scope")
    if require_ci:
        require(source_root is not None, "CI certification requires a local checkout")
        require(os.environ.get("GITHUB_ACTIONS") == "true",
                "not executing on GitHub Actions")
        require(os.environ.get("GITHUB_REPOSITORY")
                == "ores-truffle-oreslang/oreslang-source.java",
                "unexpected source GitHub repository")
        for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            value = os.environ.get(key, "")
            require(value.isascii() and value.isdecimal() and int(value) > 0,
                    "missing or malformed runner provenance: " + key)
        require(os.environ.get("GITHUB_EVENT_NAME") in
                ("pull_request", "workflow_dispatch"),
                "unexpected GitHub event for exact-head JUnit evidence")
    pinned_source_blobs = {}
    if source_root is not None:
        require(source_root.is_dir() and not source_root.is_symlink(),
                "source checkout is absent or symlinked")
        for revision, key in (("HEAD", "source_sha"),
                              ("HEAD^{tree}", "source_tree")):
            command = subprocess.run(
                ["git", "-C", str(source_root), "rev-parse", revision],
                capture_output=True, text=True, check=False, timeout=5)
            require(command.returncode == 0
                    and command.stdout.strip() == source[key],
                    "JUnit evidence does not match the checked-out " + revision)
        # Git HEAD can remain fixed while an earlier build/test step rewrites
        # tracked source in the working tree. Do not certify a dirty checkout
        # as the exact, immutable source revision.
        for diff_args in (("diff", "--quiet", "HEAD", "--"),
                          ("diff", "--cached", "--quiet", "HEAD", "--")):
            clean = subprocess.run(
                ["git", "-C", str(source_root), *diff_args],
                capture_output=True, text=True, check=False, timeout=5)
            require(clean.returncode == 0,
                    "tracked source changed after checkout; exact-head evidence denied")
        if require_ci:
            # A report cannot attest only its source SHA while hiding which
            # workflow and verifier bytes actually established the result.
            for relpath in (".github/workflows/structural-exact-head-guest.yml",
                            "scripts/verify_structural_exact_head_evidence.py"):
                tracked = subprocess.run(
                    ["git", "-C", str(source_root), "rev-parse", "HEAD:" + relpath],
                    capture_output=True, text=True, check=False, timeout=5)
                working = subprocess.run(
                    ["git", "-C", str(source_root), "hash-object", "--", relpath],
                    capture_output=True, text=True, check=False, timeout=5)
                require(tracked.returncode == 0 and working.returncode == 0
                        and bool(HEX40.fullmatch(tracked.stdout.strip()))
                        and tracked.stdout.strip() == working.stdout.strip(),
                        "workflow/verifier blob was missing or modified: " + relpath)
                pinned_source_blobs[relpath] = tracked.stdout.strip()
    evidence = []
    for name in SUITES:
        path = reports / ("TEST-dev.oreslang." + name + ".xml")
        require(path.is_file() and not path.is_symlink(),
                "missing/unsafe Surefire suite: " + name)
        require(path.stat().st_size <= 4 * 1024 * 1024,
                "oversized Surefire suite: " + name)
        raw = path.read_bytes()
        require(b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper(),
                "DTD/entity declaration forbidden in Surefire evidence: " + name)
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
            require(not any(next(case.iter(tag), None) is not None for tag in ("failure", "error", "skipped")),
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
        "pinned_source_blobs": pinned_source_blobs,
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
        # A source-free local Git checkout exercises the real HEAD/tree
        # command path without exporting any Oreslang repository contents.
        checkout = p / "synthetic-git-checkout"
        checkout.mkdir()
        subprocess.run(["git", "init", "-q", str(checkout)],
                       capture_output=True, check=True, timeout=10)
        (checkout / "README.md").write_text("synthetic only\\n".replace("\\n", "\n"),
                                            encoding="utf-8")
        # These are synthetic placeholder files, not Oreslang source or the
        # executable verifier. They let the self-test cover blob pinning.
        workflow_fixture = checkout / ".github/workflows/structural-exact-head-guest.yml"
        verifier_fixture = checkout / "scripts/verify_structural_exact_head_evidence.py"
        workflow_fixture.parent.mkdir(parents=True)
        verifier_fixture.parent.mkdir(parents=True)
        workflow_fixture.write_text("name: synthetic-only\\n".replace("\\n", "\n"),
                                    encoding="utf-8")
        verifier_fixture.write_text("# synthetic-only verifier placeholder\\n".replace("\\n", "\n"),
                                    encoding="utf-8")
        subprocess.run(["git", "-C", str(checkout), "add", "."],
                       capture_output=True, check=True, timeout=10)
        subprocess.run([
            "git", "-C", str(checkout), "-c", "user.name=CI harness",
            "-c", "user.email=ci-harness@example.invalid",
            "commit", "-qm", "synthetic evidence fixture",
        ], capture_output=True, check=True, timeout=10)
        def git_sha(revision):
            return subprocess.run(
                ["git", "-C", str(checkout), "rev-parse", revision],
                capture_output=True, text=True, check=True, timeout=5
            ).stdout.strip()
        actual = {"source_sha": git_sha("HEAD"),
                  "source_tree": git_sha("HEAD^{tree}"),
                  "scope": "java-structural-exact-head-guest"}
        provenance.write_text(json.dumps(actual), encoding="utf-8")
        require(inspect(reports, provenance, checkout)["tests_total"] == len(SUITES),
                "exact synthetic HEAD/tree was not accepted")
        # Exercise the exact-head workflow/verifier blob checks with a
        # synthetic Git repository and simulated Actions environment only.
        mock_ci = {
            "GITHUB_ACTIONS": "true",
            "GITHUB_REPOSITORY": "ores-truffle-oreslang/oreslang-source.java",
            "GITHUB_RUN_ID": "42", "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_EVENT_NAME": "pull_request",
        }
        with patch.dict(os.environ, mock_ci):
            verified = inspect(reports, provenance, checkout, require_ci=True)
            require(verified["tests_total"] == len(SUITES)
                    and len(verified["pinned_source_blobs"]) == 2,
                    "synthetic workflow/verifier Git blob attestation failed")
        # A real Git HEAD/tree match is not sufficient if compiler scripts
        # overwrite a tracked source file before the JUnit report is emitted.
        tracked = checkout / "README.md"
        original = tracked.read_text(encoding="utf-8")
        tracked.write_text(original + "dirty checkout\\n", encoding="utf-8")
        try:
            try:
                inspect(reports, provenance, checkout)
            except ValueError:
                pass
            else:
                raise ValueError("dirty Git worktree was accepted")
            subprocess.run(["git", "-C", str(checkout), "add", "README.md"],
                           capture_output=True, check=True, timeout=5)
            try:
                inspect(reports, provenance, checkout)
            except ValueError:
                pass
            else:
                raise ValueError("staged source mutation was accepted")
        finally:
            subprocess.run(["git", "-C", str(checkout), "reset", "-q", "HEAD", "--", "README.md"],
                           capture_output=True, check=True, timeout=5)
            tracked.write_text(original, encoding="utf-8")
        require(inspect(reports, provenance, checkout)["tests_total"] == len(SUITES),
                "clean checkout was not restored after tamper tests")
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            try:
                inspect(reports, provenance, checkout, require_ci=True)
            except ValueError:
                pass
            else:
                raise ValueError("offline report was promoted to actual-runner evidence")
        for field in ("source_sha", "source_tree"):
            forged = dict(actual)
            forged[field] = "f" * 40
            provenance.write_text(json.dumps(forged), encoding="utf-8")
            try:
                inspect(reports, provenance, checkout)
            except ValueError:
                pass
            else:
                raise ValueError("forged Git " + field + " was accepted")
        provenance.write_text(json.dumps(actual), encoding="utf-8")
        first.write_text("<!DOCTYPE testsuite><testsuite/>", encoding="utf-8")
        try:
            inspect(reports, provenance, checkout)
        except ValueError:
            pass
        else:
            raise ValueError("DTD-bearing report was accepted")
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
    parser.add_argument("--source-root", type=Path)
    parser.add_argument("--provenance", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        require(not any((args.reports, args.provenance, args.out, args.source_root)),
                "self-test cannot publish evidence")
        self_test()
        return
    require(all((args.reports, args.provenance, args.out, args.source_root)),
            "reports, provenance, source-root and out are required")
    require(not args.out.is_symlink(), "output evidence path must not be a symlink")
    results = inspect(args.reports, args.provenance, args.source_root, require_ci=True)
    args.out.write_text(json.dumps(results, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Verified", results["tests_total"], "actual JUnit cases from", len(SUITES), "suites")


if __name__ == "__main__":
    main()
