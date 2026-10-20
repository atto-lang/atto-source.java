#!/usr/bin/env python3
"""Offline adversarial tests for the private destination-bound publisher."""
import importlib.util
import base64
import hashlib
import re
import subprocess
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
        (self.source / "hello.ores").write_text("pub fnc main(): int { return 42; }\n")
        self.protected_policy = b"trusted/ci\n"
        self.main_protected = True
        self.main_sha = A
        self.protection_rules = {
            "required_pull_request_reviews": {
                "require_code_owner_reviews": True,
                "dismiss_stale_reviews": True,
                "required_approving_review_count": 1},
            "required_status_checks": {"strict": True,
                                       "contexts": ["preflight-regressions"],
                                       "checks": [{"context": "preflight-regressions",
                                                   "app_id": pub.REQUIRED_PRIVATE_CI_APP_ID}]},
            "enforce_admins": {"enabled": True},
            "allow_force_pushes": {"enabled": False},
            "allow_deletions": {"enabled": False}}
        self.route_calls = []
        self.visibility = ["private"] * 200
        self.destination_ids = [123456789] * 200
        self.source_ids = [pub.SOURCE_REPOSITORY_ID] * 200
        # Unit-test-only identity. The production pinned destination list is
        # unchanged and cannot authorize this synthetic trusted/ci carrier.
        pinned = patch.dict(pub.PINNED_DESTINATION_IDS, {"trusted/ci": 123456789})
        pinned.start()
        self.addCleanup(pinned.stop)
        self.real_preflight = pub.require_preflight
        self.preflight = patch.object(pub, "require_preflight")
        self.preflight.start()
        self.addCleanup(self.preflight.stop)
        # Earlier identity/policy tests use a synthetic, uncommitted fixture.
        # Keep the existing write-contract tests independent of Git checkout,
        # and separately exercise the REAL exact-tree provenance gate below.
        self.real_tree_gate = pub.require_source_tree_match
        self.tree_gate = patch.object(pub, "require_source_tree_match", return_value=(A, D))
        self.tree_gate.start()
        self.addCleanup(self.tree_gate.stop)

    def fake_api(self, route, method="GET", payload=None):
        self.route_calls.append((method, route, payload))
        if route == "repos/" + pub.SOURCE_REPOSITORY + "/git/commits/" + A:
            return dict(sha=A, tree={"sha": D})
        if route == "repos/" + pub.SOURCE_REPOSITORY:
            return dict(id=self.source_ids.pop(0),
                        full_name=pub.SOURCE_REPOSITORY, visibility="private",
                        private=True, archived=False, disabled=False)
        if route == "repos/" + pub.SOURCE_REPOSITORY + "/branches/main":
            return {"protected": self.main_protected,
                    "commit": {"sha": self.main_sha}}
        if route == "repos/" + pub.SOURCE_REPOSITORY + "/branches/main/protection":
            return self.protection_rules
        if (route == pub.PROTECTED_POLICY_ROUTE or
                route == pub.PROTECTED_POLICY_ROUTE.replace(
                    "?ref=main", "?ref=" + self.main_sha)):
            raw = self.protected_policy
            return dict(type="file", encoding="base64", size=len(raw),
                        content=base64.b64encode(raw).decode("ascii"),
                        sha=hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                                         + bytes([0]) + raw).hexdigest())
        if route == "repos/trusted/ci":
            return dict(id=self.destination_ids.pop(0),
                        full_name="trusted/ci", visibility=self.visibility.pop(0),
                        private=True, default_branch="main",
                        archived=False, disabled=False)
        if route == "repos/trusted/ci/git/blobs":
            raw = base64.b64decode(payload["content"])
            return {"sha": hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                                        + bytes([0]) + raw).hexdigest()}
        if route == "repos/trusted/ci/git/trees":
            # A fresh file tree and its parent root tree must be distinct.
            return {"sha": E if "base_tree" in payload else D}
        mapping = {
            "repos/trusted/ci/git/ref/heads/main": {"object": {"sha": A}},
            "repos/trusted/ci/git/trees/"+E: {
                "sha": E, "truncated": False, "tree": [
                    {"path": pub.PREFIX, "mode": "040000", "type": "tree",
                     "sha": D}]},
            # The synthetic fixture reuses A for existing test compatibility.
            # Distinguish each publish's main-commit lookup from its new
            # commit readback, even when one test invokes publish repeatedly.
            "repos/trusted/ci/git/commits/"+A: (
                {"sha": A, "tree": {"sha": E}, "parents": [{"sha": A}]}
                if max((i for i, (m, r, _) in enumerate(self.route_calls)
                        if m == "POST" and r == "repos/trusted/ci/git/commits"),
                       default=-1)
                   > max((i for i, (m, r, _) in enumerate(self.route_calls)
                          if m == "GET" and r == "repos/trusted/ci/git/ref/heads/main"),
                         default=-1)
                else {"tree": {"sha": B}}),
            "repos/trusted/ci/git/commits": {"sha": A},
            "repos/trusted/ci/git/refs": {
                "ref": "refs/heads/ci/oreslang-test",
                "object": {"type": "commit", "sha": A}},
            "repos/trusted/ci/git/ref/heads/ci/oreslang-test": {
                "ref": "refs/heads/ci/oreslang-test",
                "object": {"type": "commit", "sha": A}},
        }
        if route not in mapping:
            raise AssertionError("unexpected or cross-repository GitHub API route " + route)
        return mapping[route]

    def publish(self, repo="trusted/ci", branch="ci/oreslang-test"):
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            return pub.publish(repo, self.source, branch, self.root, A, D)

    def test_root_tree_attestation_rejects_wrong_or_ambiguous_prefix(self):
        correct = {"sha": E, "truncated": False,
                   "tree": [{"path": pub.PREFIX, "mode": "040000",
                             "type": "tree", "sha": D}]}
        with patch.object(pub, "gh_api", return_value=correct) as api:
            pub.verify_private_ci_root_tree("trusted/ci", E, D)
            api.assert_called_once_with("repos/trusted/ci/git/trees/" + E)
        malformed = [
            {},
            {"sha": A, "truncated": False, "tree": correct["tree"]},
            {"sha": E, "truncated": True, "tree": correct["tree"]},
            {"sha": E, "truncated": False, "tree": None},
            {"sha": E, "truncated": False, "tree": []},
            {"sha": E, "truncated": False, "tree": [
                {"path": pub.PREFIX, "mode": "100644", "type": "blob", "sha": D}]},
            {"sha": E, "truncated": False, "tree": [
                {"path": pub.PREFIX, "mode": "040000", "type": "tree", "sha": B}]},
            {"sha": E, "truncated": False, "tree": correct["tree"] * 2},
            {"sha": E, "truncated": False, "tree": [
                {"path": "not-the-source", "mode": "040000", "type": "tree",
                 "sha": D}]},
        ]
        for malformed_reply in malformed:
            with self.subTest(reply=malformed_reply):
                with patch.object(pub, "gh_api", return_value=malformed_reply):
                    with self.assertRaises(pub.Denied):
                        pub.verify_private_ci_root_tree("trusted/ci", E, D)

    def test_wrong_destination_root_tree_blocks_commit_and_ref(self):
        original = self.fake_api
        def substituted(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/trees/" + E:
                self.route_calls.append((method, route, payload))
                return {"sha": E, "truncated": False,
                        "tree": [{"path": pub.PREFIX, "mode": "040000",
                                  "type": "tree", "sha": B}]}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=substituted):
            with self.assertRaisesRegex(pub.Denied, "root tree missing exact"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, A, D)
        writes = [route for method, route, _ in self.route_calls
                  if method == "POST"]
        self.assertEqual(writes, [
            "repos/trusted/ci/git/blobs", "repos/trusted/ci/git/trees",
            "repos/trusted/ci/git/trees"])

    def test_destination_commit_attestation_requires_exact_tree_and_parent(self):
        route = "repos/trusted/ci/git/commits/" + A
        correct = {"sha": A, "tree": {"sha": E},
                   "parents": [{"sha": A}]}
        malformed = [
            {},
            {"sha": B, "tree": {"sha": E}, "parents": [{"sha": A}]},
            {"sha": A, "tree": {"sha": D}, "parents": [{"sha": A}]},
            {"sha": A, "tree": {"sha": E}, "parents": [{"sha": B}]},
            {"sha": A, "tree": {"sha": E}, "parents": []},
            {"sha": A, "tree": {"sha": E}, "parents": [{"sha": A},
                                                     {"sha": B}]},
            {"sha": A, "tree": {"sha": E}, "parents": None},
            {"sha": A, "tree": {"sha": E}, "parents": [None]},
        ]
        with patch.object(pub, "gh_api", return_value=correct) as api:
            pub.verify_private_ci_commit("trusted/ci", A, E, A)
            api.assert_called_once_with(route)
        for invalid in malformed:
            with self.subTest(invalid=invalid):
                with patch.object(pub, "gh_api", return_value=invalid):
                    with self.assertRaisesRegex(pub.Denied, "commit tree or parent"):
                        pub.verify_private_ci_commit("trusted/ci", A, E, A)

    def test_destination_commit_swap_prevents_ci_branch_publication(self):
        original = self.fake_api
        def substituted(route, method="GET", payload=None):
            if (route == "repos/trusted/ci/git/commits/" + A
                    and any(m == "POST" and r == "repos/trusted/ci/git/commits"
                            for m, r, _ in self.route_calls)):
                self.route_calls.append((method, route, payload))
                return {"sha": A, "tree": {"sha": D},
                        "parents": [{"sha": A}]}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=substituted):
            with self.assertRaisesRegex(pub.Denied, "commit tree or parent"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, A, D)
        self.assertFalse(any(m == "POST" and r.endswith("/git/refs")
                             for m, r, _ in self.route_calls))

    def test_git_pointer_metadata_denied_before_any_git_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".git").write_text("untrusted redirect")
            (root / "source.ores").write_text("value")
            with self.assertRaisesRegex(pub.Denied, "real directory"):
                pub.collect_source(root, root, omit_checkout_metadata=True)
            with self.assertRaisesRegex(pub.Denied, "real Git metadata"):
                pub.require_source_tree_match(
                    root, root, [("source.ores", b"value", "100644")])

    def test_ref_create_acknowledgement_and_readback_both_required(self):
        ref = "refs/heads/ci/oreslang-test"
        good = {"ref": ref, "object": {"type": "commit", "sha": A}}
        invalid = [{"ref": ref}, {"ref": ref, "object": {"type": "commit", "sha": B}},
                   {"ref": ref, "object": {"type": "tree", "sha": A}},
                   {"ref": "refs/heads/main", "object": {"type": "commit", "sha": A}}]
        with patch.object(pub, "gh_api", return_value=good):
            pub.verify_private_ci_ref("trusted/ci", "ci/oreslang-test", A, good)
        for bad in invalid:
            with self.subTest(creation=bad):
                with patch.object(pub, "gh_api", side_effect=AssertionError("must deny early")):
                    with self.assertRaises(pub.Denied):
                        pub.verify_private_ci_ref("trusted/ci", "ci/oreslang-test", A, bad)
            with self.subTest(readback=bad):
                with patch.object(pub, "gh_api", return_value=bad):
                    with self.assertRaises(pub.Denied):
                        pub.verify_private_ci_ref("trusted/ci", "ci/oreslang-test", A, good)

    def test_ref_switched_between_write_and_readback_rejected(self):
        actual = self.fake_api
        def changed(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/ref/heads/ci/oreslang-test":
                self.route_calls.append((method, route, payload))
                return {"ref": "refs/heads/ci/oreslang-test",
                        "object": {"type": "commit", "sha": B}}
            return actual(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=changed):
            with self.assertRaisesRegex(pub.Denied, "readback"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, A, D)

    def test_explicit_commit_and_tree_inputs_are_required(self):
        for commit, tree in [(None, D), (A, None), ("bad", D),
                             (A, "bad"), (A.upper(), D)]:
            with self.subTest(commit=commit, tree=tree):
                self.route_calls.clear()
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaisesRegex(pub.Denied, "invalid object SHA"):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, commit, tree)
                self.assertFalse(any(method == "POST"
                                     for method, _, _ in self.route_calls))

    def test_expected_commit_tree_mismatch_aborts_before_source_export(self):
        for commit, tree in [(B, D), (A, C)]:
            with self.subTest(commit=commit, tree=tree):
                self.route_calls.clear()
                with patch.object(pub, "gh_api", side_effect=self.fake_api):
                    with self.assertRaisesRegex(pub.Denied, "explicitly reviewed"):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, commit, tree)
                self.assertFalse(any(method == "POST"
                                     for method, _, _ in self.route_calls))

    def test_protected_source_main_commit_identity_is_mandatory(self):
        for bad in (None, "", "not-a-SHA", "0"*39):
            with self.subTest(bad=bad):
                self.route_calls.clear()
                self.main_sha = bad
                with self.assertRaises(pub.Denied):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.main_sha = A

    def test_immutable_main_policy_must_agree_with_live_main(self):
        original = self.fake_api
        pinned = pub.PROTECTED_POLICY_ROUTE.replace("?ref=main", "?ref=" + A)
        def forged(route, method="GET", payload=None):
            if route == pinned:
                self.route_calls.append((method, route, payload))
                return {"type": "file", "encoding": "base64",
                        "content": base64.b64encode(b"# different approval\\n").decode(),
                        "size": len(b"# different approval\\n"),
                        "sha": "0"*40}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=forged):
            with self.assertRaises(pub.Denied):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, A, D)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_protected_main_ref_change_during_export_stops_new_ci_ref(self):
        original = self.fake_api
        def moved(route, method="GET", payload=None):
            response = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs" and method == "POST":
                self.main_sha = B
            return response
        with patch.object(pub, "gh_api", side_effect=moved):
            with self.assertRaisesRegex(pub.Denied, "protected source main changed"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, A, D)
        self.assertNotIn("repos/trusted/ci/git/refs",
                         [r for m, r, _ in self.route_calls if m == "POST"])

    def test_unprotected_source_main_cannot_authorize_export(self):
        self.main_protected = False
        with self.assertRaisesRegex(pub.Denied, "not protected"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_insufficient_source_main_review_controls_denied(self):
        invalid = [
            ("required_pull_request_reviews", None),
            ("required_status_checks", None),
            ("enforce_admins", None),
            ("allow_force_pushes", {"enabled": True}),
            ("allow_deletions", {"enabled": True}),
        ]
        for key, value in invalid:
            with self.subTest(key=key):
                self.route_calls.clear()
                original = self.protection_rules[key]
                self.protection_rules[key] = value
                try:
                    with self.assertRaises(pub.Denied):
                        self.publish()
                    self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
                finally:
                    self.protection_rules[key] = original
        for field, value in [
            ("require_code_owner_reviews", False),
            ("dismiss_stale_reviews", False),
            ("required_approving_review_count", 0),
        ]:
            with self.subTest(field=field):
                self.route_calls.clear()
                reviews = self.protection_rules["required_pull_request_reviews"]
                original = reviews[field]
                reviews[field] = value
                try:
                    with self.assertRaises(pub.Denied):
                        self.publish()
                    self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
                finally:
                    reviews[field] = original

    def test_unrelated_required_checks_cannot_replace_private_ci_gate(self):
        checks = self.protection_rules["required_status_checks"]
        for contexts, apps in [
            (["unrelated-lint"], []),
            ([], [{"context": "unrelated-lint", "app_id": 1234}]),
            ([], [{"context": "preflight-regressions", "app_id": -1}]),
            (["preflight-regressions"],
             [{"context": "preflight-regressions", "app_id": -1}]),
            ([], [{"context": "preflight-regressions"}]),
        ]:
            with self.subTest(contexts=contexts, apps=apps):
                self.route_calls.clear()
                checks["contexts"] = contexts
                checks["checks"] = apps
                with self.assertRaises(pub.Denied):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_private_ci_check_pinned_to_an_app_is_accepted(self):
        checks = self.protection_rules["required_status_checks"]
        checks["contexts"] = []
        checks["checks"] = [{"context": "preflight-regressions", "app_id": 15368}]
        self.publish()
        self.assertTrue(any(m == "POST" for m, _, _ in self.route_calls))

    def test_legacy_context_only_cannot_authorize_private_export(self):
        # Names alone are spoofable: a status authored by a different app
        # must never qualify as the trusted source security check.
        checks = self.protection_rules["required_status_checks"]
        checks["contexts"] = ["preflight-regressions"]
        checks["checks"] = []
        with self.assertRaisesRegex(pub.Denied, "app-bound"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_wrong_positive_app_and_conflicting_app_are_denied(self):
        checks = self.protection_rules["required_status_checks"]
        for entries in [
            [{"context": "preflight-regressions", "app_id": 1234}],
            [{"context": "preflight-regressions", "app_id": True}],
            [{"context": "preflight-regressions", "app_id": "15368"}],
            [{"context": "preflight-regressions", "app_id": 15368},
             {"context": "preflight-regressions", "app_id": 1234}],
        ]:
            with self.subTest(entries=entries):
                self.route_calls.clear()
                checks["checks"] = entries
                with self.assertRaisesRegex(pub.Denied, "trusted GitHub Actions app"):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_main_bypass_allowance_denied(self):
        reviews = self.protection_rules["required_pull_request_reviews"]
        reviews["bypass_pull_request_allowances"] = {
            "users": [{"login": "unreviewed"}], "teams": [], "apps": []}
        with self.assertRaisesRegex(pub.Denied, "bypass"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_main_protection_revoked_mid_transfer(self):
        original = self.fake_api
        def change_after_blob(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs" and method == "POST":
                self.main_protected = False
            return result
        with patch.object(pub, "gh_api", side_effect=change_after_blob):
            with self.assertRaisesRegex(pub.Denied, "not protected"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        self.assertEqual([route for method, route, _ in self.route_calls
                          if method == "POST"], ["repos/trusted/ci/git/blobs"])

    def test_checkout_cannot_self_approve_when_protected_main_does_not(self):
        # The local file is approved, but protected main is still deny-by-default.
        self.protected_policy = b"# no approved destination on main\n"
        with self.assertRaisesRegex(pub.Denied, "differs from protected main"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_remote_policy_unavailable_fails_before_first_source_write(self):
        original = self.fake_api
        def unavailable(route, method="GET", payload=None):
            if route == pub.PROTECTED_POLICY_ROUTE:
                raise pub.Denied("authenticated source policy unavailable")
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=unavailable):
            with self.assertRaises(pub.Denied):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_protected_policy_revoked_mid_upload_blocks_ref_creation(self):
        original = self.fake_api
        def revoke_after_first_blob(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.protected_policy = b"# revoked by protected main\n"
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_after_first_blob):
            with self.assertRaisesRegex(pub.Denied, "differs from protected main"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        self.assertEqual([r for m, r, _ in self.route_calls if m == "POST"],
                         ["repos/trusted/ci/git/blobs"])

    def test_malformed_protected_policy_response_fails_closed(self):
        original = self.fake_api
        bad_payloads = [
            {"type": "file", "encoding": "base64", "size": 0,
             "content": "not base64!", "sha": "a" * 40},
            {"type": "symlink", "encoding": "base64", "size": 0,
             "content": "", "sha": "a" * 40},
            {"type": "file", "encoding": "base64", "size": 0,
             "content": "", "sha": "a" * 40},
            {"type": "file", "encoding": "base64", "size": True,
             "content": "", "sha": "a" * 40},
            {"type": "file", "encoding": "base64", "size": 16385,
             "content": "", "sha": "a" * 40},
        ]
        for payload in bad_payloads:
            self.route_calls.clear()
            def malformed(route, method="GET", payload_arg=None):
                if route == pub.PROTECTED_POLICY_ROUTE:
                    return payload
                return original(route, method, payload_arg)
            # Preserve keyword argument compatibility with the GitHub stub.
            def proxy(route, method="GET", payload=None):
                return malformed(route, method, payload)
            with patch.object(pub, "gh_api", side_effect=proxy):
                with self.assertRaises(pub.Denied):
                    pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
            self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_ref_creation_rechecks_protected_main_after_commit(self):
        original = self.fake_api
        def revoke_after_commit(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/commits" and method == "POST":
                self.protected_policy = b"# revoked just before ref creation\n"
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_after_commit):
            with self.assertRaisesRegex(pub.Denied, "differs from protected main"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        writes = [r for m, r, _ in self.route_calls if m == "POST"]
        self.assertEqual(writes[-1], "repos/trusted/ci/git/commits")
        self.assertNotIn("repos/trusted/ci/git/refs", writes)

    def test_protected_main_policy_is_read_before_each_upload_write(self):
        self.publish()
        for i, (method, route, _) in enumerate(self.route_calls):
            if method != "POST":
                continue
            previous = self.route_calls[i - 2:i]
            self.assertEqual([r for _, r, _ in previous],
                             [pub.PROTECTED_POLICY_ROUTE, "repos/trusted/ci"],
                             "every GitHub write must revalidate protected main and private identity")

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
             "type": "tree", "sha": D}])
        self.assertEqual(writes[2][2]["base_tree"], B)
        self.assertEqual(writes[3][2]["tree"], E)
        self.assertIn("Source-Commit: " + A, writes[3][2]["message"])
        self.assertIn("Source-Tree: " + D, writes[3][2]["message"])
        self.assertTrue(all(r.startswith("repos/trusted/ci") for _, r, _ in writes))

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
            {"path": pub.PREFIX, "mode": "040000", "type": "tree", "sha": D}])
        self.assertEqual(tree_writes[1]["base_tree"], B)

    def test_publish_calls_source_identity_function_before_source_walk(self):
        # A local path variable named checked_source previously shadowed the
        # identity-check function, making every authorized publish fail with
        # UnboundLocalError before any GitHub upload.
        original = pub.checked_source
        with patch.object(pub, "checked_source", wraps=original) as verify:
            self.publish()
        self.assertGreaterEqual(verify.call_count, 2)

    def test_unrecognized_source_commit_fails_before_upload(self):
        original = self.fake_api
        def source_commit_missing(route, method="GET", payload=None):
            if route == "repos/" + pub.SOURCE_REPOSITORY + "/git/commits/" + A:
                raise pub.Denied("source commit missing")
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=source_commit_missing):
            with self.assertRaisesRegex(pub.Denied, "source commit missing"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_commit_tree_mismatch_fails_before_upload(self):
        original = self.fake_api
        for response in ({"sha": A, "tree": {"sha": C}},
                         {"sha": C, "tree": {"sha": D}},
                         {"sha": A, "tree": None},
                         {"sha": A}):
            with self.subTest(response=response):
                self.route_calls.clear()
                def spoofed(route, method="GET", payload=None):
                    if route == "repos/" + pub.SOURCE_REPOSITORY + "/git/commits/" + A:
                        return response
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=spoofed):
                    with self.assertRaisesRegex(pub.Denied, "source commit"):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, A, D)
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_missing_archived_or_disabled_metadata_fails_closed(self):
        for route_to_break in ("source", "destination"):
            for missing in ("archived", "disabled"):
                with self.subTest(repo=route_to_break, field=missing):
                    self.route_calls.clear()
                    original = self.fake_api

                    def incomplete(route, method="GET", payload=None):
                        response = original(route, method, payload)
                        target = (route == "repos/" + pub.SOURCE_REPOSITORY
                                  if route_to_break == "source"
                                  else route == "repos/trusted/ci")
                        if target:
                            response.pop(missing)
                        return response

                    with patch.object(pub, "gh_api", side_effect=incomplete):
                        with self.assertRaisesRegex(pub.Denied, "identity"):
                            pub.publish("trusted/ci", self.source,
                                        "ci/oreslang-test", self.root, A, D)
                    self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_security_sensitive_mirror_files_require_codeowner_review(self):
        rules = MODULE.parent.parent / ".github" / "CODEOWNERS"
        entries = {
            line.split()[0]: line.split()[1:]
            for line in rules.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        paths = {
            "/.github/CODEOWNERS",
            "/.github/private-ci-approved-repositories.txt",
            "/.github/workflows/private-ci-mirror-preflight.yml",
            "/scripts/publish-private-ci-source.py",
            "/scripts/test-publish-private-ci-source.py",
            "/scripts/check-private-ci-destination.sh",
            "/scripts/run-in-private-ci-destination.sh",
            "/scripts/test-private-ci-destination.sh",
            "/scripts/ci_private_mirror_preflight.py",
            "/tests/test_ci_private_mirror_preflight.py",
            "/docs/security/CI_PRIVATE_SOURCE_MIRRORING.md",
            "/docs/PRIVATE_CI_MIRRORS.md",
        }
        for path in paths:
            with self.subTest(path=path):
                self.assertIn("@ORESoftware", entries.get(path, []),
                              "private source export controls need codeowner review")

    def test_shell_preflight_pins_match_reviewed_python_publisher(self):
        # A second caller may trust the Bash preflight without invoking the
        # Python publisher. Their immutable destination pins must stay equal.
        script = MODULE.with_name("check-private-ci-destination.sh").read_text()
        listed = re.findall(
            r"^  ([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)[)] expected_id=([0-9]+) ;;$",
            script, flags=re.MULTILINE)
        self.assertEqual({name: int(repo_id) for name, repo_id in listed},
                         pub.PINNED_DESTINATION_IDS)

    def test_same_slug_but_different_destination_id_aborts_before_upload(self):
        self.destination_ids = [987654321]
        with self.assertRaisesRegex(pub.Denied, "numeric identity"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_id_flip_aborts_before_later_writes(self):
        self.destination_ids = [123456789, 123456789, 123456789, 987654321]
        with self.assertRaisesRegex(pub.Denied, "numeric identity"):
            self.publish()
        self.assertEqual([r for m, r, _ in self.route_calls if m == "POST"],
                         ["repos/trusted/ci/git/blobs"])

    def test_unknown_or_malformed_destination_id_denied(self):
        for identity in (None, "123456789", False):
            with self.subTest(identity=identity):
                self.route_calls.clear()
                self.destination_ids = [identity]
                with self.assertRaisesRegex(pub.Denied, "numeric identity"):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_repo_id_mismatch_refuses_private_export(self):
        self.source_ids = [987654321]
        with self.assertRaisesRegex(pub.Denied, "numeric identity"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_source_repo_identity_change_stops_before_next_write(self):
        self.source_ids = [pub.SOURCE_REPOSITORY_ID,
                           pub.SOURCE_REPOSITORY_ID, 987654321]
        with self.assertRaisesRegex(pub.Denied, "numeric identity"):
            self.publish()
        self.assertEqual([r for m, r, _ in self.route_calls if m == "POST"],
                         ["repos/trusted/ci/git/blobs"])

    def test_unlisted_destination_id_denied_even_if_policy_mentions_slug(self):
        with patch.dict(pub.PINNED_DESTINATION_IDS, {}, clear=True):
            with self.assertRaisesRegex(pub.Denied, "not approved"):
                self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_destination_subtree_mismatch_cannot_publish_ci_ref(self):
        original = self.fake_api

        def wrong_tree(route, method="GET", payload=None):
            if (route == "repos/trusted/ci/git/trees"
                    and method == "POST" and "base_tree" not in payload):
                self.route_calls.append((method, route, payload))
                return {"sha": C}
            return original(route, method, payload)

        with patch.object(pub, "gh_api", side_effect=wrong_tree):
            with self.assertRaisesRegex(pub.Denied, "subtree differs"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        writes = [r for m, r, _ in self.route_calls if m == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs",
                                  "repos/trusted/ci/git/trees"])
        self.assertFalse(any(r.endswith("/git/refs") for r in writes))

    def test_git_provenance_does_not_use_replacement_objects_or_global_config(self):
        files = pub.collect_source(self.source, self.source,
                                   omit_checkout_metadata=True)
        tree = pub.snapshot_git_tree_sha(files)
        captured = []

        def simulated_git(args, **kwargs):
            captured.append((args, kwargs))
            response = str(self.source.resolve()) + "\n" + A + "\n" + tree + "\n"
            return subprocess.CompletedProcess(args, 0, stdout=response, stderr="")

        with patch.dict("os.environ", {
                "GIT_DIR": "/tmp/malicious-git-dir",
                "GIT_NO_REPLACE_OBJECTS": "0",
                "GIT_CONFIG_GLOBAL": "/tmp/malicious-git-config"}):
            with patch.object(pub.subprocess, "run",
                              side_effect=simulated_git):
                self.assertEqual(pub.require_source_tree_match(
                    self.source, self.source, files), (A, tree))
        self.assertEqual(len(captured), 1)
        env = captured[0][1]["env"]
        self.assertEqual(env["GIT_NO_REPLACE_OBJECTS"], "1")
        self.assertEqual(env["GIT_CONFIG_NOSYSTEM"], "1")
        self.assertEqual(env["GIT_CONFIG_GLOBAL"], pub.os.devnull)
        self.assertNotIn("GIT_DIR", env)

    def test_actual_git_tree_matches_committed_nested_snapshot(self):
        # Independent fixture: do not fake Git's HEAD^{tree}, proving the
        # in-memory snapshot uses Git's directory, mode and blob hashing.
        with tempfile.TemporaryDirectory() as root_text:
            root = Path(root_text)
            (root / "nested").mkdir()
            (root / "nested" / "data.ores").write_bytes(b"nested\n")
            (root / "run.sh").write_bytes(b"#!/bin/sh\nexit 0\n")
            (root / "run.sh").chmod(0o755)
            subprocess.run(["git", "init", "-q", str(root)],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root), "add", "--all"],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root),
                            "-c", "user.name=CI",
                            "-c", "user.email=ci@example.invalid",
                            "commit", "-qm", "fixture"],
                           check=True, capture_output=True)
            files = pub.collect_source(root, root, omit_checkout_metadata=True)
            source_commit, actual = self.real_tree_gate(root, root, files)
            expected_commit = subprocess.run(
                ["git", "-C", str(root), "rev-parse", "HEAD"],
                check=True, capture_output=True, text=True).stdout.strip()
            self.assertEqual(source_commit, expected_commit)
            expected = subprocess.run(
                ["git", "-C", str(root), "rev-parse", "HEAD^{tree}"],
                check=True, capture_output=True, text=True).stdout.strip()
            self.assertEqual(actual, expected)

            # GIT_DIR and GIT_WORK_TREE are caller-controlled. They must not
            # redirect provenance to a different repository's HEAD.
            with tempfile.TemporaryDirectory() as fake_root:
                bogus = Path(fake_root)
                subprocess.run(["git", "init", "-q", str(bogus)],
                               check=True, capture_output=True)
                with patch.dict("os.environ", {
                        "GIT_DIR": str(bogus / ".git"),
                        "GIT_WORK_TREE": str(bogus)}):
                    self.assertEqual(self.real_tree_gate(root, root, files),
                                     (source_commit, expected))

            (root / "nested" / "data.ores").write_bytes(b"changed\n")
            dirty = pub.collect_source(root, root, omit_checkout_metadata=True)
            with self.assertRaisesRegex(pub.Denied, "differs"):
                self.real_tree_gate(root, root, dirty)

            # Restoring the tracked file but adding an untracked file is
            # likewise rejected instead of exporting unrelated private bytes.
            (root / "nested" / "data.ores").write_bytes(b"nested\n")
            (root / "extra-secret.txt").write_text("untracked")
            dirty = pub.collect_source(root, root, omit_checkout_metadata=True)
            with self.assertRaisesRegex(pub.Denied, "differs"):
                self.real_tree_gate(root, root, dirty)

            with self.assertRaisesRegex(pub.Denied, "checkout root"):
                self.real_tree_gate(root / "nested", root, files)

    def test_public_visibility_aborts_before_any_write(self):
        self.visibility = ["public"]
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_visibility_flip_aborts_before_next_write(self):
        # Initial admission, base metadata and first blob must be private.
        # The fourth identity recheck rejects before constructing a root tree.
        self.visibility = ["private", "private", "private", "public"]
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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
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
        outside.write_text("trusted/ci\n")
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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_valid_looking_but_incorrect_blob_sha_is_rejected(self):
        original = self.fake_api
        def wrong(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/blobs":
                return {"sha": C}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong):
            with self.assertRaisesRegex(pub.Denied, "digest"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A, D)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_candidate_checkout_can_be_separate_from_trusted_publisher(self):
        # Publisher code/policy live under self.root, candidate source in a
        # different checkout. Unit test substitutes the exact remote HEAD
        # proof, which is separately exercised against real Git above.
        with tempfile.TemporaryDirectory() as external:
            candidate = Path(external)
            (candidate / "outside.ores").write_text("private data")
            with patch.object(pub, "gh_api", side_effect=self.fake_api):
                sha, count = pub.publish(
                    "trusted/ci", candidate, "ci/oreslang-test", self.root, A, D)
            self.assertEqual((sha, count), (A, 1))
            blob_writes = [payload for method, route, payload in self.route_calls
                           if method == "POST" and route.endswith("/git/blobs")]
            self.assertEqual(len(blob_writes), 1)
            self.assertEqual(base64.b64decode(blob_writes[0]["content"]),
                             b"private data")

    def test_distinct_candidate_with_real_git_tree_and_mocked_api(self):
        # Integration-style offline proof: trusted policy root and separate
        # real Git candidate, complete committed tree, authenticated mocked
        # source commit, and exact Git-tree payload at destination.
        with tempfile.TemporaryDirectory() as external:
            candidate = Path(external)
            (candidate / "worker.ores").write_text("define actor Worker as\nend\n")
            subprocess.run(["git", "init", "-q", str(candidate)],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", str(candidate), "add", "--all"],
                           check=True, capture_output=True)
            subprocess.run(["git", "-C", str(candidate),
                            "-c", "user.name=CI",
                            "-c", "user.email=ci@example.invalid",
                            "commit", "-qm", "synthetic actor"],
                           check=True, capture_output=True)
            commit = subprocess.run(
                ["git", "-C", str(candidate), "rev-parse", "HEAD"],
                check=True, capture_output=True, text=True).stdout.strip()
            tree = subprocess.run(
                ["git", "-C", str(candidate), "rev-parse", "HEAD^{tree}"],
                check=True, capture_output=True, text=True).stdout.strip()
            original_api = self.fake_api

            def authenticated_synthetic_api(route, method="GET", payload=None):
                if route == ("repos/" + pub.SOURCE_REPOSITORY
                             + "/git/commits/" + commit):
                    self.route_calls.append((method, route, payload))
                    return {"sha": commit, "tree": {"sha": tree}}
                if (route == "repos/trusted/ci/git/trees"
                        and method == "POST"
                        and isinstance(payload, dict)
                        and "base_tree" not in payload):
                    self.route_calls.append((method, route, payload))
                    return {"sha": tree}
                return original_api(route, method, payload)

            with patch.object(pub, "require_source_tree_match",
                              wraps=self.real_tree_gate) as verifier:
                with patch.object(pub, "gh_api",
                                  side_effect=authenticated_synthetic_api):
                    result = pub.publish("trusted/ci", candidate,
                                         "ci/oreslang-test", self.root, commit, tree)
                    self.assertEqual(result, (A, 1))
                self.assertEqual(verifier.call_count, 1)

            # Changing the source after its commit is rejected before export.
            self.route_calls.clear()
            (candidate / "worker.ores").write_text("dirty source\n")
            with patch.object(pub, "require_source_tree_match",
                              wraps=self.real_tree_gate):
                with patch.object(pub, "gh_api",
                                  side_effect=authenticated_synthetic_api):
                    with self.assertRaisesRegex(pub.Denied, "differs"):
                        pub.publish("trusted/ci", candidate,
                                    "ci/oreslang-test", self.root, commit, tree)
            self.assertFalse(any(method == "POST"
                                 for method, _, _ in self.route_calls))

    def test_candidate_checkout_cannot_self_approve_private_export(self):
        # Attacker-controlled candidate policy/script must not authorize itself.
        with tempfile.TemporaryDirectory() as external:
            candidate = Path(external)
            (candidate / ".github").mkdir()
            (candidate / ".github" / "private-ci-approved-repositories.txt"
             ).write_text("trusted/ci\n")
            (candidate / "run.sh").write_text("#!/bin/sh\nexit 0\n")
            self.approval.write_text("# protected publisher policy denies\n")
            with patch.object(pub, "gh_api", side_effect=self.fake_api):
                with self.assertRaisesRegex(pub.Denied, "revoked or absent"):
                    pub.publish("trusted/ci", candidate, "ci/oreslang-test",
                                self.root, A, D)
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_symlinked_candidate_checkout_refused(self):
        link = self.root / "linked-source"
        link.symlink_to(self.source, target_is_directory=True)
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with self.assertRaisesRegex(pub.Denied, "symlink"):
                pub.publish("trusted/ci", link, "ci/oreslang-test", self.root, A, D)
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_python_preflight_never_executes_checkout_controlled_bash(self):
        guard = self.root / "scripts" / "check-private-ci-destination.sh"
        guard.parent.mkdir(exist_ok=True)
        guard.write_text("#!/bin/bash\nexit 99\n")
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub.subprocess, "run",
                              side_effect=AssertionError("checkout code executed")) as run:
                self.real_preflight("trusted/ci", self.root)
                run.assert_not_called()
        self.assertEqual([(method, route) for method, route, _ in self.route_calls],
                         [("GET", "repos/trusted/ci")])

    def test_python_preflight_denies_unapproved_without_running_shell(self):
        self.approval.write_text("# approval revoked\n")
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub.subprocess, "run",
                              side_effect=AssertionError("checkout code executed")) as run:
                with self.assertRaisesRegex(pub.Denied, "revoked or absent"):
                    self.real_preflight("trusted/ci", self.root)
                run.assert_not_called()
        self.assertEqual(self.route_calls, [])

    def test_python_preflight_denies_replaced_destination_without_shell(self):
        self.destination_ids = [987654321]
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            with patch.object(pub.subprocess, "run",
                              side_effect=AssertionError("checkout code executed")) as run:
                with self.assertRaisesRegex(pub.Denied, "numeric identity"):
                    self.real_preflight("trusted/ci", self.root)
                run.assert_not_called()

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

    def test_exact_checkout_skips_only_real_root_git_metadata(self):
        # Normal Git checkouts contain a .git directory. Without a narrowly
        # scoped exception the exact-tree proof cannot run on real checkouts.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / ".git"
            metadata.mkdir()
            (metadata / "config").write_text("do not export\n")
            (root / "hello.ores").write_text("source\n")
            with self.assertRaisesRegex(pub.Denied, "unsupported name"):
                pub.collect_source(root, root)
            collected = pub.collect_source(root, root, omit_checkout_metadata=True)
            self.assertEqual([(p, data) for p, data, _ in collected],
                             [("hello.ores", b"source\n")])
            self.assertFalse(any(".git" in p for p, _, _ in collected))
            nested = root / "nested"
            nested.mkdir()
            (nested / ".git").mkdir()
            with self.assertRaisesRegex(pub.Denied, "unsupported name"):
                pub.collect_source(root, root, omit_checkout_metadata=True)

            (nested / ".git").rmdir()
            (metadata / "config").unlink()
            metadata.rmdir()
            metadata.symlink_to(nested, target_is_directory=True)
            with self.assertRaisesRegex(pub.Denied, "Git metadata"):
                pub.collect_source(root, root, omit_checkout_metadata=True)

    def test_subdirectory_cannot_enable_checkout_metadata_exception(self):
        (self.source / ".git").mkdir()
        with self.assertRaisesRegex(pub.Denied, "unsupported name"):
            pub.collect_source(self.source, self.root, omit_checkout_metadata=True)

    def test_checkout_git_metadata_directory_is_omitted(self):
        (self.source / ".git").mkdir()
        (self.source / ".git" / "config").write_text("not source content")
        sha, count = self.publish()
        self.assertEqual((sha, count), (A, 1))
        source_paths = [
            entry["path"] for method, route, payload in self.route_calls
            if method == "POST" and route.endswith("/git/trees")
            and isinstance(payload, dict) and "base_tree" not in payload
            for entry in payload["tree"]]
        self.assertEqual(source_paths, ["hello.ores"])

    def test_checkout_git_metadata_symlink_is_rejected(self):
        (self.source / ".git").symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(pub.Denied, "Git metadata"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
