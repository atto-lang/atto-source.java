"""Synthetic-only adversarial report tests, never compile private source."""
import os
from pathlib import Path
import tempfile
import unittest
from xml.sax.saxutils import escape
from nio_verify_surefire_evidence import EXPECTED, verify


class EvidenceVerifierTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='nio-report-fixtures-')
        self.addCleanup(self.tmp.cleanup)
        self.old = Path.cwd()
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, self.old)
        self.marker = Path('marker')
        self.marker.write_text('start')
        self.reports = Path('target/surefire-reports')
        self.reports.mkdir(parents=True)
        for name in EXPECTED:
            self.write(name)

    def write(self, name, *, classname=None, skipped=0, fail=0, errors=0,
              count=1, declared=None, suffix=''):
        classname = classname if classname is not None else f'dev.oreslang.{name}'
        problem = '<skipped/>' if skipped else ('<failure/>' if fail else ('<error/>' if errors else ''))
        xml = (f'<testsuite name="dev.oreslang.{name}" tests="{count if declared is None else declared}" '
               f'skipped="{skipped}" failures="{fail}" errors="{errors}">'
               f'<testcase classname="{escape(classname)}" name="proof">{problem}</testcase></testsuite>')
        f = self.reports / f'TEST-dev.oreslang.{name}{suffix}.xml'
        f.write_text(xml)
        os.utime(f, ns=(self.marker.stat().st_mtime_ns + 1000000000,
                        self.marker.stat().st_mtime_ns + 1000000000))
        return f

    def verify(self):
        return verify(self.marker, 'targeted')

    def test_clean(self):
        self.assertEqual((8, 8), self.verify())

    def test_missing(self):
        (self.reports / f'TEST-dev.oreslang.{EXPECTED[0]}.xml').unlink()
        with self.assertRaises(ValueError): self.verify()

    def test_skipped(self):
        self.write(EXPECTED[0], skipped=1)
        with self.assertRaises(ValueError): self.verify()

    def test_failure(self):
        self.write(EXPECTED[0], fail=1)
        with self.assertRaises(ValueError): self.verify()

    def test_error(self):
        self.write(EXPECTED[0], errors=1)
        with self.assertRaises(ValueError): self.verify()

    def test_fake_summary_count(self):
        self.write(EXPECTED[0], declared=200)
        with self.assertRaises(ValueError): self.verify()

    def test_filename_cannot_impersonate_suite(self):
        self.write(EXPECTED[0], classname='dev.oreslang.OtherTest')
        with self.assertRaises(ValueError): self.verify()

    def test_malformed_xml(self):
        f = self.reports / f'TEST-dev.oreslang.{EXPECTED[0]}.xml'
        f.write_text('<testsuite')
        with self.assertRaises(ValueError): self.verify()

    def test_report_symlink(self):
        p = self.reports / f'TEST-dev.oreslang.{EXPECTED[0]}.xml'
        p.unlink()
        external = Path('elsewhere.xml')
        external.write_text('<testsuite/>')
        p.symlink_to(external.resolve())
        with self.assertRaises(ValueError): self.verify()

    def test_stale(self):
        for p in self.reports.glob('TEST-*.xml'):
            os.utime(p, ns=(self.marker.stat().st_mtime_ns-1000000000,
                            self.marker.stat().st_mtime_ns-1000000000))
        with self.assertRaises(ValueError): self.verify()


if __name__ == '__main__':
    unittest.main()
