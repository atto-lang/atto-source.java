#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/dispatch-review-pinned-private-ci.py"
SPEC = importlib.util.spec_from_file_location("review_pinned_dispatch", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

PUB_HEAD="1"*40
DEST_HEAD="2"*40
SNAP_HEAD="3"*40
CARRIER_HEAD="4"*40
SOURCE_COMMIT="5"*40
SOURCE_TREE="6"*40
WORKFLOW_BLOB="7"*40
POLICY_BLOB="8"*40
BRANCH="ci/oreslang-"+SOURCE_TREE

def receipt():
    return {
        "schema":2,
        "source_repository":MOD.VERIFY.SOURCE_REPO,
        "source_repository_id":MOD.VERIFY.SOURCE_REPO_ID,
        "source_commit_sha":SOURCE_COMMIT,
        "source_tree_sha":SOURCE_TREE,
        "carrier_repository":MOD.VERIFY.CARRIER_REPO,
        "carrier_repository_id":MOD.VERIFY.CARRIER_REPO_ID,
        "carrier_base_sha":CARRIER_HEAD,
        "carrier_workflow_blob_sha":WORKFLOW_BLOB,
        "independent_reviewer_login":"external-security-reviewer",
        "independent_reviewer_id":9000001,
        "independent_reviewer_policy_blob_sha":POLICY_BLOB,
        "independent_reviewer_source_permission":"write",
        "independent_reviewer_carrier_permission":"write",
        "publisher_policy_pr":1123,
        "publisher_policy_head_sha":PUB_HEAD,
        "publisher_review_id":11,
        "destination_approval_pr":1092,
        "destination_approval_head_sha":DEST_HEAD,
        "destination_review_id":12,
        "snapshot_approval_pr":1189,
        "snapshot_approval_head_sha":SNAP_HEAD,
        "snapshot_review_id":13,
        "carrier_policy_pr":7,
        "carrier_policy_head_sha":CARRIER_HEAD,
        "carrier_review_id":14,
        "incident_resume_comment_id":99,
    }

class DispatcherUnitTest(unittest.TestCase):
    def write_receipt(self,value):
        td=tempfile.TemporaryDirectory()
        path=Path(td.name)/"receipt.json"
        path.write_text(json.dumps(value),encoding="utf-8")
        return td,path

    def test_load_receipt_accepts_schema2(self):
        td,path=self.write_receipt(receipt())
        try:
            self.assertEqual(MOD.load_receipt(path),receipt())
        finally:
            td.cleanup()

    def test_load_receipt_rejects_wrong_schema(self):
        value=receipt(); value["schema"]=1
        td,path=self.write_receipt(value)
        try:
            with self.assertRaises(MOD.Denied):
                MOD.load_receipt(path)
        finally:
            td.cleanup()

    def test_load_receipt_rejects_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/"receipt.json"
            path.write_text('{"schema":2,"schema":2}',encoding="utf-8")
            with self.assertRaises(MOD.Denied):
                MOD.load_receipt(path)

    def test_strict_json_rejects_wrong_shape(self):
        with self.assertRaises(MOD.Denied):
            MOD.strict_json("{}",list)

    def test_strict_json_rejects_duplicate_keys(self):
        with self.assertRaises(MOD.Denied):
            MOD.strict_json('{"x":1,"x":2}',dict)

    def test_bounded_list_rejects_second_page(self):
        with mock.patch.object(MOD,"gh_value",side_effect=[[],[{"id":1}]]):
            with self.assertRaises(MOD.Denied):
                MOD.bounded_list("repos/x/y/reviews","reviews")

    def test_bounded_list_accepts_one_page(self):
        with mock.patch.object(MOD,"gh_value",side_effect=[[{"id":1}],[]]):
            self.assertEqual(
                MOD.bounded_list("repos/x/y/reviews","reviews"),
                [{"id":1}])

    def test_compare_live_receipt_rejects_stale_receipt(self):
        live=receipt(); live["carrier_review_id"]=999
        with mock.patch.object(MOD,"canonical_receipt_from_live",return_value=live):
            with self.assertRaises(MOD.Denied):
                MOD.compare_live_receipt(receipt())

    def test_require_branch_absent_denies_existing_ref(self):
        with mock.patch.object(MOD,"matching_branch_refs",
                               return_value=[{"ref":"refs/heads/"+BRANCH}]):
            with self.assertRaises(MOD.Denied):
                MOD.require_branch_absent(MOD.VERIFY.CARRIER_REPO,BRANCH)

    def test_branch_mismatch_denied_before_collection(self):
        with mock.patch.object(MOD.PUB,"collect_source") as collect:
            with self.assertRaises(MOD.Denied):
                MOD.publish_review_pinned(
                    receipt(),Path("."),"ci/oreslang-"+"f"*40)
            collect.assert_not_called()

if __name__=="__main__":
    unittest.main()
