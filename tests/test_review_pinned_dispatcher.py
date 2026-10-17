#!/usr/bin/env python3
"""Source-free adversarial tests for review-pinned private-CI dispatcher."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "scripts" / "dispatch-review-pinned-private-ci.py"
spec = importlib.util.spec_from_file_location("review_pinned_dispatcher", MODULE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

A, B, C, D, E, F, G, H, I, J = tuple(ch * 40 for ch in "abcdef0123")


def receipt():
    return {
        "schema": 2,
        "source_repository": mod.SOURCE_REPO,
        "source_repository_id": mod.SOURCE_REPO_ID,
        "source_commit_sha": A,
        "source_tree_sha": B,
        "carrier_repository": mod.CARRIER_REPO,
        "carrier_repository_id": mod.CARRIER_REPO_ID,
        "carrier_base_sha": C,
        "carrier_workflow_blob_sha": G,
        "independent_reviewer_login": "independent-reviewer",
        "independent_reviewer_id": 9000001,
        "independent_reviewer_policy_blob_sha": H,
        "independent_reviewer_source_permission": "write",
        "independent_reviewer_carrier_permission": "maintain",
        "publisher_policy_pr": 1198,
        "publisher_policy_head_sha": D,
        "publisher_review_id": 11,
        "destination_approval_pr": 1092,
        "destination_approval_head_sha": E,
        "destination_review_id": 12,
        "snapshot_approval_pr": 1189,
        "snapshot_approval_head_sha": F,
        "snapshot_review_id": 13,
        "carrier_policy_pr": 7,
        "carrier_policy_head_sha": C,
        "carrier_review_id": 14,
        "incident_resume_comment_id": 99,
    }


class DispatcherTests(unittest.TestCase):
    def test_valid_receipt_is_canonical(self):
        doc = receipt()
        raw = mod.canonical_receipt_bytes(doc)
        self.assertEqual(json.loads(raw), doc)
        self.assertEqual(raw, mod.canonical_receipt_bytes(json.loads(raw)))

    def test_receipt_extra_key_denied(self):
        doc = receipt()
        doc["surprise"] = True
        with self.assertRaisesRegex(mod.Denied, "schema"):
            mod.validate_receipt(doc)

    def test_receipt_bad_sha_denied(self):
        doc = receipt()
        doc["source_tree_sha"] = "not-a-sha"
        with self.assertRaisesRegex(mod.Denied, "source_tree_sha"):
            mod.validate_receipt(doc)

    def test_carrier_parent_must_equal_reviewed_carrier_head(self):
        doc = receipt()
        doc["carrier_policy_head_sha"] = D
        with self.assertRaisesRegex(mod.Denied, "carrier parent"):
            mod.validate_receipt(doc)

    def test_load_receipt_rejects_noncanonical_json(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "receipt.json"
            p.write_text(json.dumps(receipt(), indent=2))
            with self.assertRaisesRegex(mod.Denied, "not canonical"):
                mod.load_receipt(p)

    def test_duplicate_json_key_denied(self):
        with self.assertRaisesRegex(mod.Denied, "duplicate JSON key"):
            mod.parse_json_bytes(b'{"schema":2,"schema":2}', "dupe")

    def test_existing_payload_branch_denied(self):
        doc = receipt()
        with patch.object(mod, "fresh_receipt", return_value=doc), \
             patch.object(mod, "source_repository_live", return_value={}), \
             patch.object(mod.pub, "checked_repo",
                          return_value={"default_branch": "main"}), \
             patch.object(mod, "matching_payload_refs",
                          return_value=[{"ref": "refs/heads/" + mod.payload_branch(doc)}]):
            with self.assertRaisesRegex(mod.Denied, "already exists"):
                mod.full_authorize(doc, True)

    def test_final_authorization_allows_only_existing_branch_predicate_to_relax(self):
        doc = receipt()
        with patch.object(mod, "fresh_receipt", return_value=doc) as fresh, \
             patch.object(mod, "source_repository_live", return_value={}), \
             patch.object(mod.pub, "checked_repo",
                          return_value={"default_branch": "main"}), \
             patch.object(mod, "matching_payload_refs",
                          side_effect=AssertionError("branch absence must not run")), \
             patch.object(mod.pub, "gh_api",
                          return_value={"sha": C, "tree": {"sha": I}}):
            self.assertEqual(mod.full_authorize(doc, False), C)
            fresh.assert_called_once_with(doc)

    def test_source_tree_mismatch_denies_before_authorization_or_writes(self):
        doc = receipt()
        files = [("x.ores", b"x", "100644")]
        with patch.object(mod.pub, "source_tree_sha", return_value=D), \
             patch.object(mod.pub, "gh_api",
                          side_effect=AssertionError("no API write/read expected")):
            called = []
            with self.assertRaisesRegex(mod.Denied, "source snapshot"):
                mod.write_payload(files, doc,
                                  authorize=lambda *_: called.append(True))
            self.assertEqual(called, [])

    def successful_api(self, doc, failures=None):
        failures = failures or {}
        calls = []
        provenance = mod.canonical_receipt_bytes(doc).decode("utf-8") + "\n"
        provenance_sha = mod.git_blob_sha(provenance.encode())
        branch = mod.payload_branch(doc)

        def api(route, method="GET", payload=None):
            calls.append((method, route, payload))
            key = (method, route)
            if key in failures:
                return failures[key]
            if method == "GET" and route.endswith("/git/commits/" + C):
                return {"sha": C, "tree": {"sha": "9" * 40}}
            if method == "GET" and route.endswith("/git/trees/" + "9" * 40):
                return {"sha": "9" * 40, "truncated": False, "tree": []}
            if method == "POST" and route.endswith("/git/blobs"):
                data = __import__("base64").b64decode(payload["content"])
                return {"sha": mod.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                paths = {item.get("path") for item in payload["tree"]}
                if mod.PREFIX in paths:
                    return {"sha": I}
                return {"sha": B}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": J}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": "refs/heads/" + branch,
                        "object": {"sha": J}}
            if method == "GET" and route.endswith("/git/ref/heads/" + branch):
                return {"ref": "refs/heads/" + branch,
                        "object": {"sha": J, "type": "commit"}}
            if method == "GET" and route.endswith("/git/commits/" + J):
                return {"sha": J, "tree": {"sha": I},
                        "parents": [{"sha": C}]}
            if method == "GET" and route.endswith("/git/trees/" + I):
                return {
                    "sha": I, "truncated": False,
                    "tree": [
                        {"path": mod.PREFIX, "type": "tree", "sha": B},
                        {"path": mod.PROVENANCE_PATH, "type": "blob",
                         "sha": provenance_sha},
                    ],
                }
            raise AssertionError("unexpected API route: " + method + " " + route)
        return api, calls

    def test_successful_payload_rechecks_authority_before_every_write(self):
        doc = receipt()
        files = [("hello.ores", b"x", "100644")]
        api, calls = self.successful_api(doc)
        auth = []

        with patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod.pub, "prepare_source_tree_batches",
                          return_value=[[("hello.ores", b"x", "100644", None)]]), \
             patch.object(mod.pub, "require_private_ci_api_budget"), \
             patch.object(mod.pub, "gh_api", side_effect=api), \
             patch.object(mod.pub, "nested_sha",
                          side_effect=lambda value, key: value[key]["sha"]):
            commit, count = mod.write_payload(
                files, doc, authorize=lambda _, absent: auth.append(absent))

        self.assertEqual((commit, count), (J, 1))
        writes = [x for x in calls if x[0] == "POST"]
        self.assertEqual(len(writes), 5)
        self.assertGreaterEqual(len(auth), len(writes) + 2)
        self.assertTrue(all(auth[:-1]))
        self.assertFalse(auth[-1])
        commit_write = [x for x in writes if x[1].endswith("/git/commits")][0]
        self.assertEqual(commit_write[2]["parents"], [C])

    def test_carrier_parent_with_existing_payload_paths_denied_before_write(self):
        doc = receipt()
        files = [("hello.ores", b"x", "100644")]
        api, calls = self.successful_api(
            doc, {("GET", f"repos/{mod.CARRIER_REPO}/git/trees/" + "9" * 40):
                  {"sha": "9" * 40, "truncated": False,
                   "tree": [{"path": mod.PROVENANCE_PATH, "type": "blob",
                             "sha": "0" * 40}]}})
        with patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod.pub, "gh_api", side_effect=api), \
             patch.object(mod.pub, "nested_sha",
                          side_effect=lambda value, key: value[key]["sha"]):
            with self.assertRaisesRegex(mod.Denied, "already contains payload paths"):
                mod.write_payload(files, doc, authorize=lambda *_: None)
        self.assertFalse(any(method == "POST" for method, _, _ in calls))

    def test_uploaded_blob_digest_mismatch_denied(self):
        doc = receipt()
        files = [("hello.ores", b"x", "100644")]
        api, _ = self.successful_api(
            doc, {("POST", f"repos/{mod.CARRIER_REPO}/git/blobs"):
                  {"sha": "0" * 40}})
        with patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod.pub, "prepare_source_tree_batches",
                          return_value=[[("hello.ores", b"x", "100644", None)]]), \
             patch.object(mod.pub, "require_private_ci_api_budget"), \
             patch.object(mod.pub, "gh_api", side_effect=api), \
             patch.object(mod.pub, "nested_sha",
                          side_effect=lambda value, key: value[key]["sha"]):
            with self.assertRaisesRegex(mod.Denied, "blob digest"):
                mod.write_payload(files, doc, authorize=lambda *_: None)

    def test_isolated_tree_mismatch_denied_before_root_commit(self):
        doc = receipt()
        files = [("hello.ores", b"x", "100644")]
        calls = []

        def api(route, method="GET", payload=None):
            calls.append((method, route))
            if method == "GET" and route.endswith("/git/commits/" + C):
                return {"sha": C, "tree": {"sha": "9" * 40}}
            if method == "POST" and route.endswith("/git/blobs"):
                return {"sha": mod.git_blob_sha(b"x")}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": D}
            raise AssertionError(route)

        with patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod.pub, "prepare_source_tree_batches",
                          return_value=[[("hello.ores", b"x", "100644", None)]]), \
             patch.object(mod.pub, "require_private_ci_api_budget"), \
             patch.object(mod.pub, "gh_api", side_effect=api), \
             patch.object(mod.pub, "nested_sha",
                          side_effect=lambda value, key: value[key]["sha"]):
            with self.assertRaisesRegex(mod.Denied, "isolated source tree"):
                mod.write_payload(files, doc, authorize=lambda *_: None)
        self.assertFalse(any(route.endswith("/git/commits") and method == "POST"
                             for method, route in calls))

    def test_wrong_single_parent_readback_denied(self):
        doc = receipt()
        files = [("hello.ores", b"x", "100644")]
        api, _ = self.successful_api(
            doc, {("GET", f"repos/{mod.CARRIER_REPO}/git/commits/{J}"):
                  {"sha": J, "tree": {"sha": I},
                   "parents": [{"sha": C}, {"sha": D}]}})
        with patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod.pub, "prepare_source_tree_batches",
                          return_value=[[("hello.ores", b"x", "100644", None)]]), \
             patch.object(mod.pub, "require_private_ci_api_budget"), \
             patch.object(mod.pub, "gh_api", side_effect=api), \
             patch.object(mod.pub, "nested_sha",
                          side_effect=lambda value, key: value[key]["sha"]):
            with self.assertRaisesRegex(mod.Denied, "single-parent"):
                mod.write_payload(files, doc, authorize=lambda *_: None)

    def test_missing_provenance_entry_denied(self):
        doc = receipt()
        files = [("hello.ores", b"x", "100644")]
        api, _ = self.successful_api(
            doc, {("GET", f"repos/{mod.CARRIER_REPO}/git/trees/{I}"):
                  {"sha": I, "truncated": False,
                   "tree": [{"path": mod.PREFIX, "type": "tree", "sha": B}]}})
        with patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod.pub, "prepare_source_tree_batches",
                          return_value=[[("hello.ores", b"x", "100644", None)]]), \
             patch.object(mod.pub, "require_private_ci_api_budget"), \
             patch.object(mod.pub, "gh_api", side_effect=api), \
             patch.object(mod.pub, "nested_sha",
                          side_effect=lambda value, key: value[key]["sha"]):
            with self.assertRaisesRegex(mod.Denied, "source/provenance"):
                mod.write_payload(files, doc, authorize=lambda *_: None)

    def test_revocation_during_snapshot_denies_before_writer(self):
        doc = receipt()
        source = Path("/synthetic/source")
        calls = []

        def auth(*_):
            calls.append(True)
            if len(calls) == 2:
                raise mod.Denied("revoked")

        with patch.object(mod, "full_authorize", side_effect=auth), \
             patch.object(mod, "verify_local_checkout", return_value=source), \
             patch.object(mod.pub, "collect_source",
                          return_value=[("x.ores", b"x", "100644")]), \
             patch.object(mod.pub, "source_tree_sha", return_value=B), \
             patch.object(mod, "write_payload",
                          side_effect=AssertionError("writer must not run")):
            with self.assertRaisesRegex(mod.Denied, "revoked"):
                mod.dispatch(source, doc)


if __name__ == "__main__":
    unittest.main()
