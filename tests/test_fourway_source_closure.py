"""Source-free adversarial tests for the four-way exact-Git-blob CI verifier.

Every fixture is a brand-new synthetic local Git repository. No private Java
source, PR blob, carrier source transfer, or network access is involved.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

# CI invokes this module with PYTHONDONTWRITEBYTECODE=1 to keep checkout clean.
sys.dont_write_bytecode = True
VERIFIER_PATH = Path(__file__).resolve().parents[1] / "ci/compiler/verify-fourway-source-closure.py"
_spec = importlib.util.spec_from_file_location("atto_fourway_closure", VERIFIER_PATH)
assert _spec and _spec.loader
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)


class FourwaySourceClosureTest(unittest.TestCase):
    def git(self, *args: str) -> str:
        p = subprocess.run(["git", *args], cwd=self.repo, text=True, check=True,
                           capture_output=True)
        return p.stdout.strip()

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="atto-fourway-synthetic-")
        self.repo = Path(self.temp.name)
        self.original_cwd = Path.cwd()
        self.original_heads = checker.PR_HEADS
        self.original_blobs = checker.EXPECTED_BLOBS
        self.original_executables = checker.EXPECTED_EXECUTABLE_FILES
        self.git("init", "-q")
        self.git("config", "user.email", "ci-review-fixture@example.invalid")
        self.git("config", "user.name", "Synthetic Review")
        self.git("config", "core.filemode", "true")
        expected: dict[str, dict[str, str]] = {}
        executables: dict[str, set[str]] = {}
        for pr, count in (("51", 28), ("52", 5), ("53", 3), ("58", 5)):
            expected[pr] = {}
            executables[pr] = set()
            for idx in range(count):
                path = f"fixtures/pr{pr}/file{idx:02d}.txt"
                target = self.repo / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(f"SYNTHETIC_{pr}_{idx}\n", encoding="utf-8")
                # Real PR #52 has exactly one executable shell script.
                if pr == "52" and idx == 0:
                    target.chmod(0o755)
                    executables[pr].add(path)
        self.git("add", ".")
        self.git("commit", "-qm", "synthetic 41-file Git fixture")
        for pr, count in (("51", 28), ("52", 5), ("53", 3), ("58", 5)):
            for idx in range(count):
                path = f"fixtures/pr{pr}/file{idx:02d}.txt"
                expected[pr][path] = self.git("rev-parse", f"HEAD:{path}")
        checker.PR_HEADS = {pr: pr.zfill(40) for pr in expected}
        checker.EXPECTED_BLOBS = expected
        checker.EXPECTED_EXECUTABLE_FILES = executables
        os.chdir(self.repo)

    def tearDown(self) -> None:
        os.chdir(self.original_cwd)
        checker.PR_HEADS = self.original_heads
        checker.EXPECTED_BLOBS = self.original_blobs
        checker.EXPECTED_EXECUTABLE_FILES = self.original_executables
        self.temp.cleanup()

    def verify(self) -> str:
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            checker.verify()
        return stdout.getvalue()

    def test_exact41_blobs_and_modes_pass(self) -> None:
        out = self.verify()
        self.assertIn("ATTO_FOURWAY_41_EXACT_PR_BLOBS=PASS count=41", out)
        self.assertIn("ATTO_FOURWAY_PR_BLOBS_VERIFIED=52:5", out)
        self.assertIn("ATTO_FOURWAY_CI_EXECUTION=NOT_PROVEN_BY_SOURCE_HASHES", out)

    def test_dirty_tracked_file_is_denied(self) -> None:
        (self.repo / "fixtures/pr51/file00.txt").write_text("CHANGED\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "nonclean checkout"):
            self.verify()

    def test_untracked_file_is_denied(self) -> None:
        (self.repo / "untracked-injection.txt").write_text("PLANTED", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "nonclean checkout"):
            self.verify()

    def test_changed_committed_blob_is_denied(self) -> None:
        file = self.repo / "fixtures/pr53/file00.txt"
        file.write_text("CHANGED AND RECOMMITTED\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "unexpected source content")
        with self.assertRaisesRegex(RuntimeError, "blob drift"):
            self.verify()

    def test_unreviewed_executable_bit_is_denied_even_with_identical_blob(self) -> None:
        file = self.repo / "fixtures/pr51/file00.txt"
        file.chmod(0o755)
        self.git("add", ".")
        self.git("commit", "-qm", "unreviewed chmod executable")
        # The file SHA stays identical: mode verification is independently required.
        self.assertEqual(self.git("rev-parse", "HEAD:fixtures/pr51/file00.txt"),
                         checker.EXPECTED_BLOBS["51"]["fixtures/pr51/file00.txt"])
        with self.assertRaisesRegex(RuntimeError, "Git mode/type/path drift"):
            self.verify()

    def test_missing_reviewed_executable_bit_is_denied(self) -> None:
        file = self.repo / "fixtures/pr52/file00.txt"
        file.chmod(0o644)
        self.git("add", ".")
        self.git("commit", "-qm", "unreviewed chmod nonexecutable")
        with self.assertRaisesRegex(RuntimeError, "Git mode/type/path drift"):
            self.verify()

    def test_committed_symlink_is_denied(self) -> None:
        file = self.repo / "fixtures/pr51/file00.txt"
        file.unlink()
        file.symlink_to("file01.txt")
        self.git("add", "-A")
        self.git("commit", "-qm", "replace source blob with symlink")
        with self.assertRaisesRegex(RuntimeError, "symlink"):
            self.verify()

    def test_wrong_expected_head_file_blob_is_denied(self) -> None:
        checker.EXPECTED_BLOBS["51"]["fixtures/pr51/file00.txt"] = "0" * 40
        with self.assertRaisesRegex(RuntimeError, "blob drift"):
            self.verify()

    def test_duplicate_pr_file_path_is_denied(self) -> None:
        shared = "fixtures/pr51/file00.txt"
        checker.EXPECTED_BLOBS["52"][shared] = checker.EXPECTED_BLOBS["51"][shared]
        with self.assertRaisesRegex(RuntimeError, "duplicate/invalid"):
            self.verify()

    def test_missing_executable_contract_file_is_denied(self) -> None:
        checker.EXPECTED_EXECUTABLE_FILES["52"].add("untracked/phantom.sh")
        with self.assertRaisesRegex(RuntimeError, "reviewed executable path not present"):
            self.verify()

    def test_mutated_mode_allowlist_for_unexpected_pr_is_denied(self) -> None:
        checker.EXPECTED_EXECUTABLE_FILES["99"] = set()
        with self.assertRaisesRegex(RuntimeError, "Git mode allowlist"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
