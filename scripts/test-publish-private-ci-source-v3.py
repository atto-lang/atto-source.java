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
from unittest.mock import patch

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
        "mode": "receipt",
        "repository_id": 1323598177,
        "base_sha": BASE,
        "workflow_path": ".github/workflows/oreslang-exact-tree-carrier.yml",
        "workflow_blob_sha": WORKFLOW,
        "test_plan_path": "ci/test-plans/java-pr1482.json",
        "test_plan_blob_sha": PLAN_BLOB,
    }


def connector_contract():
    return {
        "mode": "connector",
        "repository_id": 1412142815,
        "base_sha": BASE,
        "base_tree_sha": ROOT_TREE,
        "workflow_path": ".github/workflows/private-oreslang-exact-tree.yml",
        "workflow_blob_sha": WORKFLOW,
        "source_base_ref": "hardening/gate-b",
        "source_base_sha": "7" * 40,
    }


class PrivateCiV3Test(unittest.TestCase):
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

    def test_parse_connector_contract_pins_stacked_base_and_rejects_plan(self):
        raw = json.dumps(
            {
                "schema": 1,
                "carriers": {
                    "trusted/connector": {
                        "mode": "connector",
                        "repository_id": 1412142815,
                        "base_sha": BASE,
                        "base_tree_sha": ROOT_TREE,
                        "workflow_path": ".github/workflows/private-oreslang-exact-tree.yml",
                        "workflow_blob_sha": WORKFLOW,
                        "source_prs": {
                            "1616": {
                                "base_ref": "hardening/gate-b",
                                "base_sha": "7" * 40,
                            }
                        },
                    }
                },
            }
        ).encode()
        got = v3.parse_contract(raw, "trusted/connector", 1616, None)
        self.assertEqual(got["mode"], "connector")
        self.assertEqual(got["base_tree_sha"], ROOT_TREE)
        self.assertEqual(got["source_base_ref"], "hardening/gate-b")
        self.assertEqual(got["source_base_sha"], "7" * 40)
        with self.assertRaisesRegex(v3.Denied, "does not accept"):
            v3.parse_contract(
                raw, "trusted/connector", 1616, "ci/test-plans/injected.json"
            )
        with self.assertRaisesRegex(v3.Denied, "not authorized"):
            v3.parse_contract(raw, "trusted/connector", 1617, None)

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

    def test_checked_destination_connector_pins_exact_base_tree(self):
        carrier = connector_contract()
        def api(route, method="GET", payload=None):
            if route == "repos/trusted/connector/git/ref/heads/main":
                return {"object": {"sha": BASE}}
            if route == "repos/trusted/connector/git/commits/" + BASE:
                return {"sha": BASE, "tree": {"sha": "9" * 40}}
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
            with self.assertRaisesRegex(v3.Denied, "base tree differs"):
                v3.checked_destination("trusted/connector", carrier)

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
            with self.assertRaisesRegex(v3.Denied, "exact open PR head"):
                v3.verify_source_pr(1482, "8" * 40, contract())

    def test_verify_source_pr_connector_requires_exact_stacked_base(self):
        carrier = connector_contract()
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
            if route == "repos/" + v3.legacy.SOURCE_REPO + "/pulls/1616":
                return {
                    "number": 1616,
                    "state": "open",
                    "head": {
                        "sha": "8" * 40,
                        "repo": {"full_name": v3.legacy.SOURCE_REPO},
                    },
                    "base": {
                        "ref": carrier["source_base_ref"],
                        "sha": carrier["source_base_sha"],
                        "repo": {"full_name": v3.legacy.SOURCE_REPO},
                    },
                }
            raise AssertionError(route)
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            self.assertEqual(
                v3.verify_source_pr(1616, "8" * 40, carrier),
                v3.SOURCE_REPOSITORY_ID,
            )
        bad = dict(carrier)
        bad["source_base_sha"] = "6" * 40
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "protected connector contract"):
                v3.verify_source_pr(1616, "8" * 40, bad)

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
                v3.verify_source_pr(1482, "8" * 40, contract())

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
                patch.object(v3.legacy, "verify_protected_origin"),
                patch.object(v3, "protected_main_sha", return_value=PROTECTED),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(
                    v3,
                    "verify_source_pr",
                    return_value=v3.SOURCE_REPOSITORY_ID,
                ),
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
                ),
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
                patch.object(v3, "authorize_write"),
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
        self.assertIn(
            "Oreslang-CI-Receipt-SHA256: " + digest,
            commit_payload["message"].splitlines(),
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

    def test_publish_connector_emits_exact_carrier_envelope_without_receipt(self):
        carrier = connector_contract()
        writes = []
        commit_payload = {}

        def api(route, method="GET", payload=None):
            writes.append((method, route, payload))
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                if payload.get("base_tree") is None:
                    return {"sha": SOURCE_TREE}
                self.assertEqual(
                    payload["tree"],
                    [{
                        "path": v3.legacy.PREFIX,
                        "mode": "040000",
                        "type": "tree",
                        "sha": SOURCE_TREE,
                    }],
                )
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
                patch.object(
                    v3.legacy, "checked_policy_fingerprint", return_value=POLICY
                ),
                patch.object(
                    v3.legacy, "verify_protected_origin"
                ) as verify_origin,
                patch.object(
                    v3, "protected_main_sha", return_value=PROTECTED
                ) as protected_main,
                patch.object(
                    v3, "checked_contract", return_value=carrier
                ) as checked_contract,
                patch.object(
                    v3, "verify_source_pr",
                    return_value=v3.SOURCE_REPOSITORY_ID,
                ),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {
                            "id": carrier["repository_id"],
                            "private": True,
                            "visibility": "private",
                        },
                        ROOT_TREE,
                        None,
                    ),
                ),
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
                patch.object(v3, "authorize_write"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                result = v3.publish(
                    "trusted/connector",
                    source,
                    "4" * 40,
                    1616,
                    None,
                    root,
                )
                self.assertEqual(
                    verify_origin.call_count, 3,
                    "origin policy must be checked initially, before payload ref, and before authorization ref",
                )
                self.assertEqual(protected_main.call_count, 3)
                self.assertEqual(checked_contract.call_count, 3)

        self.assertNotIn("receipt_sha256", result)
        self.assertEqual(
            commit_payload["message"],
            "ci: exact private Oreslang tree " + "4" * 40
            + " tree " + SOURCE_TREE + "\n"
            + "carrier-base " + BASE + " tree " + ROOT_TREE + "\n"
            + "connector-authorized source-repository "
            + v3.legacy.SOURCE_REPO + " id " + str(v3.SOURCE_REPOSITORY_ID),
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
