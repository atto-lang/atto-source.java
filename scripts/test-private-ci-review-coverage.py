#!/usr/bin/env python3
"""Offline regression: private CI control files must have enforced review coverage.

No GitHub API, credentials, compiler checkout, or source transfer is needed.
This test itself MUST be both CODEOWNED and a workflow path-filter input.
"""
from pathlib import Path
import re
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
OWNER = "@ORESoftware"
SECURITY_PATHS = (
    ".github/CODEOWNERS",
    ".github/private-ci-approved-repositories.txt",
    ".github/workflows/private-ci-mirror-preflight.yml",
    "docs/security/CI_PRIVATE_SOURCE_MIRRORING.md",
    "scripts/check-private-ci-destination.sh",
    "scripts/publish-private-ci-source.py",
    "scripts/run-in-private-ci-destination.sh",
    "scripts/run-private-ci-regressions-local.sh",
    "scripts/test-run-private-ci-regressions-local.sh",
    "scripts/test-private-ci-destination.sh",
    "scripts/test-private-ci-merged-main.py",
    "scripts/test-private-ci-review-coverage.py",
    "scripts/test-publish-private-ci-source.py",
    "tools/verify-exact-source-tree.sh",
    "tools/test-verify-exact-source-tree.sh",
)


def owner_matches(pattern, path):
    if pattern.endswith("/"):
        return path.startswith(pattern.lstrip("/"))
    return pattern.lstrip("/") == path


def require_unconditional_test_step(workflow, name, command):
    """Accept only a standalone executable Action step, not commented text.

    This is intentionally a narrow textual contract over the checked-in CI
    layout, not a full YAML parser. Unknown keys, skip conditions, multiline
    run bodies and continue-on-error all fail closed.
    """
    starts = list(re.finditer(r"(?m)^      - (?:name|uses): ", workflow))
    blocks = [workflow[m.start():starts[i + 1].start() if i + 1 < len(starts)
                       else len(workflow)] for i, m in enumerate(starts)]
    expected = ["- name: " + name, "run: " + command]
    matching = [block for block in blocks if block.splitlines()[0].strip() == expected[0]]
    if len(matching) != 1:
        raise ValueError("missing or duplicated required executable step " + name)
    actual = [line.strip() for line in matching[0].splitlines() if line.strip()]
    if actual != expected:
        raise ValueError("required test step is not unconditional and executable: " + name)


def require_active_security_job(workflow):
    """Reject unsupported job-level conditions and failure masking."""
    marker = "jobs:\n  preflight-regressions:\n"
    if workflow.count(marker) != 1:
        raise ValueError("required private-CI security job is missing or duplicated")
    block = workflow.split(marker, 1)[1]
    next_job = re.search(r"(?m)^  [A-Za-z_][A-Za-z_0-9-]*:\s*$", block)
    if next_job:
        raise ValueError("private-CI workflow contains an unreviewed additional job")
    fields = re.findall(r"(?m)^    ([A-Za-z_][A-Za-z_0-9-]*):", block)
    if fields != ["runs-on", "timeout-minutes", "steps"]:
        raise ValueError("private-CI security job contains an unsupported control")
    if not re.search(r"(?m)^    runs-on: ubuntu-24\.04\s*$", block):
        raise ValueError("private-CI job runner is not pinned")
    return block


def require_exact_security_steps(job):
    """Only the reviewed checkout and three mandatory verification steps may run."""
    matches = list(re.finditer(r"(?m)^      - (?:name|uses): ", job))
    blocks = [job[m.start():matches[i + 1].start() if i + 1 < len(matches)
                  else len(job)] for i, m in enumerate(matches)]
    headings = [block.splitlines()[0].strip() for block in blocks]
    expected = [
        "- uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4",
        "- name: Validate CI security review coverage",
        "- name: Validate offline regression runner",
        "- name: Execute exact source-free publisher suite with isolated Python",
    ]
    if headings != expected:
        raise ValueError("private-CI job has an extra, missing, or unreviewed step")
    checkout = [line.strip() for line in blocks[0].splitlines() if line.strip()]
    if checkout != [expected[0], "with:", "persist-credentials: false"]:
        raise ValueError("private-CI checkout action is not precisely pinned")


