#!/usr/bin/env python3
"""Offline regression: private CI control files must have enforced review coverage.

No GitHub API, credentials, compiler checkout, or source transfer is needed.
This test itself MUST be both CODEOWNED and a workflow path-filter input.
"""
from pathlib import Path
import re
import unittest

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
    "scripts/run-private-ci-regressions-local.sh",
    "scripts/test-run-private-ci-regressions-local.sh",
    "tools/verify-exact-source-tree.sh",
    "tools/test-verify-exact-source-tree.sh",
)


def owner_matches(pattern, path):
    if pattern.endswith("/"):
        return path.startswith(pattern.lstrip("/"))
    return pattern.lstrip("/") == path


def validate(codeowners, workflow):
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
    if "python3 -B scripts/test-private-ci-review-coverage.py" not in workflow:
        raise ValueError("CODEOWNER/workflow coverage regression test is not executed")
    if "bash scripts/test-run-private-ci-regressions-local.sh" not in workflow:
        raise ValueError("offline private-CI regression runner self-test is not executed")


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

    def test_missing_test_invocation_is_rejected(self):
        damaged = self.workflow.replace(
            "python3 -B scripts/test-private-ci-review-coverage.py", "")
        with self.assertRaisesRegex(ValueError, "not executed"):
            validate(self.owners, damaged)


if __name__ == "__main__":
    unittest.main(verbosity=2)
