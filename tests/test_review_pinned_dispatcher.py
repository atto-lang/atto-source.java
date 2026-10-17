#!/usr/bin/env python3
import importlib.util, json, tempfile, unittest
from pathlib import Path

SCRIPT=Path(__file__).resolve().parents[1]/"scripts/dispatch-review-pinned-private-ci.py"
SPEC=importlib.util.spec_from_file_location("dispatcher",SCRIPT)
MOD=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MOD)

def receipt():
    return {
      "schema":2,
      "source_repository":MOD.SOURCE_REPO,"source_repository_id":MOD.SOURCE_REPO_ID,
      "source_commit_sha":"1"*40,"source_tree_sha":"2"*40,
      "carrier_repository":MOD.CARRIER_REPO,"carrier_repository_id":MOD.CARRIER_REPO_ID,
      "carrier_base_sha":"3"*40,"carrier_workflow_blob_sha":"4"*40,
      "independent_reviewer_login":"external-reviewer","independent_reviewer_id":9000001,
      "independent_reviewer_policy_blob_sha":"5"*40,
      "independent_reviewer_source_permission":"write",
      "independent_reviewer_carrier_permission":"maintain",
      "publisher_policy_pr":MOD.PUBLISHER_PR,"publisher_policy_head_sha":"6"*40,"publisher_review_id":11,
      "destination_approval_pr":MOD.DESTINATION_PR,"destination_approval_head_sha":"7"*40,"destination_review_id":12,
      "snapshot_approval_pr":MOD.SNAPSHOT_PR,"snapshot_approval_head_sha":"8"*40,"snapshot_review_id":13,
      "carrier_policy_pr":MOD.CARRIER_PR,"carrier_policy_head_sha":"3"*40,"carrier_review_id":14,
      "incident_resume_comment_id":15,
    }

