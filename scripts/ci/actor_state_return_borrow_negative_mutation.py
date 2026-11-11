#!/usr/bin/env python3
"""Fail-closed exact-head negative mutation of the actor-state borrow return gate.

Runs only inside an authorized source checkout, after positive focused JUnit
passes. No network/source export, no credentials and no alternate-org checkout.
Preserves the pristine Surefire XML receipt after proving the mutated compiler
makes the targeted regression fail with the intended assertion.
"""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

SOURCE = Path("src/main/java/dev/oreslang/types/OwnershipChecker.java")
ORIGINAL_XML = Path(
    "target/surefire-reports/TEST-dev.oreslang.ActorStateBorrowReturnFenceTest.xml"
)
MUTATION_DIR = Path("target/actor-state-negative-mutation")
# Projected returns now fail earlier at the field-loan admission gate;
# mutate the independent return fence through a whole-self borrowed return.
TARGET_TEST = "wholeActorSelfCannotEscapeViaBorrowedReturn"
GUARD = '''        if (source != null && isActorConfinedBorrow(source)) {
            throw error("actor-owned state borrow cannot escape its mailbox turn "
                    + "as a returned field or projected alias");
        }
'''


def git(*arguments: str) -> str:
    return subprocess.check_output(["git", *arguments], text=True).strip()


def main() -> None:
    if git("status", "--porcelain", "--untracked-files=no"):
        raise SystemExit("FAIL: tracked source checkout is not pristine before mutation")
    source_before = SOURCE.read_text(encoding="utf-8")
    if source_before.count(GUARD) != 1:
        raise SystemExit("FAIL: actor-return guard not found exactly once")
    if not ORIGINAL_XML.is_file():
        raise SystemExit("FAIL: positive original Surefire XML is missing")
    original_xml = ORIGINAL_XML.read_bytes()
    original_suite = ET.fromstring(original_xml)
    if (int(original_suite.attrib.get("tests", 0)) < 7
            or any(int(original_suite.attrib.get(key, -1)) != 0
                   for key in ("errors", "failures", "skipped"))):
        raise SystemExit("FAIL: original focused JUnit receipt is not passing")

    MUTATION_DIR.mkdir(parents=True, exist_ok=True)
    mutation_xml = MUTATION_DIR / "TEST-MUTATION-ActorStateBorrowReturnFenceTest.xml"
    mutation_receipt = MUTATION_DIR / "negative-mutation-receipt.json"
    head = git("rev-parse", "HEAD")
    tree = git("rev-parse", "HEAD^{tree}")

    try:
        SOURCE.write_text(source_before.replace(GUARD,
                          "        // Negative mutation: actor borrow return fence deleted.\n", 1),
                          encoding="utf-8")
        run = subprocess.run([
            "mvn", "-B", "-ntp", "-Dores.runtime.carriers=java",
            f"-Dtest=ActorStateBorrowReturnFenceTest#{TARGET_TEST}",
            "-DfailIfNoTests=true", "test"
        ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        if run.returncode == 0:
            raise SystemExit("FAIL: deleting the actor-state guard did not kill the test")
        if not ORIGINAL_XML.is_file():
            raise SystemExit("FAIL: negative run produced no Surefire XML")
        mutated_xml = ORIGINAL_XML.read_bytes()
        suite = ET.fromstring(mutated_xml)
        cases = list(suite.iter("testcase"))
        # A compile error or a previously stale 7-test receipt must never
        # count as a successful mutation kill.
        if (int(suite.attrib.get("tests", 0)) != 1
                or int(suite.attrib.get("failures", 0)) != 1
                or int(suite.attrib.get("errors", 0)) != 0
                or int(suite.attrib.get("skipped", 0)) != 0
                or len(cases) != 1
                or TARGET_TEST not in cases[0].attrib.get("name", "")):
            raise SystemExit("FAIL: mutation was not caught by target JUnit assertion")
        failure = cases[0].find("failure")
        diagnostic = "" if failure is None else (
            failure.attrib.get("message", "") + " " + (failure.text or ""))
        if ("the ownership checker must itself reject" not in diagnostic
                and "Expected java.lang.IllegalArgumentException to be thrown" not in diagnostic):
            raise SystemExit(
                "FAIL: mutation caused an unrelated test failure, not the return-borrow guard")
        mutation_xml.write_bytes(mutated_xml)
        mutation_receipt.write_text(json.dumps({
            "source_head": head,
            "source_tree": tree,
            "test_class": "ActorStateBorrowReturnFenceTest",
            "negative_test": TARGET_TEST,
            "expected_mutation": "delete actor-owned returned-borrow fence",
            "mutated_test_failures": 1,
            "mutated_test_errors": 0,
            "negative_test_executed": True,
            "source_export": False,
        }, indent=2) + "\n", encoding="utf-8")
        print("ACTOR_STATE_NEGATIVE_MUTATION_EXECUTED=true")
        print("EXACT_SOURCE_HEAD=" + head)
        print("EXACT_SOURCE_TREE=" + tree)
    finally:
        SOURCE.write_text(source_before, encoding="utf-8")
        ORIGINAL_XML.parent.mkdir(parents=True, exist_ok=True)
        ORIGINAL_XML.write_bytes(original_xml)
        if git("status", "--porcelain", "--untracked-files=no"):
            raise SystemExit("FAIL: mutation test left tracked source modified")


if __name__ == "__main__":
    main()
