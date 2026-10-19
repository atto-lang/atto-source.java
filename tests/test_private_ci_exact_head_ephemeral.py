#!/usr/bin/env python3
"""Static source-local ephemeral CI admission regression checks."""
from pathlib import Path
import unittest
import subprocess
import tempfile

REPO=Path(__file__).resolve().parents[1]
PATH=REPO/".github/workflows/private-ci-exact-head-ephemeral.yml"
BRANCH="security/review-pinned-private-ci-dispatcher-v2-20261017"
HARDENING_BRANCH="hardening/canonical-review-pinned-ledger-20261017"

class SourceLocalEphemeralWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text=PATH.read_text(encoding="utf-8")
        cls.deny=cls.text.split("  deny-unapproved-runner:\n",1)[1].split(
            "\n  approved-source-validation:\n",1)[0]
        cls.job=cls.text.split("  approved-source-validation:\n",1)[1]

    def test_only_two_explicit_reviewed_source_push_refs_are_allowed(self):
        expected=("on:\n  push:\n    branches:\n"
                  "      - "+BRANCH+"\n"
                  "      - "+HARDENING_BRANCH+"\n")
        self.assertIn(expected,self.text)
        push_branches=self.text.split("on:\n  push:\n    branches:\n",1)[1].split(
            "\npermissions:",1)[0]
        actual=[line.strip()[2:] for line in push_branches.splitlines()
                if line.strip().startswith("- ")]
        self.assertEqual(actual,[BRANCH,HARDENING_BRANCH],
                         "No additional branch, glob or third-party ref may schedule source tests")
        for disallowed in ("pull_request:", "pull_request_target:", "workflow_dispatch:"):
            self.assertNotIn(disallowed,self.text)
        self.assertNotIn("      - '*'\n",self.text)
        self.assertNotIn("      - '**'\n",self.text)

    def test_both_source_heads_need_identical_approval_and_protection(self):
        canonical="refs/heads/"+BRANCH
        hardened="refs/heads/"+HARDENING_BRANCH
        allow="(github.ref == '"+canonical+"' || github.ref == '"+hardened+"')"
        self.assertIn(allow,self.job)
        self.assertIn("!("+allow+")",self.deny)
        self.assertIn("github.ref_protected == true",self.job)
        self.assertIn("github.ref_protected != true",self.deny)
        self.assertIn("vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha",self.job)
        self.assertIn('test "$APPROVED_SHA" = "$GITHUB_SHA"',self.job)
        self.assertIn(canonical+"|"+hardened+") ;;",self.job)
        self.assertIn("vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_REF == github.ref",self.job)
        self.assertIn("vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_REF != github.ref",self.deny)
        self.assertIn('test "$APPROVED_REF" = "$GITHUB_REF"',self.job)
        self.assertIn("*) echo \"Exact reviewed source branch required\"",self.job)

    def test_hardening_branch_does_not_relax_runner_or_repository_identity(self):
        for condition in ("github.repository_id == '1400792274'",
                          "github.event.repository.fork == false",
                          "github.event.repository.private == true",
                          "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'",
                          "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != ''"):
            self.assertIn(condition,self.job)
        self.assertEqual(self.job.count("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]"),1)
        self.assertNotIn("workflow_dispatch:",self.text)

    def test_explicit_private_repo_and_protected_ref_admission(self):
        for constraint in (
            "github.repository == 'ores-truffle-oreslang/oreslang-source.java'",
            "github.event.repository.private == true",
            "github.ref_protected == true",
            "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'",
            "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha",
            "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != ''"):
            self.assertIn(constraint,self.job)

    def test_hosted_denial_without_checkout(self):
        self.assertIn("runs-on: ubuntu-24.04",self.deny)
        self.assertNotIn("actions/checkout@",self.deny)
        self.assertIn("exit 1",self.deny)

    def test_private_runner_has_strict_label_and_read_only_token(self):
        self.assertIn("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]",self.job)
        self.assertIn("permissions:\n  contents: read",self.text)
        self.assertIn("persist-credentials: false",self.job)

    def test_identity_check_happens_before_checkout(self):
        self.assertLess(self.job.index("Verify protected source authorization before checkout"),
                        self.job.index("uses: actions/checkout@"))
        self.assertIn('test "$APPROVED_SHA" = "$GITHUB_SHA"',self.job)
        self.assertIn('git rev-parse \'HEAD^{tree}\'',self.job)
        self.assertLess(self.job.index("Check exact source commit and tree before execution"),
                        self.job.index("Syntax-check every private CI authorization entrypoint"))

    def test_all_four_triplicate_security_suites_are_retained(self):
        for minimum in ("if count < 18:","if count < 94:","if count < 50:","if count < 174:"):
            self.assertIn(minimum,self.job)
        for round_name in ("PRIVATE_MIRROR_PREFLIGHT_ROUND",
                           "PRIVATE_REVIEW_PINNED_ROUND",
                           "PRIVATE_REVIEW_PINNED_DISPATCHER_ROUND",
                           "PRIVATE_PUBLISHER_REGRESSION_ROUND"):
            self.assertIn(round_name,self.job)
        self.assertGreaterEqual(self.job.count("for round in 1 2 3; do"),4)

    def test_full_maven_only_after_security_tests_and_pinned_jdk(self):
        self.assertLess(self.job.index("Execute publisher adversarial suite three complete rounds"),
                        self.job.index("Run full private Java compiler and actor-runtime Maven verification"))
        self.assertIn("graalvm/setup-graalvm@27b360dbb857524eaf0b4c4cef364d38e5b4f173",self.job)
        self.assertIn("mvn -B -ntp -Dores.runtime.carriers=native clean verify",self.job)

    def test_receipt_follows_maven_and_denies_native_image_certification(self):
        self.assertLess(self.job.index("Run full private Java compiler and actor-runtime Maven verification"),
                        self.job.index("Create private same-repository security and compiler receipt"))
        self.assertLess(self.job.index("Create private same-repository security and compiler receipt"),
                        self.job.index("Upload only minimal private receipt"))
        self.assertIn('"native_image_certified":False',self.job)
        self.assertIn('"source_export_authorized":False',self.job)
        self.assertIn('"private_source_transferred":False',self.job)
        self.assertIn('"release_ready":False',self.job)

    def test_workspace_injection_checked_before_imports(self):
        self.assertLess(self.job.index("git -c core.fileMode=true status --porcelain=v1 --untracked-files=all"),
                        self.job.index("python3 -I -m py_compile"))
        self.assertLess(self.job.index("git -c core.fileMode=true status --porcelain=v1 --untracked-files=all"),
                        self.job.index("Execute mirror preflight adversarial suite"))
        self.assertIn('test -z "$(git -c core.fileMode=true status --porcelain=v1 --untracked-files=all)"',self.job)

    def test_executable_mode_and_index_flag_bypasses_are_denied(self):
        # Force Git's mode behavior rather than inheriting core.fileMode=false.
        self.assertGreaterEqual(
            self.job.count("git -c core.fileMode=true diff --no-ext-diff --quiet --exit-code"), 2)
        self.assertIn(
            "git -c core.fileMode=true diff --no-ext-diff --cached --quiet --exit-code",
            self.job)
        self.assertIn('index_flags="$(git ls-files -v)"', self.job)
        self.assertIn("grep -v '^H ' >/dev/null", self.job)
        self.assertIn("Hidden or unexpected Git index flags", self.job)
        self.assertIn("git -c core.fileMode=true status --porcelain=v1 --untracked-files=all", self.job)
        self.assertNotIn("git diff --name-only", self.job)
        # grep -q can SIGPIPE printf under pipefail, accidentally defeating the gate.
        self.assertNotIn("grep -q -v", self.job)

    def test_approved_ref_is_required_before_checkout(self):
        self.assertIn(
            "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_REF == github.ref", self.job)
        self.assertIn(
            "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_REF != github.ref", self.deny)
        self.assertIn('APPROVED_REF: ${{ vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_REF }}', self.job)
        self.assertLess(self.job.index('test "$APPROVED_REF" = "$GITHUB_REF"'),
                        self.job.index("uses: actions/checkout@"))

    def test_synthetic_filemode_false_bypass_is_blocked(self):
        # Synthetic Git repo: never use or move Oreslang private source.
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            def git(*args, check=True):
                return subprocess.run(
                    ["git", *args], cwd=root, capture_output=True, text=True,
                    check=check)
            git("init", "-q")
            git("config", "user.email", "synthetic@example.invalid")
            git("config", "user.name", "Synthetic Gate")
            script = root / "synthetic.sh"
            script.write_text("#!/bin/sh\\nexit 0\\n", encoding="utf-8")
            script.chmod(0o644)
            git("add", "synthetic.sh")
            git("commit", "-qm", "synthetic")
            git("config", "core.fileMode", "false")
            script.chmod(0o755)
            self.assertEqual(git("diff", "--quiet", check=False).returncode, 0)
            self.assertEqual(
                git("-c", "core.fileMode=true", "diff", "--no-ext-diff",
                    "--quiet", "--exit-code", check=False).returncode, 1)

    def test_synthetic_git_hidden_index_flags_are_blocked(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            def git(*args):
                return subprocess.run(
                    ["git", *args], cwd=root, capture_output=True, text=True,
                    check=True).stdout
            git("init", "-q")
            git("config", "user.email", "synthetic@example.invalid")
            git("config", "user.name", "Synthetic Gate")
            (root / "synthetic.txt").write_text("synthetic\\n", encoding="utf-8")
            git("add", "synthetic.txt")
            git("commit", "-qm", "synthetic")
            self.assertTrue(git("ls-files", "-v").startswith("H "))
            git("update-index", "--skip-worktree", "synthetic.txt")
            self.assertFalse(git("ls-files", "-v").startswith("H "))
            git("update-index", "--no-skip-worktree", "synthetic.txt")
            git("update-index", "--assume-unchanged", "synthetic.txt")
            self.assertFalse(git("ls-files", "-v").startswith("H "))

    def test_receipt_is_exclusive_nofollow_private(self):
        self.assertIn("os.O_CREAT|os.O_EXCL|os.O_CLOEXEC|os.O_NOFOLLOW,0o600",self.job)
        self.assertNotIn('target.open("x",encoding="utf-8")',self.job)
        self.assertLess(self.job.index("Create private same-repository security and compiler receipt"),
                        self.job.index("Upload only minimal private receipt"))

    def test_runner_identity_stays_bound_to_exact_ref(self):
        self.assertIn('test "$GITHUB_EVENT_NAME" = "push"',self.job)
        self.assertIn('test "$GITHUB_REF_PROTECTED" = "true"',self.job)
        self.assertIn('test "$APPROVED_SHA" = "$GITHUB_SHA"',self.job)
        self.assertIn('test "$(git rev-parse HEAD)" = "$APPROVED_SHA"',self.job)
        self.assertIn('test "$(git rev-parse \'HEAD^{tree}\')" = "$APPROVED_TREE"',self.job)

    def test_immutable_repository_identity_rejects_renames_and_forks(self):
        self.assertIn("github.repository_id == '1400792274'", self.job)
        self.assertIn("github.repository_id != '1400792274'", self.deny)
        self.assertIn("github.event.repository.fork == false", self.job)
        self.assertIn("github.event.repository.fork == true", self.deny)
        self.assertIn("github.event.repository.private == true", self.job)
        self.assertIn("github.event.repository.private != true", self.deny)

    def test_denial_and_approved_jobs_have_opposing_identity_conditions(self):
        for approved, denied in (
            ("github.repository_id == '1400792274'","github.repository_id != '1400792274'"),
            ("github.event.repository.fork == false","github.event.repository.fork == true"),
            ("github.ref_protected == true","github.ref_protected != true"),
            ("vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'","vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER != 'true'")):
            self.assertIn(approved,self.job)
            self.assertIn(denied,self.deny)

    def test_no_source_mirroring_or_external_artifact_publish(self):
        for bad in ("git push", "repository_dispatch", "pull_request_target:", "cache: maven", "secrets."):
            self.assertNotIn(bad,self.job)

if __name__=="__main__":
    unittest.main(verbosity=2)
