#!/usr/bin/env python3
"""Offline adversarial tests for the private destination-bound publisher."""
import importlib.util
import base64
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).with_name("publish-private-ci-source.py")
spec = importlib.util.spec_from_file_location("private_publisher", MODULE)
pub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pub)
A, B, C, D, E = ("a"*40, "b"*40, "c"*40, "d"*40, "e"*40)


class DestinationBoundPublisherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.root / ".github").mkdir()
        self.approval = self.root / ".github" / "private-ci-approved-repositories.txt"
        self.approval.write_text("trusted/ci\n")
        self.protected_main_policy = self.approval.read_bytes()
        self.main_is_protected = True
        self.main_requires_codeowners = True
        self.main_approval_count = 1
        self.main_enforces_admins = True
        self.main_dismisses_stale_reviews = True
        self.main_force_pushes_allowed = False
        self.main_deletions_allowed = False
        self.main_review_bypass = {"users": [], "teams": [], "apps": []}
        (self.source / "hello.ores").write_text("pub fnc main(): int { return 42; }\n")
        self.baseline_source_tree = self.expected_source_tree()
        self.mock_source_tree = self.baseline_source_tree
        self.mock_source_head = A
        self.mock_source_head_repo = pub.TRUSTED_SOURCE_REPOSITORY
        self.mock_source_base_repo = pub.TRUSTED_SOURCE_REPOSITORY
        self.mock_source_base_ref = pub.TRUSTED_SOURCE_REF
        self.mock_source_pr_state = "open"
        self.mock_source_merged = False
        self.mock_source_merged_at = None
        self.mock_source_merge_sha = A
        self.mock_source_main_sha = A
        self.mock_source_pr_draft = False
        self.mock_source_pr_author = "trusted-contributor"
        self.mock_source_reviews = [{
            "id": 1,
            "state": "APPROVED",
            "commit_id": A,
            "submitted_at": "2026-10-18T16:00:00Z",
            "user": {"login": pub.TRUSTED_SOURCE_REVIEWER},
        }]
        self.route_calls = []
        self.visibility = ["private"] * 100
        self.mock_destination_default_branch = "main"
        self.mock_destination_main_sha = A
        self.dest_protected = True
        self.dest_review_enabled = True
        self.dest_dismiss_stale = True
        self.dest_admins = True
        self.dest_allow_force = False
        self.dest_allow_delete = False
        self.dest_bypass = {"users": [], "teams": [], "apps": []}
        self.destination_codeowners = (
            b"/.github/ @ORESoftware\n"
            b"/.github/CODEOWNERS @ORESoftware\n")
        self.destination_codeowners_exists = True
        self.destination_codeowners_sha_override = None
        self.preflight = patch.object(pub, "require_preflight")
        self.preflight.start()
        self.addCleanup(self.preflight.stop)

    def fake_api(self, route, method="GET", payload=None):
        self.route_calls.append((method, route, payload))
        branch_route = (f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/branches/"
                        f"{pub.TRUSTED_SOURCE_REF}")
        if route == branch_route:
            return {"name": pub.TRUSTED_SOURCE_REF,
                    "protected": self.main_is_protected,
                    "commit": {"sha": self.mock_source_main_sha}}
        if route == branch_route + "/protection":
            return {
                "required_pull_request_reviews": {
                    "require_code_owner_reviews": self.main_requires_codeowners,
                    "required_approving_review_count": self.main_approval_count,
                    "dismiss_stale_reviews": self.main_dismisses_stale_reviews,
                    "bypass_pull_request_allowances": self.main_review_bypass,
                },
                "enforce_admins": {"enabled": self.main_enforces_admins},
                "allow_force_pushes": {"enabled": self.main_force_pushes_allowed},
                "allow_deletions": {"enabled": self.main_deletions_allowed},
            }
        trusted_policy_route = (
            f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/contents/"
            f".github/private-ci-approved-repositories.txt"
            f"?ref={pub.TRUSTED_SOURCE_REF}")
        if route == trusted_policy_route:
            contents = self.protected_main_policy
            return {
                "type": "file",
                "encoding": "base64",
                "size": len(contents),
                "content": base64.b64encode(contents).decode("ascii"),
                "sha": hashlib.sha1(
                    b"blob " + str(len(contents)).encode("ascii")
                    + bytes([0]) + contents).hexdigest(),
            }
        if route == f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/pulls/1286":
            return {
                "number": 1286,
                "state": self.mock_source_pr_state,
                "merged": self.mock_source_merged,
                "merged_at": self.mock_source_merged_at,
                "merge_commit_sha": self.mock_source_merge_sha,
                "draft": self.mock_source_pr_draft,
                "user": {"login": self.mock_source_pr_author},
                "head": {"sha": self.mock_source_head,
                         "repo": {"full_name": self.mock_source_head_repo}},
                "base": {"ref": self.mock_source_base_ref,
                         "repo": {"full_name": self.mock_source_base_repo}},
            }
        if route == f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/git/commits/{A}":
            return {"sha": A, "tree": {"sha": self.mock_source_tree}}
        if route == f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/git/commits/{E}":
            return {"sha": E, "tree": {"sha": self.mock_source_tree}}
        if route == f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/pulls/1286/reviews?per_page=100":
            return {"reviews": self.mock_source_reviews}
        if route == "repos/trusted/ci":
            return dict(full_name="trusted/ci", visibility=self.visibility.pop(0),
                        private=True, default_branch=self.mock_destination_default_branch)
        if route == "repos/trusted/ci/branches/main":
            return {"name": "main", "protected": self.dest_protected,
                    "commit": {"sha": self.mock_destination_main_sha}}
        if route == "repos/trusted/ci/branches/main/protection":
            return {
                "required_pull_request_reviews": {
                    "require_code_owner_reviews": self.dest_review_enabled,
                    "required_approving_review_count": 1,
                    "dismiss_stale_reviews": self.dest_dismiss_stale,
                    "bypass_pull_request_allowances": self.dest_bypass},
                "enforce_admins": {"enabled": self.dest_admins},
                "allow_force_pushes": {"enabled": self.dest_allow_force},
                "allow_deletions": {"enabled": self.dest_allow_delete},
            }
        if route == "repos/trusted/ci/contents/.github/CODEOWNERS?ref=" + A:
            if not self.destination_codeowners_exists:
                raise pub.Denied("authenticated GitHub API request failed")
            raw = self.destination_codeowners
            digest = hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                                  + bytes([0]) + raw).hexdigest()
            return {"type": "file", "encoding": "base64",
                    "size": len(raw),
                    "content": base64.b64encode(raw).decode("ascii"),
                    "sha": self.destination_codeowners_sha_override or digest}
        if route == "repos/trusted/ci/git/ref/heads/main":
            return {"ref": "refs/heads/main", "object": {"sha": self.mock_destination_main_sha, "type": "commit"}}
        if route == "repos/trusted/ci/git/blobs":
            raw = base64.b64decode(payload["content"])
            return {"sha": hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                                        + bytes([0]) + raw).hexdigest()}
        if route == "repos/trusted/ci/git/trees":
            # The server must return the exact canonical digest of the isolated
            # source tree; no orphaned/stale destination files may survive.
            return {"sha": E if "base_tree" in payload else self.expected_source_tree()}
        mapping = {
            "repos/trusted/ci/git/ref/heads/main": {"object": {"sha": A}},
            "repos/trusted/ci/git/commits/"+A: {"tree": {"sha": B}},
            "repos/trusted/ci/git/commits": {"sha": A},
            "repos/trusted/ci/git/refs": {"ref": "refs/heads/ci/oreslang-test", "object": {"type": "commit", "sha": A}},
        }
        if route not in mapping:
            raise AssertionError("unexpected or cross-repository GitHub API route " + route)
        return mapping[route]

    def expected_source_tree(self):
        return pub.git_tree_digest(pub.collect_source(self.source, self.root))

    def publish(self, repo="trusted/ci", branch="ci/oreslang-test", expected_tree=None):
        expected = expected_tree if expected_tree is not None else self.expected_source_tree()
        self.mock_source_tree = expected
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            return pub.publish(repo, self.source, branch, self.root, expected,
                               1286, A)


    def publish_merged(self):
        self.mock_source_pr_state = "closed"
        self.mock_source_merged = True
        self.mock_source_merged_at = "2026-10-18T18:00:00Z"
        self.mock_source_merge_sha = E
        self.mock_source_main_sha = E
        self.mock_source_tree = self.baseline_source_tree
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            return pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                               self.root, self.baseline_source_tree, None, E, 1286)

    def test_merged_main_positive_exact_tip(self):
        sha, count = self.publish_merged()
        self.assertEqual((sha, count), (A, 1))
        self.assertTrue(any(m == "POST" and route.endswith("/git/refs")
                            for m, route, _ in self.route_calls))

    def test_merged_main_denies_after_merge_approval(self):
        self.mock_source_reviews[0]["submitted_at"] = "2026-10-18T18:00:01Z"
        with self.assertRaisesRegex(pub.Denied, "approval must precede"):
            self.publish_merged()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_merged_main_bad_merge_timestamp_denied(self):
        for stamp in ("not-a-date", "2026-10-18T18:00:00", None):
            with self.subTest(stamp=stamp):
                self.mock_source_pr_state = "closed"
                self.mock_source_merged = True
                self.mock_source_merge_sha = E
                self.mock_source_main_sha = E
                self.mock_source_merged_at = stamp
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.baseline_source_tree, None, E, 1286)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_merged_main_moved_tip_denied(self):
        self.mock_source_pr_state = "closed"
        self.mock_source_merged = True
        self.mock_source_merged_at = "2026-10-18T18:00:00Z"
        self.mock_source_merge_sha = E
        self.mock_source_main_sha = B
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "HEAD no longer matches"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, None, E, 1286)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_merged_main_no_review_denied(self):
        self.mock_source_reviews = []
        with self.assertRaisesRegex(pub.Denied, "no independent CODEOWNER"):
            self.publish_merged()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_merged_main_tip_changed_after_blob_denied(self):
        self.mock_source_pr_state = "closed"
        self.mock_source_merged = True
        self.mock_source_merged_at = "2026-10-18T18:00:00Z"
        self.mock_source_merge_sha = E
        self.mock_source_main_sha = E
        def update_after_blob(route, method="GET", payload=None):
            answer = self.fake_api(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.mock_source_main_sha = B
            return answer
        with patch.object(pub, "gh_api", side_effect=update_after_blob):
            with self.assertRaisesRegex(pub.Denied, "HEAD no longer matches"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, None, E, 1286)
        self.assertTrue(any(m == "POST" and route.endswith("/git/blobs")
                            for m, route, _ in self.route_calls))
        self.assertFalse(any(m == "POST" and route.endswith("/git/refs")
                             for m, route, _ in self.route_calls))

    def test_source_pr_provenance_denies_other_head_and_foreign_repositories(self):
        for attr, value in (
                ("mock_source_head", B),
                ("mock_source_head_repo", "other-org/fork"),
                ("mock_source_base_repo", "other-org/fork"),
                ("mock_source_base_ref", "not-main"),
                ("mock_source_pr_state", "closed")):
            old = getattr(self, attr)
            setattr(self, attr, value)
            try:
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with patch.object(pub, "collect_source",
                                      side_effect=AssertionError("premature source read")):
                        with self.assertRaisesRegex(pub.Denied, "source PR provenance"):
                            pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                        self.root, self.baseline_source_tree, 1286, A)
            finally:
                setattr(self, attr, old)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_pr_wrong_commit_tree_denied_before_source_read(self):
        self.mock_source_tree = B
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub, "collect_source",
                              side_effect=AssertionError("premature source read")):
                with self.assertRaisesRegex(pub.Denied, "HEAD commit/tree"):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_pr_head_move_aborts_before_destination_ref(self):
        def move_after_blob(route, method="GET", payload=None):
            result = self.fake_api(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.mock_source_head = B
            return result
        with patch.object(pub, "gh_api", side_effect=move_after_blob):
            with self.assertRaisesRegex(pub.Denied, "source PR provenance"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertTrue(any(r.endswith("/git/blobs")
                            for m, r, _ in self.route_calls if m == "POST"))
        self.assertFalse(any(r.endswith("/git/refs")
                             for m, r, _ in self.route_calls if m == "POST"))

    def test_source_pr_move_stops_next_private_blob_upload(self):
        # Git blobs retain source bytes even if no branch ref is created.
        # A source PR revision change must stop the very next source write.
        (self.source / "second.ores").write_text("second private fixture")
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api

        def move_head_after_first_blob(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.mock_source_head = B
            return response

        with patch.object(pub, "gh_api", side_effect=move_head_after_first_blob):
            with self.assertRaisesRegex(pub.Denied, "source PR provenance"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        writes = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_invalid_pr_number_or_commit_denied_before_any_source_read(self):
        for number, sha in ((None, A), (0, A), (True, A),
                            (1286, None), (1286, "bad-commit")):
            with self.subTest(number=number, commit=sha):
                with patch.object(pub, "collect_source",
                                  side_effect=AssertionError("premature source read")):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.baseline_source_tree, number, sha)
        self.assertEqual([], self.route_calls)

    def test_unreviewed_source_pr_denied_before_source_read(self):
        self.mock_source_reviews = []
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub, "collect_source",
                              side_effect=AssertionError("premature source read")):
                with self.assertRaisesRegex(pub.Denied, "no independent CODEOWNER"):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_source_pr_review_of_previous_commit_denied(self):
        self.mock_source_reviews[0]["commit_id"] = B
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_codeowner_review_dismissal_cancels_previous_approval(self):
        self.mock_source_reviews.append({
            "id": 2, "state": "DISMISSED", "commit_id": A,
            "submitted_at": "2026-10-18T18:00:00Z",
            "user": {"login": pub.TRUSTED_SOURCE_REVIEWER},
        })
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_draft_or_self_review_source_pr_denied(self):
        for field, value in (("mock_source_pr_draft", True),
                             ("mock_source_pr_author", pub.TRUSTED_SOURCE_REVIEWER)):
            with self.subTest(field=field):
                before = getattr(self, field)
                setattr(self, field, value)
                try:
                    with patch.object(pub, "gh_api", side_effect=self.fake_api):
                        with self.assertRaises(pub.Denied):
                            pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                        self.root, self.baseline_source_tree, 1286, A)
                finally:
                    setattr(self, field, before)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_review_pagination_fail_closed(self):
        self.mock_source_reviews *= pub.MAX_PR_REVIEWS
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "pagination"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_approval_revocation_during_transfer_aborts_before_ref(self):
        def revoke_after_blob(route, method="GET", payload=None):
            result = self.fake_api(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.mock_source_reviews[-1]["state"] = "DISMISSED"
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_after_blob):
            with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(method == "POST" and route.endswith("/git/refs")
                             for method, route, _ in self.route_calls))

    def test_review_time_fractional_seconds_reversal_denied(self):
        # Before datetime parsing, ISO lexical sort put 'Z' after '.500Z'
        # and incorrectly treated an older approval as the latest action.
        self.mock_source_reviews[0]["submitted_at"] = "2026-10-18T16:00:00Z"
        self.mock_source_reviews.append({
            "id": 2, "state": "DISMISSED", "commit_id": A,
            "submitted_at": "2026-10-18T16:00:00.500Z",
            "user": {"login": pub.TRUSTED_SOURCE_REVIEWER},
        })
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_review_time_timezone_offsets_order_correctly(self):
        self.mock_source_reviews[0]["submitted_at"] = "2026-10-18T19:00:00+03:00"
        self.mock_source_reviews.append({
            "id": 2, "state": "CHANGES_REQUESTED", "commit_id": A,
            "submitted_at": "2026-10-18T16:00:01Z",
            "user": {"login": pub.TRUSTED_SOURCE_REVIEWER},
        })
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "exact-HEAD CODEOWNER"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_bad_or_naive_review_timestamp_fails_closed(self):
        for value in ("yesterday", "2026-10-18T16:00:00", None):
            with self.subTest(timestamp=value):
                self.mock_source_reviews[0]["submitted_at"] = value
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaisesRegex(pub.Denied, "chronology|timestamp"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_duplicate_review_id_rejected(self):
        self.mock_source_reviews.append(dict(self.mock_source_reviews[0]))
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "duplicated"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.baseline_source_tree, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_real_github_reviews_list_parser(self):
        from unittest.mock import Mock
        route = (f"repos/{pub.TRUSTED_SOURCE_REPOSITORY}/pulls/1286/"
                 "reviews?per_page=100")
        payload = '{"id":9,"state":"APPROVED"}'
        with patch.object(pub.subprocess, "run", return_value=Mock(
                returncode=0, stdout="[" + payload + "]")):
            self.assertEqual(pub.gh_api(route)["reviews"][0]["id"], 9)
        with patch.object(pub.subprocess, "run", return_value=Mock(
                returncode=0, stdout='{"reviews":[]}')):
            with self.assertRaisesRegex(pub.Denied, "review response shape"):
                pub.gh_api(route)

    def test_invalid_source_proof_rejected_before_any_source_access(self):
        with patch.object(pub, "collect_source", side_effect=AssertionError(
                "private bytes must not be touched")):
            with patch.object(pub, "require_preflight", side_effect=AssertionError(
                    "bad attestation must be rejected before destination preflight")):
                for invalid in (None, "", "not-a-sha", "A" * 40):
                    with self.subTest(proof=invalid):
                        with self.assertRaises(pub.Denied):
                            pub.publish("trusted/ci", self.source,
                                        "ci/oreslang-test", self.root, invalid, 1286, A)
        self.assertEqual([], self.route_calls)

    def test_git_tree_digest_agrees_with_independent_git_index(self):
        """Exercise real Git tree serialization, not a duplicated SHA oracle."""
        import subprocess

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            subprocess.run(["git", "init", "-q", str(root)],
                           check=True, capture_output=True)
            source_root = root / "source"
            source_root.mkdir()
            files = {
                "alpha.ores": b"alpha\n",
                "nested/b.ores": b"beta\n",
                "nested/bin": b"#!/bin/sh\nexit 0\n",
                "nested.deep/space name.txt": b"space",
                "nested.deep/unicode-λ.txt": "unicode".encode("utf-8"),
                "nested.ores": b"lexical sort"
            }
            for rel, data in files.items():
                file_path = source_root / rel
                file_path.parent.mkdir(parents=True, exist_ok=True)
                file_path.write_bytes(data)
            (source_root / "nested/bin").chmod(0o755)
            subprocess.run(["git", "-C", str(root), "add", "--all"],
                           check=True, capture_output=True)
            root_tree = subprocess.check_output(
                ["git", "-C", str(root), "write-tree"], text=True).strip()
            git_tree = subprocess.check_output(
                ["git", "-C", str(root), "rev-parse", root_tree + ":source"],
                text=True).strip()
            observed = pub.collect_source(source_root, root)
            calculated = pub.git_tree_digest(observed)
            self.assertEqual(git_tree, calculated)
            mutated = [
                (rel, b"different bytes" if rel == "alpha.ores" else data, mode)
                for rel, data, mode in observed
            ]
            self.assertNotEqual(git_tree, pub.git_tree_digest(mutated))
            changed_mode = [
                (rel, data, "100755" if rel == "alpha.ores" else mode)
                for rel, data, mode in observed
            ]
            self.assertNotEqual(git_tree, pub.git_tree_digest(changed_mode))
            removed = [entry for entry in observed if entry[0] != "nested/b.ores"]
            self.assertNotEqual(git_tree, pub.git_tree_digest(removed))
            self.assertEqual("100755", dict(
                (name, mode) for name, _, mode in observed)["nested/bin"])

    def test_randomized_git_tree_digest_matches_real_git(self):
        """Differentially test nested Git tree hashing against git write-tree.

        Uses only synthetic data: no private compiler source, API requests,
        GitHub tokens, or cross-organization file transfers.
        """
        import os
        import random
        import shutil
        import subprocess

        rng = random.Random(20261018)
        names = ("alpha", "Z", "a0", "nested", "space name", "λ",
                 "日本", "dot.name", "a-b", "long_name_123")
        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            source = root / "source"
            subprocess.run(["git", "init", "-q", str(root)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for case in range(64):
                if source.exists():
                    shutil.rmtree(source)
                source.mkdir()
                snapshot = []
                for index in range(rng.randint(1, 20)):
                    directories = [rng.choice(names)
                                   for _ in range(rng.randint(0, 3))]
                    name = "/".join(directories + [f"{index}.ores"])
                    path = source / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    data = rng.randbytes(rng.randint(0, 2048))
                    mode = rng.choice(("100644", "100755"))
                    path.write_bytes(data)
                    os.chmod(path, 0o755 if mode == "100755" else 0o644)
                    snapshot.append((name, data, mode))
                subprocess.run(["git", "-C", str(root), "add", "-A"],
                               check=True, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
                root_tree = subprocess.check_output(
                    ["git", "-C", str(root), "write-tree"],
                    text=True).strip()
                reference = subprocess.check_output(
                    ["git", "-C", str(root), "rev-parse", root_tree + ":source"],
                    text=True).strip()
                self.assertEqual(reference, pub.git_tree_digest(snapshot),
                                 f"case={case} canonical")
                rng.shuffle(snapshot)
                self.assertEqual(reference, pub.git_tree_digest(snapshot),
                                 f"case={case} permuted")

    def test_malformed_digest_entries_fail_closed(self):
        for entries in (
            [(42, b"data", "100644")],
            [("safe", "not bytes", "100644")],
            [("safe", b"data", "120000")],
            [("../escape", b"data", "100644")],
            [("a/b", b"x", "100644"), ("a", b"y", "100644")],
            [("repeat", b"x", "100644"), ("repeat", b"y", "100644")],
            [("unsafe\\path", b"x", "100644")],
            [(".git/config", b"x", "100644")],
            [(".GIT/config", b"x", "100644")],
            [("nested/.gIt/config", b"x", "100644")],
            [("bad\\x00name", b"x", "100644")],
            [("bad\\x1fname", b"x", "100644")],
            [("bad\\x7fname", b"x", "100644")],
            [("nested//file", b"x", "100644")],
            [("nested/./file", b"x", "100644")],
            [("nested/../file", b"x", "100644")],
            [("name/", b"x", "100644")],
            [("", b"x", "100644")],
            [("bad\\ud800name", b"x", "100644")],
        ):
            with self.subTest(entries=repr(entries)):
                with self.assertRaises(pub.Denied):
                    pub.git_tree_digest(entries)

    def test_stale_codeowner_reviews_cannot_authorize_source_export(self):
        self.main_dismisses_stale_reviews = False
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "outdated revisions"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_review_bypass_actor_grants_cannot_authorize_export(self):
        for category in ("users", "teams", "apps"):
            with self.subTest(category=category):
                self.route_calls.clear()
                self.main_review_bypass = {"users": [], "teams": [], "apps": []}
                self.main_review_bypass[category] = [{"login": "bypass-actor"}]
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaisesRegex(pub.Denied, "actor bypass"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.expected_source_tree(), 1286, A)
                self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_malformed_review_bypass_grants_fail_closed(self):
        for grants in (None, ["unvalidated"], {"users": None},
                       {"unknown": []}, {"apps": "all"}):
            with self.subTest(grants=repr(grants)):
                self.route_calls.clear()
                self.main_review_bypass = grants
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaisesRegex(pub.Denied, "(malformed|actor bypass)"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.expected_source_tree(), 1286, A)
                self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_unprotected_source_main_cannot_authorize_any_export(self):
        self.main_is_protected = False
        pinned = self.expected_source_tree()
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub, "collect_source",
                              side_effect=AssertionError("source read")):
                with self.assertRaisesRegex(pub.Denied, "not protected"):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, pinned, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_codeowner_reviews_must_be_enforced(self):
        self.main_requires_codeowners = False
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "CODEOWNER"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_at_least_one_review_must_be_required(self):
        self.main_approval_count = 0
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "CODEOWNER"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_administrator_review_bypass_must_be_disabled(self):
        self.main_enforces_admins = False
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "admin bypass"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_stale_reviews_must_be_dismissed(self):
        self.main_dismisses_stale_reviews = False
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "outdated revisions"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_force_push_permission_denied(self):
        self.main_force_pushes_allowed = True
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "force-push"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_deletion_permission_denied(self):
        self.main_deletions_allowed = True
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "deletion"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_missing_force_push_setting_fails_closed(self):
        original = self.fake_api
        def missing_rule(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route.endswith("/branches/main/protection"):
                response.pop("allow_force_pushes", None)
            return response
        with patch.object(pub, "gh_api", side_effect=missing_rule):
            with self.assertRaisesRegex(pub.Denied, "unknown bypass"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_main_protection_revoked_during_upload_blocks_ref(self):
        original = self.fake_api
        def revoke(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.main_is_protected = False
            return response
        with patch.object(pub, "gh_api", side_effect=revoke):
            with self.assertRaisesRegex(pub.Denied, "not protected"):
                self.publish()
        self.assertFalse(any(route.endswith("/git/refs")
                             for _, route, _ in self.route_calls))

    def test_unreviewed_local_allowlist_cannot_self_authorize(self):
        pinned = self.expected_source_tree()
        self.approval.write_text("trusted/ci\nother/attacker\n")
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            # Rejection must happen *before* reading any private source bytes.
            with patch.object(pub, "collect_source",
                              side_effect=AssertionError("source read")):
                with self.assertRaisesRegex(pub.Denied, "protected source main"):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, pinned, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_unavailable_protected_main_policy_blocks_before_source_read(self):
        pinned = self.expected_source_tree()
        def api_unavailable(route, method="GET", payload=None):
            if route.endswith("private-ci-approved-repositories.txt?ref=main"):
                raise pub.Denied("authenticated GitHub API request failed")
            return self.fake_api(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=api_unavailable):
            with patch.object(pub, "collect_source",
                              side_effect=AssertionError("source read")):
                with self.assertRaisesRegex(pub.Denied, "GitHub API request failed"):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, pinned, 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_protected_main_revocation_blocks_new_branch(self):
        original = self.fake_api
        def revoke_after_first_blob(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.protected_main_policy = b"# approval revoked in protected main\n"
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_after_first_blob):
            with self.assertRaisesRegex(pub.Denied, "protected source main"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        self.assertFalse(any(route.endswith("/git/refs")
                             for _, route, _ in self.route_calls))

    def test_protected_main_revocation_stops_next_blob_upload(self):
        # A failed final ref alone is insufficient: already-created dangling
        # Git blobs retain transferred private bytes. Stop subsequent uploads.
        (self.source / "extra.ores").write_text("another private source file")
        pinned = self.expected_source_tree()
        original = self.fake_api

        def revoke_after_first_blob(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.protected_main_policy = b"# revoked on protected main\n"
            return response

        with patch.object(pub, "gh_api", side_effect=revoke_after_first_blob):
            with self.assertRaisesRegex(pub.Denied, "protected source main"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        writes = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_protected_main_bad_blob_digest_rejected_before_upload(self):
        original = self.fake_api
        def forged(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route.endswith("private-ci-approved-repositories.txt?ref=main"):
                return dict(result, sha="f" * 40)
            return result
        with patch.object(pub, "gh_api", side_effect=forged):
            with self.assertRaisesRegex(pub.Denied, "blob digest"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_protected_main_invalid_base64_rejected_before_upload(self):
        original = self.fake_api
        def malformed(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route.endswith("private-ci-approved-repositories.txt?ref=main"):
                return dict(result, content="!!!")
            return result
        with patch.object(pub, "gh_api", side_effect=malformed):
            with self.assertRaisesRegex(pub.Denied, "cannot be decoded"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_missing_expected_source_tree_fails_before_upload(self):
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaises(pub.Denied):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, 1286, A)
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_wrong_expected_tree_fails_before_any_blob_upload(self):
        self.mock_source_tree = "0" * 40
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "attested Git tree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, "0" * 40, 1286, A)
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_source_mutation_after_digest_capture_fails_before_any_upload(self):
        pinned = self.expected_source_tree()
        (self.source / "hello.ores").write_text("tampered source contents")
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "attested Git tree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_wrong_destination_tree_sha_fails_before_new_branch(self):
        pinned = self.expected_source_tree()
        original = self.fake_api

        def wrong_tree(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/trees" and "base_tree" not in payload:
                return {"sha": "d" * 40}
            return original(route, method, payload)

        with patch.object(pub, "gh_api", side_effect=wrong_tree):
            with self.assertRaisesRegex(pub.Denied, "destination source subtree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        self.assertFalse(any(route.endswith("/git/refs")
                             for _, route, _ in self.route_calls))

    def test_destination_default_head_move_stops_second_blob(self):
        (self.source / "extra.ores").write_text("synthetic second source")
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api

        def moved(route, method="GET", payload=None):
            result = original(route, method, payload)
            if method == "POST" and route == "repos/trusted/ci/git/blobs":
                self.mock_destination_main_sha = B
            return result

        with patch.object(pub, "gh_api", side_effect=moved):
            with self.assertRaisesRegex(pub.Denied, "destination default HEAD changed"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        writes = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_destination_default_branch_switch_stops_second_blob(self):
        (self.source / "extra.ores").write_text("synthetic second source")
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api

        def switched(route, method="GET", payload=None):
            result = original(route, method, payload)
            if method == "POST" and route == "repos/trusted/ci/git/blobs":
                self.mock_destination_default_branch = "new-main"
            return result

        with patch.object(pub, "gh_api", side_effect=switched):
            with self.assertRaisesRegex(pub.Denied, "destination default branch changed"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        writes = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_initial_destination_ref_wrong_shape_denied(self):
        pinned = self.baseline_source_tree
        original = self.fake_api
        for forged in ({"object": None},
                       {"object": {"sha": A, "type": "tag"}},
                       {"ref": "refs/heads/other", "object": {"sha": A}}):
            with self.subTest(forged=forged):
                self.route_calls.clear()
                def api(route, method="GET", payload=None):
                    if route == "repos/trusted/ci/git/ref/heads/main":
                        self.route_calls.append((method, route, payload))
                        return forged
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=api):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root,
                                    pinned, 1286, A)
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_commit_malformed_tree_denied(self):
        pinned = self.baseline_source_tree
        original = self.fake_api
        for forged in ({"tree": None},
                       {"sha": B, "tree": {"sha": B}},
                       {"tree": "unexpected-text"}):
            with self.subTest(forged=forged):
                self.route_calls.clear()
                def api(route, method="GET", payload=None):
                    if route == "repos/trusted/ci/git/commits/" + A:
                        self.route_calls.append((method, route, payload))
                        return forged
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=api):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root,
                                    pinned, 1286, A)
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_ref_type_changes_after_first_blob(self):
        (self.source / "two.ores").write_text("second synthetic")
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api
        def api(route, method="GET", payload=None):
            answer = original(route, method, payload)
            if method == "POST" and route == "repos/trusted/ci/git/blobs":
                self.mock_destination_main_sha = B
            if route == "repos/trusted/ci/git/ref/heads/main":
                seen_blobs = any(m == "POST" and r.endswith("/git/blobs")
                                 for m, r, _ in self.route_calls)
                if seen_blobs:
                    return {"ref": "refs/heads/main", "object": {"sha": A, "type": "tag"}}
            return answer
        with patch.object(pub, "gh_api", side_effect=api):
            with self.assertRaisesRegex(pub.Denied, "must target a commit"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        self.assertEqual([r for m, r, _ in self.route_calls if m == "POST"],
                         ["repos/trusted/ci/git/blobs"])

    def test_destination_default_ref_malformed_denied_before_upload(self):
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api

        def malformed(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route == "repos/trusted/ci/git/ref/heads/main":
                calls = sum(r == route for _, r, _ in self.route_calls)
                if calls >= 2:
                    return {"ref": "refs/heads/main", "object": None}
            return response

        with patch.object(pub, "gh_api", side_effect=malformed):
            with self.assertRaisesRegex(pub.Denied, "destination default ref must target a commit"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_unprotected_destination_rejected_before_private_bytes(self):
        self.dest_protected = False
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "destination default branch is unprotected"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_review_bypass_and_unsafe_writes_rejected(self):
        scenarios = (
            ("codeowners", "dest_review_enabled", False, "workflow review"),
            ("stale reviews", "dest_dismiss_stale", False, "workflow review"),
            ("admin bypass", "dest_admins", False, "workflow review"),
            ("force push", "dest_allow_force", True, "unsafe writes"),
            ("deletion", "dest_allow_delete", True, "unsafe writes"),
            ("actor bypass", "dest_bypass", {"users": [{"login": "actor"}]}, "bypass"),
        )
        for label, setting, value, error in scenarios:
            with self.subTest(label=label):
                self.route_calls.clear()
                prior = getattr(self, setting)
                setattr(self, setting, value)
                try:
                    with patch.object(pub, "gh_api", side_effect=self.fake_api):
                        with self.assertRaisesRegex(pub.Denied, error):
                            self.publish()
                    self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
                finally:
                    setattr(self, setting, prior)

    def test_destination_protection_revoked_after_first_blob_stops_next_write(self):
        (self.source / "extra.ores").write_text("synthetic second source")
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api

        def revoke(route, method="GET", payload=None):
            result = original(route, method, payload)
            if method == "POST" and route == "repos/trusted/ci/git/blobs":
                self.dest_protected = False
            return result

        with patch.object(pub, "gh_api", side_effect=revoke):
            with self.assertRaisesRegex(pub.Denied, "destination default branch is unprotected"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        writes = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_destination_missing_codeowners_fails_before_first_blob(self):
        self.destination_codeowners_exists = False
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaises(pub.Denied):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_codeowners_rules_must_cover_all_workflows(self):
        invalid_rules = [
            b"",
            b"# no active owners\n",
            b"/.github/CODEOWNERS @ORESoftware\n",
            b"/.github/workflows/ @ORESoftware\n",
            b"/.github/ @ORESoftware\n",
            b"/.github/ @ORESoftware\n/.github/CODEOWNERS @bad-reviewer\n",
            b"/.github/ @ORESoftware\n/.github/CODEOWNERS @ORESoftware\n"
            b"/.github/workflows/ @untrusted\n",
        ]
        for rules in invalid_rules:
            with self.subTest(rules=rules):
                self.route_calls.clear()
                self.destination_codeowners = rules
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaises(pub.Denied):
                        self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_codeowners_blob_digest_mismatch_fails_closed(self):
        self.destination_codeowners_sha_override = "f" * 40
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "CODEOWNERS Git blob differs"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_codeowners_revoked_after_first_blob(self):
        (self.source / "extra.ores").write_text("synthetic second source")
        pinned = self.expected_source_tree()
        self.mock_source_tree = pinned
        original = self.fake_api

        def revoke(route, method="GET", payload=None):
            result = original(route, method, payload)
            if method == "POST" and route == "repos/trusted/ci/git/blobs":
                self.destination_codeowners_exists = False
            return result

        with patch.object(pub, "gh_api", side_effect=revoke):
            with self.assertRaises(pub.Denied):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, pinned, 1286, A)
        writes = [route for method, route, _ in self.route_calls
                  if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_only_exact_private_repo_write_routes_and_new_ref(self):
        sha, count = self.publish()
        self.assertEqual((sha, count), (A, 1))
        writes = [(m, r, p) for m, r, p in self.route_calls if m == "POST"]
        self.assertEqual([r for _, r, _ in writes], [
            "repos/trusted/ci/git/blobs", "repos/trusted/ci/git/trees",
            "repos/trusted/ci/git/trees", "repos/trusted/ci/git/commits",
            "repos/trusted/ci/git/refs"])
        self.assertEqual(writes[-1][2]["ref"], "refs/heads/ci/oreslang-test")
        self.assertEqual(writes[1][2]["tree"][0]["path"], "hello.ores")
        self.assertNotIn("base_tree", writes[1][2])
        self.assertEqual(writes[2][2]["tree"], [
            {"path": "private-oreslang-source", "mode": "040000",
             "type": "tree", "sha": self.expected_source_tree()}])
        self.assertEqual(writes[2][2]["base_tree"], B)
        self.assertEqual(writes[3][2]["tree"], E)
        self.assertTrue(all(r.startswith("repos/trusted/ci") for _, r, _ in writes))

    def test_created_ci_ref_acknowledgement_must_match_exact_commit(self):
        # GitHub must acknowledge the attested commit, not merely the ref name.
        expected = self.expected_source_tree()
        original = self.fake_api
        variants = [
            {"ref": "refs/heads/ci/oreslang-test"},
            {"ref": "refs/heads/ci/oreslang-test", "object": None},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "commit", "sha": B}},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "tag", "sha": A}},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "commit", "sha": "invalid"}},
        ]
        for response in variants:
            with self.subTest(response=response):
                self.route_calls.clear()
                def api(route, method="GET", payload=None):
                    if route == "repos/trusted/ci/git/refs" and method == "POST":
                        self.route_calls.append((method, route, payload))
                        return response
                    return original(route, method, payload)
                self.mock_source_tree = expected
                with patch.object(pub, "gh_api", side_effect=api):
                    with self.assertRaisesRegex(pub.Denied, "attested CI commit"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, expected, 1286, A)
                posts = [route for method, route, _ in self.route_calls
                         if method == "POST" and route.endswith("/git/refs")]
                self.assertEqual(len(posts), 1)

    def test_existing_stale_files_cannot_survive_clean_source_snapshot(self):
        # Assume base_tree contains a stale private-oreslang-source/secret.ores.
        # A new isolated source tree MUST NOT be based on that default tree.
        (self.source / "current.ores").write_text("new source")
        self.publish()
        tree_writes = [payload for method, route, payload in self.route_calls
                       if method == "POST" and route.endswith("/git/trees")]
        self.assertEqual(len(tree_writes), 2)
        self.assertNotIn("base_tree", tree_writes[0])
        self.assertEqual([item["path"] for item in tree_writes[0]["tree"]],
                         ["current.ores", "hello.ores"])
        self.assertEqual(tree_writes[1]["tree"], [
            {"path": pub.PREFIX, "mode": "040000", "type": "tree", "sha": self.expected_source_tree()}])
        self.assertEqual(tree_writes[1]["base_tree"], B)

    def test_public_visibility_aborts_before_any_write(self):
        self.visibility = ["public"]
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_visibility_flip_aborts_before_next_write(self):
        self.visibility = ["private", "private", "public"]
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertEqual([r for m, r, _ in self.route_calls if m == "POST"],
                         ["repos/trusted/ci/git/blobs"])

    def test_mid_transfer_policy_revocation_aborts_without_new_branch(self):
        original = self.fake_api
        def revoke_after_blob(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.approval.write_text("# approval revoked")
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_after_blob):
            with self.assertRaisesRegex(pub.Denied, "approval.*(revoked|changed)"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        writes = [r for m, r, _ in self.route_calls if m == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_initial_unapproved_policy_fails_even_with_stale_preflight(self):
        self.approval.write_text("# reviewer removed the destination")
        with self.assertRaisesRegex(pub.Denied, "revoked or absent"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_symlinked_approval_policy_fails_before_upload(self):
        self.approval.unlink()
        self.approval.symlink_to(self.source / "hello.ores")
        with self.assertRaisesRegex(pub.Denied, "approval policy"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_wrong_repo_and_invalid_branch_rejected_before_preflight(self):
        for repo, branch in [("public/test;evil", "ci/oreslang-test"),
                             ("trusted/ci", "main"),
                             ("trusted/ci", "refs/heads/main"),
                             ("trusted/ci", "ci/other")]:
            with self.subTest(repo=repo, branch=branch):
                with self.assertRaises(pub.Denied):
                    self.publish(repo, branch)
        self.assertEqual(self.route_calls, [])

    def test_symlinked_source_rejected_before_upload(self):
        (self.source / "secret").symlink_to(self.root)
        with self.assertRaises(pub.Denied):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_symlinked_directory_rejected_before_upload(self):
        (self.source / "linked").symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(pub.Denied):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_nested_regular_source_files_keep_expected_paths(self):
        directory = self.source / "nested"
        directory.mkdir()
        (directory / "inside.ores").write_text("safe")
        _, count = self.publish()
        self.assertEqual(count, 2)
        new_tree = [payload for method, route, payload in self.route_calls
                    if method == "POST" and route.endswith("/git/trees")]
        self.assertEqual(len(new_tree), 2)
        paths = [item["path"] for item in new_tree[0]["tree"]]
        self.assertEqual(paths, ["hello.ores", "nested/inside.ores"])
        self.assertNotIn("base_tree", new_tree[0])
        self.assertEqual(new_tree[1]["tree"][0]["path"], "private-oreslang-source")

    def test_directory_entry_quota_rejects_before_upload(self):
        (self.source / "another.ores").write_text("another")
        with patch.object(pub, "MAX_DIRECTORY_ENTRIES", 1):
            with self.assertRaisesRegex(pub.Denied, "entry count"):
                self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_parent_directory_swap_to_symlink_fails_before_upload(self):
        nested = self.source / "nested"
        nested.mkdir()
        (nested / "inside.ores").write_text("safe")
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "private.ores").write_text("do not export")
        original_open = pub.os.open
        swapped = [False]
        def swap_parent(name, flags, *args, **kwargs):
            if name == "nested" and kwargs.get("dir_fd") is not None and not swapped[0]:
                swapped[0] = True
                nested.rename(self.source / "nested-original")
                nested.symlink_to(outside, target_is_directory=True)
            return original_open(name, flags, *args, **kwargs)
        with patch.object(pub.os, "open", side_effect=swap_parent):
            with self.assertRaises(pub.Denied):
                self.publish(expected_tree=self.baseline_source_tree)
        self.assertTrue(swapped[0])
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_file_swap_to_symlink_fails_before_upload(self):
        sensitive = self.root / "outside-secret"
        sensitive.write_text("secret")
        in_source = self.source / "hello.ores"
        original_open = pub.os.open
        swapped = [False]
        def swap_file(name, flags, *args, **kwargs):
            if name == "hello.ores" and kwargs.get("dir_fd") is not None and not swapped[0]:
                swapped[0] = True
                in_source.unlink()
                in_source.symlink_to(sensitive)
            return original_open(name, flags, *args, **kwargs)
        with patch.object(pub.os, "open", side_effect=swap_file):
            with self.assertRaises(pub.Denied):
                self.publish(expected_tree=self.baseline_source_tree)
        self.assertTrue(swapped[0])
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_hardlink_to_outside_file_fails_before_upload(self):
        sensitive = self.root / "outside-secret"
        sensitive.write_text("secret")
        (self.source / "hardlink.ores").hardlink_to(sensitive)
        with self.assertRaisesRegex(pub.Denied, "hardlinked"):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_nonregular_source_fifo_fails_before_upload(self):
        import os
        import stat
        fifo = self.source / "suspicious-fifo"
        os.mkfifo(fifo)
        self.assertTrue(stat.S_ISFIFO(fifo.stat().st_mode))
        with self.assertRaises(pub.Denied):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_policy_symlink_swap_between_check_and_read_rejected(self):
        # Regression: policy is opened with O_NOFOLLOW via a dirfd, not
        # Path.read_bytes(). Intercept the real syscall or this test could
        # pass merely because a different (already revoked) policy is present.
        outside = self.root / "outside-approved-policy"
        outside.write_text("trusted/ci\n")
        original_open = pub.os.open
        swapped = [False]
        def swap_policy(name, flags, *args, **kwargs):
            if (name == "private-ci-approved-repositories.txt"
                    and kwargs.get("dir_fd") is not None and not swapped[0]):
                swapped[0] = True
                self.approval.rename(self.root / "original-approved-policy")
                self.approval.symlink_to(outside)
            return original_open(name, flags, *args, **kwargs)
        with patch.object(pub.os, "open", side_effect=swap_policy):
            with self.assertRaises(pub.Denied):
                self.publish(expected_tree=self.baseline_source_tree)
        self.assertTrue(swapped[0], "attack hook never hit production policy read")
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_policy_parent_directory_swap_rejected(self):
        outside = self.root / "external-approved-dir"
        outside.mkdir()
        (outside / "private-ci-approved-repositories.txt").write_text("trusted/ci\n")
        self.approval.write_text("# no approval\n")
        original_open = pub.os.open
        replaced = [False]
        def redirect(name, flags, *args, **kwargs):
            if name == ".github" and kwargs.get("dir_fd") is not None and not replaced[0]:
                replaced[0] = True
                (self.root / ".github").rename(self.root / "old-policy-dir")
                (self.root / ".github").symlink_to(outside, target_is_directory=True)
            return original_open(name, flags, *args, **kwargs)
        with patch.object(pub.os, "open", side_effect=redirect):
            with self.assertRaises(pub.Denied):
                self.publish()
        self.assertTrue(replaced[0])
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_hardlinked_approval_policy_rejected(self):
        outside = self.root / "outside-approved-policy"
        outside.write_bytes(self.approval.read_bytes())
        self.approval.unlink()
        self.approval.hardlink_to(outside)
        with self.assertRaisesRegex(pub.Denied, "single-link"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_oversized_approval_policy_rejected(self):
        self.approval.write_text("#" + "x" * (16 * 1024 + 1))
        with self.assertRaisesRegex(pub.Denied, "size limit"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_repeated_failed_reads_do_not_leak_descriptors(self):
        import os
        if not Path("/proc/self/fd").is_dir():
            self.skipTest("requires Linux /proc/self/fd")
        bad = self.source / "nested"
        bad.mkdir()
        (bad / "escape").symlink_to(self.root)
        before = len(os.listdir("/proc/self/fd"))
        for _ in range(200):
            with self.assertRaises(pub.Denied):
                pub.collect_source(self.source, self.root)
        after = len(os.listdir("/proc/self/fd"))
        self.assertLessEqual(after, before + 2)

    def test_concurrent_ancestor_swaps_do_not_expose_external_bytes(self):
        import os
        import threading
        stop = threading.Event()
        root = self.source / "nested"
        moved = self.source / "moved"
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "private.ores").write_text("EXTERNAL SECRET")
        def attacker():
            for _ in range(700):
                if stop.is_set(): break
                try:
                    if root.is_symlink(): root.unlink()
                    if root.is_dir():
                        for item in root.iterdir(): item.unlink()
                        root.rmdir()
                    root.mkdir()
                    (root / "safe.ores").write_text("safe")
                    root.rename(moved)
                    root.symlink_to(outside, target_is_directory=True)
                    root.unlink()
                    for item in moved.iterdir(): item.unlink()
                    moved.rmdir()
                except OSError: pass
        before = len(os.listdir("/proc/self/fd")) if Path("/proc/self/fd").is_dir() else None
        worker = threading.Thread(target=attacker, daemon=True)
        worker.start()
        try:
            for _ in range(450):
                try: files = pub.collect_source(self.source, self.root)
                except pub.Denied: continue
                self.assertFalse(any(b"EXTERNAL SECRET" in data for _, data, _ in files))
        finally:
            stop.set()
            worker.join(3)
        self.assertFalse(worker.is_alive())
        if before is not None:
            self.assertLessEqual(len(os.listdir("/proc/self/fd")), before + 2)

    def test_concurrent_policy_symlink_swap_never_grants_approval(self):
        import os
        import threading
        outside = self.root / "outside-policy"
        outside.write_text("trusted/ci\n")
        self.approval.write_text("# no approval\n")
        stop = threading.Event()
        def attacker():
            for _ in range(700):
                if stop.is_set(): break
                try:
                    if self.approval.is_file() or self.approval.is_symlink():
                        self.approval.unlink()
                    self.approval.symlink_to(outside)
                    self.approval.unlink()
                    self.approval.write_text("# no approval\n")
                except OSError: pass
        before = len(os.listdir("/proc/self/fd")) if Path("/proc/self/fd").is_dir() else None
        worker = threading.Thread(target=attacker, daemon=True)
        worker.start()
        try:
            for _ in range(450):
                with self.assertRaises(pub.Denied):
                    pub.checked_policy_fingerprint(self.root, "trusted/ci")
        finally:
            stop.set()
            worker.join(3)
        self.assertFalse(worker.is_alive())
        if before is not None:
            self.assertLessEqual(len(os.listdir("/proc/self/fd")), before + 2)

    def test_invalid_api_sha_rejects_without_commit(self):
        original = self.fake_api
        def bad(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/blobs":
                return {"sha": "not a sha"}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=bad):
            with self.assertRaises(pub.Denied):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_valid_looking_but_incorrect_blob_sha_is_rejected(self):
        original = self.fake_api
        def wrong(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/blobs":
                return {"sha": C}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong):
            with self.assertRaisesRegex(pub.Denied, "digest"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_source_tree(), 1286, A)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_source_must_be_under_trusted_checkout(self):
        outside = self.root.parent / ("unsafe-" + self.root.name)
        outside.mkdir()
        try:
            (outside / "outside.ores").write_text("private data")
            with patch.object(pub, "gh_api", side_effect=self.fake_api):
                with self.assertRaisesRegex(pub.Denied, "trusted checkout"):
                    pub.publish("trusted/ci", outside, "ci/oreslang-test",
                                self.root, self.expected_source_tree(), 1286, A)
        finally:
            (outside / "outside.ores").unlink()
            outside.rmdir()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_preflight_does_not_execute_checkout_controlled_shell(self):
        # An unreviewed source checkout can contain an attacker-controlled
        # guard; it must never run with the publisher's GitHub credentials.
        scripts = self.root / "scripts"
        scripts.mkdir(exist_ok=True)
        marker = self.root / "checkout-guard-executed"
        guard = scripts / "check-private-ci-destination.sh"
        guard.write_text("#!/bin/sh\\ntouch " + str(marker) + "\\n")
        guard.chmod(0o755)
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub.subprocess, "run", side_effect=AssertionError(
                    "publisher invoked an executable from the checkout")):
                pub.require_preflight("trusted/ci", self.root)
        self.assertFalse(marker.exists())
        self.assertTrue(any(route == "repos/trusted/ci"
                            for _, route, _ in self.route_calls))

    def test_preflight_denies_disallowed_destination_without_shell(self):
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub.subprocess, "run", side_effect=AssertionError(
                    "unreviewed checkout shell must not execute")):
                with self.assertRaisesRegex(pub.Denied, "revoked or absent"):
                    pub.require_preflight("not-approved/infra", self.root)
        self.assertEqual([], self.route_calls)

    def test_denied_preflight_halts_before_api(self):
        with patch.object(pub, "require_preflight", side_effect=pub.Denied("not approved")):
            with self.assertRaisesRegex(pub.Denied, "not approved"):
                self.publish()
        self.assertEqual(self.route_calls, [])

    def test_oversized_source_file_rejected(self):
        (self.source / "large").write_bytes(b"x" * (pub.MAX_SINGLE + 1))
        with self.assertRaises(pub.Denied):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")

    def test_casefolded_git_metadata_directory_rejected_before_export(self):
        (self.source / ".GIT").mkdir()
        (self.source / ".GIT" / "config").write_text("do not export")
        with self.assertRaisesRegex(pub.Denied, "unsupported name"):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any("/branches/main" in route
                            for _, route, _ in self.route_calls))

    def test_git_metadata_directory_rejected(self):
        (self.source / ".git").mkdir()
        with self.assertRaises(pub.Denied):
            self.publish(expected_tree=self.baseline_source_tree)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertTrue(any(r.endswith("/branches/main")
                            for _, r, _ in self.route_calls),
                        "publisher admission path did not run")


def load_tests(loader, standard_tests, pattern):
    """Always include the separate production merged-main regression module.

    The CI self-test workflow executes this entrypoint directly. Without this
    hook, adding test-private-ci-merged-main.py is insufficient to run it.
    """
    spec = importlib.util.spec_from_file_location(
        "merged_main_regressions",
        Path(__file__).with_name("test-private-ci-merged-main.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    standard_tests.addTests(loader.loadTestsFromModule(module))
    return standard_tests


if __name__ == "__main__":
    unittest.main(verbosity=2)
