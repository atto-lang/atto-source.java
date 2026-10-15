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
        self.approval.write_text("trusted/ci 123\n")
        approved_bytes = self.approval.read_bytes()
        self.protected_policy_sha = hashlib.sha1(
            b"blob " + str(len(approved_bytes)).encode("ascii")
            + b"\0" + approved_bytes
        ).hexdigest()
        (self.source / "hello.ores").write_text("pub fnc main(): int { return 42; }\n")
        self.expected_tree = pub.source_tree_sha(pub.collect_source(self.source, self.root))
        self.remote_source_commit = D
        self.remote_source_tree = None
        self.route_calls = []
        self.source_identity = ["ores-truffle-oreslang/oreslang-source.java"] * 100
        self.source_ids = [1400792274] * 100
        self.source_private = [True] * 100
        self.source_visibility = ["private"] * 100
        self.source_protected = [True] * 100
        self.protection_rules = {
            "required_pull_request_reviews": {
                "required_approving_review_count": 1,
                "dismiss_stale_reviews": True,
                "bypass_pull_request_allowances": {"users": [], "teams": [], "apps": []}},
            "required_status_checks": {"strict": True, "contexts": ["ci/test"]},
            "enforce_admins": {"enabled": True},
            "allow_force_pushes": {"enabled": False},
            "allow_deletions": {"enabled": False},
        }
        self.visibility = ["private"] * 100
        self.repository_ids = [123] * 100
        self.archived = [False] * 100
        self.disabled = [False] * 100
        self.preflight = patch.object(pub, "require_preflight", return_value=123)
        self.preflight.start()
        self.addCleanup(self.preflight.stop)

    def fake_api(self, route, method="GET", payload=None):
        self.route_calls.append((method, route, payload))
        if route == pub.SOURCE_METADATA_ROUTE:
            return dict(full_name=self.source_identity.pop(0),
                        id=self.source_ids.pop(0), private=self.source_private.pop(0),
                        visibility=self.source_visibility.pop(0), archived=False,
                        disabled=False, default_branch="main")
        if route == pub.PROTECTED_BRANCH_ROUTE:
            return dict(name="main", protected=self.source_protected.pop(0),
                        commit={"sha": A})
        if route == pub.SOURCE_PROTECTION_ROUTE:
            return self.protection_rules
        if route == pub.PROTECTED_POLICY_ROUTE:
            return {"type": "file", "sha": self.protected_policy_sha}
        if route == f"repos/{pub.SOURCE_REPOSITORY}/git/commits/" + D:
            return {"sha": self.remote_source_commit,
                    "tree": {"sha": self.expected_tree if self.remote_source_tree is None
                             else self.remote_source_tree}}
        if route == "repos/trusted/ci":
            return dict(full_name="trusted/ci", visibility=self.visibility.pop(0),
                        private=True, default_branch="main",
                        id=self.repository_ids.pop(0),
                        archived=self.archived.pop(0),
                        disabled=self.disabled.pop(0))
        if route == "repos/trusted/ci/git/blobs":
            raw = base64.b64decode(payload["content"])
            return {"sha": hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                                        + bytes([0]) + raw).hexdigest()}
        if route == "repos/trusted/ci/git/trees":
            # A fresh file tree and its parent root tree must be distinct.
            return {"sha": E if "base_tree" in payload else self.expected_tree}
        mapping = {
            "repos/trusted/ci/git/ref/heads/main": {"object": {"sha": A}},
            "repos/trusted/ci/git/commits/"+A: {"tree": {"sha": B}},
            "repos/trusted/ci/git/commits": {"sha": C},
            "repos/trusted/ci/git/commits/"+C: {"tree": {"sha": E}},
            "repos/trusted/ci/git/refs": {
                "ref": "refs/heads/ci/oreslang-test", "object": {"sha": C}},
            "repos/trusted/ci/git/ref/heads/ci/oreslang-test": {
                "object": {"sha": C}},
            "repos/trusted/ci/git/trees/"+E: {
                "truncated": False,
                "tree": [{"path": pub.PREFIX, "mode": "040000",
                          "type": "tree", "sha": self.expected_tree}]},
        }
        if route not in mapping:
            raise AssertionError("unexpected or cross-repository GitHub API route " + route)
        return mapping[route]

    def publish(self, repo="trusted/ci", branch="ci/oreslang-test"):
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            return pub.publish(repo, self.source, branch, self.root, self.expected_tree, D)

    def test_pinned_immutable_source_commit_is_authenticated_before_any_source_write(self):
        self.remote_source_commit = E
        with self.assertRaisesRegex(pub.Denied, "source commit identity"):
            self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_mismatched_remote_commit_tree_denies_before_source_read_or_upload(self):
        self.remote_source_tree = E
        with patch.object(pub, "collect_source", side_effect=AssertionError(
                "source must not be read without independently verified tree")):
            with self.assertRaisesRegex(pub.Denied, "source commit tree"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_commit_revalidation_denies_on_remote_tree_flip_before_first_write(self):
        original = self.fake_api
        first = [True]
        def commit_flip(route, method="GET", payload=None):
            if route == f"repos/{pub.SOURCE_REPOSITORY}/git/commits/" + D:
                if first[0]:
                    first[0] = False
                    return original(route, method, payload)
                self.remote_source_tree = E
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=commit_flip):
            with self.assertRaisesRegex(pub.Denied, "source commit tree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_invalid_source_commit_sha_denies_before_metadata_or_file_read(self):
        for bad in (None, "", "A" * 40, "g" * 40, "0" * 39):
            with self.subTest(bad=bad):
                self.route_calls.clear()
                with self.assertRaises(pub.Denied):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, self.expected_tree, bad)
                self.assertEqual(self.route_calls, [])

    def test_unsafe_merge_admission_rules_block_all_source_writes(self):
        import copy
        invalid = [
            ("required_pull_request_reviews", None),
            ("required_pull_request_reviews", {
                "required_approving_review_count": 0,
                "dismiss_stale_reviews": True}),
            ("required_pull_request_reviews", {
                "required_approving_review_count": 1,
                "dismiss_stale_reviews": False}),
            ("required_status_checks", {"strict": True, "contexts": []}),
            ("required_status_checks", {"strict": False, "contexts": ["ci/test"]}),
            ("enforce_admins", {"enabled": False}),
            ("allow_force_pushes", {"enabled": True}),
            ("allow_deletions", {"enabled": True}),
        ]
        for key, value in invalid:
            with self.subTest(key=key, value=value):
                existing = self.protection_rules
                self.protection_rules = copy.deepcopy(existing)
                self.protection_rules[key] = value
                self.route_calls.clear()
                try:
                    with self.assertRaises(pub.Denied):
                        self.publish()
                    self.assertFalse(any(method == "POST"
                                         for method, _, _ in self.route_calls))
                finally:
                    self.protection_rules = existing

    def test_review_bypass_is_never_transfer_authorization(self):
        import copy
        for kind in ("users", "teams", "apps"):
            with self.subTest(kind=kind):
                existing = self.protection_rules
                self.protection_rules = copy.deepcopy(existing)
                self.protection_rules["required_pull_request_reviews"][
                    "bypass_pull_request_allowances"][kind] = [{"login": "bypass"}]
                self.route_calls.clear()
                try:
                    with self.assertRaisesRegex(pub.Denied, "review bypass"):
                        self.publish()
                    self.assertFalse(any(method == "POST"
                                         for method, _, _ in self.route_calls))
                finally:
                    self.protection_rules = existing

    def test_unprotected_source_main_denies_without_source_write(self):
        self.source_protected = [False] * 100
        with self.assertRaisesRegex(pub.Denied, "main branch is not protected"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_identity_and_visibility_denied_before_any_write(self):
        cases = (
            ("source_identity", "attacker/oreslang"),
            ("source_ids", 1400792275),
            ("source_ids", True),
            ("source_private", False),
            ("source_visibility", "public"),
        )
        for attribute, value in cases:
            with self.subTest(attribute=attribute, value=value):
                self.route_calls.clear()
                original = getattr(self, attribute)
                setattr(self, attribute, [value] * 100)
                try:
                    with self.assertRaisesRegex(pub.Denied, "source repository identity"):
                        self.publish()
                    self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
                finally:
                    setattr(self, attribute, original)

    def test_source_branch_protection_revoked_before_first_git_write(self):
        self.source_protected = [True, False] + [False] * 98
        with self.assertRaisesRegex(pub.Denied, "main branch is not protected"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_only_exact_private_repo_write_routes_and_new_ref(self):
        sha, count = self.publish()
        self.assertEqual((sha, count), (C, 1))
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
             "type": "tree", "sha": self.expected_tree}])
        self.assertEqual(writes[2][2]["base_tree"], B)
        self.assertEqual(writes[3][2]["tree"], E)
        self.assertTrue(all(r.startswith("repos/trusted/ci") for _, r, _ in writes))

    def test_post_readback_public_visibility_denies_success(self):
        original = self.fake_api
        def go_public_after_root_readback(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/trees/" + E:
                self.visibility[0] = "public"
            return result
        with patch.object(pub, "gh_api", side_effect=go_public_after_root_readback):
            with self.assertRaisesRegex(pub.Denied, "private visibility"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)
        self.assertTrue(any(m == "POST" and r.endswith("/git/refs")
                            for m, r, _ in self.route_calls))

    def test_post_readback_protected_policy_revocation_denies_success(self):
        original = self.fake_api
        def revoke_policy_after_root_readback(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/trees/" + E:
                self.protected_policy_sha = "f" * 40
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_policy_after_root_readback):
            with self.assertRaisesRegex(pub.Denied, "not approved on protected source main"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)
        self.assertTrue(any(m == "POST" and r.endswith("/git/refs")
                            for m, r, _ in self.route_calls))

    def test_malformed_nested_git_metadata_is_denied_not_crash(self):
        bad_routes = (
            "repos/trusted/ci/git/ref/heads/main",
            "repos/trusted/ci/git/commits/" + A,
            "repos/trusted/ci/git/refs",
            "repos/trusted/ci/git/ref/heads/ci/oreslang-test",
            "repos/trusted/ci/git/commits/" + C,
        )
        original = self.fake_api
        for route in bad_routes:
            with self.subTest(route=route):
                self.route_calls.clear()
                def invalid_shape(request, method="GET", payload=None):
                    if request == route:
                        self.route_calls.append((method, request, payload))
                        response = original(request, method, payload)
                        member = "tree" if "/git/commits/" in request else "object"
                        return dict(response, **{member: None})
                    return original(request, method, payload)
                with patch.object(pub, "gh_api", side_effect=invalid_shape):
                    with self.assertRaisesRegex(pub.Denied, "malformed object metadata"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.expected_tree, D)

    def test_created_branch_confirmation_must_point_to_commit(self):
        original = self.fake_api
        def changed_ref(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/refs":
                self.route_calls.append((method, route, payload))
                return {"ref": "refs/heads/ci/oreslang-test",
                        "object": {"sha": A}}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=changed_ref):
            with self.assertRaisesRegex(pub.Denied, "created CI branch"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)

    def test_branch_readback_must_point_to_created_commit(self):
        original = self.fake_api
        def changed_ref(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/ref/heads/ci/oreslang-test":
                self.route_calls.append((method, route, payload))
                return {"object": {"sha": A}}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=changed_ref):
            with self.assertRaisesRegex(pub.Denied, "branch readback"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)

    def test_branch_commit_readback_must_match_published_tree(self):
        original = self.fake_api
        def wrong_commit_tree(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/commits/" + C:
                self.route_calls.append((method, route, payload))
                return {"tree": {"sha": B}}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong_commit_tree):
            with self.assertRaisesRegex(pub.Denied, "commit tree differs"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)

    def test_committed_subtree_readback_must_match_pinned_source(self):
        original = self.fake_api
        def wrong_root_tree(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/trees/" + E:
                self.route_calls.append((method, route, payload))
                return {"truncated": False,
                        "tree": [{"path": pub.PREFIX, "mode": "040000",
                                  "type": "tree", "sha": B}]}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong_root_tree):
            with self.assertRaisesRegex(pub.Denied, "committed CI source subtree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)

    def test_truncated_or_unknown_git_tree_readback_is_denied(self):
        original = self.fake_api
        for truncated in (True, None, "false"):
            with self.subTest(truncated=truncated):
                self.route_calls.clear()
                def incomplete_tree(route, method="GET", payload=None):
                    if route == "repos/trusted/ci/git/trees/" + E:
                        self.route_calls.append((method, route, payload))
                        return {"truncated": truncated,
                                "tree": [{"path": pub.PREFIX, "mode": "040000",
                                          "type": "tree", "sha": self.expected_tree}]}
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=incomplete_tree):
                    with self.assertRaisesRegex(pub.Denied, "committed CI source subtree"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root, self.expected_tree, D)

    def test_committed_subtree_must_not_have_duplicate_entries(self):
        original = self.fake_api
        def ambiguous_root_tree(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/trees/" + E:
                self.route_calls.append((method, route, payload))
                item = {"path": pub.PREFIX, "mode": "040000", "type": "tree",
                        "sha": self.expected_tree}
                return {"truncated": False, "tree": [item, dict(item)]}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=ambiguous_root_tree):
            with self.assertRaisesRegex(pub.Denied, "committed CI source subtree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, self.expected_tree, D)

    def test_existing_stale_files_cannot_survive_clean_source_snapshot(self):
        # Assume base_tree contains a stale private-oreslang-source/secret.ores.
        # A new isolated source tree MUST NOT be based on that default tree.
        (self.source / "current.ores").write_text("new source")
        self.expected_tree = pub.source_tree_sha(pub.collect_source(self.source, self.root))
        self.publish()
        tree_writes = [payload for method, route, payload in self.route_calls
                       if method == "POST" and route.endswith("/git/trees")]
        self.assertEqual(len(tree_writes), 2)
        self.assertNotIn("base_tree", tree_writes[0])
        self.assertEqual([item["path"] for item in tree_writes[0]["tree"]],
                         ["current.ores", "hello.ores"])
        self.assertEqual(tree_writes[1]["tree"], [
            {"path": pub.PREFIX, "mode": "040000", "type": "tree", "sha": self.expected_tree}])
        self.assertEqual(tree_writes[1]["base_tree"], B)

    def test_git_tree_sha_matches_known_git_write_tree_result(self):
        # Verified independently with git init, git add, git write-tree
        # (main.ores contains hello\n; nested/file.ores contains world\n
        # and has executable mode 100755).
        files = [
            ("main.ores", b"hello\n", "100644"),
            ("nested/file.ores", b"world\n", "100755"),
        ]
        self.assertEqual(pub.source_tree_sha(files),
                         "6440ebf7d6ce11004a61c53fde672c98a19e1e52")

    def test_invalid_expected_tree_rejected_before_preflight_or_upload(self):
        for invalid in (None, "", "0" * 39, "g" * 40, "A" * 40):
            with self.subTest(invalid=invalid):
                self.route_calls.clear()
                with self.assertRaises(pub.Denied):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                self.root, invalid, D)
                self.assertEqual(self.route_calls, [])

    def test_wrong_pinned_tree_rejects_before_any_source_upload(self):
        self.expected_tree = "f" * 40
        with self.assertRaisesRegex(pub.Denied, "source snapshot Git tree"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_wrong_destination_source_tree_rejects_before_commit(self):
        original = self.fake_api
        def wrong_tree(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/trees" and "base_tree" not in payload:
                self.route_calls.append((method, route, payload))
                return {"sha": D}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong_tree):
            with self.assertRaisesRegex(pub.Denied, "destination source subtree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root,
                            self.expected_tree, D)
        self.assertFalse(any(r.endswith("/git/commits") or r.endswith("/git/refs")
                             for m, r, _ in self.route_calls if m == "POST"))

    def test_git_tree_hash_is_sensitive_to_file_mode_and_nested_paths(self):
        baseline = self.expected_tree
        nested = self.source / "nested"
        nested.mkdir()
        child = nested / "child.ores"
        child.write_text("hello")
        files = pub.collect_source(self.source, self.root)
        nested_hash = pub.source_tree_sha(files)
        self.assertNotEqual(baseline, nested_hash)
        child.chmod(0o755)
        changed_mode_hash = pub.source_tree_sha(pub.collect_source(self.source, self.root))
        self.assertNotEqual(nested_hash, changed_mode_hash)

    def test_unmerged_branch_policy_cannot_authorize_source_export(self):
        # Local PR checkout contains an approval, but protected source main
        # does not. No source bytes may be sent.
        self.protected_policy_sha = "f" * 40
        with self.assertRaisesRegex(pub.Denied, "not approved on protected source main"):
            self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_missing_or_malformed_protected_main_metadata_is_denied(self):
        original = self.fake_api
        for wrong in ({}, {"type": "dir", "sha": self.protected_policy_sha},
                      {"type": "file", "sha": None}):
            with self.subTest(wrong=wrong):
                self.route_calls.clear()
                def untrusted_main(route, method="GET", payload=None):
                    if route == pub.PROTECTED_POLICY_ROUTE:
                        self.route_calls.append((method, route, payload))
                        return wrong
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=untrusted_main):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, self.expected_tree, D)
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_protected_main_revocation_blocks_next_git_write(self):
        original = self.fake_api
        def revoke_main_after_first_blob(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.protected_policy_sha = "f" * 40
            return response
        with patch.object(pub, "gh_api", side_effect=revoke_main_after_first_blob):
            with self.assertRaisesRegex(pub.Denied, "not approved on protected source main"):
                pub.publish("trusted/ci", self.source,
                            "ci/oreslang-test", self.root, self.expected_tree, D)
        self.assertEqual([route for method, route, _ in self.route_calls if method == "POST"],
                         ["repos/trusted/ci/git/blobs"])

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


    def test_missing_invalid_and_replaced_numeric_identity_fail_closed(self):
        for invalid in (None, 0, -1, True, "123"):
            with self.subTest(invalid=invalid):
                self.route_calls.clear()
                self.repository_ids = [invalid] + [123] * 10
                with self.assertRaisesRegex(pub.Denied, "numeric repository identity"):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_preflight_repository_identity_pin_cannot_change_before_source_publish(self):
        # Simulate a valid earlier authorization for a different numeric ID.
        with patch.object(pub, "require_preflight", return_value=321):
            with self.assertRaisesRegex(pub.Denied, "numeric ID differs from reviewed approval policy"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_missing_archived_or_disabled_status_is_not_treated_as_active(self):
        for state in ("archived", "disabled"):
            with self.subTest(state=state):
                self.route_calls.clear()
                self.repository_ids = [123] * 10
                self.visibility = ["private"] * 10
                self.archived = [None if state == "archived" else False] + [False] * 9
                self.disabled = [None if state == "disabled" else False] + [False] * 9
                with self.assertRaisesRegex(pub.Denied, "archived, disabled"):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_private_repo_replacement_during_transfer_aborts_before_next_write(self):
        self.repository_ids = [123, 123, 456] + [123] * 10
        with self.assertRaisesRegex(pub.Denied, "numeric repository identity"):
            self.publish()
        self.assertEqual([r for m, r, _ in self.route_calls if m == "POST"],
                         ["repos/trusted/ci/git/blobs"])

    def test_initial_archived_or_disabled_private_destination_fails_closed(self):
        for state in ("archived", "disabled"):
            with self.subTest(state=state):
                self.route_calls.clear()
                self.visibility = ["private"] * 10
                self.repository_ids = [123] * 10
                self.archived = [state == "archived"] + [False] * 9
                self.disabled = [state == "disabled"] + [False] * 9
                with self.assertRaisesRegex(pub.Denied, "archived, disabled"):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_repository_archive_or_disable_during_transfer_aborts(self):
        for state in ("archived", "disabled"):
            with self.subTest(state=state):
                self.route_calls.clear()
                self.visibility = ["private"] * 10
                self.repository_ids = [123] * 10
                self.archived = [False, False, state == "archived"] + [False] * 7
                self.disabled = [False, False, state == "disabled"] + [False] * 7
                with self.assertRaisesRegex(pub.Denied, "archived, disabled"):
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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, self.expected_tree, D)
        writes = [r for m, r, _ in self.route_calls if m == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_duplicate_policy_entries_fail_before_any_source_upload(self):
        for duplicate in ("trusted/ci 123", "TRUSTED/CI 123", "other/ci 123"):
            with self.subTest(duplicate=duplicate):
                self.route_calls.clear()
                self.approval.write_text("trusted/ci 123\n" + duplicate + "\n")
                with self.assertRaisesRegex(pub.Denied, "duplicate repository names or IDs"):
                    self.publish()
                self.assertFalse(any(method == "POST"
                                     for method, _, _ in self.route_calls))

    def test_policy_numeric_identity_mismatch_blocks_before_source_read(self):
        # A name-only review cannot authorize a recreated repository.
        self.approval.write_text("trusted/ci 456\n")
        with self.assertRaisesRegex(pub.Denied, "numeric ID differs from reviewed approval policy"):
            self.publish()
        self.assertEqual(self.route_calls, [])

    def test_name_only_or_malformed_numeric_policy_never_authorizes(self):
        for malformed in (
            "trusted/ci\n", "trusted/ci 0\n", "trusted/ci -1\n",
            "trusted/ci 0123\n", "trusted/ci\t123\n",
            "trusted/ci 123 # misleading comment\n", "trusted/ci 123 \n"
        ):
            with self.subTest(malformed=malformed):
                self.approval.write_text(malformed)
                self.route_calls.clear()
                with self.assertRaisesRegex(pub.Denied, "numeric repository ID"):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

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
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_symlinked_directory_rejected_before_upload(self):
        (self.source / "linked").symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_nested_regular_source_files_keep_expected_paths(self):
        directory = self.source / "nested"
        directory.mkdir()
        (directory / "inside.ores").write_text("safe")
        self.expected_tree = pub.source_tree_sha(pub.collect_source(self.source, self.root))
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
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

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
                self.publish()
        self.assertTrue(swapped[0])
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

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
                self.publish()
        self.assertTrue(swapped[0])
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_directory_entry_added_during_snapshot_is_rejected(self):
        # Even a new regular file that was not in the first scandir listing
        # must invalidate the snapshot rather than being silently omitted.
        raw_open = pub.os.open
        changed = [False]

        def add_file_during_read(name, flags, *args, **kwargs):
            if name == "hello.ores" and kwargs.get("dir_fd") is not None and not changed[0]:
                changed[0] = True
                (self.source / "injected.ores").write_text("added after directory scan")
            return raw_open(name, flags, *args, **kwargs)

        with patch.object(pub.os, "open", side_effect=add_file_during_read):
            with self.assertRaisesRegex(pub.Denied, "source directory changed"):
                self.publish()
        self.assertTrue(changed[0])
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_directory_replacement_between_stat_and_open_is_rejected(self):
        # A non-symlink directory swap must not redirect reads to a new inode.
        nested = self.source / "nested"
        nested.mkdir()
        (nested / "original.ores").write_text("original")
        replacement = self.root / "replacement-dir"
        replacement.mkdir()
        (replacement / "other.ores").write_text("different")
        moved = self.root / "moved-original-dir"
        raw_open = pub.os.open
        swapped = [False]

        def replace_directory(name, flags, *args, **kwargs):
            if name == "nested" and kwargs.get("dir_fd") is not None and not swapped[0]:
                swapped[0] = True
                nested.rename(moved)
                replacement.rename(nested)
            return raw_open(name, flags, *args, **kwargs)

        with patch.object(pub.os, "open", side_effect=replace_directory):
            with self.assertRaisesRegex(pub.Denied, "source directory changed"):
                self.publish()
        self.assertTrue(swapped[0])
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_regular_file_replacement_between_stat_and_open_is_rejected(self):
        # A regular-to-regular rename used to evade the previous type check:
        # the descriptor must match the exact inode seen by os.stat.
        original = self.source / "hello.ores"
        alternate = self.root / "replacement.ores"
        alternate.write_text("different private source")
        raw_open = pub.os.open
        swapped = [False]

        def replace_before_open(name, flags, *args, **kwargs):
            if name == "hello.ores" and kwargs.get("dir_fd") is not None and not swapped[0]:
                swapped[0] = True
                alternate.replace(original)
            return raw_open(name, flags, *args, **kwargs)

        with patch.object(pub.os, "open", side_effect=replace_before_open):
            with self.assertRaisesRegex(pub.Denied, "source file changed"):
                self.publish()
        self.assertTrue(swapped[0])
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_same_inode_mutation_between_stat_and_open_is_rejected(self):
        # The original inode may be rewritten in place before it is opened;
        # compare size/mtime/ctime and mode, not only dev+inode.
        original = self.source / "hello.ores"
        raw_open = pub.os.open
        changed = [False]

        def mutate_before_open(name, flags, *args, **kwargs):
            if name == "hello.ores" and kwargs.get("dir_fd") is not None and not changed[0]:
                changed[0] = True
                original.write_text("same inode, different and longer source bytes")
            return raw_open(name, flags, *args, **kwargs)

        with patch.object(pub.os, "open", side_effect=mutate_before_open):
            with self.assertRaisesRegex(pub.Denied, "source file changed"):
                self.publish()
        self.assertTrue(changed[0])
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_file_mutation_during_snapshot_is_rejected_before_upload(self):
        # Wrap the opened source reader and mutate the same inode after read;
        # size or nanosecond timestamps must not silently change.
        raw_fdopen = pub.os.fdopen
        source = self.source / "hello.ores"
        tampered = [False]

        class MutatingReader:
            def __init__(self, wrapped):
                self.wrapped = wrapped
            def __enter__(self):
                return self
            def __exit__(self, typ, value, trace):
                return self.wrapped.__exit__(typ, value, trace)
            def fileno(self):
                return self.wrapped.fileno()
            def read(self, size=-1):
                data = self.wrapped.read(size)
                source.write_text("changed while snapshotting with more content")
                tampered[0] = True
                return data

        def wrap_source_reader(fd, mode, *args, **kwargs):
            stream = raw_fdopen(fd, mode, *args, **kwargs)
            # The approval policy is also opened with fdopen; only the
            # source test file is intentionally mutated.
            import os
            if (Path("/proc/self/fd").is_dir()
                    and os.readlink("/proc/self/fd/" + str(fd)) == str(source)):
                return MutatingReader(stream)
            return stream

        if not Path("/proc/self/fd").is_dir():
            self.skipTest("requires Linux /proc/self/fd for exact source-fd identification")
        with patch.object(pub.os, "fdopen", side_effect=wrap_source_reader):
            with self.assertRaisesRegex(pub.Denied, "changed while snapshotting"):
                self.publish()
        self.assertTrue(tampered[0])
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_hardlink_to_outside_file_fails_before_upload(self):
        sensitive = self.root / "outside-secret"
        sensitive.write_text("secret")
        (self.source / "hardlink.ores").hardlink_to(sensitive)
        with self.assertRaisesRegex(pub.Denied, "hardlinked"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_nonregular_source_fifo_fails_before_upload(self):
        import os
        import stat
        fifo = self.source / "suspicious-fifo"
        os.mkfifo(fifo)
        self.assertTrue(stat.S_ISFIFO(fifo.stat().st_mode))
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_policy_symlink_swap_between_check_and_read_rejected(self):
        outside = self.root / "outside-approved-policy"
        outside.write_text("trusted/ci 123\n")
        self.approval.write_text("# no approval\n")
        original_read = Path.read_bytes
        def malicious_read(path):
            if path == self.approval:
                saved = original_read(path)
                path.unlink()
                path.symlink_to(outside)
                data = original_read(path)
                path.unlink()
                path.write_bytes(saved)
                return data
            return original_read(path)
        with patch.object(Path, "read_bytes", malicious_read):
            with self.assertRaises(pub.Denied):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_policy_parent_directory_swap_rejected(self):
        outside = self.root / "external-approved-dir"
        outside.mkdir()
        (outside / "private-ci-approved-repositories.txt").write_text("trusted/ci 123\n")
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
        outside.write_text("trusted/ci 123\n")
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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, self.expected_tree, D)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_valid_looking_but_incorrect_blob_sha_is_rejected(self):
        original = self.fake_api
        def wrong(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/blobs":
                return {"sha": C}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong):
            with self.assertRaisesRegex(pub.Denied, "digest"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, self.expected_tree, D)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_source_must_be_under_trusted_checkout(self):
        outside = self.root.parent / ("unsafe-" + self.root.name)
        outside.mkdir()
        try:
            (outside / "outside.ores").write_text("private data")
            with patch.object(pub, "gh_api", side_effect=self.fake_api):
                with self.assertRaisesRegex(pub.Denied, "trusted checkout"):
                    pub.publish("trusted/ci", outside, "ci/oreslang-test", self.root, self.expected_tree, D)
        finally:
            (outside / "outside.ores").unlink()
            outside.rmdir()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_denied_preflight_halts_before_api(self):
        with patch.object(pub, "require_preflight", side_effect=pub.Denied("not approved")):
            with self.assertRaisesRegex(pub.Denied, "not approved"):
                self.publish()
        self.assertEqual(self.route_calls, [])

    def test_oversized_source_file_rejected(self):
        (self.source / "large").write_bytes(b"x" * (pub.MAX_SINGLE + 1))
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_undecodable_source_name_denied_before_git_writes(self):
        import os
        if os.name != "posix":
            self.skipTest("requires POSIX raw-byte file names")
        encoded_dir = os.fsencode(self.source)
        bad_file = os.path.join(encoded_dir, b"bad-\xff.ores")
        fd = os.open(bad_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        with self.assertRaisesRegex(pub.Denied, "non-UTF-8 name"):
            self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_git_metadata_directory_rejected(self):
        (self.source / ".git").mkdir()
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
