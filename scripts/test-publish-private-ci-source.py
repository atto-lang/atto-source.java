#!/usr/bin/env python3
import base64
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name("publish-private-ci-source.py")
SPEC = importlib.util.spec_from_file_location("ores_private_ci_publisher", SCRIPT)
pub = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(pub)

REPOSITORY = "trusted/ci"
REPOSITORY_ID = 1412142815
SOURCE_COMMIT = "1" * 40
SOURCE_TREE = "2" * 40
BASE_COMMIT = "3" * 40
BASE_TREE = "4" * 40
NEW_ROOT_TREE = "5" * 40
DEST_COMMIT = "6" * 40


def blob_sha(data):
    return hashlib.sha1(
        b"blob " + str(len(data)).encode("ascii") + bytes([0]) + data
    ).hexdigest()


def policy(repository=REPOSITORY, repository_id=REPOSITORY_ID):
    return (
        "# reviewed private carrier\n"
        f"{repository} {repository_id}\n"
    ).encode("utf-8")


class GitTreeFixture:
    def __init__(self, root, files=None, tree_sha=SOURCE_TREE):
        self.root = Path(root).resolve()
        self.files = files or {
            "README.md": ("100644", b"hello\n"),
            "scripts/run.sh": ("100755", b"#!/bin/sh\nexit 0\n"),
        }
        self.tree_sha = tree_sha
        self.blobs = {
            blob_sha(data): data
            for _, data in self.files.values()
        }

    def __call__(self, root, *args, timeout=45):
        del timeout
        self.assert_root(root)
        if args == ("rev-parse", "--show-toplevel"):
            return (str(self.root) + "\n").encode()
        if args == (
                "rev-parse", "--verify",
                SOURCE_COMMIT + "^{commit}"):
            return (SOURCE_COMMIT + "\n").encode()
        if args == (
                "rev-parse",
                SOURCE_COMMIT + "^{tree}"):
            return (self.tree_sha + "\n").encode()
        if args == (
                "ls-tree", "-r", "-z",
                "--full-tree", SOURCE_COMMIT):
            records = []
            for path, (mode, data) in self.files.items():
                sha = blob_sha(data)
                records.append(
                    f"{mode} blob {sha}\t{path}".encode("utf-8"))
            return bytes([0]).join(records) + bytes([0])
        if len(args) == 3 and args[:2] == ("cat-file", "-s"):
            data = self.blobs[args[2]]
            return (str(len(data)) + "\n").encode()
        if len(args) == 3 and args[:2] == ("cat-file", "blob"):
            return self.blobs[args[2]]
        raise AssertionError("unexpected git call: " + repr(args))

    def assert_root(self, root):
        if Path(root).resolve() != self.root:
            raise AssertionError("wrong git root")


