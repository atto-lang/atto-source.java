import importlib.util
from pathlib import Path
import tempfile
import unittest

FILE = Path(__file__).resolve().parents[1] / "scripts" / "verify_numeric_junit_receipts.py"
spec = importlib.util.spec_from_file_location("numeric_receipts", FILE)
verify_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify_module)


def document(name, method="real_guest", extra="", tests=1, skipped=0):
    q = "dev.oreslang." + name
    return (f'<testsuite name="{q}" tests="{tests}" errors="0" failures="0" skipped="{skipped}">'
            + f'<testcase classname="{q}" name="{method}">{extra}</testcase></testsuite>')


class NumericReceiptVerifierTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "target"
        self.root.mkdir()
        self.dir = self.root / "surefire-reports"
        self.dir.mkdir()
        for name in verify_module.REQUIRED:
            self.write(name, document(name))

    def write(self, name, contents):
        (self.dir / ("TEST-dev.oreslang." + name + ".xml")).write_text(contents)

    def test_all_eight_unique_nonempty_suites_accepted(self):
        self.assertEqual(verify_module.verify(self.dir), 8)
        self.assertEqual(verify_module.main([str(self.dir)]), 0)

    def test_missing_report_denied(self):
        (self.dir / ("TEST-dev.oreslang." + verify_module.REQUIRED[0] + ".xml")).unlink()
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.dir)

    def test_skip_empty_failure_error_and_duplicate_denied(self):
        name = verify_module.REQUIRED[0]
        variants = [
            document(name, tests=0),
            document(name, skipped=1),
            document(name, extra='<skipped/>'),
            document(name, extra='<failure/>'),
            document(name, extra='<error/>'),
            document(name, tests=2),
            document(name, method='other', tests=2).replace('</testsuite>',
                '<testcase classname="dev.oreslang.' + name + '" name="other"/></testsuite>'),
            document(name).replace('name="real_guest"', 'name=""'),
            document(name).replace('classname="dev.oreslang.' + name + '"', 'classname="bad"'),
            '<!DOCTYPE foo [<!ENTITY x "surprise">]>' + document(name),
            '<testsuite><bad></testsuite>',
        ]
        for raw in variants:
            with self.subTest(raw=raw[:80]):
                self.write(name, raw)
                with self.assertRaises(verify_module.ReceiptFailure):
                    verify_module.verify(self.dir)

    def test_symlink_ancestor_report_and_hardlink_denied(self):
        original = self.dir / ("TEST-dev.oreslang." + verify_module.REQUIRED[0] + ".xml")
        link = self.dir / "linked-report.xml"
        link.symlink_to(original)
        original.unlink()
        original.symlink_to(link)
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.dir)
        original.unlink()
        link.unlink()
        self.write(verify_module.REQUIRED[0], document(verify_module.REQUIRED[0]))
        hard = self.dir / "other-file"
        import os
        os.link(original, hard)
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.dir)
        hard.unlink()
        other = Path(self.temp.name) / "alias"
        other.symlink_to(self.dir, target_is_directory=True)
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(other)

    def test_oversized_and_invalid_required_identity_denied(self):
        name = verify_module.REQUIRED[0]
        self.write(name, 'X' * (verify_module.MAX_XML_BYTES + 1))
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.dir)
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.root / '..' / 'target')
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.dir, ('../../escape',))
        with self.assertRaises(verify_module.ReceiptFailure):
            verify_module.verify(self.dir, (name, name))


if __name__ == "__main__":
    unittest.main()
