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
ORIGINAL_VERIFY_PROTECTED_ORIGIN = pub.verify_protected_origin
ORIGINAL_VERIFY_EXACT_SOURCE_TREE = pub.verified_exact_source_tree
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
        self.route_calls = []
        self.visibility = ["private"] * 100
        self.preflight = patch.object(pub, "require_preflight")
        self.preflight.start()
        self.addCleanup(self.preflight.stop)
        # Existing isolated transfer/FS tests model the downstream publisher;
        # protected-source provenance gets independent real-guard tests below.
        self.origin_guard = patch.object(pub, "verify_protected_origin")
        self.origin_guard.start()
        self.addCleanup(self.origin_guard.stop)
        # Existing transfer/FS tests verify post-attestation behavior; the
        # source Git-tree verifier is tested separately against mocked origin.
        self.source_tree_guard = patch.object(
            pub, "verified_exact_source_tree", return_value=D)
        self.source_tree_guard.start()
        self.addCleanup(self.source_tree_guard.stop)

    def fake_api(self, route, method="GET", payload=None):
        self.route_calls.append((method, route, payload))
        if route == "repos/trusted/ci":
            return dict(full_name="trusted/ci", visibility=self.visibility.pop(0),
                        private=True, default_branch="main")
        if route == "repos/trusted/ci/git/blobs":
            raw = base64.b64decode(payload["content"])
            return {"sha": hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                                        + bytes([0]) + raw).hexdigest()}
        if route == "repos/trusted/ci/git/trees":
            # A fresh file tree and its parent root tree must be distinct.
            return {"sha": E if "base_tree" in payload else D}
        mapping = {
            "repos/trusted/ci/git/ref/heads/main": {"object": {"sha": A}},
            "repos/trusted/ci/git/commits/"+A: {"tree": {"sha": B}},
            "repos/trusted/ci/git/commits": {"sha": A},
            "repos/trusted/ci/git/refs": {"ref": "refs/heads/ci/oreslang-test"},
        }
        if route not in mapping:
            raise AssertionError("unexpected or cross-repository GitHub API route " + route)
        return mapping[route]

    def publish(self, repo="trusted/ci", branch="ci/oreslang-test"):
        with patch.object(pub, "gh_api", side_effect=self.fake_api):
            return pub.publish(repo, self.source, branch, self.root, A)

    def verified_origin_api(self, protected=True, policy=b"trusted/ci\n",
                            codeowners=True, stale=True, admins=True,
                            approvals=1, force=False, deletions=False,
                            bypasses=None):
        routes = []
        def fake(route, method="GET", payload=None):
            routes.append(route)
            if route == "repos/"+pub.SOURCE_REPO:
                return {"full_name": pub.SOURCE_REPO, "private": True}
            if route == "repos/"+pub.SOURCE_REPO+"/branches/main":
                return {"name": "main", "protected": protected,
                        "commit": {"sha": A}}
            if route == "repos/"+pub.SOURCE_REPO+"/branches/main/protection":
                reviews = {"require_code_owner_reviews": codeowners,
                           "dismiss_stale_reviews": stale,
                           "required_approving_review_count": approvals}
                if bypasses is not None:
                    reviews["bypass_pull_request_allowances"] = bypasses
                return {"required_pull_request_reviews": reviews,
                        "enforce_admins": {"enabled": admins},
                        "allow_force_pushes": {"enabled": force},
                        "allow_deletions": {"enabled": deletions}}
            if route == ("repos/"+pub.SOURCE_REPO+"/contents/"
                         +pub.POLICY_PATH+"?ref="+A):
                return {"path": pub.POLICY_PATH, "type": "file",
                        "encoding": "base64",
                        "content": base64.b64encode(policy).decode("ascii")}
            return self.fake_api(route, method, payload)
        return fake, routes

    def test_gh_api_accepts_successful_empty_delete(self):
        response = type(
            "Response", (), {"returncode": 0, "stdout": "", "stderr": ""})()
        with patch.object(pub.subprocess, "run", return_value=response):
            self.assertEqual(
                pub.gh_api(
                    "repos/trusted/ci/git/refs/heads/ci/cleanup",
                    method="DELETE"),
                {})

    def test_origin_main_must_be_protected_before_export(self):
        api, routes = self.verified_origin_api(protected=False)
        with patch.object(pub, "verify_protected_origin",
                          side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
            with patch.object(pub, "gh_api", side_effect=api):
                with patch.object(pub, "collect_source") as collect:
                    with self.assertRaisesRegex(pub.Denied, "not protected"):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, A)
                    collect.assert_not_called()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.assertNotIn("repos/"+pub.SOURCE_REPO+"/contents/"
                         +pub.POLICY_PATH+"?ref="+A, routes)

    def test_origin_main_requires_real_codeowner_and_admin_enforcement(self):
        for rule in ("codeowners", "stale", "admins"):
            with self.subTest(rule=rule):
                options = {rule: False}
                api, routes = self.verified_origin_api(**options)
                with patch.object(pub, "verify_protected_origin",
                                  side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
                    with patch.object(pub, "gh_api", side_effect=api):
                        with patch.object(pub, "collect_source") as collect:
                            with self.assertRaisesRegex(pub.Denied, "CODEOWNER"):
                                pub.publish("trusted/ci", self.source,
                                            "ci/oreslang-test", self.root, A)
                            collect.assert_not_called()
                self.assertNotIn("repos/"+pub.SOURCE_REPO+"/contents/"
                                 +pub.POLICY_PATH+"?ref="+A, routes)
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_origin_main_rejects_force_push_delete_and_review_bypasses(self):
        for config in ({"approvals": 0}, {"force": True},
                       {"deletions": True},
                       {"bypasses": {"users": ["someone"], "teams": [], "apps": []}},
                       {"bypasses": {"users": "invalid"}}):
            with self.subTest(config=config):
                api, routes = self.verified_origin_api(**config)
                with patch.object(pub, "verify_protected_origin",
                                  side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
                    with patch.object(pub, "gh_api", side_effect=api):
                        with patch.object(pub, "collect_source") as collect:
                            with self.assertRaises(pub.Denied):
                                pub.publish("trusted/ci", self.source,
                                            "ci/oreslang-test", self.root, A)
                            collect.assert_not_called()
                self.assertNotIn(
                    "repos/"+pub.SOURCE_REPO+"/contents/"
                    +pub.POLICY_PATH+"?ref="+A, routes)
                self.assertFalse(any(method == "POST"
                                     for method, _, _ in self.route_calls))

    def test_origin_protection_api_permission_denial_rejects_export(self):
        api, _ = self.verified_origin_api()
        def denied(route, method="GET", payload=None):
            if route.endswith("/branches/main/protection"):
                raise pub.Denied("GitHub protection settings API unavailable")
            return api(route, method, payload)
        with patch.object(pub, "verify_protected_origin",
                          side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
            with patch.object(pub, "gh_api", side_effect=denied):
                with patch.object(pub, "collect_source") as collect:
                    with self.assertRaisesRegex(pub.Denied, "unavailable"):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, A)
                    collect.assert_not_called()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_origin_main_must_match_local_approved_policy(self):
        api, _ = self.verified_origin_api(
            protected=True, policy=b"# no authorized destination\n")
        with patch.object(pub, "verify_protected_origin",
                          side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
            with patch.object(pub, "gh_api", side_effect=api):
                with patch.object(pub, "collect_source") as collect:
                    with self.assertRaisesRegex(pub.Denied, "diverges"):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root, A)
                    collect.assert_not_called()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_protected_origin_verification_before_and_after_upload(self):
        api, routes = self.verified_origin_api(protected=True)
        with patch.object(pub, "verify_protected_origin",
                          side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
            with patch.object(pub, "gh_api", side_effect=api):
                sha, count = pub.publish("trusted/ci", self.source,
                                         "ci/oreslang-test", self.root, A)
        self.assertEqual((sha, count), (A, 1))
        branch_reads = [r for r in routes
                        if r == "repos/"+pub.SOURCE_REPO+"/branches/main"]
        self.assertEqual(len(branch_reads), 2)

    def test_protected_origin_recheck_rejects_last_minute_revocation(self):
        api, routes = self.verified_origin_api(protected=True)
        calls = [0]
        def revoke(route, method="GET", payload=None):
            if route == "repos/"+pub.SOURCE_REPO+"/branches/main":
                calls[0] += 1
                if calls[0] == 2:
                    return {"name": "main", "protected": False,
                            "commit": {"sha": A}}
            return api(route, method, payload)
        with patch.object(pub, "verify_protected_origin",
                          side_effect=ORIGINAL_VERIFY_PROTECTED_ORIGIN):
            with patch.object(pub, "gh_api", side_effect=revoke):
                with self.assertRaisesRegex(pub.Denied, "not protected"):
                    pub.publish("trusted/ci", self.source,
                                "ci/oreslang-test", self.root, A)
        self.assertEqual(calls[0], 2)
        self.assertFalse(any(r == "repos/trusted/ci/git/refs"
                             for m, r, _ in self.route_calls if m == "POST"))

    def test_git_tree_proof_exactly_matches_all_local_files(self):
        data = (self.source / "hello.ores").read_bytes()
        git_blob = hashlib.sha1(
            b"blob "+str(len(data)).encode("ascii")+bytes([0])+data
        ).hexdigest()
        tree = [{"path": "hello.ores", "type": "blob",
                 "mode": "100644", "sha": git_blob}]
        seen = []
        def origin_api(route, method="GET", payload=None):
            seen.append(route)
            if route == "repos/"+pub.SOURCE_REPO+"/git/commits/"+A:
                return {"sha": A, "tree": {"sha": D}}
            if route == "repos/"+pub.SOURCE_REPO+"/git/trees/"+D+"?recursive=1":
                return {"sha": D, "truncated": False, "tree": tree}
            raise AssertionError("unexpected origin route: "+route)
        with patch.object(pub, "gh_api", side_effect=origin_api):
            self.assertEqual(
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE(
                    [("hello.ores", data, "100644")], A), D)
            with self.assertRaisesRegex(pub.Denied, "differs"):
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE(
                    [("hello.ores", data+b"dirty", "100644")], A)
            with self.assertRaisesRegex(pub.Denied, "differs"):
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE(
                    [("hello.ores", data, "100755")], A)
            with self.assertRaisesRegex(pub.Denied, "differs"):
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE(
                    [("hello.ores", data, "100644"),
                     ("untracked.secret", b"secret", "100644")], A)
            with self.assertRaisesRegex(pub.Denied, "differs"):
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE([], A)
        self.assertTrue(seen)

    def test_git_tree_attestation_refuses_truncation_and_symlinks(self):
        data = (self.source / "hello.ores").read_bytes()
        def fake(route, method="GET", payload=None):
            if route.endswith("/git/commits/"+A):
                return {"sha": A, "tree": {"sha": D}}
            if route.endswith("/git/trees/"+D+"?recursive=1"):
                return {"sha": D, "truncated": True, "tree": []}
            raise AssertionError(route)
        with patch.object(pub, "gh_api", side_effect=fake):
            with self.assertRaisesRegex(pub.Denied, "incomplete"):
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE(
                    [("hello.ores", data, "100644")], A)
        def symlink(route, method="GET", payload=None):
            if route.endswith("/git/commits/"+A):
                return {"sha": A, "tree": {"sha": D}}
            if route.endswith("/git/trees/"+D+"?recursive=1"):
                return {"sha": D, "truncated": False,
                        "tree": [{"path":"link", "type":"blob",
                                  "mode":"120000", "sha": B}]}
            raise AssertionError(route)
        with patch.object(pub, "gh_api", side_effect=symlink):
            with self.assertRaisesRegex(pub.Denied, "symlink"):
                ORIGINAL_VERIFY_EXACT_SOURCE_TREE(
                    [("hello.ores", data, "100644")], A)

    def test_publisher_rejects_mismatched_uploaded_tree_before_branch(self):
        # GitHub returns a different tree SHA even though blobs matched.
        self.source_tree_guard.return_value = C
        with self.assertRaisesRegex(pub.Denied, "uploaded source tree differs"):
            self.publish()
        self.assertNotIn("repos/trusted/ci/git/refs",
                         [route for method, route, _ in self.route_calls
                          if method == "POST"])

    def test_source_commit_is_mandatory_exact_sha(self):
        with patch.object(pub, "collect_source") as source:
            with self.assertRaises(pub.Denied):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root, "main")
            source.assert_not_called()

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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A)
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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_valid_looking_but_incorrect_blob_sha_is_rejected(self):
        original = self.fake_api
        def wrong(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/blobs":
                return {"sha": C}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong):
            with self.assertRaisesRegex(pub.Denied, "digest"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root, A)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_source_must_be_under_trusted_checkout(self):
        outside = self.root.parent / ("unsafe-" + self.root.name)
        outside.mkdir()
        try:
            (outside / "outside.ores").write_text("private data")
            with patch.object(pub, "gh_api", side_effect=self.fake_api):
                with self.assertRaisesRegex(pub.Denied, "trusted checkout"):
                    pub.publish("trusted/ci", outside, "ci/oreslang-test", self.root, A)
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

    def test_git_metadata_directory_rejected(self):
        (self.source / ".git").mkdir()
        with self.assertRaises(pub.Denied):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
