"""Source-free, synthetic adversarial checks for Surefire qualification evidence."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

VERIFIER = Path(__file__).resolve().parents[1] / 'scripts' / 'nio_verify_surefire_evidence.py'
SUITES = (
    'VmNioTcpListenerTest', 'NativeNetStdlibTest', 'NioReadinessGroupTest',
    'NativeNioFutureReactorTest', 'OresSchedulerTest',
    'OresCarrierPermitPoolTest', 'OresFuturesTest', 'OresFutureCallbackTest',
)


class SurefireEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ores-surefire-fixture-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.marker = self.root / 'start.marker'
        self.marker.write_text('phase start')
        self.reports = self.root / 'target/surefire-reports'
        self.reports.mkdir(parents=True)
        for suite in SUITES:
            p = self.report(suite)
            p.write_text(self.xml(suite))
            future = self.marker.stat().st_mtime_ns + 1_000_000_000
            os.utime(p, ns=(future, future))

    def report(self, suite):
        return self.reports / ('TEST-dev.oreslang.' + suite + '.xml')

    def xml(self, suite):
        return (
            '<testsuite name="' + suite + '" tests="1" skipped="0" '
            'failures="0" errors="0"><testcase classname="dev.oreslang.'
            + suite + '" name="synthetic"/></testsuite>'
        )

    def check(self, code=76):
        p = subprocess.run(
            [sys.executable, str(VERIFIER), str(self.marker), 'targeted'],
            cwd=self.root, capture_output=True, text=True, check=False, timeout=15
        )
        self.assertEqual(code, p.returncode, p.stderr)
        return p

    def test_all_eight_real_classnames_pass_synthetic(self):
        self.check(0)

    def test_filename_cannot_spoof_test_class(self):
        suite = SUITES[0]
        self.report(suite).write_text(self.xml(suite).replace(
            'dev.oreslang.' + suite, 'dev.oreslang.OtherClass'
        ))
        self.check()

    def test_suite_name_cannot_replace_missing_testcase_classname(self):
        suite = SUITES[0]
        self.report(suite).write_text(
            self.xml(suite).replace('classname="dev.oreslang.' + suite + '"', '')
        )
        self.check()

    def test_failure_counter_without_child_is_rejected(self):
        p = self.report(SUITES[0])
        p.write_text(p.read_text().replace('failures="0"', 'failures="1"'))
        self.check()

    def test_skipped_counter_without_child_is_rejected(self):
        p = self.report(SUITES[0])
        p.write_text(p.read_text().replace('skipped="0"', 'skipped="1"'))
        self.check()

    def test_report_symlink_is_rejected(self):
        p = self.report(SUITES[0])
        outside = self.root / 'external.xml'
        outside.write_text(self.xml(SUITES[0]))
        p.unlink()
        p.symlink_to(outside)
        self.check()

    def test_start_marker_symlink_is_rejected(self):
        outside = self.root / 'external.marker'
        self.marker.rename(outside)
        self.marker.symlink_to(outside)
        self.check()

    def test_dtd_is_rejected(self):
        p = self.report(SUITES[0])
        p.write_text('<!DOCTYPE testsuite [<!ENTITY x "x">]>' + p.read_text())
        self.check()

    def test_malformed_xml_is_rejected(self):
        self.report(SUITES[0]).write_text('<bad')
        self.check()

    def test_stale_reports_are_rejected(self):
        earlier = self.marker.stat().st_mtime_ns - 1_000_000_000
        for p in self.reports.iterdir():
            os.utime(p, ns=(earlier, earlier))
        self.check()


if __name__ == '__main__':
    unittest.main()
