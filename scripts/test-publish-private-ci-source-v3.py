#!/usr/bin/env python3
"""Source-free unit tests for the protected v3 private-CI dispatcher."""
import base64
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SCRIPT = Path(__file__).with_name("publish-private-ci-source-v3.py")
SPEC = importlib.util.spec_from_file_location("private_ci_v3", SCRIPT)
v3 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v3)

SOURCE_TREE = "a" * 40
ROOT_TREE = "b" * 40
COMMIT = "c" * 40
BASE = "d" * 40
WORKFLOW = "e" * 40
PLAN_BLOB = "f" * 40
PROTECTED = "1" * 40
POLICY = b"p" * 32
PLAN = (
    json.dumps(
        {
            "schema": "ores-private-ci-test-plan-v1",
            "source_repo": v3.legacy.SOURCE_REPO,
            "allowed_prs": [1482],
            "commands": [
                {
                    "argv": ["python3", "-c", "print('ok')"],
                    "cwd": ".",
                    "timeout_seconds": 30,
                }
            ],
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    + "\n"
).encode()


def contract():
    return {
        "repository_id": 1323598177,
        "base_sha": BASE,
        "workflow_path": ".github/workflows/oreslang-exact-tree-carrier.yml",
        "workflow_blob_sha": WORKFLOW,
        "test_plan_path": "ci/test-plans/java-pr1482.json",
        "test_plan_blob_sha": PLAN_BLOB,
    }


class PrivateCiV3Test(unittest.TestCase):
    def test_remote_protected_file_rejects_blob_sha_mismatch(self):
        raw = b"reviewed-bytes\n"
        encoded = base64.b64encode(raw).decode("ascii")

        with patch.object(
            v3.legacy,
            "gh_api",
            return_value={
                "path": "scripts/publish-private-ci-source-v3.py",
                "type": "file",
                "encoding": "base64",
                "content": encoded,
                "sha": "0" * 40,
            },
        ):
            with self.assertRaisesRegex(v3.Denied, "blob digest mismatch"):
                v3.remote_file_at_source_main(
                    "scripts/publish-private-ci-source-v3.py",
                    PROTECTED,
                    4096,
                )

    def test_control_plane_rejects_local_runtime_divergence(self):
        with (
            patch.object(v3, "require_control_plane_location"),
            patch.object(
                v3,
                "read_local_protected_file",
                return_value=b"modified-local-code\n",
            ),
            patch.object(
                v3,
                "remote_file_at_source_main",
                return_value=(b"reviewed-protected-code\n", "1" * 40),
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "control plane diverges"):
                v3.checked_control_plane(Path("."), PROTECTED)

    def test_control_plane_requires_executing_modules_under_trusted_root(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            scripts = root / "scripts"
            scripts.mkdir()
            v3_path = scripts / "publish-private-ci-source-v3.py"
            legacy_path = scripts / "publish-private-ci-source.py"
            v3_path.write_text("# reviewed v3\n")
            legacy_path.write_text("# reviewed legacy\n")

            with (
                patch.object(v3, "__file__", str(v3_path)),
                patch.object(v3, "LEGACY_PATH", legacy_path),
            ):
                v3.require_control_plane_location(root)

            outside = root / "modified-copy.py"
            outside.write_text("# modified\n")
            with (
                patch.object(v3, "__file__", str(outside)),
                patch.object(v3, "LEGACY_PATH", legacy_path),
            ):
                with self.assertRaisesRegex(v3.Denied, "outside the trusted control plane"):
                    v3.require_control_plane_location(root)

    def test_safe_policy_path_rejects_route_ambiguity(self):
        bad = (
            "ci/test-plans/./x.json",
            "ci/test-plans//x.json",
            "ci/test-plans/x.json?ref=evil.json",
            "ci/test-plans/x.json#fragment.json",
            "ci/test-plans/x%2Fy.json",
            "ci/test-plans/../x.json",
            "ci/test-plans/.GIT/x.json",
            "ci/test-plans/x y.json",
        )
        for value in bad:
            with self.subTest(value=value):
                with self.assertRaisesRegex(v3.Denied, "invalid protected carrier path"):
                    v3.safe_policy_path(value, "ci/test-plans/", ".json")
        self.assertEqual(
            v3.safe_policy_path(
                "ci/test-plans/java-pr1482.json",
                "ci/test-plans/",
                ".json",
            ),
            "ci/test-plans/java-pr1482.json",
        )

    def test_parse_contract_requires_exact_pr_and_pinned_fields(self):
        raw = json.dumps(
            {
                "schema": 1,
                "carriers": {
                    "trusted/ci": {
                        "repository_id": 42,
                        "base_sha": BASE,
                        "workflow_path": ".github/workflows/oreslang-exact-tree-carrier.yml",
                        "workflow_blob_sha": WORKFLOW,
                        "test_plans": {
                            "ci/test-plans/java-pr1482.json": {
                                "blob_sha": PLAN_BLOB,
                                "allowed_prs": [1482],
                            }
                        },
                    }
                },
            }
        ).encode()
        got = v3.parse_contract(
            raw, "trusted/ci", 1482, "ci/test-plans/java-pr1482.json"
        )
        self.assertEqual(got["repository_id"], 42)
        self.assertEqual(got["base_sha"], BASE)
        self.assertEqual(got["workflow_blob_sha"], WORKFLOW)
        self.assertEqual(got["test_plan_blob_sha"], PLAN_BLOB)
        with self.assertRaisesRegex(v3.Denied, "not authorized"):
            v3.parse_contract(
                raw, "trusted/ci", 1483, "ci/test-plans/java-pr1482.json"
            )

    def test_contract_rejects_duplicate_json_keys(self):
        raw = (
            '{"schema":1,"schema":1,"carriers":{"trusted/ci":{'
            '"repository_id":42,"base_sha":"' + BASE + '",'
            '"workflow_path":".github/workflows/x.yml",'
            '"workflow_blob_sha":"' + WORKFLOW + '",'
            '"test_plans":{}}}}'
        ).encode()
        with self.assertRaisesRegex(v3.Denied, "duplicate JSON key"):
            v3.parse_contract(raw, "trusted/ci", 1, "ci/test-plans/x.json")

    def test_contract_rejects_nonfinite_json_numbers(self):
        base = (
            '{"schema":1,"carriers":{"trusted/ci":{'
            '"repository_id":NaN,"base_sha":"' + BASE + '",'
            '"workflow_path":".github/workflows/x.yml",'
            '"workflow_blob_sha":"' + WORKFLOW + '",'
            '"test_plans":{}}}}'
        ).encode()
        with self.assertRaisesRegex(v3.Denied, "nonfinite"):
            v3.parse_contract(base, "trusted/ci", 1, "ci/test-plans/x.json")

    def test_test_plan_rejects_nonfinite_json_numbers(self):
        raw = (
            '{"schema":"ores-private-ci-test-plan-v1",'
            '"source_repo":"' + v3.legacy.SOURCE_REPO + '",'
            '"allowed_prs":[1482],"commands":[],"score":1e999999}'
        ).encode()
        with self.assertRaisesRegex(v3.Denied, "nonfinite"):
            v3.validate_test_plan(raw, 1482)

    def test_checked_destination_rejects_source_bearing_reviewed_base(self):
        carrier = contract()
        def api(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/ref/heads/main":
                return {"object": {"sha": BASE}}
            if route == "repos/trusted/ci/git/commits/" + BASE:
                return {"sha": BASE, "tree": {"sha": ROOT_TREE}}
            if route == "repos/trusted/ci/git/trees/" + ROOT_TREE + "?recursive=1":
                return {
                    "sha": ROOT_TREE,
                    "truncated": False,
                    "tree": [
                        {
                            "path": v3.legacy.PREFIX + "/old.ores",
                            "type": "blob",
                            "mode": "100644",
                            "sha": "2" * 40,
                        }
                    ],
                }
            raise AssertionError(route)
        with patch.object(
            v3.legacy,
            "checked_repo",
            return_value={
                "id": carrier["repository_id"],
                "private": True,
                "visibility": "private",
                "archived": False,
                "disabled": False,
                "fork": False,
                "default_branch": "main",
            },
        ), patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "not source-free"):
                v3.checked_destination("trusted/ci", carrier)

    def test_verify_source_pr_requires_exact_open_head(self):
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                    "archived": False,
                    "disabled": False,
                    "fork": False,
                }
            if route == "repos/" + v3.legacy.SOURCE_REPO + "/pulls/1482":
                return {
                    "number": 1482,
                    "state": "open",
                    "head": {
                        "sha": "9" * 40,
                        "repo": {"full_name": v3.legacy.SOURCE_REPO},
                    },
                    "base": {"ref": "main"},
                }
            raise AssertionError(route)
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "exact reviewed open PR head"):
                v3.verify_source_pr(1482, "8" * 40)

    def test_verify_source_pr_rejects_source_repository_id_replacement(self):
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID + 1,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                    "archived": False,
                    "disabled": False,
                    "fork": False,
                }
            raise AssertionError(route)
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "identity/state"):
                v3.verify_source_pr(1482, "8" * 40)

    def test_verify_source_pr_accepts_independent_exact_head_codeowner_approval(self):
        head_sha = "8" * 40
        reviews_route = (
            f"repos/{v3.legacy.SOURCE_REPO}/pulls/1482/reviews?per_page=100"
        )
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                    "archived": False,
                    "disabled": False,
                    "fork": False,
                }
            if route == "repos/" + v3.legacy.SOURCE_REPO + "/pulls/1482":
                return {
                    "number": 1482,
                    "state": "open",
                    "draft": False,
                    "user": {"login": "contributor"},
                    "head": {
                        "sha": head_sha,
                        "repo": {"full_name": v3.legacy.SOURCE_REPO},
                    },
                    "base": {
                        "ref": "main",
                        "repo": {"full_name": v3.legacy.SOURCE_REPO},
                    },
                }
            if route == reviews_route:
                return {
                    "reviews": [{
                        "id": 41,
                        "state": "APPROVED",
                        "commit_id": head_sha,
                        "submitted_at": "2026-10-20T18:00:00Z",
                        "user": {"login": v3.TRUSTED_SOURCE_REVIEWER},
                    }]
                }
            raise AssertionError(route)
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            self.assertEqual(
                v3.verify_source_pr(1482, head_sha),
                v3.SOURCE_REPOSITORY_ID,
            )

    def test_verify_source_pr_rejects_stale_dismissed_or_self_approval(self):
        head_sha = "8" * 40
        reviews_route = (
            f"repos/{v3.legacy.SOURCE_REPO}/pulls/1482/reviews?per_page=100"
        )
        def make_api(state="APPROVED", commit_id=None, author="contributor"):
            commit_id = head_sha if commit_id is None else commit_id
            def api(route, method="GET", payload=None):
                if route == "repos/" + v3.legacy.SOURCE_REPO:
                    return {
                        "id": v3.SOURCE_REPOSITORY_ID,
                        "full_name": v3.legacy.SOURCE_REPO,
                        "private": True,
                        "visibility": "private",
                        "archived": False,
                        "disabled": False,
                        "fork": False,
                    }
                if route == "repos/" + v3.legacy.SOURCE_REPO + "/pulls/1482":
                    return {
                        "number": 1482,
                        "state": "open",
                        "draft": False,
                        "user": {"login": author},
                        "head": {
                            "sha": head_sha,
                            "repo": {"full_name": v3.legacy.SOURCE_REPO},
                        },
                        "base": {
                            "ref": "main",
                            "repo": {"full_name": v3.legacy.SOURCE_REPO},
                        },
                    }
                if route == reviews_route:
                    return {
                        "reviews": [{
                            "id": 42,
                            "state": state,
                            "commit_id": commit_id,
                            "submitted_at": "2026-10-20T18:00:00Z",
                            "user": {"login": v3.TRUSTED_SOURCE_REVIEWER},
                        }]
                    }
                raise AssertionError(route)
            return api

        for label, kwargs in (
            ("stale", {"commit_id": "9" * 40}),
            ("dismissed", {"state": "DISMISSED"}),
        ):
            with self.subTest(label=label):
                with patch.object(v3.legacy, "gh_api", side_effect=make_api(**kwargs)):
                    with self.assertRaisesRegex(v3.Denied, "exact-HEAD"):
                        v3.verify_source_pr(1482, head_sha)

        with patch.object(
            v3.legacy,
            "gh_api",
            side_effect=make_api(author=v3.TRUSTED_SOURCE_REVIEWER),
        ):
            with self.assertRaisesRegex(v3.Denied, "self-authorize"):
                v3.verify_source_pr(1482, head_sha)

    def test_authorize_write_rechecks_carrier_eligibility(self):
        carrier = contract()
        ineligible = {
            "id": carrier["repository_id"],
            "full_name": "trusted/ci",
            "private": True,
            "visibility": "private",
            "archived": True,
            "disabled": False,
            "fork": False,
            "default_branch": "main",
        }
        with (
            patch.object(
                v3.legacy,
                "checked_policy_fingerprint",
                return_value=POLICY,
            ),
            patch.object(v3.legacy, "checked_repo", return_value=ineligible),
        ):
            with self.assertRaisesRegex(v3.Denied, "identity/state"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier)

    def test_published_root_and_commit_readback_accepts_exact_leaves(self):
        base_tree = "7" * 40
        source_blob = "2" * 40
        receipt_blob = "3" * 40
        source_elements = [{
            "path": "hello.ores",
            "mode": "100644",
            "type": "blob",
            "sha": source_blob,
        }]
        base_entries = [
            {
                "path": ".github/workflows/oreslang-exact-tree-carrier.yml",
                "type": "blob", "mode": "100644", "sha": WORKFLOW,
            },
            {
                "path": "ci/test-plans/java-pr1482.json",
                "type": "blob", "mode": "100644", "sha": PLAN_BLOB,
            },
        ]
        published_entries = base_entries + [
            {
                "path": v3.legacy.PREFIX + "/hello.ores",
                "type": "blob", "mode": "100644", "sha": source_blob,
            },
            {
                "path": v3.RECEIPT_PATH,
                "type": "blob", "mode": "100644", "sha": receipt_blob,
            },
        ]

        def api(route, method="GET", payload=None):
            if route == f"repos/trusted/ci/git/trees/{base_tree}?recursive=1":
                return {"sha": base_tree, "truncated": False, "tree": base_entries}
            if route == f"repos/trusted/ci/git/trees/{ROOT_TREE}?recursive=1":
                return {"sha": ROOT_TREE, "truncated": False, "tree": published_entries}
            if route == f"repos/trusted/ci/git/commits/{COMMIT}":
                return {
                    "sha": COMMIT,
                    "tree": {"sha": ROOT_TREE},
                    "parents": [{"sha": BASE}],
                }
            raise AssertionError(route)

        with patch.object(v3.legacy, "gh_api", side_effect=api):
            v3.verify_published_root_and_commit(
                "trusted/ci", base_tree, BASE, SOURCE_TREE,
                source_elements, receipt_blob, ROOT_TREE, COMMIT,
            )

    def test_published_root_readback_rejects_extra_or_changed_leaf(self):
        base_tree = "7" * 40
        source_blob = "2" * 40
        receipt_blob = "3" * 40
        source_elements = [{
            "path": "hello.ores",
            "mode": "100644",
            "type": "blob",
            "sha": source_blob,
        }]
        base_entries = [{
            "path": ".github/workflows/oreslang-exact-tree-carrier.yml",
            "type": "blob", "mode": "100644", "sha": WORKFLOW,
        }]
        published_entries = base_entries + [
            {
                "path": v3.legacy.PREFIX + "/hello.ores",
                "type": "blob", "mode": "100644", "sha": source_blob,
            },
            {
                "path": v3.RECEIPT_PATH,
                "type": "blob", "mode": "100644", "sha": receipt_blob,
            },
            {
                "path": "unexpected.txt",
                "type": "blob", "mode": "100644", "sha": "8" * 40,
            },
        ]

        def api(route, method="GET", payload=None):
            if route == f"repos/trusted/ci/git/trees/{base_tree}?recursive=1":
                return {"sha": base_tree, "truncated": False, "tree": base_entries}
            if route == f"repos/trusted/ci/git/trees/{ROOT_TREE}?recursive=1":
                return {"sha": ROOT_TREE, "truncated": False, "tree": published_entries}
            raise AssertionError(route)

        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "root tree differs"):
                v3.verify_published_root_and_commit(
                    "trusted/ci", base_tree, BASE, SOURCE_TREE,
                    source_elements, receipt_blob, ROOT_TREE, COMMIT,
                )

    def test_published_commit_readback_rejects_wrong_tree_or_parent(self):
        base_tree = "7" * 40
        source_blob = "2" * 40
        receipt_blob = "3" * 40
        source_elements = [{
            "path": "hello.ores",
            "mode": "100644",
            "type": "blob",
            "sha": source_blob,
        }]
        base_entries = []
        published_entries = [
            {
                "path": v3.legacy.PREFIX + "/hello.ores",
                "type": "blob", "mode": "100644", "sha": source_blob,
            },
            {
                "path": v3.RECEIPT_PATH,
                "type": "blob", "mode": "100644", "sha": receipt_blob,
            },
        ]
        bad_commits = (
            {"sha": COMMIT, "tree": {"sha": "9" * 40}, "parents": [{"sha": BASE}]},
            {"sha": COMMIT, "tree": {"sha": ROOT_TREE}, "parents": [{"sha": "9" * 40}]},
            {"sha": COMMIT, "tree": {"sha": ROOT_TREE}, "parents": []},
        )
        for bad in bad_commits:
            with self.subTest(bad=bad):
                def api(route, method="GET", payload=None):
                    if route == f"repos/trusted/ci/git/trees/{base_tree}?recursive=1":
                        return {"sha": base_tree, "truncated": False, "tree": base_entries}
                    if route == f"repos/trusted/ci/git/trees/{ROOT_TREE}?recursive=1":
                        return {"sha": ROOT_TREE, "truncated": False, "tree": published_entries}
                    if route == f"repos/trusted/ci/git/commits/{COMMIT}":
                        return bad
                    raise AssertionError(route)

                with patch.object(v3.legacy, "gh_api", side_effect=api):
                    with self.assertRaisesRegex(v3.Denied, "commit tree or parent"):
                        v3.verify_published_root_and_commit(
                            "trusted/ci", base_tree, BASE, SOURCE_TREE,
                            source_elements, receipt_blob, ROOT_TREE, COMMIT,
                        )

    def test_publish_binds_receipt_branch_and_authorization_ref(self):
        carrier = contract()
        writes = []
        blob_payloads = []
        commit_payload = {}

        def api(route, method="GET", payload=None):
            writes.append((method, route, payload))
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                blob_payloads.append(data)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                if payload.get("base_tree") is None:
                    return {"sha": SOURCE_TREE}
                return {"sha": ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                commit_payload.update(payload)
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": payload["ref"], "object": {"sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "verify_protected_origin", return_value=PROTECTED
                ) as verify_origin,
                patch.object(v3, "checked_control_plane") as control_plane,
                patch.object(v3, "checked_contract", return_value=carrier) as checked_contract,
                patch.object(
                    v3,
                    "verify_source_pr",
                    return_value=v3.SOURCE_REPOSITORY_ID,
                ) as verify_source_pr,
                patch.object(
                    v3,
                    "checked_destination",
                    return_value=(
                        {
                            "id": carrier["repository_id"],
                            "private": True,
                            "visibility": "private",
                        },
                        "3" * 40,
                        PLAN,
                    ),
                ) as checked_destination,
                patch.object(
                    v3.legacy,
                    "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(
                    v3.legacy,
                    "verified_exact_source_tree",
                    return_value=SOURCE_TREE,
                ),
                patch.object(v3, "authorize_write") as authorize_write,
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(v3, "require_exact_ref") as require_exact_ref,
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                result = v3.publish(
                    "trusted/ci",
                    source,
                    "4" * 40,
                    1482,
                    "ci/test-plans/java-pr1482.json",
                    root,
                    now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                )

        self.assertEqual(verify_origin.call_count, 3)
        self.assertEqual(control_plane.call_count, 3)
        self.assertEqual(checked_contract.call_count, 3)
        self.assertEqual(checked_destination.call_count, 3)
        self.assertEqual(verify_source_pr.call_count, 3)
        self.assertEqual(authorize_write.call_count, 7)

        self.assertEqual(result["branch"], "ci/oreslang-" + SOURCE_TREE)
        self.assertEqual(
            result["authorization_ref"], "ci/authorized-" + COMMIT
        )
        receipt_bytes = next(
            item for item in blob_payloads if b'"schema":"ores-private-ci-receipt-v3"' in item
        )
        receipt = json.loads(receipt_bytes)
        self.assertEqual(receipt["expires_at"], "2026-10-21T00:00:00Z")
        self.assertEqual(receipt["source"]["pr"], 1482)
        self.assertEqual(
            receipt["source"]["repo_id"], v3.SOURCE_REPOSITORY_ID)
        self.assertEqual(receipt["source"]["head_sha"], "4" * 40)
        self.assertEqual(receipt["source"]["root_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["mirror"]["root_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["destination"]["repo_id"], 1323598177)
        self.assertEqual(receipt["test_plan"]["path"], carrier["test_plan_path"])
        self.assertEqual(
            receipt["test_plan"]["sha256"], hashlib.sha256(PLAN).hexdigest()
        )
        digest = v3.canonical_digest(receipt)
        self.assertEqual(result["receipt_sha256"], digest)
        self.assertEqual(
            commit_payload["message"],
            (
                "ci: exact private Oreslang tree "
                + "4" * 40
                + " tree "
                + SOURCE_TREE
                + "\ncarrier-base "
                + BASE
                + " tree "
                + "3" * 40
                + "\nreceipt-sha256 "
                + digest
                + "\nconnector-authorized source-repository "
                + v3.legacy.SOURCE_REPO
                + " id "
                + str(v3.SOURCE_REPOSITORY_ID)
            ),
        )
        refs = [
            payload["ref"]
            for method, route, payload in writes
            if method == "POST" and route.endswith("/git/refs")
        ]
        self.assertEqual(
            refs,
            [
                "refs/heads/ci/oreslang-" + SOURCE_TREE,
                "refs/heads/ci/authorized-" + COMMIT,
            ],
        )
        self.assertEqual(verify_source_pr.call_count, 3)
        self.assertEqual(
            [call.args for call in require_exact_ref.call_args_list],
            [
                (
                    "trusted/ci", "ci/oreslang-" + SOURCE_TREE, COMMIT,
                    carrier["repository_id"],
                ),
                (
                    "trusted/ci", "ci/oreslang-" + SOURCE_TREE, COMMIT,
                    carrier["repository_id"],
                ),
                (
                    "trusted/ci", "ci/authorized-" + COMMIT, COMMIT,
                    carrier["repository_id"],
                ),
            ],
        )

    def test_publish_rolls_back_payload_if_protection_changes_before_authorization(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": payload["ref"], "object": {"sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy,
                    "verify_protected_origin",
                    side_effect=[PROTECTED, PROTECTED, v3.Denied("protection revoked")],
                ),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
                patch.object(v3, "delete_exact_ref") as delete_ref,
            ):
                with self.assertRaisesRegex(v3.Denied, "protection revoked"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )

        delete_ref.assert_called_once_with(
            "trusted/ci", "ci/oreslang-" + SOURCE_TREE, COMMIT,
            carrier["repository_id"]
        )

    def test_publish_rolls_back_payload_if_pr_head_moves_before_authorization(self):
        carrier = contract()
        writes = []

        def api(route, method="GET", payload=None):
            writes.append((method, route, payload))
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": payload["ref"], "object": {"sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            verify_calls = [v3.SOURCE_REPOSITORY_ID, v3.SOURCE_REPOSITORY_ID, v3.Denied("PR head moved")]
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(v3.legacy, "verify_protected_origin", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", side_effect=verify_calls),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
                patch.object(v3, "delete_exact_ref") as delete_ref,
            ):
                with self.assertRaisesRegex(v3.Denied, "PR head moved"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )

        delete_ref.assert_called_once_with(
            "trusted/ci", "ci/oreslang-" + SOURCE_TREE, COMMIT,
            carrier["repository_id"]
        )
        ref_posts = [
            payload["ref"]
            for method, route, payload in writes
            if method == "POST" and route.endswith("/git/refs")
        ]
        self.assertEqual(ref_posts, ["refs/heads/ci/oreslang-" + SOURCE_TREE])

    def test_publish_rolls_back_payload_if_authorization_creation_fails(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                if payload["ref"].startswith("refs/heads/ci/authorized-"):
                    raise v3.Denied("authorization creation failed")
                return {"ref": payload["ref"], "object": {"sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(v3.legacy, "verify_protected_origin", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
                patch.object(v3, "delete_exact_ref") as delete_ref,
            ):
                with self.assertRaisesRegex(v3.Denied, "authorization creation failed"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )

        self.assertEqual(
            [call.args for call in delete_ref.call_args_list],
            [
                (
                    "trusted/ci", "ci/authorized-" + COMMIT, COMMIT,
                    carrier["repository_id"],
                ),
                (
                    "trusted/ci", "ci/oreslang-" + SOURCE_TREE, COMMIT,
                    carrier["repository_id"],
                ),
            ],
        )

    def test_publish_rolls_back_both_refs_on_authorization_confirmation_mismatch(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                if payload["ref"].startswith("refs/heads/ci/authorized-"):
                    return {"ref": payload["ref"], "object": {"sha": "9" * 40}}
                return {"ref": payload["ref"], "object": {"sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(v3.legacy, "verify_protected_origin", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
                patch.object(v3, "delete_exact_ref") as delete_ref,
            ):
                with self.assertRaisesRegex(v3.Denied, "dispatcher authorization ref"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )

        self.assertEqual(
            [call.args for call in delete_ref.call_args_list],
            [
                (
                    "trusted/ci", "ci/authorized-" + COMMIT, COMMIT,
                    carrier["repository_id"],
                ),
                (
                    "trusted/ci", "ci/oreslang-" + SOURCE_TREE, COMMIT,
                    carrier["repository_id"],
                ),
            ],
        )

    def test_ref_request_accepts_exact_authorization_ref_and_uses_get_route(self):
        response = type(
            "Result", (),
            {"returncode": 0, "stderr": "", "stdout": "{}"},
        )()
        ref_name = "ci/authorized-" + COMMIT
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "fixture", "PATH": "/usr/bin:/bin"},
            ),
            patch.object(v3.subprocess, "run", return_value=response) as run,
        ):
            self.assertIs(
                v3._gh_ref_request("trusted/ci", ref_name, "GET"),
                response,
            )
        argv = run.call_args.args[0]
        self.assertIn(
            "repos/trusted/ci/git/ref/heads/ci%2Fauthorized-" + COMMIT,
            argv,
        )

    def test_ref_request_uses_plural_delete_route(self):
        response = type(
            "Result", (),
            {"returncode": 0, "stderr": "", "stdout": ""},
        )()
        ref_name = "ci/authorized-" + COMMIT
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "fixture", "PATH": "/usr/bin:/bin"},
            ),
            patch.object(v3.subprocess, "run", return_value=response) as run,
        ):
            self.assertIs(
                v3._gh_ref_request("trusted/ci", ref_name, "DELETE"),
                response,
            )
        argv = run.call_args.args[0]
        self.assertIn(
            "repos/trusted/ci/git/refs/heads/ci%2Fauthorized-" + COMMIT,
            argv,
        )

    def test_ref_request_rejects_unrelated_ci_namespace(self):
        with self.assertRaisesRegex(v3.Denied, "target is invalid"):
            v3._gh_ref_request("trusted/ci", "ci/not-an-oreslang-handoff", "GET")

    def test_ref_request_uses_absolute_cli_and_minimal_environment(self):
        response = Mock(returncode=0, stderr="", stdout="{}")
        ref_name = "ci/authorized-" + COMMIT
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "fixture", "PATH": "/usr/bin:/bin"},
            ),
            patch.object(v3.subprocess, "run", return_value=response) as run,
        ):
            v3._gh_ref_request("trusted/ci", ref_name, "GET")
        self.assertEqual(run.call_args.args[0][0], "/usr/bin/gh")
        self.assertEqual(
            run.call_args.kwargs["env"],
            {"GH_TOKEN": "fixture", "PATH": "/usr/bin:/bin"},
        )

    def test_ref_request_rejects_oversized_or_nontext_output(self):
        ref_name = "ci/authorized-" + COMMIT
        payloads = (
            Mock(returncode=0, stdout="x" * 129, stderr=""),
            Mock(returncode=0, stdout="", stderr="x" * 129),
            Mock(returncode=0, stdout=None, stderr=""),
            Mock(returncode=0, stdout="", stderr=None),
        )
        with (
            patch.object(v3.legacy, "MAX_GH_API_RESPONSE_CHARS", 128),
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "fixture", "PATH": "/usr/bin:/bin"},
            ),
        ):
            for response in payloads:
                with self.subTest(stdout=response.stdout, stderr=response.stderr):
                    with patch.object(v3.subprocess, "run", return_value=response):
                        with self.assertRaisesRegex(v3.Denied, "exceeds size limit"):
                            v3._gh_ref_request("trusted/ci", ref_name, "GET")

    def test_ref_request_accepts_bounded_text_output(self):
        response = Mock(returncode=0, stdout="{}", stderr="")
        ref_name = "ci/authorized-" + COMMIT
        with (
            patch.object(v3.legacy, "MAX_GH_API_RESPONSE_CHARS", 128),
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "fixture", "PATH": "/usr/bin:/bin"},
            ),
            patch.object(v3.subprocess, "run", return_value=response),
        ):
            self.assertIs(v3._gh_ref_request("trusted/ci", ref_name, "GET"), response)

    def test_live_ref_rejects_duplicate_or_nonfinite_json(self):
        payloads = (
            '{"ref":"refs/heads/ci/authorized-' + COMMIT +
            '","object":{"type":"commit","sha":"' + COMMIT +
            '","sha":"' + COMMIT + '"}}',
            '{"ref":"refs/heads/ci/authorized-' + COMMIT +
            '","object":{"type":"commit","sha":"' + COMMIT +
            '"},"score":NaN}',
        )
        with patch.object(
            v3.legacy, "checked_repo",
            return_value={
                "id": 1323598177,
                "full_name": "trusted/ci",
                "private": True,
                "visibility": "private",
            },
        ):
            for payload in payloads:
                with self.subTest(payload=payload):
                    response = Mock(returncode=0, stderr="", stdout=payload)
                    with patch.object(v3, "_gh_ref_request", return_value=response):
                        with self.assertRaises(v3.Denied):
                            v3.live_ref_sha(
                                "trusted/ci",
                                "ci/authorized-" + COMMIT,
                                1323598177,
                            )

    def test_live_ref_404_requires_carrier_identity_to_remain_visible(self):
        response = type(
            "Result", (),
            {"returncode": 1, "stderr": "HTTP 404: Not Found", "stdout": ""},
        )()
        with (
            patch.object(v3, "_gh_ref_request", return_value=response),
            patch.object(
                v3.legacy,
                "checked_repo",
                side_effect=v3.Denied("carrier no longer visible"),
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "no longer visible"):
                v3.live_ref_sha(
                    "trusted/ci", "ci/authorized-" + COMMIT, 1323598177
                )

    def test_live_ref_rejects_carrier_repository_id_replacement(self):
        response = type(
            "Result", (),
            {"returncode": 0, "stderr": "", "stdout": "{}"},
        )()
        with (
            patch.object(v3, "_gh_ref_request", return_value=response),
            patch.object(
                v3.legacy,
                "checked_repo",
                return_value={
                    "id": 999,
                    "full_name": "trusted/ci",
                    "private": True,
                    "visibility": "private",
                },
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "repository identity changed"):
                v3.live_ref_sha(
                    "trusted/ci", "ci/authorized-" + COMMIT, 1323598177
                )

    def test_live_ref_404_is_absent_only_after_private_carrier_recheck(self):
        response = type(
            "Result", (),
            {"returncode": 1, "stderr": "HTTP 404: Not Found", "stdout": ""},
        )()
        with (
            patch.object(v3, "_gh_ref_request", return_value=response),
            patch.object(
                v3.legacy,
                "checked_repo",
                return_value={
                    "id": 1323598177,
                    "full_name": "trusted/ci",
                    "private": True,
                    "visibility": "private",
                },
            ) as checked_repo,
        ):
            self.assertIsNone(
                v3.live_ref_sha(
                    "trusted/ci", "ci/authorized-" + COMMIT, 1323598177
                )
            )
        checked_repo.assert_called_once_with("trusted/ci")

    def test_delete_exact_ref_missing_is_idempotent(self):
        with patch.object(v3, "live_ref_sha", return_value=None):
            self.assertFalse(
                v3.delete_exact_ref(
                    "trusted/ci", "ci/oreslang-test", COMMIT, 1323598177
                )
            )

    def test_delete_exact_ref_refuses_moved_ref(self):
        with patch.object(v3, "live_ref_sha", return_value="9" * 40):
            with self.assertRaisesRegex(v3.Denied, "moved"):
                v3.delete_exact_ref(
                    "trusted/ci", "ci/oreslang-test", COMMIT, 1323598177
                )

    def test_delete_exact_ref_verifies_absence_after_delete(self):
        response = type("Result", (), {"returncode": 0, "stderr": ""})()
        with (
            patch.object(v3, "live_ref_sha", side_effect=[COMMIT, None]),
            patch.object(v3, "_gh_ref_request", return_value=response) as request,
        ):
            self.assertTrue(
                v3.delete_exact_ref(
                    "trusted/ci", "ci/oreslang-test", COMMIT, 1323598177
                )
            )
        request.assert_called_once_with(
            "trusted/ci", "ci/oreslang-test", "DELETE"
        )

    def test_delete_exact_ref_rejects_still_present_after_delete(self):
        response = type("Result", (), {"returncode": 0, "stderr": ""})()
        with (
            patch.object(v3, "live_ref_sha", side_effect=[COMMIT, COMMIT]),
            patch.object(v3, "_gh_ref_request", return_value=response),
        ):
            with self.assertRaisesRegex(v3.Denied, "still exists"):
                v3.delete_exact_ref(
                    "trusted/ci", "ci/oreslang-test", COMMIT, 1323598177
                )

    def test_manifest_matches_carrier_row_shape(self):
        rows, digest = v3.manifest(
            [
                ("b", b"two", "100755"),
                ("a", b"one", "100644"),
            ]
        )
        self.assertEqual([row[0] for row in rows], ["a", "b"])
        self.assertEqual(
            digest, hashlib.sha256(v3.canonical_bytes(rows)).hexdigest()
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)

