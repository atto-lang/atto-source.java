"""Source-free adversarial probes for mandatory Surefire execution receipts."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

VERIFIER = (Path(__file__).resolve().parents[1] /
            "scripts" / "verify-nio-surefire-reports.py")
REQUIRED = ("VmNioTcpListenerTest", "NativeNetStdlibTest",
            "NioReadinessGroupTest", "NativeNioFutureReactorTest",
            "OresSchedulerTest", "OresCarrierPermitPoolTest",
            "OresFuturesTest", "OresFutureCallbackTest")


class ReportProofTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="ores-surefire-proof-")
        self.addCleanup(self.tmp.cleanup)
        self.reports = Path(self.tmp.name) / "surefire-reports"
        self.reports.mkdir()
        for name in REQUIRED:
            self.write(name)

    def write(self, name, *, skipped=0, tests=1, failures=0, errors=0,
              case_skip=False, case_failure=False):
        inside = ("<skipped/>" if case_skip else "") + (
            "<failure message='injected'/>" if case_failure else "")
        (self.reports / ("TEST-" + name + ".xml")).write_text(
            f'<testsuite name="dev.oreslang.runtime.{name}" tests="{tests}" '
            f'skipped="{skipped}" failures="{failures}" errors="{errors}">'
            f'<testcase name="probe">{inside}</testcase></testsuite>')

    def run_verifier(self):
        return subprocess.run(
            [sys.executable, str(VERIFIER), str(self.reports)],
            capture_output=True, text=True, timeout=5)

    def test_all_mandatory_suites_execute(self):
        r = self.run_verifier()
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("8 non-skipped test cases", r.stdout)

    def test_missing_suite_rejected(self):
        (self.reports / ("TEST-" + REQUIRED[0] + ".xml")).unlink()
        self.assertEqual(74, self.run_verifier().returncode)

    def test_skipped_only_suite_rejected(self):
        self.write(REQUIRED[0], skipped=1, case_skip=True)
        self.assertEqual(74, self.run_verifier().returncode)

    def test_skipped_element_cannot_lie_about_counter(self):
        self.write(REQUIRED[0], skipped=0, case_skip=True)
        self.assertEqual(74, self.run_verifier().returncode)

    def test_surefire_failure_rejected(self):
        self.write(REQUIRED[0], failures=1, case_failure=True)
        self.assertEqual(74, self.run_verifier().returncode)

    def test_malformed_xml_rejected(self):
        (self.reports / ("TEST-" + REQUIRED[0] + ".xml")).write_text("<testsuite")
        self.assertEqual(74, self.run_verifier().returncode)

    def test_duplicate_suite_rejected(self):
        (self.reports / "TEST-duplicate.xml").write_text(
            (self.reports / ("TEST-" + REQUIRED[0] + ".xml")).read_text())
        self.assertEqual(74, self.run_verifier().returncode)

    def test_symlinked_report_rejected(self):
        original = self.reports / ("TEST-" + REQUIRED[0] + ".xml")
        original.unlink()
        external = Path(self.tmp.name) / "external.xml"
        external.write_text("<testsuite/>")
        original.symlink_to(external)
        self.assertEqual(74, self.run_verifier().returncode)


if __name__ == "__main__":
    unittest.main()
