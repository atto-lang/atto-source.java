#!/usr/bin/env python3
"""Offline adversarial tests for the private destination-bound publisher."""
import importlib.util
import base64
import hashlib
import re
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).with_name("publish-private-ci-source.py")
spec = importlib.util.spec_from_file_location("private_publisher", MODULE)
pub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pub)
EXACT_VERIFIER = pub.verify_exact_source_snapshot
UPSTREAM_VERIFIER = pub.verify_upstream_commit_tree
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
        self.main_policy = b"trusted/ci\n"
        self.main_sha = A
        self.main_protected = True
        (self.source / "hello.ores").write_text("pub fnc main(): int { return 42; }\n")
        self.route_calls = []
        self.visibility = ["private"] * 200
        self.destination_ids = [123456789] * 200
        self.source_ids = [pub.SOURCE_REPOSITORY_ID] * 200
        # Unit-test-only identity. The production pinned destination list is
        # unchanged and cannot authorize this synthetic trusted/ci carrier.
        pinned = patch.dict(pub.PINNED_DESTINATION_IDS, {"trusted/ci": 123456789})
        pinned.start()
        self.addCleanup(pinned.stop)
        self.preflight = patch.object(pub, "require_preflight")
        self.preflight.start()
        self.addCleanup(self.preflight.stop)
        # Legacy simulated GitHub tests isolate destination-side checks.
        # Exact commit/tree verification is exercised independently below.
        self.source_snapshot = patch.object(
            pub, "verify_exact_source_snapshot", return_value=D)
        self.source_snapshot.start()
        self.addCleanup(self.source_snapshot.stop)
        self.upstream_snapshot = patch.object(
            pub, "verify_upstream_commit_tree")
        self.upstream_snapshot.start()
        self.addCleanup(self.upstream_snapshot.stop)

    def fake_api(self, route, method="GET", payload=None):
        self.route_calls.append((method, route, payload))
        if route == "repos/" + pub.SOURCE_REPOSITORY + "/branches/main":
            return {"protected": self.main_protected,
                    "commit": {"sha": self.main_sha}}
        if route == ("repos/" + pub.SOURCE_REPOSITORY
                     + "/contents/.github/private-ci-approved-repositories.txt"
                     + "?ref=" + self.main_sha):
            raw = self.main_policy
            return {"type": "file", "encoding": "base64",
                    "content": base64.b64encode(raw).decode("ascii"),
                    "sha": hashlib.sha1(
                        b"blob " + str(len(raw)).encode("ascii")
                        + b"\0" + raw).hexdigest()}
        if route == "repos/" + pub.SOURCE_REPOSITORY:
            return dict(id=self.source_ids.pop(0),
                        full_name=pub.SOURCE_REPOSITORY, visibility="private",
                        private=True, archived=False, disabled=False)
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
            "repos/trusted/ci/git/commits/"+A: {"tree": {"sha": B}},
            "repos/trusted/ci/git/commits": {"sha": A},
            "repos/trusted/ci/git/trees/"+E: {
                "sha": E, "truncated": False, "tree": [
                    {"path": pub.PREFIX, "type": "tree", "mode": "040000", "sha": D}]},
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
            return pub.publish(repo, self.source, branch, self.root)

    def test_git_snapshot_refuses_missing_or_redirected_root_metadata(self):
        marker = self.root / ".git"
        with patch.object(pub.subprocess, "run") as called:
            with self.assertRaisesRegex(pub.Denied, "anchored Git metadata"):
                pub.git_snapshot_output(self.root, "rev-parse", "HEAD")
            called.assert_not_called()
            marker.write_text("gitdir: /untrusted/git")
            with self.assertRaisesRegex(pub.Denied, "anchored Git metadata"):
                pub.git_snapshot_output(self.root, "rev-parse", "HEAD")
            called.assert_not_called()
            marker.unlink()
            marker.symlink_to(self.source, target_is_directory=True)
            with self.assertRaisesRegex(pub.Denied, "anchored Git metadata"):
                pub.git_snapshot_output(self.root, "rev-parse", "HEAD")
            called.assert_not_called()

    def test_upstream_commit_tree_requires_original_github_objects(self):
        source_root = "repos/" + pub.SOURCE_REPOSITORY
        def exact(route, method="GET", payload=None):
            self.assertEqual(method, "GET")
            if route == source_root + "/git/commits/" + A:
                return {"sha": A, "tree": {"sha": D}}
            if route == source_root + "/git/trees/" + D:
                return {"sha": D, "truncated": False, "tree": [
                    {"path": "source", "type": "tree",
                     "mode": "040000", "sha": E}]}
            raise AssertionError("unexpected original-repository route: " + route)

        with patch.object(pub, "gh_api", side_effect=exact):
            UPSTREAM_VERIFIER(A, D, "")
            UPSTREAM_VERIFIER(A, E, "source")
            with self.assertRaisesRegex(pub.Denied, "attested"):
                UPSTREAM_VERIFIER(A, C, "")
            with self.assertRaisesRegex(pub.Denied, "attested"):
                UPSTREAM_VERIFIER(A, C, "source")
            with self.assertRaisesRegex(pub.Denied, "absent"):
                UPSTREAM_VERIFIER(A, E, "wrong-subtree")
            with self.assertRaisesRegex(pub.Denied, "invalid source subtree"):
                UPSTREAM_VERIFIER(A, E, "../source")

    def test_upstream_commit_rejects_missing_or_invalid_tree_metadata(self):
        route = "repos/" + pub.SOURCE_REPOSITORY + "/git/commits/" + A
        for response, expected_message in [
            ({}, "no verifiable Git tree"),
            ({"tree": None}, "no verifiable Git tree"),
            ({"tree": []}, "no verifiable Git tree"),
            ({"tree": {"sha": "not-a-sha"}}, "invalid object SHA"),
        ]:
            with self.subTest(response=response):
                def mocked(route_name, method="GET", payload=None):
                    self.assertEqual(route_name, route)
                    return response
                with patch.object(pub, "gh_api", side_effect=mocked):
                    with self.assertRaisesRegex(pub.Denied, expected_message):
                        UPSTREAM_VERIFIER(A, D, "")

    def test_upstream_github_commit_or_tree_identity_mismatch_is_rejected(self):
        source_root = "repos/" + pub.SOURCE_REPOSITORY
        def mismatched_commit(route, method="GET", payload=None):
            if route == source_root + "/git/commits/" + A:
                return {"sha": B, "tree": {"sha": D}}
            raise AssertionError(route)
        with patch.object(pub, "gh_api", side_effect=mismatched_commit):
            with self.assertRaisesRegex(pub.Denied, "commit identity"):
                UPSTREAM_VERIFIER(A, D, "")
        def mismatched_tree(route, method="GET", payload=None):
            if route == source_root + "/git/commits/" + A:
                return {"sha": A, "tree": {"sha": D}}
            if route == source_root + "/git/trees/" + D:
                return {"sha": C, "truncated": False, "tree": [
                    {"path": "source", "type": "tree",
                     "mode": "040000", "sha": E}]}
            raise AssertionError(route)
        with patch.object(pub, "gh_api", side_effect=mismatched_tree):
            with self.assertRaisesRegex(pub.Denied, "tree identity"):
                UPSTREAM_VERIFIER(A, E, "source")

    def test_upstream_github_tree_rejects_truncation_and_forged_metadata(self):
        source_root = "repos/" + pub.SOURCE_REPOSITORY
        for data, message in [
            ({"truncated": True, "tree": []}, "truncated"),
            ({"truncated": False, "tree": None}, "listing"),
            ({"truncated": False, "tree": [
                {"path": "source", "type": "blob",
                 "mode": "100644", "sha": E}]}, "absent"),
            ({"truncated": False, "tree": [
                {"path": "source", "type": "tree",
                 "mode": "100644", "sha": E}]}, "unexpected"),
        ]:
            def metadata(route, method="GET", payload=None):
                if route == source_root + "/git/commits/" + A:
                    return {"sha": A, "tree": {"sha": D}}
                if route == source_root + "/git/trees/" + D:
                    return {"sha": D, **data}
                raise AssertionError(route)
            with self.subTest(data=data):
                with patch.object(pub, "gh_api", side_effect=metadata):
                    with self.assertRaisesRegex(pub.Denied, message):
                        UPSTREAM_VERIFIER(A, E, "source")

    def test_upstream_attestation_failure_halts_private_upload(self):
        with patch.object(pub, "verify_upstream_commit_tree",
                          side_effect=pub.Denied("not in original GitHub")):
            with self.assertRaisesRegex(pub.Denied, "original GitHub"):
                self.publish()
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_publisher_aborts_before_upload_when_git_snapshot_rejected(self):
        with patch.object(pub, "verify_exact_source_snapshot",
                          side_effect=pub.Denied("Git tree mismatch")):
            with self.assertRaisesRegex(pub.Denied, "Git tree mismatch"):
                self.publish()
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_uploaded_subtree_must_equal_verified_source_tree(self):
        with patch.object(pub, "verify_exact_source_snapshot", return_value=C):
            with self.assertRaisesRegex(pub.Denied, "destination subtree"):
                self.publish()
        routes = [route for method, route, _ in self.route_calls
                  if method == "POST"]
        self.assertIn("repos/trusted/ci/git/trees", routes)
        self.assertNotIn("repos/trusted/ci/git/commits", routes)
        self.assertNotIn("repos/trusted/ci/git/refs", routes)

    def test_source_git_snapshot_requires_explicit_commit_and_tree(self):
        with self.assertRaisesRegex(pub.Denied, "source commit SHA"):
            EXACT_VERIFIER(self.source, self.root, [], None, D)
        with self.assertRaisesRegex(pub.Denied, "Git tree SHA"):
            EXACT_VERIFIER(self.source, self.root, [], A, None)

    def test_exact_snapshot_verifier_rejects_dirty_or_untracked_files(self):
        files = pub.collect_source(self.source, self.root)
        blob = hashlib.sha1(b"blob " + str(len(files[0][1])).encode("ascii")
                            + b"\0" + files[0][1]).hexdigest()
        entry = ("100644 blob " + blob + "\tsource/hello.ores").encode() + b"\0"
        expected = {
            ("rev-parse", "--verify", "HEAD^{commit}"): A.encode() + b"\n",
            ("rev-parse", "--verify", "HEAD:source"): D.encode() + b"\n",
            ("ls-tree", "-r", "-z", "--full-tree", "HEAD", "--", "source"): entry,
        }
        def metadata(root, *args):
            self.assertEqual(root, self.root)
            return expected[args]
        with patch.object(pub, "git_snapshot_output", side_effect=metadata):
            self.assertEqual(EXACT_VERIFIER(
                self.source, self.root, files, A, D), D)
            with self.assertRaisesRegex(pub.Denied, "checkout commit"):
                EXACT_VERIFIER(self.source, self.root, files, B, D)
            with self.assertRaisesRegex(pub.Denied, "Git tree differs"):
                EXACT_VERIFIER(self.source, self.root, files, A, C)
            with self.assertRaisesRegex(pub.Denied, "diverges"):
                EXACT_VERIFIER(self.source, self.root,
                               [("hello.ores", b"changed", "100644")], A, D)
            with self.assertRaisesRegex(pub.Denied, "untracked"):
                EXACT_VERIFIER(self.source, self.root,
                               files + [("new.ores", b"untracked", "100644")],
                               A, D)
            with self.assertRaisesRegex(pub.Denied, "missing"):
                EXACT_VERIFIER(self.source, self.root, [], A, D)
            with self.assertRaisesRegex(pub.Denied, "diverges"):
                EXACT_VERIFIER(self.source, self.root,
                               [(files[0][0], files[0][1], "100755")], A, D)

    def test_exact_snapshot_rejects_symlinks_and_gitlinks(self):
        files = pub.collect_source(self.source, self.root)
        for mode, kind in (("120000", "blob"), ("160000", "commit")):
            entry = (mode + " " + kind + " " + A
                     + "\tsource/hello.ores").encode() + b"\0"
            def metadata(root, *args):
                if args[0] == "rev-parse":
                    return (A if "HEAD^{commit}" in args else D).encode() + b"\n"
                return entry
            with patch.object(pub, "git_snapshot_output", side_effect=metadata):
                with self.assertRaisesRegex(pub.Denied, "unexportable"):
                    EXACT_VERIFIER(self.source, self.root, files, A, D)

    def test_git_metadata_is_excluded_only_at_trusted_checkout_root(self):
        marker = self.root / ".git"
        marker.mkdir()
        (marker / "config").write_text("PRIVATE GIT METADATA")
        files = pub.collect_source(self.root, self.root)
        self.assertTrue(any(path == "source/hello.ores"
                            for path, _, _ in files))
        self.assertFalse(any(".git" in path.split("/")
                             for path, _, _ in files))
        # Git metadata inside an exported subtree is never silently ignored.
        (self.source / ".git").mkdir()
        with self.assertRaisesRegex(pub.Denied, "nested"):
            pub.collect_source(self.root, self.root)
        (self.source / ".git").rmdir()
        # Nor can the trusted root metadata itself be a symlink.
        (marker / "config").unlink()
        marker.rmdir()
        marker.symlink_to(self.source, target_is_directory=True)
        with self.assertRaisesRegex(pub.Denied, "Git metadata"):
            pub.collect_source(self.root, self.root)

    def test_real_git_commit_and_tree_snapshot_round_trip(self):
        # Unlike the API-mocked cases, invoke the real Git executable and the
        # production snapshot checker using a disposable private-free fixture.
        import subprocess
        def git(*args):
            result = subprocess.run(
                ["git", "-C", str(self.root), *args],
                capture_output=True, check=True, timeout=10)
            return result.stdout.strip().decode("ascii")
        git("init", "-q")
        git("add", "--", "source/hello.ores")
        git("-c", "user.name=CI Fixture",
            "-c", "user.email=ci@example.invalid",
            "commit", "-qm", "verified")
        commit = git("rev-parse", "--verify", "HEAD^{commit}")
        tree = git("rev-parse", "--verify", "HEAD:source")
        clean = pub.collect_source(self.source, self.root)
        self.assertEqual(EXACT_VERIFIER(
            self.source, self.root, clean, commit, tree), tree)
        with self.assertRaisesRegex(pub.Denied, "checkout commit"):
            EXACT_VERIFIER(self.source, self.root, clean, B, tree)
        with self.assertRaisesRegex(pub.Denied, "Git tree differs"):
            EXACT_VERIFIER(self.source, self.root, clean, commit, C)
        (self.source / "untracked.ores").write_text("untracked")
        with self.assertRaisesRegex(pub.Denied, "untracked"):
            EXACT_VERIFIER(self.source, self.root,
                           pub.collect_source(self.source, self.root), commit, tree)
        (self.source / "untracked.ores").unlink()
        (self.source / "hello.ores").write_text("modified and unreviewed")
        with self.assertRaisesRegex(pub.Denied, "diverges"):
            EXACT_VERIFIER(self.source, self.root,
                           pub.collect_source(self.source, self.root), commit, tree)

    def test_regular_git_pointer_to_external_metadata_is_rejected(self):
        # A .git regular file may contain an external gitdir redirect.
        # Git then reads a commit/tree from outside the trusted checkout,
        # even when the collected files happen to match that external tree.
        external = self.root / "external-git-metadata"
        external.mkdir()
        marker = self.root / ".git"
        marker.write_text("gitdir: " + str(external) + "\n")
        with self.assertRaisesRegex(pub.Denied, "real directory"):
            pub.collect_source(self.root, self.root)
        self.assertTrue(marker.is_file())

    def test_wrapper_requires_explicit_git_provenance(self):
        script = MODULE.with_name("run-in-private-ci-destination.sh").read_text()
        self.assertIn("source-commit", script)
        self.assertIn("source-tree", script)
        self.assertIn("[[ $# -ne 9", script)

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

    def test_ref_attestation_rejects_malformed_payloads(self):
        valid = {"ref": "refs/heads/ci/oreslang-test",
                 "object": {"type": "commit", "sha": A}}
        with patch.object(pub, "gh_api") as call:
            for bad in (None, [], "not-an-object", 0):
                with self.subTest(creation=bad):
                    with self.assertRaisesRegex(pub.Denied, "exact new CI ref"):
                        pub.verify_private_ci_ref(
                            "trusted/ci", "ci/oreslang-test", A, bad)
                    call.assert_not_called()
        for bad in (None, [], "wrong-shape", 0):
            with self.subTest(readback=bad):
                with patch.object(pub, "gh_api", return_value=bad) as call:
                    with self.assertRaisesRegex(pub.Denied, "does not point"):
                        pub.verify_private_ci_ref(
                            "trusted/ci", "ci/oreslang-test", A, valid)
                    call.assert_called_once_with(
                        "repos/trusted/ci/git/ref/heads/ci/oreslang-test")

    def test_new_branch_ref_must_confirm_the_created_commit_sha(self):
        base = self.fake_api
        for value in [
            {"ref": "refs/heads/ci/oreslang-test"},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "commit", "sha": B}},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "tree", "sha": A}},
            {"ref": "refs/heads/main",
             "object": {"type": "commit", "sha": A}},
        ]:
            with self.subTest(value=value):
                self.route_calls.clear()
                def swapped(route, method="GET", payload=None):
                    if method == "POST" and route == "repos/trusted/ci/git/refs":
                        return value
                    return base(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=swapped):
                    with self.assertRaisesRegex(pub.Denied, "exact new CI ref"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root)
                self.assertFalse(any(r == "repos/trusted/ci/git/ref/heads/ci/oreslang-test"
                                     for _, r, _ in self.route_calls))

    def test_new_branch_readback_must_match_verified_commit(self):
        base = self.fake_api
        for value in [
            {"ref": "refs/heads/ci/oreslang-test"},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "commit", "sha": B}},
            {"ref": "refs/heads/ci/oreslang-test",
             "object": {"type": "tree", "sha": A}},
            {"ref": "refs/heads/other",
             "object": {"type": "commit", "sha": A}},
        ]:
            with self.subTest(value=value):
                self.route_calls.clear()
                def swapped(route, method="GET", payload=None):
                    if method == "GET" and route == "repos/trusted/ci/git/ref/heads/ci/oreslang-test":
                        return value
                    return base(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=swapped):
                    with self.assertRaisesRegex(pub.Denied, "does not point"):
                        pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                                    self.root)
                self.assertEqual(
                    sum(1 for method, route, _ in self.route_calls
                        if method == "POST" and route == "repos/trusted/ci/git/refs"), 1)

    def test_destination_root_attestation_rejects_mismatched_tree_metadata(self):
        valid = {"sha": E, "truncated": False,
                 "tree": [{"path": pub.PREFIX, "type": "tree",
                           "mode": "040000", "sha": D}]}
        invalid = [
            {},
            {"sha": C, "truncated": False, "tree": valid["tree"]},
            {"sha": E, "truncated": True, "tree": valid["tree"]},
            {"sha": E, "truncated": False, "tree": None},
            {"sha": E, "truncated": False, "tree": []},
            {"sha": E, "truncated": False, "tree":
                [{"path": pub.PREFIX, "type": "blob",
                  "mode": "100644", "sha": D}]},
            {"sha": E, "truncated": False, "tree":
                [{"path": pub.PREFIX, "type": "tree",
                  "mode": "100644", "sha": D}]},
            {"sha": E, "truncated": False, "tree":
                [{"path": pub.PREFIX, "type": "tree",
                  "mode": "040000", "sha": C}]},
            {"sha": E, "truncated": False, "tree":
                [valid["tree"][0], valid["tree"][0]]},
        ]
        route = "repos/trusted/ci/git/trees/" + E
        with patch.object(pub, "gh_api", return_value=valid) as call:
            pub.verify_destination_root_tree("trusted/ci", E, D)
            call.assert_called_once_with(route)
        for response in invalid:
            with self.subTest(response=response):
                with patch.object(pub, "gh_api", return_value=response):
                    with self.assertRaises(pub.Denied):
                        pub.verify_destination_root_tree("trusted/ci", E, D)

    def test_destination_root_attestation_aborts_before_any_commit_or_ref(self):
        original = self.fake_api
        def wrong_root(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/trees/" + E and method == "GET":
                self.route_calls.append((method, route, payload))
                return {"sha": E, "truncated": False, "tree": [
                    {"path": pub.PREFIX, "type": "tree",
                     "mode": "040000", "sha": C}]}
            return original(route, method, payload)
        with patch.object(pub, "gh_api", side_effect=wrong_root):
            with self.assertRaisesRegex(pub.Denied, "exact reviewed source subtree"):
                pub.publish("trusted/ci", self.source, "ci/oreslang-test",
                            self.root)
        writes = [route for method, route, _ in self.route_calls
                  if method == "POST"]
        self.assertIn("repos/trusted/ci/git/trees", writes)
        self.assertNotIn("repos/trusted/ci/git/commits", writes)
        self.assertNotIn("repos/trusted/ci/git/refs", writes)

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
                                        "ci/oreslang-test", self.root)
                    self.assertFalse(any(m == "POST" for m, _, _ in self.route_calls))

    def test_shell_preflight_pins_match_reviewed_python_publisher(self):
        # A second caller may trust the Bash preflight without invoking the
        # Python publisher. Their immutable destination pins must stay equal.
        script = MODULE.with_name("check-private-ci-destination.sh").read_text()
        listed = re.findall(
            r"^  ([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)[)] expected_id=([0-9]+) ;;$",
            script, flags=re.MULTILINE)
        # setUp adds trusted/ci only inside this offline fixture. It must
        # never appear in the actual reviewed production destination pins.
        production = {name: repo_id
                      for name, repo_id in pub.PINNED_DESTINATION_IDS.items()
                      if name != "trusted/ci"}
        self.assertEqual({name: int(repo_id) for name, repo_id in listed},
                         production)
        self.assertNotIn("trusted/ci", dict(listed))

    def test_pinned_funded_carrier_is_not_approved_by_empty_source_policy(self):
        # A numeric repository identity pin is a prerequisite, NOT consent
        # to export source code. A reviewed policy entry is indispensable.
        carrier = "ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra"
        self.assertEqual(pub.PINNED_DESTINATION_IDS[carrier], 1412142815)
        self.approval.write_text("# No approved private destinations\n")
        with self.assertRaisesRegex(pub.Denied, "not approved"):
            self.publish(repo=carrier)
        self.assertEqual(self.route_calls, [])

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

    def test_unprotected_original_source_main_rejects_export(self):
        self.main_protected = False
        with self.assertRaisesRegex(pub.Denied, "main is not protected"):
            self.publish()
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_unreviewed_local_policy_is_not_original_main_approval(self):
        # The local branch may name the destination, but protected original
        # main must contain exactly the same reviewed approval bytes.
        self.approval.write_text("trusted/ci\n# unreviewed local edit\n")
        with self.assertRaisesRegex(pub.Denied, "differs from protected"):
            self.publish()
        self.assertFalse(any(method == "POST"
                             for method, _, _ in self.route_calls))

    def test_protected_main_policy_metadata_must_be_verifiable(self):
        original = self.fake_api
        for mutation in (
                {"type": "symlink", "encoding": "base64", "content": ""},
                {"type": "file", "encoding": "none", "content": ""},
                {"type": "file", "encoding": "base64", "content": "not valid !"},
                {"type": "file", "encoding": "base64", "content":
                 base64.b64encode(self.main_policy).decode(), "sha": B}):
            with self.subTest(mutation=mutation):
                self.route_calls.clear()
                def fake(route, method="GET", payload=None):
                    if "/contents/.github/private-ci-approved-repositories.txt?" in route:
                        return mutation
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=fake):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root)
                self.assertFalse(any(method == "POST"
                                     for method, _, _ in self.route_calls))

    def test_protected_main_changes_during_transfer_abort_further_writes(self):
        original = self.fake_api
        def rotate_main_after_first_blob(route, method="GET", payload=None):
            response = original(route, method, payload)
            if method == "POST" and route == "repos/trusted/ci/git/blobs":
                self.main_sha = B
            return response
        with patch.object(pub, "gh_api", side_effect=rotate_main_after_first_blob):
            with self.assertRaisesRegex(pub.Denied, "source main changed"):
                pub.publish("trusted/ci", self.source,
                            "ci/oreslang-test", self.root)
        writes = [route for method, route, _ in self.route_calls
                  if method == "POST"]
        self.assertEqual(writes, ["repos/trusted/ci/git/blobs"])

    def test_remote_main_policy_verifier_rejects_malformed_branch(self):
        original = self.fake_api
        for invalid in (
                {"protected": False, "commit": {"sha": A}},
                {"protected": True, "commit": None},
                {"protected": True, "commit": {"sha": "invalid"}}):
            with self.subTest(invalid=invalid):
                self.route_calls.clear()
                def fake(route, method="GET", payload=None):
                    if route == "repos/" + pub.SOURCE_REPOSITORY + "/branches/main":
                        return invalid
                    return original(route, method, payload)
                with patch.object(pub, "gh_api", side_effect=fake):
                    with self.assertRaises(pub.Denied):
                        pub.publish("trusted/ci", self.source,
                                    "ci/oreslang-test", self.root)
                self.assertFalse(any(method == "POST"
                                     for method, _, _ in self.route_calls))

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
