"""Source-free tests for the source-local exact-tree NIO qualification gate.

These tests create disposable local Git repositories, replace java/javac/mvn with
controlled fixtures, and NEVER check out or export private Oreslang sources.
They exercise admission and receipt mechanics, not actual JUnit/Maven builds.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


GATE = Path(__file__).resolve().parents[1] / "scripts" / "test-nio-exact-tree-source-local.sh"


def run(*args: str, cwd: Path, env: dict[str, str] | None = None):
    return subprocess.run(args, cwd=cwd, env=env, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=30, check=False)


class SourceLocalNioGateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ores-nio-gate-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.root.mkdir()
        self.tools = Path(self.temporary.name) / "tools"
        self.tools.mkdir()
        self.outside = Path(self.temporary.name) / "external"
        self.outside.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "Source-free Gate Test"],
                       cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "gate@example.invalid"],
                       cwd=self.root, check=True)
        (self.root / "scripts").mkdir()
        shutil.copyfile(GATE, self.root / "scripts" / GATE.name)
        (self.root / ".gitignore").write_text("target/\n")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", "test immutable input"],
                       cwd=self.root, check=True)
        self.sha = run("git", "rev-parse", "HEAD", cwd=self.root).stdout.strip()
        self.tree = run("git", "rev-parse", "HEAD^{tree}", cwd=self.root).stdout.strip()
        assert len(self.sha) == len(self.tree) == 40
        for tool, payload in {
            "java": '#!/bin/sh\necho \'openjdk version "25.0.1"\' >&2\n',
            "javac": '#!/bin/sh\necho "javac 25.0.1"\n',
            "mvn": """#!/bin/sh
if [ "$1" = "-version" ]; then
  printf 'Apache Maven (fixture)\\nJava version: 25.0.1\\n'
  exit 0
fi
case " $* " in
  *" clean "*)
    if [ "${FAKE_SKIP_CLEAN:-0}" = 1 ]; then exit 0; fi
    rm -rf target; exit 0 ;;
  *" verify "*)
    if [ "${FAKE_FAIL_VERIFY:-0}" = 1 ]; then exit 9; fi ;;
esac
printf 'SIMULATED MAVEN %s\\n' "$*"
exit 0
""",
        }.items():
            binary = self.tools / tool
            binary.write_text(payload)
            binary.chmod(0o755)
        self.env = os.environ.copy()
        self.env["PATH"] = str(self.tools) + os.pathsep + self.env["PATH"]

    def gate(self, sha: str | None = None, tree: str | None = None,
             *, env: dict[str, str] | None = None):
        return run("bash", str(self.root / "scripts" / GATE.name),
                   sha or self.sha, tree or self.tree, cwd=self.root,
                   env=env or self.env)

    def receipt(self) -> str:
        return (self.root / "target" / "nio-exact-tree-gate" /
                "receipt.txt").read_text()

    def test_clean_pinned_checkout_writes_unsigned_success_receipt(self):
        completed = self.gate()
        self.assertEqual(0, completed.returncode, completed.stderr)
        record = self.receipt()
        self.assertIn("result=PASS", record)
        self.assertIn("stage=complete", record)
        self.assertIn("observed_commit=" + self.sha, record)
        self.assertIn("observed_tree=" + self.tree, record)
        for name in ("targeted-junit.log", "full-maven-verify.log"):
            self.assertTrue((self.root / "target" /
                             "nio-exact-tree-gate" / name).exists())

    def test_wrong_head_or_tree_fail_before_toolchain(self):
        for wrong_sha, wrong_tree in [
            ("0" * 40, self.tree), (self.sha, "0" * 40)]:
            with self.subTest(wrong_sha=wrong_sha, wrong_tree=wrong_tree):
                result = self.gate(wrong_sha, wrong_tree)
                self.assertEqual(66, result.returncode, result.stderr)
                self.assertIn("result=FAIL", self.receipt())
                self.assertIn("stage=source-identity", self.receipt())

    def test_dirty_checkout_is_rejected(self):
        (self.root / "untracked.ores").write_text("untracked\n")
        result = self.gate()
        self.assertEqual(67, result.returncode, result.stderr)
        self.assertIn("result=FAIL", self.receipt())

    def test_target_directory_symlink_never_writes_outside(self):
        (self.root / "target").symlink_to(self.outside, target_is_directory=True)
        result = self.gate()
        self.assertEqual(68, result.returncode, result.stderr)
        self.assertEqual([], list(self.outside.iterdir()))

    def test_receipt_file_symlink_never_overwrites_outside(self):
        evidence = self.root / "target" / "nio-exact-tree-gate"
        evidence.mkdir(parents=True)
        marker = self.outside / "original.txt"
        marker.write_text("do-not-touch\n")
        (evidence / "receipt.txt").symlink_to(marker)
        result = self.gate()
        self.assertEqual(68, result.returncode, result.stderr)
        self.assertEqual("do-not-touch\n", marker.read_text())

    def test_successful_clean_that_leaves_stale_target_fails_closed(self):
        target = self.root / "target"
        target.mkdir()
        (target / "stale.class").write_text("old compiler output")
        environment = self.env.copy()
        environment["FAKE_SKIP_CLEAN"] = "1"
        completed = self.gate(env=environment)
        self.assertEqual(71, completed.returncode, completed.stderr)
        self.assertIn("result=FAIL", self.receipt())
        self.assertIn("stage=clean-stale-build", self.receipt())
        self.assertFalse((target / "nio-exact-tree-gate" / "targeted-junit.log").exists())

    def test_nonzero_maven_exit_survives_tee_and_receipt(self):
        environment = self.env.copy()
        environment["FAKE_FAIL_VERIFY"] = "1"
        completed = self.gate(env=environment)
        self.assertEqual(9, completed.returncode, completed.stderr)
        self.assertIn("result=FAIL", self.receipt())
        self.assertIn("stage=full-maven-verify", self.receipt())


if __name__ == "__main__":
    unittest.main()
