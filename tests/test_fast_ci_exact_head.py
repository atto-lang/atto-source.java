"""Offline regressions for exact-head checkout/provenance in Java CI workflows."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ("fast-ci.yml", "ci.yml")
HEAD_EXPRESSION = "${{ github.event.pull_request.head.sha || github.sha }}"


def read_verification_scripts(name):
    lines = (ROOT / ".github" / "workflows" / name).read_text().splitlines()
    scripts = []
    for i, line in enumerate(lines):
        if line != "      - name: Verify exact source revision":
            continue
        next_step = next((j for j in range(i + 1, len(lines))
                          if lines[j].startswith("      - ")), len(lines))
        block = lines[i + 1:next_step]
        marker = next((j for j, part in enumerate(block) if part == "        run: |"), None)
        if marker is None:
            raise AssertionError(f"{name}: missing provenance command block")
        script_lines = [part[10:] for part in block[marker + 1:]
                        if part.startswith("          ")]
        scripts.append("\n".join(script_lines) + "\n")
    return scripts


class ExactHeadWorkflowContract(unittest.TestCase):
    def test_every_workflow_checks_out_exact_head_before_testing(self):
        for name in WORKFLOWS:
            source = (ROOT / ".github" / "workflows" / name).read_text()
            with self.subTest(workflow=name):
                self.assertIn("ref: " + HEAD_EXPRESSION, source)
                self.assertIn("EXPECTED_HEAD: " + HEAD_EXPRESSION, source)
                self.assertIn("persist-credentials: false", source)
                scripts = read_verification_scripts(name)
                self.assertTrue(scripts, "missing explicit source provenance step")
                self.assertLess(source.index("- name: Verify exact source revision"),
                                source.index("- name: Set up GraalVM"))
                for script in scripts:
                    self.assertIn('test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"', script)
                    self.assertIn("git diff --quiet --exit-code", script)
                    self.assertIn("git diff --cached --quiet --exit-code", script)
                    self.assertIn('git ls-files --others --exclude-standard', script)
                    self.assertIn("HEAD^{tree}", script)

    def test_provenance_fails_closed_on_all_dirty_states(self):
        script = read_verification_scripts("fast-ci.yml")[0]
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)

            def git(*args):
                return subprocess.run(("git", *args), cwd=directory,
                                      capture_output=True, text=True, check=True).stdout.strip()

            git("init", "-q")
            git("config", "user.name", "CI Contract")
            git("config", "user.email", "contract@example.invalid")
            fixture = directory / "source.txt"
            fixture.write_text("original\n")
            git("add", "source.txt")
            git("commit", "-qm", "initial")
            head = git("rev-parse", "HEAD")

            def verify(expected=head):
                return subprocess.run(("bash", "-c", script), cwd=directory,
                                      env={**os.environ, "EXPECTED_HEAD": expected},
                                      capture_output=True, text=True).returncode

            self.assertEqual(verify(), 0, "clean exact-head repo must pass")
            self.assertNotEqual(verify("f" * 40), 0, "wrong SHA must fail")
            self.assertNotEqual(verify("not-an-object"), 0, "malformed SHA must fail")

            fixture.write_text("unstaged\n")
            self.assertNotEqual(verify(), 0, "unstaged change must fail")
            git("add", "source.txt")
            self.assertNotEqual(verify(), 0, "staged-only change must fail")
            git("reset", "--hard", "HEAD")
            (directory / "stray.txt").write_text("untracked\n")
            self.assertNotEqual(verify(), 0, "untracked file must fail")


if __name__ == "__main__":
    unittest.main()
