#!/usr/bin/env python3
"""Synthetic receipt rejection tests, NOT executed Oreslang guest evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from xml.etree.ElementTree import Element, SubElement, tostring

from verify_graal_i64_evidence import (
    check_junit, check_report, no_duplicate_json_pairs, read_bounded,
    reject_json_constant, JUNIT_CLASS, REQUIRED_JUNIT_NAMES,
)

SHA = "a" * 40
DIGEST = "b" * 64


def fixture():
    suite = {"cases": []}
    results = []
    for i in range(9):
        name = "native." + str(i)
        suite["cases"].append({"id": name, "kind": "native_exit", "expected_exit": i,
                               "backends": {"graal": "required"}})
        results.append({"id": name, "status": "pass", "observed_exit": i, "detail": ""})
    for i in range(6):
        name = "diagnostic." + str(i)
        suite["cases"].append({"id": name, "kind": "diagnostic",
                               "diagnostic_class": "unknown_name",
                               "backends": {"graal": "required"}})
        results.append({"id": name, "status": "pass",
                        "diagnostic_class": "unknown_name", "detail": ""})
    report = {"schema_version": "1.1.0", "backend": "graal",
              "suite_sha256": DIGEST, "source_revision": SHA, "results": results}
    return suite, report


def xml(tests=17, *, skips=0):
    suite = Element("testsuite", name=JUNIT_CLASS, tests=str(tests), skipped=str(skips),
                    errors="0", failures="0")
    names = sorted(REQUIRED_JUNIT_NAMES)
    for n in range(tests):
        SubElement(suite, "testcase",
                   name=names[n] if n < len(names) else "unexpected-" + str(n),
                   classname=JUNIT_CLASS)
    return tostring(suite)


class EvidenceAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.suite, self.report = fixture()

    def check(self):
        return check_report(self.report, self.suite, DIGEST, SHA)

    def rejects(self, phrase):
        with self.assertRaisesRegex(ValueError, phrase):
            self.check()

    def test_complete_fixture_set(self):
        self.assertEqual(self.check(), 15)

    def test_wrong_revision(self):
        self.report["source_revision"] = "c" * 40
        self.rejects("unapproved source commit")

    def test_wrong_suite_digest(self):
        self.report["suite_sha256"] = "c" * 64
        self.rejects("mismatched canonical fixture suite digest")

    def test_wrong_backend(self):
        self.report["backend"] = "llvm"
        self.rejects("wrong backend")

    def test_substituted_successful_fixture(self):
        self.report["results"][-1]["id"] = "diagnostic.fake"
        self.rejects("substituted semantic fixture")

    def test_duplicate_successful_fixture(self):
        self.report["results"][-1]["id"] = self.report["results"][0]["id"]
        self.rejects("duplicated")

    def test_missing_result(self):
        self.report["results"].pop()
        self.rejects("missing or extra")

    def test_extra_result(self):
        self.report["results"].append({"id": "fake", "status": "pass"})
        self.rejects("missing or extra")

    def test_false_success_with_wrong_native_exit(self):
        self.report["results"][1]["observed_exit"] = 0
        self.rejects("wrong or absent real guest exit")

    def test_false_success_with_missing_native_exit(self):
        self.report["results"][1].pop("observed_exit")
        self.rejects("wrong or absent real guest exit")

    def test_false_success_with_wrong_diagnostic(self):
        self.report["results"][-1]["diagnostic_class"] = "call_arity"
        self.rejects("unrelated compiler diagnostic")

    def test_false_success_with_missing_diagnostic(self):
        self.report["results"][-1].pop("diagnostic_class")
        self.rejects("unrelated compiler diagnostic")

    def test_pending_status(self):
        self.report["results"][0]["status"] = "pending"
        self.rejects("pending/failed case")

    def test_failed_status(self):
        self.report["results"][0]["status"] = "fail"
        self.rejects("pending/failed case")

    def test_pending_registry_entry(self):
        self.suite["cases"][0]["backends"]["graal"] = "pending"
        self.rejects("pending canonical Graal fixture")

    def test_pass_cannot_carry_error_text(self):
        self.report["results"][0]["detail"] = "compiler crashed"
        self.rejects("passing fixture has error details")

    def test_no_smuggled_diagnostic(self):
        self.report["results"][0]["diagnostic_class"] = "unknown_name"
        self.rejects("masquerades as diagnostic")

    def test_no_smuggled_runtime_exit(self):
        self.report["results"][-1]["observed_exit"] = 0
        self.rejects("smuggles runtime exit")

    def test_canonical_inventory_exactly_15(self):
        self.suite["cases"].append(copy.deepcopy(self.suite["cases"][0]))
        self.rejects("exactly 15 cases")

    def test_junit_happy_path(self):
        self.assertEqual(check_junit(xml()), 17)

    def test_junit_incomplete(self):
        with self.assertRaisesRegex(ValueError, "missing, substituted or duplicate"):
            check_junit(xml(16))

    def test_junit_skip(self):
        with self.assertRaisesRegex(ValueError, "skipped"):
            check_junit(xml(17, skips=1))

    def test_junit_forged_count(self):
        bad = xml(17).replace(b'tests="17"', b'tests="18"')
        with self.assertRaisesRegex(ValueError, "test case count disagrees"):
            check_junit(bad)

    def test_junit_per_test_output_is_not_a_failure(self):
        from xml.etree.ElementTree import fromstring
        doc = fromstring(xml(17))
        SubElement(doc.find("testcase"), "system-out").text = "diagnostic metadata"
        self.assertEqual(check_junit(tostring(doc)), 17)

    def test_junit_hidden_failure_node(self):
        from xml.etree.ElementTree import fromstring
        doc = fromstring(xml(17))
        SubElement(doc.find("testcase"), "failure")
        with self.assertRaisesRegex(ValueError, "contains failure"):
            check_junit(tostring(doc))

    def test_junit_unrelated_suite_identity_denied(self):
        from xml.etree.ElementTree import fromstring
        doc = fromstring(xml(17))
        doc.set("name", "dev.oreslang.UnrelatedSuite")
        with self.assertRaisesRegex(ValueError, "wrong JUnit suite"):
            check_junit(tostring(doc))

    def test_junit_unrelated_testcase_class_denied(self):
        from xml.etree.ElementTree import fromstring
        doc = fromstring(xml(17))
        doc.find("testcase").set("classname", "dev.oreslang.UnrelatedSuite")
        with self.assertRaisesRegex(ValueError, "unrelated class"):
            check_junit(tostring(doc))

    def test_junit_duplicate_name_denied(self):
        from xml.etree.ElementTree import fromstring
        doc = fromstring(xml(17))
        cases = doc.findall("testcase")
        cases[1].set("name", cases[0].get("name"))
        with self.assertRaisesRegex(ValueError, "duplicate named"):
            check_junit(tostring(doc))

    def test_junit_substituted_name_denied(self):
        from xml.etree.ElementTree import fromstring
        doc = fromstring(xml(17))
        doc.find("testcase").set("name", "arbitrary-success")
        with self.assertRaisesRegex(ValueError, "substituted"):
            check_junit(tostring(doc))

    def test_junit_unrelated_extra_pass_denied(self):
        with self.assertRaisesRegex(ValueError, "missing, substituted or duplicate"):
            check_junit(xml(18))

    def test_reject_duplicate_json_key(self):
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            json.loads('{"status":"pass","status":"fail"}',
                       object_pairs_hook=no_duplicate_json_pairs)

    def test_reject_nan(self):
        with self.assertRaisesRegex(ValueError, "non-finite"):
            json.loads('{"exit":NaN}', parse_constant=reject_json_constant)

    def test_reject_missing_symlink_and_oversize_receipts(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "evidence.json"
            with self.assertRaisesRegex(ValueError, "missing"):
                read_bounded(p)
            p.write_bytes(b"x" * 120)
            self.assertEqual(read_bounded(p), b"x" * 120)
            with self.assertRaisesRegex(ValueError, "oversized"):
                read_bounded(p, limit=119)
            q = Path(tmp) / "alias.json"
            q.symlink_to(p)
            with self.assertRaisesRegex(ValueError, "symlinked"):
                read_bounded(q)


if __name__ == "__main__":
    unittest.main()
