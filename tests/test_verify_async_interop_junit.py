"""Source-free negative acceptance for the exact 42-case JUnit XML receipt."""
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / "tools" / "verify_async_interop_junit.py"
spec = importlib.util.spec_from_file_location("junit_verify", MODULE)
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)

class JUnitProofTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for suite, count in verify.EXPECTED.items():
            node = ET.Element("testsuite", dict(name=suite, tests=str(count),
                              errors="0", failures="0", skipped="0"))
            for i in range(count):
                ET.SubElement(node, "testcase", dict(classname=suite, name=f"method_{i}"))
            ET.ElementTree(node).write(self.root / f"TEST-{suite}.xml")

    def report(self, name):
        return self.root / f"TEST-dev.oreslang.runtime.{name}.xml"

    def test_all_four_suites_42_records(self):
        self.assertEqual(verify.verify(self.root)["executed"], 42)

    def test_missing_suite_denied(self):
        self.report("TaskFutureAdaptersTest").unlink()
        with self.assertRaisesRegex(ValueError, "missing or symlinked"):
            verify.verify(self.root)

    def test_skipped_suite_denied(self):
        p = self.report("OneShotObservableTest")
        tree = ET.parse(p); tree.getroot().set("skipped", "1"); tree.write(p)
        with self.assertRaisesRegex(ValueError, "incorrect execution"):
            verify.verify(self.root)

    def test_missing_testcase_denied(self):
        p = self.report("OresFutureObserverTest")
        tree = ET.parse(p); tree.getroot().remove(tree.getroot().find("testcase")); tree.write(p)
        with self.assertRaisesRegex(ValueError, "missing, misattributed"):
            verify.verify(self.root)

    def test_hidden_case_failure_denied(self):
        p = self.root / "TEST-dev.oreslang.FutureFromInlineTaskLanguageTest.xml"
        tree = ET.parse(p); ET.SubElement(tree.getroot().find("testcase"), "failure"); tree.write(p)
        with self.assertRaisesRegex(ValueError, "missing, misattributed"):
            verify.verify(self.root)

    def test_duplicate_case_denied(self):
        p = self.report("TaskFutureAdaptersTest")
        tree = ET.parse(p); tree.getroot().findall("testcase")[1].set("name", "method_0"); tree.write(p)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify.verify(self.root)

    def test_wrong_suite_denied(self):
        p = self.report("TaskFutureAdaptersTest")
        tree = ET.parse(p); tree.getroot().set("name", "unrelated"); tree.write(p)
        with self.assertRaisesRegex(ValueError, "wrong JUnit"):
            verify.verify(self.root)

    def test_symlinked_report_denied(self):
        p = self.report("TaskFutureAdaptersTest")
        q = self.root / "elsewhere.xml"
        p.rename(q); p.symlink_to(q)
        with self.assertRaisesRegex(ValueError, "missing or symlinked"):
            verify.verify(self.root)

    def test_bad_count_denied(self):
        p = self.report("TaskFutureAdaptersTest")
        tree = ET.parse(p); tree.getroot().set("tests", "abc"); tree.write(p)
        with self.assertRaisesRegex(ValueError, "invalid tests"):
            verify.verify(self.root)

    def test_invalid_xml_denied(self):
        self.report("TaskFutureAdaptersTest").write_text("<testsuite", encoding="utf-8")
        with self.assertRaises(ET.ParseError):
            verify.verify(self.root)

if __name__ == "__main__":
    unittest.main()
