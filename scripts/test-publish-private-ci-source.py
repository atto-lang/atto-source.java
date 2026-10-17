#!/usr/bin/env python3
"""Offline adversarial tests for the private destination-bound publisher."""
import importlib.util
import base64
import copy
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
        self.approval.write_text("trusted/ci 246810\n")
        (self.source / "hello.ores").write_text("pub fnc main(): int { return 42; }\n")
        self.route_calls = []
        self.visibility = ["private"] * 100
        self.destination_ids = [246810] * 100
        self.preflight = patch.object(pub, "require_preflight")
        self.preflight.start()
        self.addCleanup(self.preflight.stop)
        # Existing transport/TOCTOU tests isolate destination API semantics.
        # The new independently tested source provenance gate is mocked here,
        # never in production.
        for method, kwargs in (("reviewed_source_main", {}),
                               ("require_live_source_authority", {}),
                               ("require_pinned_snapshot", {"return_value": D})):
            guard = patch.object(pub, method, **kwargs)
            guard.start()
            self.addCleanup(guard.stop)

    def fake_api(self, route, method="GET", payload=None):
        self.route_calls.append((method, route, payload))
        if route == "repos/trusted/ci":
            return dict(full_name="trusted/ci", visibility=self.visibility.pop(0),
                        private=True, id=self.destination_ids.pop(0), default_branch="main")
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
            return pub.publish(repo, self.source, branch, self.root)

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

    def test_unprotected_source_main_denied_before_any_upload(self):
        with patch.object(pub, "reviewed_source_main",
                          side_effect=pub.Denied("source main is not protected")):
            with self.assertRaisesRegex(pub.Denied, "not protected"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_source_approval_revoked_before_first_blob_does_not_export(self):
        # Revocation after the initial approved snapshot must be checked
        # again at the first upload, not merely before creating the CI ref.
        with patch.object(pub, "require_live_source_authority",
                          side_effect=pub.Denied("source approval revoked")):
            with self.assertRaisesRegex(pub.Denied, "source approval revoked"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_source_approval_revoked_before_second_blob_stops_export(self):
        (self.source / "second.ores").write_text("another source program\\n")
        guard_calls = [0]
        def authorize_next_file(_approved_main):
            guard_calls[0] += 1
            if guard_calls[0] == 2:
                raise pub.Denied("source approval revoked during private upload")
        with patch.object(pub, "require_live_source_authority",
                          side_effect=authorize_next_file):
            with self.assertRaisesRegex(pub.Denied, "revoked during private upload"):
                self.publish()
        writes = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])
        self.assertEqual(guard_calls[0], 2)

    def test_source_tree_mismatch_denied_before_any_upload(self):
        with patch.object(pub, "require_pinned_snapshot",
                          side_effect=pub.Denied("source commit Git tree differs")):
            with self.assertRaisesRegex(pub.Denied, "source commit Git tree"):
                self.publish()
        self.assertFalse(any(method == "POST" for method, _, _ in self.route_calls))

    def test_unapproved_numeric_id_denied_before_first_upload(self):
        # Reused/renamed GitHub slug may still identify a private repository.
        self.destination_ids = [246811] * 100
        with self.assertRaisesRegex(pub.Denied, "numeric repository ID"):
            self.publish()
        self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_numeric_id_flip_aborts_before_second_upload(self):
        self.destination_ids = [246810, 246810, 246811] + [246811] * 100
        with self.assertRaisesRegex(pub.Denied, "numeric repository ID"):
            self.publish()
        self.assertEqual([route for method, route, _ in self.route_calls
                          if method == "POST"], ["repos/trusted/ci/git/blobs"])

    def test_malformed_or_boolean_numeric_api_id_denied(self):
        for invalid in (None, True, "246810", 0, -1, 246811):
            with self.subTest(invalid=invalid):
                self.route_calls.clear()
                self.visibility = ["private"] * 100
                self.destination_ids = [invalid] * 100
                with self.assertRaises(pub.Denied):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_policy_requires_exact_reviewed_name_and_numeric_id(self):
        invalid_policies = (
            "trusted/ci\n",
            "trusted/ci 0246810\n",
            "trusted/ci 246810 # comment\n",
            "trusted/ci\t246810\n",
            "trusted/ci 246810\nTRUSTED/CI 246810\n",
            "trusted/ci 246810\ntrusted/ci 246811\n",
        )
        for contents in invalid_policies:
            with self.subTest(contents=contents):
                self.approval.write_text(contents)
                self.route_calls.clear()
                with self.assertRaises(pub.Denied):
                    self.publish()
                self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))
        self.approval.write_text("trusted/ci 246810\n")

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

    def test_origin_main_commit_changed_before_ref_denied(self):
        with patch.object(pub, "reviewed_source_main",
                          side_effect=["a" * 40, "b" * 40]):
            with self.assertRaisesRegex(pub.Denied, "approval commit changed"):
                self.publish()
        writes = [route for method, route, _ in self.route_calls
                  if method == "POST"]
        self.assertIn("repos/trusted/ci/git/commits", writes)
        self.assertNotIn("repos/trusted/ci/git/refs", writes)

    def test_source_protection_revoked_before_publication_leaves_no_ref(self):
        calls = [0]
        def check_origin(*args):
            calls[0] += 1
            if calls[0] == 2:
                raise pub.Denied("source main protection was revoked")
        with patch.object(pub, "reviewed_source_main", side_effect=check_origin):
            with self.assertRaisesRegex(pub.Denied, "revoked"):
                self.publish()
        self.assertEqual(calls[0], 2)
        uploads = [route for method, route, _ in self.route_calls if method == "POST"]
        self.assertIn("repos/trusted/ci/git/commits", uploads)
        self.assertNotIn("repos/trusted/ci/git/refs", uploads)

    def test_mid_transfer_policy_revocation_aborts_without_new_branch(self):
        original = self.fake_api
        def revoke_after_blob(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == "repos/trusted/ci/git/blobs":
                self.approval.write_text("# approval revoked")
            return result
        with patch.object(pub, "gh_api", side_effect=revoke_after_blob):
            with self.assertRaisesRegex(pub.Denied, "approval.*(revoked|changed)"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root)
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
        outside.write_text("trusted/ci 246810\n")
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
        (outside / "private-ci-approved-repositories.txt").write_text("trusted/ci 246810\n")
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
        outside.write_text("trusted/ci 246810\n")
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
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_valid_looking_but_incorrect_blob_sha_is_rejected(self):
        original = self.fake_api
        def wrong(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/blobs":
                return {"sha": C}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong):
            with self.assertRaisesRegex(pub.Denied, "digest"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test", self.root)
        self.assertFalse(any(r.endswith("/git/refs") for _, r, _ in self.route_calls))

    def test_source_must_be_under_trusted_checkout(self):
        outside = self.root.parent / ("unsafe-" + self.root.name)
        outside.mkdir()
        try:
            (outside / "outside.ores").write_text("private data")
            with patch.object(pub, "gh_api", side_effect=self.fake_api):
                with self.assertRaisesRegex(pub.Denied, "trusted checkout"):
                    pub.publish("trusted/ci", outside, "ci/oreslang-test", self.root)
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


class ProtectedSourceSnapshotGateTests(unittest.TestCase):
    """No private blob API POST is reachable without protected provenance."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.root / ".github").mkdir()
        (self.root / "scripts").mkdir()
        (self.root / ".github/private-ci-approved-repositories.txt").write_text(
            "trusted/ci 246810\n")
        (self.root / "scripts/publish-private-ci-source.py").write_bytes(
            MODULE.read_bytes())
        (self.root / "scripts/check-private-ci-destination.sh").write_text(
            "#!/bin/bash\n")
        (self.root / "scripts/run-in-private-ci-destination.sh").write_text(
            "#!/bin/bash\n")
        (self.source / "hello.ores").write_text("safe\n")
        self.entries = pub.collect_source(self.source, self.root)
        self.tree = pub.checked_git_tree(self.entries)
        self.commit = "a" * 40
        self.routes = []
        self.protection = {
            "required_pull_request_reviews": {
                "required_approving_review_count": 1,
                "require_code_owner_reviews": True,
                "dismiss_stale_reviews": True,
                "bypass_pull_request_allowances": {
                    "users": [], "teams": [], "apps": []
                }
            },
            "required_status_checks": {
                "strict": True, "contexts": ["required-native-compiler"],
                "checks": []
            },
            "enforce_admins": {"enabled": True},
            "allow_force_pushes": {"enabled": False},
            "allow_deletions": {"enabled": False}
        }

    def remote(self, route, method="GET", payload=None):
        self.routes.append((method, route))
        if route == "repos/" + pub.SOURCE_REPO:
            return {"id": 1400792274, "full_name": pub.SOURCE_REPO,
                    "private": True}
        if route == "repos/" + pub.SOURCE_REPO + "/branches/main":
            return {"protected": True, "commit": {"sha": self.commit}}
        if route == "repos/" + pub.SOURCE_REPO + "/branches/main/protection":
            return self.protection
        if route.startswith("repos/" + pub.SOURCE_REPO + "/contents/"):
            path = route.split("/contents/", 1)[1].split("?ref=", 1)[0]
            data = (self.root / path).read_bytes()
            digest = hashlib.sha1(
                b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            return {"type": "file", "encoding": "base64",
                    "content": base64.b64encode(data).decode(), "sha": digest}
        if route == "repos/" + pub.SOURCE_REPO + "/git/commits/" + self.commit:
            return {"sha": self.commit, "tree": {"sha": self.tree}}
        raise AssertionError("unexpected source policy route " + route)

    def test_live_approval_check_requires_same_protected_main(self):
        with patch.object(pub, "gh_api", side_effect=self.remote):
            pub.require_live_source_authority(self.commit)
            with self.assertRaisesRegex(pub.Denied, "authorization changed"):
                pub.require_live_source_authority("b" * 40)
        def unprotected(route, method="GET", payload=None):
            if route == "repos/" + pub.SOURCE_REPO + "/branches/main":
                return {"protected": False, "commit": {"sha": self.commit}}
            return self.remote(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=unprotected):
            with self.assertRaisesRegex(pub.Denied, "authorization changed"):
                pub.require_live_source_authority(self.commit)

    def test_live_approval_check_rejects_protection_disable(self):
        self.protection["required_pull_request_reviews"]["require_code_owner_reviews"] = False
        with patch.object(pub, "gh_api", side_effect=self.remote):
            with self.assertRaisesRegex(pub.Denied, "mandatory independent"):
                pub.require_live_source_authority(self.commit)

    def test_single_file_git_tree_object_matches_exact_known_encoding(self):
        mode, data = self.entries[0][2], self.entries[0][1]
        blob = hashlib.sha1(
            b"blob " + str(len(data)).encode() + b"\0" + data).digest()
        body = mode.encode() + b" hello.ores\0" + blob
        expected = hashlib.sha1(
            b"tree " + str(len(body)).encode() + b"\0" + body).hexdigest()
        self.assertEqual(self.tree, expected)

    def test_tree_changes_with_content_and_executable_bit(self):
        alt = [("hello.ores", b"changed\n", "100644")]
        executable = [("hello.ores", self.entries[0][1], "100755")]
        self.assertNotEqual(self.tree, pub.checked_git_tree(alt))
        self.assertNotEqual(self.tree, pub.checked_git_tree(executable))

    def test_nested_paths_have_distinct_git_tree_hash(self):
        self.assertNotEqual(pub.checked_git_tree(
            [("hello.ores", b"x", "100644"),
             ("sub/other.ores", b"y", "100644")]),
            pub.checked_git_tree(
            [("hello.ores", b"x", "100644"),
             ("other.ores", b"y", "100644")]))

    def test_source_commit_and_branch_must_match_exact_tree(self):
        with patch.object(pub, "gh_api", side_effect=self.remote):
            self.assertEqual(self.tree, pub.require_pinned_snapshot(
                "ci/oreslang-" + self.tree, self.commit, self.entries))
        with patch.object(pub, "gh_api", side_effect=self.remote):
            with self.assertRaisesRegex(pub.Denied, "source bytes/modes"):
                pub.require_pinned_snapshot(
                    "ci/oreslang-" + "f" * 40, self.commit, self.entries)
        for invalid in ("ci/oreslang-test", "ci/oreslang-" + self.tree.upper(),
                        "ci/oreslang-" + self.tree[:-1]):
            with self.assertRaises(pub.Denied):
                pub.require_pinned_snapshot(invalid, self.commit, self.entries)

    def test_wrong_remote_source_commit_tree_fails_before_any_write(self):
        def false_tree(route, method="GET", payload=None):
            if "/git/commits/" in route:
                return {"sha": self.commit, "tree": {"sha": "0" * 40}}
            return self.remote(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=false_tree):
            with self.assertRaisesRegex(pub.Denied, "source commit Git tree"):
                pub.require_pinned_snapshot(
                    "ci/oreslang-" + self.tree, self.commit, self.entries)

    def test_deny_insufficient_main_branch_protection(self):
        """Protecting against force-push alone is insufficient to export source."""
        negative = [
            ("missing policy", None, None),
            ("missing approval reviewers", ("required_pull_request_reviews",), None),
            ("single review is not required", ("required_pull_request_reviews",
                                             "required_approving_review_count"), 0),
            ("code owner review disabled", ("required_pull_request_reviews",
                                           "require_code_owner_reviews"), False),
            ("stale reviews accepted", ("required_pull_request_reviews",
                                       "dismiss_stale_reviews"), False),
            ("admin bypass enabled", ("enforce_admins", "enabled"), False),
            ("strict CI disabled", ("required_status_checks", "strict"), False),
            ("empty CI status checks", ("required_status_checks", "contexts"), []),
            ("blank CI status name", ("required_status_checks", "contexts"), [" "]),
            ("invalid check job", ("required_status_checks", "checks"),
             [{"context": ""}]),
            ("force push enabled", ("allow_force_pushes", "enabled"), True),
            ("branch deletion enabled", ("allow_deletions", "enabled"), True),
            ("unreviewed bypass user", ("required_pull_request_reviews",
                                       "bypass_pull_request_allowances", "users"),
             [{"login": "bypass"}]),
        ]
        for label, path, value in negative:
            with self.subTest(case=label):
                proof = copy.deepcopy(self.protection)
                if path is None:
                    proof = None
                else:
                    parent = proof
                    for item in path[:-1]:
                        parent = parent[item]
                    parent[path[-1]] = value
                    if label == "empty CI status checks":
                        proof["required_status_checks"]["checks"] = []
                with patch.object(pub, "gh_api", return_value=proof) as api:
                    with self.assertRaises(pub.Denied):
                        pub.require_enforced_main_protection()
                    api.assert_called_once_with(
                        "repos/" + pub.SOURCE_REPO + "/branches/main/protection")

    def test_enforced_protection_checked_before_policy_contents(self):
        """Insufficient rules must deny before fetching any protected files."""
        self.protection["required_pull_request_reviews"][
            "require_code_owner_reviews"] = False
        with patch.object(pub, "gh_api", side_effect=self.remote):
            with self.assertRaisesRegex(pub.Denied, "code-owner"):
                pub.reviewed_source_main(self.root, "trusted/ci")
        self.assertFalse(any("/contents/" in route
                             for method, route in self.routes))
        self.assertFalse(any(method == "POST" for method, _ in self.routes))

    def test_enforced_protection_rechecked_after_policy_files(self):
        """Changing required CI/review config mid-check invalidates approval."""
        inspections = [0]
        def change_protection(route, method="GET", payload=None):
            if route == "repos/" + pub.SOURCE_REPO + "/branches/main/protection":
                inspections[0] += 1
                if inspections[0] == 2:
                    self.protection["required_status_checks"]["contexts"] = []
            return self.remote(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=change_protection):
            with self.assertRaisesRegex(pub.Denied, "status checks"):
                pub.reviewed_source_main(self.root, "trusted/ci")
        self.assertEqual(inspections[0], 2)

    def test_protected_source_policy_matches_reviewed_main(self):
        with patch.object(pub, "gh_api", side_effect=self.remote):
            pub.reviewed_source_main(self.root, "trusted/ci")

    def test_all_protected_files_use_same_immutable_main_sha(self):
        with patch.object(pub, "gh_api", side_effect=self.remote):
            result = pub.reviewed_source_main(self.root, "trusted/ci")
        self.assertEqual(result, self.commit)
        paths = [route for _, route in self.routes if "/contents/" in route]
        self.assertEqual(len(paths), 4)
        self.assertTrue(all(route.endswith("?ref=" + self.commit)
                            for route in paths))

    def test_main_revision_change_mid_policy_verification_denied(self):
        branch_reads = [0]
        def changing_main(route, method="GET", payload=None):
            if route == "repos/" + pub.SOURCE_REPO + "/branches/main":
                branch_reads[0] += 1
                sha = self.commit if branch_reads[0] == 1 else "f" * 40
                return {"protected": True, "commit": {"sha": sha}}
            return self.remote(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=changing_main):
            with self.assertRaisesRegex(pub.Denied, "changed during policy"):
                pub.reviewed_source_main(self.root, "trusted/ci")
        self.assertEqual(branch_reads[0], 2)

    def test_missing_protected_main_commit_identity_denied(self):
        def no_head(route, method="GET", payload=None):
            if route == "repos/" + pub.SOURCE_REPO + "/branches/main":
                return {"protected": True, "commit": {"sha": None}}
            return self.remote(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=no_head):
            with self.assertRaises(pub.Denied):
                pub.reviewed_source_main(self.root, "trusted/ci")

    def test_unprotected_source_main_denied_before_source_reads(self):
        def unprotected(route, method="GET", payload=None):
            if route == "repos/" + pub.SOURCE_REPO:
                return {"id": 1400792274, "full_name": pub.SOURCE_REPO,
                        "private": True}
            if route == "repos/" + pub.SOURCE_REPO + "/branches/main":
                return {"protected": False}
            raise AssertionError("unexpected API call")
        with patch.object(pub, "gh_api", side_effect=unprotected) as request:
            with self.assertRaisesRegex(pub.Denied, "not protected"):
                pub.reviewed_source_main(self.root, "trusted/ci")
            self.assertEqual(request.call_count, 2)

    def test_source_repo_name_collision_or_visibility_denied(self):
        for forged in (
                {"id": 1400792275, "full_name": pub.SOURCE_REPO,
                 "private": True},
                {"id": 1400792274, "full_name": "impostor/source",
                 "private": True},
                {"id": 1400792274, "full_name": pub.SOURCE_REPO,
                 "private": False}):
            with self.subTest(forged=forged):
                with patch.object(pub, "gh_api", return_value=forged) as api:
                    with self.assertRaisesRegex(pub.Denied, "identity"):
                        pub.reviewed_source_main(self.root, "trusted/ci")
                    self.assertEqual(api.call_count, 1)

    def test_locally_forged_allowlist_denied(self):
        original = (self.root / ".github/private-ci-approved-repositories.txt").read_bytes()
        (self.root / ".github/private-ci-approved-repositories.txt").write_text(
            "trusted/ci 246810\nunreviewed/destination 246811\n")
        def unchanged_remote(route, method="GET", payload=None):
            if route.endswith("/contents/.github/private-ci-approved-repositories.txt?ref=" + self.commit):
                data = original
                return {"type": "file", "encoding": "base64",
                        "content": base64.b64encode(data).decode(),
                        "sha": hashlib.sha1(
                            b"blob " + str(len(data)).encode() + b"\0" + data
                        ).hexdigest()}
            return self.remote(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=unchanged_remote):
            with self.assertRaisesRegex(pub.Denied, "differs from protected main"):
                pub.reviewed_source_main(self.root, "trusted/ci")

    def test_modified_local_publisher_denied(self):
        clean = (self.root / "scripts/publish-private-ci-source.py").read_bytes()
        def unchanged_remote(route, method="GET", payload=None):
            if route.endswith("/contents/scripts/publish-private-ci-source.py?ref=" + self.commit):
                return {"type": "file", "encoding": "base64",
                        "content": base64.b64encode(clean).decode(),
                        "sha": hashlib.sha1(
                            b"blob " + str(len(clean)).encode() + b"\0" + clean
                        ).hexdigest()}
            return self.remote(route, method, payload)
        (self.root / "scripts/publish-private-ci-source.py").write_bytes(clean+b"\n# modified")
        with patch.object(pub, "gh_api", side_effect=unchanged_remote):
            with self.assertRaisesRegex(pub.Denied, "differs from protected main"):
                pub.reviewed_source_main(self.root, "trusted/ci")

    def test_invalid_git_path_and_duplicate_collisions_denied(self):
        for entries in ([("../secret", b"x", "100644")],
                        [("same", b"x", "100644"), ("same", b"y", "100644")],
                        [("a", b"x", "100644"), ("a/x", b"y", "100644")],
                        [("wrong", b"x", "120000")]):
            with self.subTest(entries=entries), self.assertRaises(pub.Denied):
                pub.checked_git_tree(entries)


if __name__ == "__main__":
    unittest.main(verbosity=2)
