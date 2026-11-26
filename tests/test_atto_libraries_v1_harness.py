"""Host-only runner regression tests; no compiler or guest proof."""
import importlib.util
import pathlib
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("runner", pathlib.Path(__file__).with_name("atto_libraries_v1.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LibraryRunnerTests(unittest.TestCase):
    def test_required_inventory(self):
        cases = [pathlib.Path(p) for p in sorted(runner.REQUIRED_CASES)]
        runner.validate_corpus(cases)
        runner.validate_corpus(cases + [pathlib.Path("extra.atto")])
        with self.assertRaisesRegex(ValueError, "missing required"):
            runner.validate_corpus([])
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaisesRegex(ValueError, "missing required"):
                    runner.validate_corpus([p for p in cases if p != case])

    def test_rejection_requires_normal_exit_and_diagnostic(self):
        def result(code, stderr="", stdout=""):
            return subprocess.CompletedProcess([], code, stdout, stderr)
        self.assertTrue(runner.check_rejection(result(1, "type error"), ["type error"]))
        self.assertFalse(runner.check_rejection(result(0, "type error"), ["type error"]))
        self.assertFalse(runner.check_rejection(result(-9, "type error"), ["type error"]))
        self.assertFalse(runner.check_rejection(result(1, ""), []))
        self.assertFalse(runner.check_rejection(result(1, "other"), ["type error"]))


if __name__ == "__main__":
    unittest.main()