def require_exact_publisher_execution(workflow):
    """Pin the reviewed publisher execution step; reject masked or inert edits."""
    name = "Execute exact source-free publisher suite with isolated Python"
    starts = list(re.finditer(r"(?m)^      - (?:name|uses): ", workflow))
    blocks = [workflow[m.start():starts[i + 1].start() if i + 1 < len(starts)
                       else len(workflow)] for i, m in enumerate(starts)]
    matching = [block for block in blocks
                if block.splitlines()[0].strip() == "- name: " + name]
    if len(matching) != 1:
        raise ValueError("private-CI full publisher execution step is missing or duplicated")
    expected = [
        "- name: " + name,
        "run: |",
        "set -euo pipefail",
        "bash -n tools/verify-exact-source-tree.sh tools/test-verify-exact-source-tree.sh",
        "bash tools/test-verify-exact-source-tree.sh",
        'echo "CI_PRIVATE_PUBLISHER_PROVENANCE|commit=$(git rev-parse HEAD)|tree=$(git rev-parse \'HEAD^{tree}\')|run=$GITHUB_RUN_ID|attempt=$GITHUB_RUN_ATTEMPT"',
        "PRIVATE_CI_TEST_REPEATS=3 bash scripts/run-private-ci-regressions-local.sh",
        'echo "CI_PRIVATE_PUBLISHER_SUITE_PASS|commit=$(git rev-parse HEAD)|tree=$(git rev-parse \'HEAD^{tree}\')"',
        "{",
        "echo '### Source-free private publisher regressions'",
        'echo "Git commit: $(git rev-parse HEAD)"',
        'echo "Git tree: $(git rev-parse \'HEAD^{tree}\')"',
        "echo 'Outcome: exact-tree Bash verifier and full offline publisher x3 passed'",
        '} >> "$GITHUB_STEP_SUMMARY"',
    ]
    actual = [line.strip() for line in matching[0].splitlines() if line.strip()]
    if actual != expected:
        raise ValueError("private-CI full publisher test step was modified or disabled")


def validate(codeowners, workflow):
    if len(SECURITY_PATHS) != len(set(SECURITY_PATHS)):
        raise ValueError("duplicated security manifest entry")
    entries = []
    for raw in codeowners.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        # This security-specific file intentionally accepts only exact rooted
        # paths or directory prefixes. Fail closed on glob/negation rules:
        # otherwise an unmodelled later rule could override a trusted owner.
        if (len(fields) != 2 or not fields[0].startswith("/")
                or any(c in fields[0] for c in "*?![]\\")
                or not fields[1].startswith("@")):
            raise ValueError("unrecognized CODEOWNERS rule")
        entries.append(tuple(fields))
    for path in SECURITY_PATHS:
        applicable = [owners for pattern, owners in entries
                      if owner_matches(pattern, path)]
        if not applicable or applicable[-1] != OWNER:
            raise ValueError("missing exact CODEOWNER review for " + path)

    for event in ("pull_request", "push"):
        match = list(re.finditer(
            r"(?m)^  " + event + r":\s*$", workflow))
        if len(match) != 1:
            raise ValueError("missing or duplicate private-CI workflow event " + event)
        following = workflow[match[0].end():]
        end = re.search(r"(?m)^(?:  [A-Za-z_][A-Za-z_0-9]*:|[A-Za-z_][A-Za-z_0-9]*:)", following)
        block = following[:end.start()] if end else following
        filtered = re.findall(r"(?m)^      - '([^'\n]+)'\s*$", block)
        if len(filtered) != len(set(filtered)):
            raise ValueError("duplicated private-CI trigger path")
        missing = set(SECURITY_PATHS) - set(filtered)
        if missing:
            raise ValueError(event + " does not trigger on " + sorted(missing)[0])
    active_job = require_active_security_job(workflow)
    require_exact_security_steps(active_job)
    require_exact_publisher_execution(active_job)
    require_unconditional_test_step(
        active_job, "Validate CI security review coverage",
        "python3 -I -B scripts/test-private-ci-review-coverage.py")
    require_unconditional_test_step(
        active_job, "Validate offline regression runner",
        "bash scripts/test-run-private-ci-regressions-local.sh")


class ProtectedReviewCoverageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.owners = (ROOT / ".github" / "CODEOWNERS").read_text()
        cls.workflow = (ROOT / ".github" / "workflows"
                        / "private-ci-mirror-preflight.yml").read_text()

    def test_current_checkout_is_reviewed_and_triggered(self):
        validate(self.owners, self.workflow)

    def test_missing_codeowner_is_rejected(self):
        damaged = self.owners.replace(
            "/tools/verify-exact-source-tree.sh @ORESoftware", "")
        with self.assertRaisesRegex(ValueError, "CODEOWNER"):
            validate(damaged, self.workflow)

    def test_later_overriding_owner_is_rejected(self):
        damaged = self.owners + "\n/tools/verify-exact-source-tree.sh @different\n"
        with self.assertRaisesRegex(ValueError, "CODEOWNER"):
            validate(damaged, self.workflow)

    def test_unmodelled_glob_rule_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unrecognized"):
            validate(self.owners + "\n/* @different\n", self.workflow)

    def test_missing_push_trigger_is_rejected(self):
        beginning, separator, push_block = self.workflow.partition("  push:\n")
        self.assertTrue(separator)
        damaged = beginning + separator + push_block.replace(
            "      - 'tools/verify-exact-source-tree.sh'\n", "", 1)
        with self.assertRaisesRegex(ValueError, "does not trigger"):
            validate(self.owners, damaged)

    def test_duplicate_manifest_entry_is_rejected(self):
        with patch(__name__ + ".SECURITY_PATHS",
                   SECURITY_PATHS + ("tools/verify-exact-source-tree.sh",)):
            with self.assertRaisesRegex(ValueError, "duplicated security manifest"):
                validate(self.owners, self.workflow)

    def test_duplicate_trigger_is_rejected(self):
        beginning, separator, push_block = self.workflow.partition("  push:\n")
        self.assertTrue(separator)
        extra = "      - 'scripts/test-private-ci-review-coverage.py'\n"
        damaged = beginning + separator + extra + push_block
        with self.assertRaisesRegex(ValueError, "duplicated"):
            validate(self.owners, damaged)

    def test_missing_runner_self_test_is_rejected(self):
        damaged = self.workflow.replace(
            "bash scripts/test-run-private-ci-regressions-local.sh", "")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)

    def test_missing_test_invocation_is_rejected(self):
        damaged = self.workflow.replace(
            "python3 -I -B scripts/test-private-ci-review-coverage.py", "")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)


    def test_commented_out_security_invocation_is_rejected(self):
        damaged = self.workflow.replace(
            "run: python3 -I -B scripts/test-private-ci-review-coverage.py",
            "# run: python3 -I -B scripts/test-private-ci-review-coverage.py")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)

    def test_commented_out_runner_invocation_is_rejected(self):
        damaged = self.workflow.replace(
            "run: bash scripts/test-run-private-ci-regressions-local.sh",
            "# run: bash scripts/test-run-private-ci-regressions-local.sh")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)

    def test_disabled_security_step_is_rejected(self):
        damaged = self.workflow.replace(
            "        run: python3 -I -B scripts/test-private-ci-review-coverage.py",
            "        if: false\n"
            "        run: python3 -I -B scripts/test-private-ci-review-coverage.py")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)

    def test_continue_on_error_security_step_is_rejected(self):
        damaged = self.workflow.replace(
            "        run: python3 -I -B scripts/test-private-ci-review-coverage.py",
            "        continue-on-error: true\n"
            "        run: python3 -I -B scripts/test-private-ci-review-coverage.py")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)

    def test_nonexecuting_shell_block_is_rejected(self):
        damaged = self.workflow.replace(
            "        run: python3 -I -B scripts/test-private-ci-review-coverage.py",
            "        run: |\n"
            "          exit 0\n"
            "          python3 -I -B scripts/test-private-ci-review-coverage.py")
        with self.assertRaisesRegex(ValueError, "unconditional and executable"):
            validate(self.owners, damaged)

    def test_duplicated_security_step_is_rejected(self):
        block = ("      - name: Validate CI security review coverage\n"
                 "        run: python3 -I -B scripts/test-private-ci-review-coverage.py\n")
        damaged = self.workflow.replace(block, block + block)
        with self.assertRaisesRegex(ValueError, "duplicated required executable step|unreviewed step"):
            validate(self.owners, damaged)

    def test_job_skip_condition_is_denied(self):
        damaged = self.workflow.replace(
            "  preflight-regressions:\n",
            "  preflight-regressions:\n    " + "if: false" + "\n")
        with self.assertRaisesRegex(ValueError, "unsupported control"):
            validate(self.owners, damaged)

    def test_job_failure_masking_is_denied(self):
        damaged = self.workflow.replace(
            "  preflight-regressions:\n",
            "  preflight-regressions:\n    " + "continue-on-error: true" + "\n")
        with self.assertRaisesRegex(ValueError, "unsupported control"):
            validate(self.owners, damaged)

    def test_job_extra_dependency_is_denied(self):
        damaged = self.workflow.replace(
            "  preflight-regressions:\n",
            "  preflight-regressions:\n    needs: optional-security-job\n")
        with self.assertRaisesRegex(ValueError, "unsupported control"):
            validate(self.owners, damaged)


    def test_full_publisher_step_skip_is_denied(self):
        damaged = self.workflow.replace(
            "      - name: Execute exact source-free publisher suite with isolated Python\n",
            "      - name: Execute exact source-free publisher suite with isolated Python\n"
            "        " + "if: false" + "\n")
        with self.assertRaisesRegex(ValueError, "full publisher test step"):
            validate(self.owners, damaged)

    def test_full_publisher_result_masking_is_denied(self):
        damaged = self.workflow.replace(
            "PRIVATE_CI_TEST_REPEATS=3 bash scripts/run-private-ci-regressions-local.sh",
            "PRIVATE_CI_TEST_REPEATS=3 bash scripts/run-private-ci-regressions-local.sh || true")
        with self.assertRaisesRegex(ValueError, "full publisher test step"):
            validate(self.owners, damaged)

    def test_full_publisher_early_exit_is_denied(self):
        damaged = self.workflow.replace(
            "          set -euo pipefail\n",
            "          set -euo pipefail\n          exit 0\n")
        with self.assertRaisesRegex(ValueError, "full publisher test step"):
            validate(self.owners, damaged)


    def test_unreviewed_second_job_is_denied(self):
        damaged = (self.workflow
                   + "  decoy-job:\n"
                   + "    runs-on: ubuntu-24.04\n"
                   + "    steps:\n"
                   + "      - name: Unreviewed\n"
                   + "        run: echo bypass\n")
        with self.assertRaisesRegex(ValueError, "unreviewed additional job"):
            validate(self.owners, damaged)

    def test_spoofed_full_suite_in_second_job_is_denied(self):
        marker = "      - name: Execute exact source-free publisher suite with isolated Python\n"
        start = self.workflow.index(marker)
        step = self.workflow[start:]
        damaged = (self.workflow[:start]
                   + "  decoy-job:\n"
                   + "    runs-on: ubuntu-24.04\n"
                   + "    steps:\n" + step)
        with self.assertRaisesRegex(ValueError, "unreviewed additional job"):
            validate(self.owners, damaged)

    def test_security_review_step_placed_in_second_job_is_denied(self):
        start = self.workflow.index("      - name: Validate CI security review coverage\n")
        end = self.workflow.index("      - name: Validate offline regression runner\n")
        step = self.workflow[start:end]
        damaged = (self.workflow[:start] + self.workflow[end:]
                   + "  decoy-job:\n"
                   + "    runs-on: ubuntu-24.04\n"
                   + "    steps:\n" + step)
        with self.assertRaisesRegex(ValueError, "unreviewed additional job"):
            validate(self.owners, damaged)

    def test_unreviewed_additional_action_step_is_denied(self):
        damaged = self.workflow + "      - uses: actions/cache@unreviewed-step\n"
        with self.assertRaisesRegex(ValueError, "unreviewed step"):
            validate(self.owners, damaged)

    def test_unreviewed_step_before_security_check_is_denied(self):
        marker = "      - name: Validate CI security review coverage\n"
        damaged = self.workflow.replace(marker,
              "      - uses: actions/cache@unreviewed-step\n" + marker)
        with self.assertRaisesRegex(ValueError, "unreviewed step"):
            validate(self.owners, damaged)

    def test_checkout_pin_mutation_is_denied(self):
        damaged = self.workflow.replace(
            "actions/checkout@11d5960a326750d5838078e36cf38b85af677262",
            "actions/checkout@v4")
        with self.assertRaisesRegex(ValueError, "unreviewed step"):
            validate(self.owners, damaged)

    def test_checkout_credential_persistence_is_denied(self):
        damaged = self.workflow.replace("persist-credentials: false",
                                       "persist-credentials: true")
        with self.assertRaisesRegex(ValueError, "not precisely pinned"):
            validate(self.owners, damaged)



if __name__ == "__main__":
    unittest.main(verbosity=2)
