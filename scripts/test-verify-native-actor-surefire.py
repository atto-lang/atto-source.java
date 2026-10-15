#!/usr/bin/env python3
"""Synthetic denial tests for positive JNI/guest Surefire evidence gates."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

MODULE = Path(__file__).with_name("verify-native-actor-surefire.py")
spec = importlib.util.spec_from_file_location("native_surefire_gate", MODULE)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class NativeSurefireEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.reports = Path(self.tmp.name)

    def report(self, name, tests, *, failures=0, errors=0, skipped=0, cases=None):
        root = ET.Element("testsuite", {
            "name": gate.PREFIX + name, "tests": str(tests),
            "failures": str(failures), "errors": str(errors),
            "skipped": str(skipped),
        })
        selected = (sorted(gate.REQUIRED_METHODS[name])
                    if name in gate.REQUIRED_METHODS else [])
        for n in range(tests if cases is None else cases):
            # Use actual JUnit method names for the real JNI contract,
            # while other report fixtures may use synthetic test names.
            method = selected[n] if n < len(selected) else f"test{n}"
            case = ET.SubElement(root, "testcase", name=method)
            if n == 0 and skipped:
                ET.SubElement(case, "skipped")
            if n == 0 and failures:
                ET.SubElement(case, "failure")
        target = self.reports / ("TEST-" + name + ".xml")
        target.write_bytes(ET.tostring(root))
        return target

    def test_green_jni_requires_all_fifteen_cases(self):
        self.report("NativeActorTurnSchedulerJniTest", 15)
        self.assertEqual(gate.verify(self.reports, "jni"),
                         {"NativeActorTurnSchedulerJniTest": 15})

    def test_green_guest_policy_requires_all_six_classes(self):
        for name in gate.REQUIRED["policy"]:
            self.report(name, 1)
        self.assertEqual(len(gate.verify(self.reports, "policy")), 6)

    def test_jni_partial_execution_rejected(self):
        self.report("NativeActorTurnSchedulerJniTest", 14)
        with self.assertRaises(gate.VerificationFailure):
            gate.verify(self.reports, "jni")

    def test_jni_valid_count_but_replaced_method_rejected(self):
        report = self.report("NativeActorTurnSchedulerJniTest", 15)
        root = ET.fromstring(report.read_bytes())
        root.findall("testcase")[0].set("name", "unrelatedGreenerTest")
        report.write_bytes(ET.tostring(root))
        with self.assertRaisesRegex(gate.VerificationFailure, "methods absent or replaced"):
            gate.verify(self.reports, "jni")

    def test_jni_duplicate_method_even_with_valid_count_rejected(self):
        report = self.report("NativeActorTurnSchedulerJniTest", 15)
        root = ET.fromstring(report.read_bytes())
        cases = root.findall("testcase")
        cases[-1].set("name", cases[0].get("name"))
        report.write_bytes(ET.tostring(root))
        with self.assertRaisesRegex(gate.VerificationFailure, "duplicate testcase"):
            gate.verify(self.reports, "jni")

    def test_missing_policy_class_rejected(self):
        for name in list(gate.REQUIRED["policy"])[:-1]:
            self.report(name, 2)
        with self.assertRaises(gate.VerificationFailure):
            gate.verify(self.reports, "policy")

    def test_silent_skip_and_failed_test_rejected(self):
        for flags in ({"skipped": 1}, {"failures": 1}, {"errors": 1}):
            with self.subTest(flags=flags):
                self.report("NativeActorTurnSchedulerJniTest", 15, **flags)
                with self.assertRaises(gate.VerificationFailure):
                    gate.verify(self.reports, "jni")

    def test_claimed_count_disagrees_with_cases_rejected(self):
        self.report("NativeActorTurnSchedulerJniTest", 15, cases=1)
        with self.assertRaises(gate.VerificationFailure):
            gate.verify(self.reports, "jni")

    def test_invalid_xml_and_symlink_reports_rejected(self):
        target = self.report("NativeActorTurnSchedulerJniTest", 15)
        target.write_text("<testsuite")
        with self.assertRaises(gate.VerificationFailure):
            gate.verify(self.reports, "jni")
        target.unlink()
        target.symlink_to(self.reports / "not-here")
        with self.assertRaises(gate.VerificationFailure):
            gate.verify(self.reports, "jni")

    def test_empty_reports_rejected(self):
        with self.assertRaises(gate.VerificationFailure):
            gate.verify(self.reports, "jni")


if __name__ == "__main__":
    unittest.main(verbosity=2)
