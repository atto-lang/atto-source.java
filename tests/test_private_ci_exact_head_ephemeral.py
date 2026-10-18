#!/usr/bin/env python3
"""Static source-local ephemeral CI admission regression checks."""
from pathlib import Path
import unittest

REPO=Path(__file__).resolve().parents[1]
PATH=REPO/".github/workflows/private-ci-exact-head-ephemeral.yml"
BRANCH="security/review-pinned-private-ci-dispatcher-v2-20261017"

class SourceLocalEphemeralWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text=PATH.read_text(encoding="utf-8")
        cls.deny=cls.text.split("  deny-unapproved-runner:\n",1)[1].split(
            "\n  approved-source-validation:\n",1)[0]
        cls.job=cls.text.split("  approved-source-validation:\n",1)[1]

    def test_only_canonical_source_push_ref_is_allowed(self):
        self.assertIn("on:\n  push:\n    branches:\n      - "+BRANCH,self.text)
        for disallowed in ("pull_request:", "pull_request_target:", "workflow_dispatch:"):
            self.assertNotIn(disallowed,self.text)

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
        self.assertLess(self.job.index("git status --porcelain --untracked-files=all"),
                        self.job.index("python3 -I -m py_compile"))
        self.assertLess(self.job.index("git status --porcelain --untracked-files=all"),
                        self.job.index("Execute mirror preflight adversarial suite"))
        self.assertIn('test -z "$(git status --porcelain --untracked-files=all)"',self.job)

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

    def test_no_source_mirroring_or_external_artifact_publish(self):
        for bad in ("git push", "repository_dispatch", "pull_request_target:", "cache: maven", "secrets."):
            self.assertNotIn(bad,self.job)

if __name__=="__main__":
    unittest.main(verbosity=2)