class PrivateCiGitTreeTest(unittest.TestCase):
    def test_collects_exact_regular_git_objects(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            with mock.patch.object(
                    pub, "git_bytes", side_effect=fixture):
                entries, tree = pub.collect_git_tree(
                    root, SOURCE_COMMIT)
        self.assertEqual(SOURCE_TREE, tree)
        self.assertEqual(
            [(path, data, mode) for path, data, mode, _ in entries],
            [
                ("README.md", b"hello\n", "100644"),
                ("scripts/run.sh", b"#!/bin/sh\nexit 0\n", "100755"),
            ],
        )
        for _, data, _, sha in entries:
            self.assertEqual(blob_sha(data), sha)

    def test_untracked_worktree_bytes_are_never_collected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "UNTRACKED_SECRET").write_text(
                "do-not-copy", encoding="utf-8")
            fixture = GitTreeFixture(
                root, {"tracked.txt": ("100644", b"tracked")})
            with mock.patch.object(
                    pub, "git_bytes", side_effect=fixture):
                entries, _ = pub.collect_git_tree(
                    root, SOURCE_COMMIT)
        self.assertEqual(["tracked.txt"], [x[0] for x in entries])
        self.assertNotIn(
            b"do-not-copy", [x[1] for x in entries])

    def test_source_commit_must_resolve_to_exact_requested_sha(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            def git(root_arg, *args, timeout=45):
                if args == (
                        "rev-parse", "--verify",
                        SOURCE_COMMIT + "^{commit}"):
                    return ("9" * 40 + "\n").encode()
                return fixture(root_arg, *args, timeout=timeout)
            with mock.patch.object(pub, "git_bytes", side_effect=git):
                with self.assertRaisesRegex(
                        pub.Denied, "requested exact SHA"):
                    pub.collect_git_tree(root, SOURCE_COMMIT)

    def test_git_symlink_and_submodule_modes_fail_closed(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            for record in (
                    b"120000 blob " + b"a" * 40 + b"\tlink\0",
                    b"160000 commit " + b"a" * 40 + b"\tmodule\0"):
                def git(root_arg, *args, timeout=45, record=record):
                    if args == (
                            "ls-tree", "-r", "-z",
                            "--full-tree", SOURCE_COMMIT):
                        return record
                    return fixture(root_arg, *args, timeout=timeout)
                with mock.patch.object(
                        pub, "git_bytes", side_effect=git):
                    with self.assertRaisesRegex(
                            pub.Denied, "symlink, submodule"):
                        pub.collect_git_tree(root, SOURCE_COMMIT)

    def test_unsupported_git_paths_fail_before_blob_read(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            sha = blob_sha(b"x")
            record = (
                f"100644 blob {sha}\t../escape".encode()
                + bytes([0]))
            def git(root_arg, *args, timeout=45):
                if args == (
                        "ls-tree", "-r", "-z",
                        "--full-tree", SOURCE_COMMIT):
                    return record
                return fixture(root_arg, *args, timeout=timeout)
            with mock.patch.object(
                    pub, "git_bytes", side_effect=git):
                with self.assertRaisesRegex(
                        pub.Denied, "unsupported path"):
                    pub.collect_git_tree(root, SOURCE_COMMIT)

    def test_local_blob_bytes_must_match_git_object_id(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            listed = "a" * 40
            record = (
                f"100644 blob {listed}\tfile.txt".encode()
                + bytes([0]))
            def git(root_arg, *args, timeout=45):
                if args == (
                        "ls-tree", "-r", "-z",
                        "--full-tree", SOURCE_COMMIT):
                    return record
                if args == ("cat-file", "-s", listed):
                    return b"4\n"
                if args == ("cat-file", "blob", listed):
                    return b"oops"
                return fixture(root_arg, *args, timeout=timeout)
            with mock.patch.object(
                    pub, "git_bytes", side_effect=git):
                with self.assertRaisesRegex(
                        pub.Denied, "do not match source object ID"):
                    pub.collect_git_tree(root, SOURCE_COMMIT)

    def test_file_count_is_bounded_before_blob_reads(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            record = (
                b"100644 blob " + b"a" * 40
                + b"\tfile.txt\0")
            raw = record * (pub.MAX_FILES + 1)
            def git(root_arg, *args, timeout=45):
                if args == (
                        "ls-tree", "-r", "-z",
                        "--full-tree", SOURCE_COMMIT):
                    return raw
                return fixture(root_arg, *args, timeout=timeout)
            with mock.patch.object(
                    pub, "git_bytes", side_effect=git):
                with self.assertRaisesRegex(
                        pub.Denied, "file-count limit"):
                    pub.collect_git_tree(root, SOURCE_COMMIT)

    def test_single_blob_size_is_bounded_before_blob_read(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fixture = GitTreeFixture(root)
            listed = "a" * 40
            record = (
                f"100644 blob {listed}\tlarge.bin".encode()
                + bytes([0]))
            def git(root_arg, *args, timeout=45):
                if args == (
                        "ls-tree", "-r", "-z",
                        "--full-tree", SOURCE_COMMIT):
                    return record
                if args == ("cat-file", "-s", listed):
                    return (
                        str(pub.MAX_SINGLE + 1) + "\n"
                    ).encode()
                return fixture(root_arg, *args, timeout=timeout)
            with mock.patch.object(
                    pub, "git_bytes", side_effect=git):
                with self.assertRaisesRegex(
                        pub.Denied, "file exceeds"):
                    pub.collect_git_tree(root, SOURCE_COMMIT)


class PolicyTest(unittest.TestCase):
    def test_policy_pins_slug_and_immutable_repository_id(self):
        digest, repository_id = pub._policy_authorization(
            policy(), REPOSITORY)
        self.assertEqual(hashlib.sha256(policy()).digest(), digest)
        self.assertEqual(REPOSITORY_ID, repository_id)

    def test_slug_only_duplicate_and_unknown_policy_fail_closed(self):
        with self.assertRaisesRegex(pub.Denied, "invalid entry"):
            pub._policy_authorization(
                (REPOSITORY + "\n").encode(), REPOSITORY)
        with self.assertRaisesRegex(pub.Denied, "duplicate"):
            pub._policy_authorization(
                policy() + policy().splitlines()[-1] + b"\n",
                REPOSITORY)
        with self.assertRaisesRegex(pub.Denied, "revoked or absent"):
            pub._policy_authorization(
                b"other/repo 9\n", REPOSITORY)

    def test_main_policy_must_be_exact_reviewed_git_blob(self):
        response = {
            "type": "file",
            "encoding": "base64",
            "sha": pub.EXPECTED_POLICY_BLOB_SHA,
            "content": base64.b64encode(policy()).decode("ascii"),
        }
        with mock.patch.object(
                pub, "gh_api", return_value=response):
            _, repository_id = (
                pub.checked_protected_policy_fingerprint(
                    REPOSITORY))
        self.assertEqual(REPOSITORY_ID, repository_id)

        changed = dict(response, sha="f" * 40)
        with mock.patch.object(
                pub, "gh_api", return_value=changed):
            with self.assertRaisesRegex(
                    pub.Denied, "reviewed policy blob"):
                pub.checked_protected_policy_fingerprint(
                    REPOSITORY)

    def test_carrier_workflow_must_match_reviewed_blob(self):
        valid = {
            "type": "file",
            "sha": pub.EXPECTED_CARRIER_WORKFLOW_BLOB_SHA,
        }
        with mock.patch.object(pub, "gh_api", return_value=valid):
            self.assertEqual(
                pub.EXPECTED_CARRIER_WORKFLOW_BLOB_SHA,
                pub.checked_carrier_workflow(REPOSITORY, BASE_COMMIT))

        with mock.patch.object(
                pub, "gh_api",
                return_value=dict(valid, sha="f" * 40)):
            with self.assertRaisesRegex(
                    pub.Denied, "reviewed workflow blob"):
                pub.checked_carrier_workflow(REPOSITORY, BASE_COMMIT)

    def test_live_repository_identity_and_visibility_are_pinned(self):
        valid = {
            "full_name": REPOSITORY,
            "id": REPOSITORY_ID,
            "visibility": "private",
            "private": True,
        }
        with mock.patch.object(pub, "gh_api", return_value=valid):
            pub.checked_repo(REPOSITORY, REPOSITORY_ID)

        with mock.patch.object(
                pub, "gh_api",
                return_value=dict(valid, id=REPOSITORY_ID + 1)):
            with self.assertRaisesRegex(pub.Denied, "repository ID"):
                pub.checked_repo(REPOSITORY, REPOSITORY_ID)
        with mock.patch.object(
                pub, "gh_api",
                return_value=dict(
                    valid, visibility="public", private=False)):
            with self.assertRaisesRegex(pub.Denied, "private visibility"):
                pub.checked_repo(REPOSITORY, REPOSITORY_ID)

    def test_symlinked_local_policy_is_rejected(self):
        if not hasattr(pub.os, "O_NOFOLLOW"):
            self.skipTest("O_NOFOLLOW unavailable")
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / ".github").mkdir()
            outside = root / "outside-policy"
            outside.write_bytes(policy())
            (root / ".github" /
             "private-ci-approved-repositories.txt").symlink_to(
                    outside)
            with self.assertRaisesRegex(
                    pub.Denied, "cannot be opened safely"):
                pub.checked_policy_fingerprint(
                    root, REPOSITORY)


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = b"exact-source\n"
        self.source_blob = blob_sha(self.data)
        self.calls = []
        self.repo_ids = [REPOSITORY_ID] * 50
        self.visibility = ["private"] * 50

    def tearDown(self):
        self.temp.cleanup()

    def collect(self, source, source_commit):
        self.assertEqual(self.root.resolve(), source.resolve())
        self.assertEqual(SOURCE_COMMIT, source_commit)
        return [
            ("source.txt", self.data, "100644", self.source_blob)
        ], SOURCE_TREE

    def api(self, route, method="GET", payload=None):
        self.calls.append((method, route, payload))
        if route == "repos/" + REPOSITORY:
            visibility = self.visibility.pop(0)
            return {
                "full_name": REPOSITORY,
                "id": self.repo_ids.pop(0),
                "visibility": visibility,
                "private": visibility == "private",
                "default_branch": "main",
            }
        if route == f"repos/{REPOSITORY}/git/ref/heads/main":
            return {"object": {"sha": BASE_COMMIT}}
        if route == f"repos/{REPOSITORY}/git/commits/{BASE_COMMIT}":
            return {"tree": {"sha": BASE_TREE}}
        if route == (
                f"repos/{REPOSITORY}/contents/"
                f"{pub.CARRIER_WORKFLOW_PATH}?ref={BASE_COMMIT}"):
            return {
                "type": "file",
                "sha": pub.EXPECTED_CARRIER_WORKFLOW_BLOB_SHA,
            }
        if route == f"repos/{REPOSITORY}/git/blobs" and method == "POST":
            return {"sha": self.source_blob}
        if route == f"repos/{REPOSITORY}/git/trees" and method == "POST":
            if payload.get("base_tree") is None:
                return {"sha": SOURCE_TREE}
            return {"sha": NEW_ROOT_TREE}
        if route == f"repos/{REPOSITORY}/git/commits" and method == "POST":
            return {"sha": DEST_COMMIT}
        if route == f"repos/{REPOSITORY}/git/refs" and method == "POST":
            return {"ref": "refs/heads/ci/oreslang-test"}
        raise AssertionError(
            f"unexpected GitHub API call: {method} {route}")

    def patches(self):
        return (
            mock.patch.object(pub, "require_preflight"),
            mock.patch.object(
                pub, "checked_policy_fingerprint",
                return_value=(b"reviewed", REPOSITORY_ID)),
            mock.patch.object(
                pub, "checked_protected_policy_fingerprint",
                return_value=(b"reviewed", REPOSITORY_ID)),
            mock.patch.object(
                pub, "collect_git_tree", side_effect=self.collect),
            mock.patch.object(
                pub, "gh_api", side_effect=self.api),
        )

    def publish(self):
        p = self.patches()
        with p[0], p[1], p[2], p[3], p[4]:
            return pub.publish(
                REPOSITORY,
                self.root,
                SOURCE_COMMIT,
                "ci/oreslang-test",
                self.root,
            )

    def test_success_publishes_only_exact_source_tree_then_new_ref(self):
        commit, count, tree = self.publish()
        self.assertEqual(DEST_COMMIT, commit)
        self.assertEqual(1, count)
        self.assertEqual(SOURCE_TREE, tree)

        posts = [
            route for method, route, _ in self.calls
            if method == "POST"
        ]
        self.assertEqual(
            [
                f"repos/{REPOSITORY}/git/blobs",
                f"repos/{REPOSITORY}/git/trees",
                f"repos/{REPOSITORY}/git/trees",
                f"repos/{REPOSITORY}/git/commits",
                f"repos/{REPOSITORY}/git/refs",
            ],
            posts,
        )
        isolated = next(
            payload for method, route, payload in self.calls
            if method == "POST"
            and route == f"repos/{REPOSITORY}/git/trees"
            and payload.get("base_tree") is None)
        self.assertEqual(
            self.source_blob,
            isolated["tree"][0]["sha"])

    def test_destination_subtree_sha_must_equal_source_root_tree(self):
        original = self.api
        def wrong_tree(route, method="GET", payload=None):
            result = original(route, method, payload)
            if (route == f"repos/{REPOSITORY}/git/trees"
                    and method == "POST"
                    and payload.get("base_tree") is None):
                return {"sha": "a" * 40}
            return result
        p = self.patches()
        with p[0], p[1], p[2], p[3], mock.patch.object(
                pub, "gh_api", side_effect=wrong_tree):
            with self.assertRaisesRegex(
                    pub.Denied, "exact source Git tree"):
                pub.publish(
                    REPOSITORY, self.root, SOURCE_COMMIT,
                    "ci/oreslang-test", self.root)
        self.assertFalse(any(
            method == "POST"
            and route.endswith("/git/commits")
            for method, route, _ in self.calls))

    def test_uploaded_blob_sha_must_equal_source_git_blob(self):
        original = self.api
        def wrong_blob(route, method="GET", payload=None):
            result = original(route, method, payload)
            if route == f"repos/{REPOSITORY}/git/blobs":
                return {"sha": "b" * 40}
            return result
        p = self.patches()
        with p[0], p[1], p[2], p[3], mock.patch.object(
                pub, "gh_api", side_effect=wrong_blob):
            with self.assertRaisesRegex(
                    pub.Denied, "exact source Git blob"):
                pub.publish(
                    REPOSITORY, self.root, SOURCE_COMMIT,
                    "ci/oreslang-test", self.root)

    def test_repository_id_flip_stops_before_later_write(self):
        self.repo_ids = [
            REPOSITORY_ID,
            REPOSITORY_ID,
            REPOSITORY_ID + 1,
        ]
        with self.assertRaisesRegex(pub.Denied, "repository ID"):
            self.publish()
        posts = [
            route for method, route, _ in self.calls
            if method == "POST"
        ]
        self.assertEqual(
            [f"repos/{REPOSITORY}/git/blobs"],
            posts)

    def test_unreviewed_carrier_workflow_stops_before_source_collection(self):
        with (
            mock.patch.object(pub, "require_preflight"),
            mock.patch.object(
                pub, "checked_policy_fingerprint",
                return_value=(b"reviewed", REPOSITORY_ID)),
            mock.patch.object(
                pub, "checked_protected_policy_fingerprint",
                return_value=(b"reviewed", REPOSITORY_ID)),
            mock.patch.object(pub, "collect_git_tree") as collect,
        ):
            original = self.api
            def changed_workflow(route, method="GET", payload=None):
                if route == (
                        f"repos/{REPOSITORY}/contents/"
                        f"{pub.CARRIER_WORKFLOW_PATH}?ref={BASE_COMMIT}"):
                    return {"type": "file", "sha": "f" * 40}
                return original(route, method, payload)

            with mock.patch.object(
                    pub, "gh_api", side_effect=changed_workflow):
                with self.assertRaisesRegex(
                        pub.Denied, "reviewed workflow blob"):
                    pub.publish(
                        REPOSITORY, self.root, SOURCE_COMMIT,
                        "ci/oreslang-test", self.root)
            collect.assert_not_called()

    def test_policy_drift_stops_before_source_collection(self):
        with (
            mock.patch.object(pub, "require_preflight"),
            mock.patch.object(
                pub, "checked_policy_fingerprint",
                return_value=(b"local", REPOSITORY_ID)),
            mock.patch.object(
                pub, "checked_protected_policy_fingerprint",
                return_value=(b"main", REPOSITORY_ID)),
            mock.patch.object(
                pub, "collect_git_tree") as collect,
        ):
            with self.assertRaisesRegex(
                    pub.Denied, "differs from protected main"):
                pub.publish(
                    REPOSITORY, self.root, SOURCE_COMMIT,
                    "ci/oreslang-test", self.root)
            collect.assert_not_called()

    def test_source_directory_must_be_exact_trusted_repo_root(self):
        child = self.root / "child"
        child.mkdir()
        with (
            mock.patch.object(pub, "require_preflight"),
            mock.patch.object(
                pub, "checked_policy_fingerprint",
                return_value=(b"reviewed", REPOSITORY_ID)),
            mock.patch.object(
                pub, "checked_protected_policy_fingerprint",
                return_value=(b"reviewed", REPOSITORY_ID)),
        ):
            with self.assertRaisesRegex(
                    pub.Denied, "trusted checkout root"):
                pub.publish(
                    REPOSITORY, child, SOURCE_COMMIT,
                    "ci/oreslang-test", self.root)

    def test_invalid_branch_or_commit_rejects_before_preflight(self):
        with mock.patch.object(pub, "require_preflight") as preflight:
            with self.assertRaisesRegex(pub.Denied, "invalid destination"):
                pub.publish(
                    REPOSITORY, self.root, SOURCE_COMMIT,
                    "main", self.root)
            preflight.assert_not_called()

        with mock.patch.object(
                pub, "require_preflight") as preflight:
            with self.assertRaisesRegex(
                    pub.Denied, "source commit"):
                pub.publish(
                    REPOSITORY, self.root, "abc",
                    "ci/oreslang-test", self.root)
            preflight.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
