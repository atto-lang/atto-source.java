#!/usr/bin/env python3
"""Regression tests for the actual private publisher's merged-main verifier.

Mocks GitHub responses; never publishes blobs, branches, or sensitive source.
"""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "private_publisher", Path(__file__).with_name("publish-private-ci-source.py"))
pub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pub)

A, B, C = ("a" * 40, "b" * 40, "c" * 40)


class MergedMainProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.main_head = A
        self.merge_sha = A
        self.tree_sha = C
        self.pr_merged = True
        self.pr_state = "closed"
        self.pr_draft = False
        self.review_state = "APPROVED"
        self.review_commit = B
        self.calls = []

    def api(self, route, method="GET", payload=None):
        self.calls.append((method, route))
        root = "repos/" + pub.TRUSTED_SOURCE_REPOSITORY
        if route == root + "/pulls/1286":
            return {"number": 1286, "state": self.pr_state,
                    "merged": self.pr_merged, "draft": self.pr_draft,
                    "merged_at": "2026-10-18T18:58:12Z",
                    "merge_commit_sha": self.merge_sha,
                    "user": {"login": "independent-contributor"},
                    "head": {"sha": B, "repo": {"full_name": pub.TRUSTED_SOURCE_REPOSITORY}},
                    "base": {"ref": "main", "repo": {"full_name": pub.TRUSTED_SOURCE_REPOSITORY}}}
        if route == root + "/pulls/1286/reviews?per_page=100":
            return {"reviews": [{"id": 1, "state": self.review_state,
                    "commit_id": self.review_commit,
                    "submitted_at": "2026-10-18T18:00:00Z",
                    "user": {"login": pub.TRUSTED_SOURCE_REVIEWER}}]}
        if route == root + "/branches/main":
            return {"name": "main", "protected": True,
                    "commit": {"sha": self.main_head}}
        if route == root + "/git/commits/" + A:
            return {"sha": A, "tree": {"sha": self.tree_sha}}
        raise AssertionError("unexpected GitHub API route: " + route)

    def verify(self):
        with patch.object(pub, "gh_api", side_effect=self.api):
            return pub.require_merged_main_attestation(1286, A, C)

    def test_independently_reviewed_exact_main_positive(self):
        self.assertIsNone(self.verify())
        self.assertTrue(any(path.endswith("/branches/main") for _, path in self.calls))

    def test_wrong_main_head_denied(self):
        self.main_head = B
        with self.assertRaisesRegex(pub.Denied, "protected main HEAD"):
            self.verify()

    def test_wrong_merge_sha_denied(self):
        self.merge_sha = B
        with self.assertRaisesRegex(pub.Denied, "merged source PR provenance"):
            self.verify()

    def test_pr_not_merged_denied(self):
        self.pr_merged = False
        with self.assertRaises(pub.Denied):
            self.verify()

    def test_still_open_pr_denied(self):
        self.pr_state = "open"
        with self.assertRaises(pub.Denied):
            self.verify()

    def test_draft_denied(self):
        self.pr_draft = True
        with self.assertRaises(pub.Denied):
            self.verify()

    def test_missing_security_approval_denied(self):
        self.review_state = "DISMISSED"
        with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
            self.verify()

    def test_approval_for_wrong_head_denied(self):
        self.review_commit = A
        with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
            self.verify()

    def test_wrong_commit_tree_denied(self):
        self.tree_sha = B
        with self.assertRaisesRegex(pub.Denied, "commit/tree"):
            self.verify()

    def test_boolean_pr_number_denied(self):
        with patch.object(pub, "gh_api", side_effect=self.api):
            with self.assertRaisesRegex(pub.Denied, "merged source PR number"):
                pub.require_merged_main_attestation(True, A, C)
        self.assertEqual(self.calls, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
