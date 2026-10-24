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
        "repository_id": 1323598177,
        "base_sha": BASE,
        "workflow_path": ".github/workflows/oreslang-exact-tree-carrier.yml",
        "workflow_blob_sha": WORKFLOW,
        "test_plan_path": "ci/test-plans/java-pr1482.json",
        "test_plan_blob_sha": PLAN_BLOB,
    }


class PrivateCiV3Test(unittest.TestCase):
    def review_governance_fixture(self, policy=b"trusted/ci 246810\n"):
        codeowners = (
            "\n".join(
                path + " " + v3.legacy.SECURITY_CODEOWNER
                for path in v3.legacy.SECURITY_CODEOWNER_PATHS
            )
            + "\n"
        ).encode("utf-8")
        blobs = {}
        for path in v3.REVIEWED_GOVERNANCE_PATHS:
            if path == v3.legacy.POLICY_PATH:
                raw = policy
            elif path == v3.legacy.CODEOWNERS_PATH:
                raw = codeowners
            else:
                raw = (path + "\n").encode("utf-8")
            blobs[path] = (raw, v3.git_blob_sha(raw))
        tree = {
            path: ("100644", "blob", blob)
            for path, (_raw, blob) in blobs.items()
        }
        return blobs, tree

    def review_authority_api(self, main_sha="2"*40, head_sha="3"*40,
                             author_id=424242, protected=False):
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                }
            if route == (
                    "repos/" + v3.legacy.SOURCE_REPO
                    + "/branches/" + v3.legacy.SOURCE_PROTECTED_BRANCH):
                return {
                    "name": v3.legacy.SOURCE_PROTECTED_BRANCH,
                    "protected": protected,
                    "commit": {"sha": main_sha},
                }
            if route == (
                    "repos/" + v3.legacy.SOURCE_REPO
                    + "/pulls/" + str(v3.REVIEW_AUTHORITY_PR)):
                return {
                    "number": v3.REVIEW_AUTHORITY_PR,
                    "state": "closed",
                    "merged_at": "2026-10-20T18:00:00Z",
                    "user": {"id": author_id, "login": "authority-author"},
                    "head": {
                        "sha": head_sha,
                        "repo": {
                            "full_name": v3.legacy.SOURCE_REPO,
                            "id": v3.SOURCE_REPOSITORY_ID,
                        },
                    },
                    "base": {
                        "ref": v3.legacy.SOURCE_PROTECTED_BRANCH,
                        "repo": {
                            "full_name": v3.legacy.SOURCE_REPO,
                            "id": v3.SOURCE_REPOSITORY_ID,
                        },
                    },
                }
            raise AssertionError((method, route))
        return api

    def approved_authority_review(self, head_sha="3"*40, review_id=7,
                                  state="APPROVED",
                                  submitted="2026-10-20T17:59:00Z"):
        return {
            "id": review_id,
            "state": state,
            "commit_id": head_sha,
            "submitted_at": submitted,
            "user": {
                "id": v3.REVIEW_AUTHORITY_USER_ID,
                "login": v3.REVIEW_AUTHORITY_LOGIN,
            },
        }

    def test_unprotected_main_accepts_exact_independent_review_attestation(self):
        policy = b"trusted/ci 246810\n"
        blobs, tree = self.review_governance_fixture(policy)
        api = self.review_authority_api()
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(
                v3, "gh_api_list",
                return_value=[self.approved_authority_review()],
            ),
            patch.object(v3, "governance_tree_entries", return_value=tree),
            patch.object(
                v3, "remote_file_at_source_main",
                side_effect=lambda path, _ref, _limit: blobs[path],
            ),
        ):
            self.assertEqual(
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                ),
                "2" * 40,
            )

    def test_review_attestation_rejects_author_as_security_reviewer(self):
        policy = b"trusted/ci 246810\n"
        api = self.review_authority_api(
            author_id=v3.REVIEW_AUTHORITY_USER_ID
        )
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "independent merged"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                )

    def test_review_attestation_rejects_latest_revocation(self):
        policy = b"trusted/ci 246810\n"
        api = self.review_authority_api()
        reviews = [
            self.approved_authority_review(review_id=7),
            self.approved_authority_review(
                review_id=8, state="CHANGES_REQUESTED",
                submitted="2026-10-20T17:59:30Z",
            ),
        ]
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(v3, "gh_api_list", return_value=reviews),
        ):
            with self.assertRaisesRegex(v3.Denied, "stale, late, or not approved"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                )

    def test_review_attestation_rejects_destination_repository_id_mismatch(self):
        policy = b"trusted/ci 246810\n"
        blobs, tree = self.review_governance_fixture(policy)
        api = self.review_authority_api()
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(
                v3, "gh_api_list",
                return_value=[self.approved_authority_review()],
            ),
            patch.object(v3, "governance_tree_entries", return_value=tree),
            patch.object(
                v3, "remote_file_at_source_main",
                side_effect=lambda path, _ref, _limit: blobs[path],
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "repository ID"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246811
                )

    def test_review_attestation_rejects_governance_git_entry_drift(self):
        policy = b"trusted/ci 246810\n"
        blobs, reviewed = self.review_governance_fixture(policy)
        current = dict(reviewed)
        target = "scripts/publish-private-ci-source-v3.py"
        current[target] = ("100755", "blob", reviewed[target][2])
        api = self.review_authority_api()
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(
                v3, "gh_api_list",
                return_value=[self.approved_authority_review()],
            ),
            patch.object(
                v3, "governance_tree_entries",
                side_effect=[reviewed, current],
            ),
            patch.object(
                v3, "remote_file_at_source_main",
                side_effect=lambda path, _ref, _limit: blobs[path],
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "Git entry drifted"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                )

    def test_authorized_main_prefers_classic_protection_when_available(self):
        branch = {
            "name": v3.legacy.SOURCE_PROTECTED_BRANCH,
            "protected": True,
            "commit": {"sha": PROTECTED},
        }
        with (
            patch.object(v3.legacy, "gh_api", return_value=branch),
            patch.object(
                v3.legacy, "verify_protected_origin",
                return_value=PROTECTED,
            ) as protected,
            patch.object(v3, "review_attested_main_sha") as fallback,
        ):
            self.assertEqual(
                v3.authorized_main_sha("trusted/ci", POLICY, 246810),
                PROTECTED,
            )
            protected.assert_called_once_with("trusted/ci", POLICY, 246810)
            fallback.assert_not_called()

    def test_maven_plan_requires_offline_mode_and_exact_pom_blob(self):
        base = {
            "schema": "ores-private-ci-test-plan-v1",
            "source_repo": v3.legacy.SOURCE_REPO,
            "allowed_prs": [1482],
            "commands": [{
                "argv": ["mvn", "-B", "test"],
                "cwd": ".",
                "timeout_seconds": 300,
            }],
        }
        raw = (json.dumps(base) + "\n").encode()
        with self.assertRaisesRegex(v3.Denied, "offline"):
            v3.validate_test_plan(raw, 1482)

        base["commands"][0]["argv"] = ["mvn", "-o", "-B", "test"]
        raw = (json.dumps(base) + "\n").encode()
        with self.assertRaisesRegex(v3.Denied, "bind exact pom.xml"):
            v3.validate_test_plan(raw, 1482)

        pom = b"<project/>\n"
        base["required_source_blobs"] = {"pom.xml": v3.git_blob_sha(pom)}
        raw = (json.dumps(base) + "\n").encode()
        meta = v3.validate_test_plan(raw, 1482)
        self.assertTrue(meta["uses_maven"])
        self.assertEqual(
            meta["required_source_blobs"]["pom.xml"],
            v3.git_blob_sha(pom),
        )
        v3.verify_required_source_blobs(
            [("pom.xml", pom, "100644")],
            meta["required_source_blobs"],
        )

    def test_required_source_blob_mismatch_fails_before_upload(self):
        expected = v3.git_blob_sha(b"reviewed pom\n")
        with self.assertRaisesRegex(v3.Denied, "required blob: pom.xml"):
            v3.verify_required_source_blobs(
                [("pom.xml", b"changed pom\n", "100644")],
                {"pom.xml": expected},
            )

    def test_test_plan_rejects_unreviewed_extension_fields(self):
        doc = json.loads(PLAN)
        doc["network"] = "allow"
        raw = (json.dumps(doc) + "\n").encode()
        with self.assertRaisesRegex(v3.Denied, "unexpected fields"):
            v3.validate_test_plan(raw, 1482)

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

    def test_local_control_plane_read_requires_descriptor_safety_flags(self):
        with (
            patch.object(v3.legacy, "safe_descriptor_flags_available", return_value=False),
            patch.object(v3.os, "open") as opened,
        ):
            with self.assertRaisesRegex(v3.Denied, "cannot safely read"):
                v3.read_local_protected_file(
                    Path("."), "scripts/publish-private-ci-source-v3.py", 4096
                )
            opened.assert_not_called()

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
            with self.assertRaisesRegex(v3.Denied, "exact open PR head"):
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

    def test_upload_source_tree_batches_utf8_content_in_one_authorized_write(self):
        files = [
            ("a.ores", b"one\n", "100644"),
            ("bin/run.sh", b"#!/bin/sh\n", "100755"),
        ]
        auth = Mock()
        with patch.object(
                v3.legacy, "gh_api", return_value={"sha": SOURCE_TREE}) as api:
            actual = v3.upload_source_tree(
                "trusted/ci", files, SOURCE_TREE, auth
            )

        self.assertEqual(actual, SOURCE_TREE)
        auth.assert_called_once_with()
        api.assert_called_once()
        route = api.call_args.args[0]
        kwargs = api.call_args.kwargs
        self.assertEqual(route, "repos/trusted/ci/git/trees")
        self.assertEqual(kwargs["method"], "POST")
        self.assertNotIn("base_tree", kwargs["payload"])
        entries = kwargs["payload"]["tree"]
        self.assertEqual(
            [(e["path"], e["mode"], e["content"]) for e in entries],
            [
                ("a.ores", "100644", "one\n"),
                ("bin/run.sh", "100755", "#!/bin/sh\n"),
            ],
        )
        self.assertTrue(all("sha" not in e for e in entries))

    def test_upload_source_tree_batches_under_one_auth_per_tree_write(self):
        files = [
            ("a.ores", b"one\n", "100644"),
            ("b.ores", b"two\n", "100644"),
        ]
        auth = Mock()
        first = "1" * 40
        calls = []

        def api(route, method="GET", payload=None):
            calls.append((route, method, payload))
            if len(calls) == 1:
                return {"sha": first}
            return {"sha": SOURCE_TREE}

        with (
            patch.object(v3, "MAX_TREE_BATCH_ENTRIES", 1),
            patch.object(v3.legacy, "gh_api", side_effect=api),
        ):
            actual = v3.upload_source_tree(
                "trusted/ci", files, SOURCE_TREE, auth
            )

        self.assertEqual(actual, SOURCE_TREE)
        self.assertEqual(auth.call_count, 2)
        self.assertEqual(len(calls), 2)
        self.assertNotIn("base_tree", calls[0][2])
        self.assertEqual(calls[1][2]["base_tree"], first)

    def test_upload_source_tree_binary_fallback_is_separately_authorized(self):
        data = b"\xff\x00binary"
        blob_sha = v3.git_blob_sha(data)
        auth = Mock()
        writes = []

        def api(route, method="GET", payload=None):
            writes.append((route, method, payload))
            if route.endswith("/git/blobs"):
                return {"sha": blob_sha}
            if route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE}
            raise AssertionError(route)

        with patch.object(v3.legacy, "gh_api", side_effect=api):
            actual = v3.upload_source_tree(
                "trusted/ci",
                [("fixture.bin", data, "100644")],
                SOURCE_TREE,
                auth,
            )

        self.assertEqual(actual, SOURCE_TREE)
        self.assertEqual(auth.call_count, 2)
        self.assertTrue(writes[0][0].endswith("/git/blobs"))
        self.assertTrue(writes[1][0].endswith("/git/trees"))
        entry = writes[1][2]["tree"][0]
        self.assertEqual(entry["sha"], blob_sha)
        self.assertNotIn("content", entry)

    def test_upload_source_tree_rejects_nonidentical_final_tree(self):
        auth = Mock()
        with patch.object(
                v3.legacy, "gh_api", return_value={"sha": "9" * 40}):
            with self.assertRaisesRegex(
                    v3.Denied, "differs from pinned PR tree"):
                v3.upload_source_tree(
                    "trusted/ci",
                    [("a.ores", b"one\n", "100644")],
                    SOURCE_TREE,
                    auth,
                )
        auth.assert_called_once_with()

    def test_full_tree_file_bound_exceeds_current_source_scale(self):
        self.assertGreaterEqual(v3.legacy.MAX_FILES, 2048)

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
            patch.object(v3.legacy, "require_resolved_blocking_incidents"),
            patch.object(
                v3.legacy,
                "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"]),
            ),
            patch.object(v3.legacy, "checked_repo", return_value=ineligible),
        ):
            with self.assertRaisesRegex(v3.Denied, "identity/state"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)

    def test_authorize_write_rejects_reviewed_repository_id_change(self):
        carrier = contract()
        with (
            patch.object(v3.legacy, "require_resolved_blocking_incidents"),
            patch.object(
                v3.legacy,
                "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"] + 1),
            ),
            patch.object(v3.legacy, "checked_repo") as checked_repo,
        ):
            with self.assertRaisesRegex(v3.Denied, "repository identity changed"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)
            checked_repo.assert_not_called()

    def test_authorize_write_rejects_reopened_incident_before_api_or_blob(self):
        """Fail closed when a P0 changes after preflight but before a write."""
        carrier = contract()
        with (
            patch.object(
                v3.legacy, "require_resolved_blocking_incidents",
                side_effect=v3.Denied("blocking P0 incident #77 remains unresolved"),
            ) as incident_gate,
            patch.object(v3.legacy, "checked_policy_approval") as policy,
            patch.object(v3.legacy, "checked_repo") as repo,
        ):
            with self.assertRaisesRegex(v3.Denied, "#77 remains unresolved"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)
            incident_gate.assert_called_once_with()
            policy.assert_not_called()
            repo.assert_not_called()

    def test_authorize_write_rejects_reopened_incident_127_before_api_or_blob(self):
        """Every source-bearing write must fail closed if P0 #127 reopens."""
        carrier = contract()
        with (
            patch.object(
                v3.legacy, "require_resolved_blocking_incidents",
                side_effect=v3.Denied("blocking P0 incident #127 remains unresolved"),
            ) as incident_gate,
            patch.object(v3.legacy, "checked_policy_approval") as policy,
            patch.object(v3.legacy, "checked_repo") as repo,
        ):
            with self.assertRaisesRegex(v3.Denied, "#127 remains unresolved"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)
            incident_gate.assert_called_once_with()
            policy.assert_not_called()
            repo.assert_not_called()

    def test_authorize_write_rejects_source_governance_revocation(self):
        carrier = contract()
        eligible = {
            "id": carrier["repository_id"],
            "full_name": "trusted/ci",
            "private": True,
            "visibility": "private",
            "archived": False,
            "disabled": False,
            "fork": False,
            "default_branch": "main",
        }
        with (
            patch.object(v3.legacy, "require_resolved_blocking_incidents"),
            patch.object(
                v3.legacy, "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"]),
            ),
            patch.object(v3.legacy, "checked_repo", return_value=eligible),
            patch.object(
                v3, "authorized_main_sha", return_value="9" * 40
            ) as source_authority,
            patch.object(v3, "verify_source_pr") as source_pr,
            patch.object(v3.legacy, "gh_api") as api,
        ):
            with self.assertRaisesRegex(
                    v3.Denied, "source governance authority changed"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)
            source_authority.assert_called_once_with(
                "trusted/ci", POLICY, carrier["repository_id"])
            source_pr.assert_not_called()
            api.assert_not_called()

    def test_authorize_write_rejects_source_pr_head_move(self):
        carrier = contract()
        eligible = {
            "id": carrier["repository_id"],
            "full_name": "trusted/ci",
            "private": True,
            "visibility": "private",
            "archived": False,
            "disabled": False,
            "fork": False,
            "default_branch": "main",
        }
        with (
            patch.object(v3.legacy, "require_resolved_blocking_incidents"),
            patch.object(
                v3.legacy, "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"]),
            ),
            patch.object(v3.legacy, "checked_repo", return_value=eligible),
            patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
            patch.object(
                v3, "verify_source_pr",
                return_value=v3.SOURCE_REPOSITORY_ID + 1,
            ) as source_pr,
            patch.object(v3.legacy, "gh_api") as api,
        ):
            with self.assertRaisesRegex(
                    v3.Denied, "source PR head/repository changed"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)
            source_pr.assert_called_once_with(1482, "4" * 40)
            api.assert_not_called()

    def test_authorize_write_rejects_carrier_main_advance(self):
        carrier = contract()
        eligible = {
            "id": carrier["repository_id"],
            "full_name": "trusted/ci",
            "private": True,
            "visibility": "private",
            "archived": False,
            "disabled": False,
            "fork": False,
            "default_branch": "main",
        }
        with (
            patch.object(v3.legacy, "require_resolved_blocking_incidents"),
            patch.object(
                v3.legacy, "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"]),
            ),
            patch.object(v3.legacy, "checked_repo", return_value=eligible),
            patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
            patch.object(
                v3, "verify_source_pr",
                return_value=v3.SOURCE_REPOSITORY_ID,
            ),
            patch.object(
                v3.legacy, "gh_api",
                return_value={"object": {"sha": "8" * 40}},
            ) as api,
        ):
            with self.assertRaisesRegex(
                    v3.Denied, "carrier main advanced"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier,
                    PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)
            api.assert_called_once_with("repos/trusted/ci/git/ref/heads/main")

    def test_authorize_write_accepts_only_all_live_fences(self):
        carrier = contract()
        eligible = {
            "id": carrier["repository_id"],
            "full_name": "trusted/ci",
            "private": True,
            "visibility": "private",
            "archived": False,
            "disabled": False,
            "fork": False,
            "default_branch": "main",
        }
        with (
            patch.object(v3.legacy, "require_resolved_blocking_incidents") as incidents,
            patch.object(
                v3.legacy, "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"]),
            ) as policy,
            patch.object(v3.legacy, "checked_repo", return_value=eligible) as repo,
            patch.object(v3, "authorized_main_sha", return_value=PROTECTED) as authority,
            patch.object(
                v3, "verify_source_pr",
                return_value=v3.SOURCE_REPOSITORY_ID,
            ) as source_pr,
            patch.object(
                v3.legacy, "gh_api",
                return_value={"object": {"sha": BASE}},
            ) as api,
        ):
            v3.authorize_write(
                Path("."), "trusted/ci", POLICY, carrier,
                PROTECTED, 1482, "4" * 40, v3.SOURCE_REPOSITORY_ID)

        incidents.assert_called_once_with()
        policy.assert_called_once_with(Path("."), "trusted/ci")
        repo.assert_called_once_with("trusted/ci", carrier["repository_id"])
        authority.assert_called_once_with(
            "trusted/ci", POLICY, carrier["repository_id"])
        source_pr.assert_called_once_with(1482, "4" * 40)
        api.assert_called_once_with("repos/trusted/ci/git/ref/heads/main")

    def test_publish_rejects_contract_repository_id_not_in_reviewed_allowlist(self):
        carrier = contract()
        mismatched = dict(carrier, repository_id=carrier["repository_id"] + 1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(
                    v3, "authorized_main_sha",
                    return_value=PROTECTED,
                ),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=mismatched),
                patch.object(v3, "verify_source_pr") as verify_source_pr,
            ):
                with self.assertRaisesRegex(
                    v3.Denied, "contract repository ID"
                ):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                    )
                verify_source_pr.assert_not_called()

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
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(
                    v3, "authorized_main_sha", return_value=PROTECTED
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
        self.assertEqual(authorize_write.call_count, 6)

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
                + "\nconnector-authorized source-repository "
                + v3.legacy.SOURCE_REPO
                + " id "
                + str(v3.SOURCE_REPOSITORY_ID)
                + "\nOreslang-CI-Receipt-SHA256: "
                + digest
            ),
        )
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
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(
                    v3,
                    "authorized_main_sha",
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
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
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
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
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
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
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
        with patch.object(v3.subprocess, "run", return_value=response) as run:
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
        with patch.object(v3.subprocess, "run", return_value=response) as run:
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
        checked_repo.assert_called_once_with("trusted/ci", 1323598177)

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

