#!/usr/bin/env python3
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "nio_verify_raw_worktree.py"


def git(cwd: Path, *args: str) -> str:
    return subprocess.check_output(
        ("git", *args), cwd=cwd, text=True, stderr=subprocess.STDOUT
    ).strip()


class RawWorktreeAliasTest(unittest.TestCase):
    def make_repo(self, root: Path) -> tuple[str, str]:
        git(root, "init", "-q")
        git(root, "config", "user.email", "nio-gate@example.invalid")
        git(root, "config", "user.name", "NIO gate test")
        source = root / "src"
        source.mkdir()
        (source / "value.txt").write_text("exact bytes\n", encoding="utf-8")
        git(root, "add", "src/value.txt")
        git(root, "commit", "-q", "-m", "fixture")
        return git(root, "rev-parse", "HEAD"), git(root, "rev-parse", "HEAD^{tree}")

    def run_verifier(self, repo: Path, commit: str, tree: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            (sys.executable, "-I", "-B", str(VERIFIER), commit, tree),
            cwd=repo,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_ordinary_tracked_file_passes(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            commit, tree = self.make_repo(repo)
            result = self.run_verifier(repo, commit, tree)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("raw tracked files match staged Git blobs", result.stdout)

    @unittest.skipUnless(hasattr(os, "symlink"), "symlink unavailable")
    def test_symlinked_parent_directory_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            repo = root / "repo"
            outside = root / "outside"
            repo.mkdir()
            outside.mkdir()
            commit, tree = self.make_repo(repo)
            (outside / "value.txt").write_text("exact bytes\n", encoding="utf-8")
            (repo / "src" / "value.txt").unlink()
            (repo / "src").rmdir()
            (repo / "src").symlink_to(outside, target_is_directory=True)

            result = self.run_verifier(repo, commit, tree)
            self.assertEqual(result.returncode, 77)
            self.assertIn("symlinked/non-directory ancestor", result.stderr)

    @unittest.skipUnless(hasattr(os, "link"), "hard links unavailable")
    def test_tracked_hardlink_alias_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            repo = root / "repo"
            outside = root / "outside"
            repo.mkdir()
            outside.mkdir()
            commit, tree = self.make_repo(repo)
            os.link(repo / "src" / "value.txt", outside / "alias.txt")

            result = self.run_verifier(repo, commit, tree)
            self.assertEqual(result.returncode, 77)
            self.assertIn("multiple hard links", result.stderr)


if __name__ == "__main__":
    unittest.main()