class DispatcherTest(unittest.TestCase):
    def test_valid_receipt_shape(self):
        self.assertIs(MOD.validate_receipt(receipt()), receipt()) if False else MOD.validate_receipt(receipt())

    def test_closed_publisher_1198_receipt_denied(self):
        r=receipt(); r["publisher_policy_pr"]=1198
        with self.assertRaises(MOD.Denied): MOD.validate_receipt(r)

    def test_superseded_publisher_1123_receipt_denied(self):
        r=receipt(); r["publisher_policy_pr"]=1123
        with self.assertRaises(MOD.Denied): MOD.validate_receipt(r)

    def test_operator_accounts_are_never_independent(self):
        for reviewer_id in (11139560,2148448):
            r=receipt(); r["independent_reviewer_id"]=reviewer_id
            with self.assertRaises(MOD.Denied): MOD.validate_receipt(r)

    def test_carrier_parent_must_equal_reviewed_carrier_head(self):
        r=receipt(); r["carrier_base_sha"]="9"*40
        with self.assertRaises(MOD.Denied): MOD.validate_receipt(r)

    def test_weak_receipt_permission_denied(self):
        r=receipt(); r["independent_reviewer_source_permission"]="read"
        with self.assertRaises(MOD.Denied): MOD.validate_receipt(r)

    def test_existing_exact_branch_denied(self):
        old=MOD.api_list
        try:
            MOD.api_list=lambda route:[{"ref":"refs/heads/ci/oreslang-"+"2"*40}]
            with self.assertRaises(MOD.Denied): MOD.branch_absent(receipt())
        finally: MOD.api_list=old

    def test_other_matching_ref_does_not_satisfy_exact_branch(self):
        old=MOD.api_list
        try:
            MOD.api_list=lambda route:[{"ref":"refs/heads/ci/oreslang-"+"2"*40+"-other"}]
            self.assertEqual(MOD.branch_absent(receipt()),"ci/oreslang-"+"2"*40)
        finally: MOD.api_list=old

    def test_content_file_recomputes_git_blob_hash(self):
        old=MOD.PUB.gh_api
        try:
            raw=b"hello"
            MOD.PUB.gh_api=lambda route:{
              "type":"file","encoding":"base64","content":"aGVsbG8=","size":5,"sha":"0"*40
            }
            with self.assertRaises(MOD.Denied): MOD.content_file("x/y","a", "b")
        finally: MOD.PUB.gh_api=old

    def test_disabled_reviewer_policy_denied_even_with_receipt(self):
        old=MOD.content_file
        policy={
          "schema":2,"enabled":False,"minimumPermission":"write",
          "disallowedReviewerIds":[11139560,2148448],"reviewer":None
        }
        raw=json.dumps(policy,separators=(",",":")).encode()
        try:
            MOD.content_file=lambda *args,**kwargs:(raw,receipt()["independent_reviewer_policy_blob_sha"])
            with self.assertRaisesRegex(MOD.Denied,"not enabled"):
                MOD.require_live_reviewer_policy(receipt())
        finally: MOD.content_file=old

    def test_source_and_carrier_reviewer_policy_must_be_byte_identical(self):
        old=MOD.content_file; calls=[0]
        enabled={
          "schema":2,"enabled":True,"minimumPermission":"write",
          "disallowedReviewerIds":[11139560,2148448],
          "reviewer":{"login":"external-reviewer","id":9000001}
        }
        a=json.dumps(enabled,separators=(",",":")).encode(); b=a+b" "
        def fake(*args,**kwargs):
            calls[0]+=1
            return (a if calls[0]==1 else b,receipt()["independent_reviewer_policy_blob_sha"])
        try:
            MOD.content_file=fake
            with self.assertRaisesRegex(MOD.Denied,"policy mismatch"):
                MOD.require_live_reviewer_policy(receipt())
        finally: MOD.content_file=old

    def test_live_policy_can_require_stronger_permission(self):
        old=MOD.content_file
        policy={
          "schema":2,"enabled":True,"minimumPermission":"admin",
          "disallowedReviewerIds":[11139560,2148448],
          "reviewer":{"login":"external-reviewer","id":9000001}
        }
        raw=json.dumps(policy,separators=(",",":")).encode()
        try:
            MOD.content_file=lambda *args,**kwargs:(raw,receipt()["independent_reviewer_policy_blob_sha"])
            with self.assertRaisesRegex(MOD.Denied,"below live reviewer policy"):
                MOD.require_live_reviewer_policy(receipt())
        finally: MOD.content_file=old

    def test_mid_write_authorization_revocation_causes_zero_writes(self):
        r=receipt()
        with tempfile.TemporaryDirectory() as td:
            rp=Path(td)/"receipt.json"; rp.write_text(json.dumps(r))
            source=Path(td)/"src"; source.mkdir()
            saved={name:getattr(MOD,name) for name in ("branch_absent","refresh_authorization","git_identity")}
            pub_saved={name:getattr(MOD.PUB,name) for name in
                       ("collect_source","source_tree_sha","prepare_source_tree_batches","require_private_ci_api_budget","gh_api")}
            refreshes=[0]; writes=[]
            try:
                MOD.branch_absent=lambda x:"ci/oreslang-"+x["source_tree_sha"]
                def refresh(_):
                    refreshes[0]+=1
                    if refreshes[0]>=2: raise MOD.Denied("revoked")
                    return "a"*40
                MOD.refresh_authorization=refresh; MOD.git_identity=lambda *args:None
                MOD.PUB.collect_source=lambda *args:[("secret.bin",b"\x00","100644")]
                MOD.PUB.source_tree_sha=lambda files:r["source_tree_sha"]
                MOD.PUB.prepare_source_tree_batches=lambda files:[[("secret.bin",b"\x00","100644",None)]]
                MOD.PUB.require_private_ci_api_budget=lambda *args:None
                def api(route,method="GET",payload=None):
                    if method!="GET": writes.append((route,payload))
                    return {}
                MOD.PUB.gh_api=api
                with self.assertRaisesRegex(MOD.Denied,"revoked"): MOD.dispatch(str(rp),str(source))
                self.assertEqual(writes,[])
            finally:
                for name,val in saved.items(): setattr(MOD,name,val)
                for name,val in pub_saved.items(): setattr(MOD.PUB,name,val)

    def test_root_payload_scope_is_only_source_and_provenance(self):
        r=receipt()
        with tempfile.TemporaryDirectory() as td:
            rp=Path(td)/"receipt.json"; rp.write_text(json.dumps(r))
            source=Path(td)/"src"; source.mkdir()
            saved={name:getattr(MOD,name) for name in ("branch_absent","refresh_authorization","git_identity")}
            pub_saved={name:getattr(MOD.PUB,name) for name in
                       ("collect_source","source_tree_sha","prepare_source_tree_batches","require_private_ci_api_budget","gh_api","valid_sha")}
            refreshes=[0]; root_payload=[]
            try:
                MOD.branch_absent=lambda x:"ci/oreslang-"+x["source_tree_sha"]
                def refresh(_):
                    refreshes[0]+=1
                    if refreshes[0]>=4: raise MOD.Denied("stop-before-commit")
                    return "a"*40
                MOD.refresh_authorization=refresh; MOD.git_identity=lambda *args:None
                MOD.PUB.collect_source=lambda *args:[("a.txt",b"x","100644")]
                MOD.PUB.source_tree_sha=lambda files:r["source_tree_sha"]
                MOD.PUB.prepare_source_tree_batches=lambda files:[[("a.txt",b"x","100644","x")]]
                MOD.PUB.require_private_ci_api_budget=lambda *args:None
                MOD.PUB.valid_sha=lambda value:value
                def api(route,method="GET",payload=None):
                    if route.endswith("/git/trees") and method=="POST":
                        if payload.get("base_tree")=="a"*40:
                            root_payload.append(payload); return {"sha":"b"*40}
                        return {"sha":r["source_tree_sha"]}
                    return {}
                MOD.PUB.gh_api=api
                with self.assertRaisesRegex(MOD.Denied,"stop-before-commit"): MOD.dispatch(str(rp),str(source))
                self.assertEqual(len(root_payload),1)
                self.assertEqual({x["path"] for x in root_payload[0]["tree"]},
                                 {MOD.PREFIX,MOD.PROVENANCE})
            finally:
                for name,val in saved.items(): setattr(MOD,name,val)
                for name,val in pub_saved.items(): setattr(MOD.PUB,name,val)

if __name__=="__main__": unittest.main()
