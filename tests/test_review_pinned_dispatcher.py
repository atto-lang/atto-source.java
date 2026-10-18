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
        r=receipt()
        self.assertIs(MOD.validate_receipt(r),r)

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

    def test_open_incident_denied_even_before_resume_comment(self):
        old_repo=MOD.repo_identity; old_api=MOD.PUB.gh_api
        try:
            MOD.repo_identity=lambda *args:None
            MOD.PUB.gh_api=lambda route:{
              "id":MOD.INCIDENT_ISSUE_ID,"number":MOD.INCIDENT_NUMBER,
              "state":"open","state_reason":None
            }
            with self.assertRaisesRegex(MOD.Denied,"incident remains unresolved"):
                MOD.require_incident_closed()
        finally:
            MOD.repo_identity=old_repo; MOD.PUB.gh_api=old_api

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


    def test_authorization_branch_binds_exact_payload_commit(self):
        self.assertEqual(
            MOD.authorization_branch("a"*40),
            "ci/authorized-"+"a"*40)
        with self.assertRaises(MOD.Denied):
            MOD.authorization_branch("not-a-sha")

    def test_existing_authorization_ref_denied(self):
        old=MOD.api_list
        try:
            MOD.api_list=lambda route:[
                {"ref":"refs/heads/ci/authorized-"+"a"*40}
            ]
            with self.assertRaisesRegex(MOD.Denied,"authorization ref already exists"):
                MOD.authorization_ref_absent("a"*40)
        finally:
            MOD.api_list=old

    def test_authorization_ref_is_created_only_after_final_live_recheck(self):
        r=receipt(); commit="a"*40
        old_api=MOD.PUB.gh_api; old_list=MOD.api_list; old_refresh=MOD.refresh_authorization
        events=[]
        payload_branch="ci/oreslang-"+r["source_tree_sha"]
        try:
            def api(route,method="GET",payload=None):
                if method=="GET":
                    self.assertTrue(route.endswith("/git/ref/heads/"+payload_branch))
                    events.append("payload-readback")
                    return {
                        "ref":"refs/heads/"+payload_branch,
                        "object":{"type":"commit","sha":commit},
                    }
                events.append("authorization-write")
                self.assertEqual(events[-2],"refresh")
                self.assertEqual(
                    payload,
                    {"ref":"refs/heads/ci/authorized-"+commit,"sha":commit})
                return {
                    "ref":"refs/heads/ci/authorized-"+commit,
                    "object":{"type":"commit","sha":commit},
                }
            MOD.PUB.gh_api=api
            MOD.api_list=lambda route:[]
            MOD.refresh_authorization=lambda _: events.append("refresh")
            self.assertEqual(
                MOD.create_authorization_ref(r,commit),
                "ci/authorized-"+commit)
            self.assertEqual(
                events,
                ["payload-readback","refresh","authorization-write"])
        finally:
            MOD.PUB.gh_api=old_api; MOD.api_list=old_list; MOD.refresh_authorization=old_refresh

    def test_wrapped_github_base64_is_accepted(self):
        import base64, hashlib
        raw=b"hello world"
        encoded=base64.b64encode(raw).decode()
        wrapped=encoded[:4]+"\n"+encoded[4:]
        sha=hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()
        out,got=MOD.decode_contents_api_file(
            {"type":"file","encoding":"base64","content":wrapped,"size":len(raw),"sha":sha},
            "wrapped",1024)
        self.assertEqual(out,raw); self.assertEqual(got,sha)

    def test_crlf_wrapped_binary_contents_roundtrip_uses_git_blob_nul(self):
        import base64, hashlib
        # Binary payload + normal GitHub Contents line wrapping.
        raw=bytes(range(256))
        encoded=base64.b64encode(raw).decode("ascii")
        wrapped="\r\n".join(encoded[i:i+64] for i in range(0,len(encoded),64))+"\n"
        sha=hashlib.sha1(b"blob "+str(len(raw)).encode("ascii")+b"\0"+raw).hexdigest()
        result=MOD.decode_contents_api_file({
            "type":"file","encoding":"base64","content":wrapped,
            "size":len(raw),"sha":sha
        },"binary-wrapped",1024)
        self.assertEqual(result,(raw,sha))

    def test_contents_api_rejects_backslash_newline_and_wrong_separator_sha(self):
        import base64, hashlib
        raw=b"binary\\0payload"
        encoded=base64.b64encode(raw).decode("ascii")
        good_sha=hashlib.sha1(b"blob "+str(len(raw)).encode("ascii")+b"\0"+raw).hexdigest()
        wrong_sha=hashlib.sha1(b"blob "+str(len(raw)).encode("ascii")+b"\\0"+raw).hexdigest()
        self.assertNotEqual(good_sha,wrong_sha)
        for content,sha in ((encoded,wrong_sha),(encoded+"\\n",good_sha),
                            (encoded+"\\r\\n",good_sha),(encoded+"=",good_sha)):
            with self.subTest(content=content,sha=sha):
                with self.assertRaises(MOD.Denied):
                    MOD.decode_contents_api_file({
                        "type":"file","encoding":"base64","content":content,
                        "size":len(raw),"sha":sha
                    },"noncanonical",1024)

    def test_non_crlf_base64_whitespace_is_denied(self):
        import base64, hashlib
        raw=b"hello"; encoded=base64.b64encode(raw).decode()+"\t"
        sha=hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()
        with self.assertRaises(MOD.Denied):
            MOD.decode_contents_api_file(
              {"type":"file","encoding":"base64","content":encoded,"size":len(raw),"sha":sha},
              "bad-space",1024)

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

    def test_destination_and_snapshot_ledgers_use_their_own_reviewed_heads(self):
        r=receipt()
        calls=[]
        old=MOD.require_exact_ledger
        try:
            MOD.require_exact_ledger=lambda repo,path,ref,expected: calls.append(
                (repo,path,ref,expected))
            MOD.require_reviewed_ledgers(r)
        finally:
            MOD.require_exact_ledger=old
        self.assertEqual(len(calls),2)
        self.assertEqual(calls[0][2],r["destination_approval_head_sha"])
        self.assertEqual(calls[1][2],r["snapshot_approval_head_sha"])
        self.assertNotEqual(calls[0][2],r["publisher_policy_head_sha"])
        self.assertNotEqual(calls[1][2],r["publisher_policy_head_sha"])
        self.assertEqual(calls[0][3],f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}")
        self.assertEqual(
            calls[1][3],
            f"{r['source_commit_sha']} {r['source_tree_sha']} {MOD.CARRIER_REPO_ID}")

    def test_dispatch_attests_reviewed_executable_before_source_admission(self):
        r=receipt()
        with tempfile.TemporaryDirectory() as td:
            rp=Path(td)/"receipt.json"; rp.write_text(json.dumps(r))
            calls=[]
            saved={name:getattr(MOD,name) for name in
                   ("branch_absent","refresh_authorization","require_staged_source")}
            pub_saved=MOD.PUB.require_trusted_publisher_checkout
            try:
                def attest(root,head):
                    calls.append(("attest",root,head))
                    raise MOD.Denied("unreviewed executable")
                MOD.PUB.require_trusted_publisher_checkout=attest
                MOD.branch_absent=lambda *_:calls.append(("branch",))
                MOD.refresh_authorization=lambda *_:calls.append(("refresh",))
                MOD.require_staged_source=lambda *_:calls.append(("source",))
                with self.assertRaisesRegex(MOD.Denied,"unreviewed executable"):
                    MOD.dispatch(str(rp),str(Path(td)/"src"))
                self.assertEqual(calls[0][0],"attest")
                self.assertFalse(any(x[0] in ("branch","refresh","source") for x in calls[1:]))
            finally:
                MOD.PUB.require_trusted_publisher_checkout=pub_saved
                for name,val in saved.items(): setattr(MOD,name,val)

    def test_staged_source_must_be_below_dispatcher_root_not_root_or_git_metadata(self):
        original_root=MOD.ROOT
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)/"trusted"; root.mkdir()
            staged=root/"snapshot"; staged.mkdir()
            metadata=root/".git"; metadata.mkdir()
            MOD.ROOT=root
            try:
                self.assertEqual(MOD.require_staged_source(staged),staged.resolve())
                with self.assertRaisesRegex(MOD.Denied,"staged separately"):
                    MOD.require_staged_source(root)
                with self.assertRaisesRegex(MOD.Denied,"Git metadata"):
                    MOD.require_staged_source(metadata)
                outside=Path(td)/"outside"; outside.mkdir()
                with self.assertRaisesRegex(MOD.Denied,"escapes reviewed"):
                    MOD.require_staged_source(outside)
            finally:
                MOD.ROOT=original_root

    def test_mid_write_authorization_revocation_causes_zero_writes(self):
        r=receipt()
        with tempfile.TemporaryDirectory() as td:
            rp=Path(td)/"receipt.json"; rp.write_text(json.dumps(r))
            source=Path(td)/"src"; source.mkdir()
            saved={name:getattr(MOD,name) for name in
                   ("branch_absent","refresh_authorization","require_staged_source")}
            pub_saved={name:getattr(MOD.PUB,name) for name in
                       ("require_trusted_publisher_checkout","collect_source","source_tree_sha",
                        "prepare_source_tree_batches","require_private_ci_api_budget","gh_api")}
            refreshes=[0]; writes=[]
            try:
                MOD.branch_absent=lambda x:"ci/oreslang-"+x["source_tree_sha"]
                def refresh(_,require_new_ref=False):
                    refreshes[0]+=1
                    if refreshes[0]>=2: raise MOD.Denied("revoked")
                    return "a"*40
                MOD.refresh_authorization=refresh
                MOD.require_staged_source=lambda path:Path(path)
                MOD.PUB.require_trusted_publisher_checkout=lambda *args:None
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

    def test_cleanup_exact_payload_ref_removes_only_our_commit(self):
        r=receipt(); branch="ci/oreslang-"+r["source_tree_sha"]
        old_api=MOD.PUB.gh_api; old_no=MOD.PUB.gh_api_no_content; old_list=MOD.api_list
        removed=[]
        try:
            MOD.PUB.gh_api=lambda route:{
              "ref":"refs/heads/"+branch,
              "object":{"sha":"a"*40,"type":"commit"}
            }
            MOD.PUB.gh_api_no_content=lambda route,method="POST",payload=None: removed.append(
                (route,method))
            MOD.api_list=lambda route:[]
            MOD.cleanup_exact_payload_ref(r,"a"*40)
            self.assertEqual(len(removed),1)
            self.assertEqual(removed[0][1],"DELETE")
        finally:
            MOD.PUB.gh_api=old_api; MOD.PUB.gh_api_no_content=old_no; MOD.api_list=old_list

    def test_cleanup_exact_payload_ref_refuses_moved_ref(self):
        r=receipt(); branch="ci/oreslang-"+r["source_tree_sha"]
        old_api=MOD.PUB.gh_api; old_no=MOD.PUB.gh_api_no_content
        try:
            MOD.PUB.gh_api=lambda route:{
              "ref":"refs/heads/"+branch,
              "object":{"sha":"b"*40,"type":"commit"}
            }
            MOD.PUB.gh_api_no_content=lambda *args,**kwargs: self.fail("must not delete moved ref")
            with self.assertRaisesRegex(MOD.Denied,"ref moved"):
                MOD.cleanup_exact_payload_ref(r,"a"*40)
        finally:
            MOD.PUB.gh_api=old_api; MOD.PUB.gh_api_no_content=old_no


    def test_cleanup_exact_authorization_ref_removes_only_exact_commit(self):
        commit="a"*40; branch="ci/authorized-"+commit
        old_api=MOD.PUB.gh_api; old_no=MOD.PUB.gh_api_no_content; old_list=MOD.api_list
        calls=[0]; removed=[]
        try:
            def listing(route):
                calls[0]+=1
                return ([{"ref":"refs/heads/"+branch}] if calls[0]==1 else [])
            MOD.api_list=listing
            MOD.PUB.gh_api=lambda route:{
                "ref":"refs/heads/"+branch,
                "object":{"sha":commit,"type":"commit"}
            }
            MOD.PUB.gh_api_no_content=lambda route,method="POST",payload=None: removed.append((route,method))
            MOD.cleanup_exact_authorization_ref_if_present(commit)
            self.assertEqual(removed,[(f"repos/{MOD.CARRIER_REPO}/git/refs/heads/{branch}","DELETE")])
        finally:
            MOD.PUB.gh_api=old_api; MOD.PUB.gh_api_no_content=old_no; MOD.api_list=old_list

    def test_cleanup_authorization_ref_refuses_foreign_commit(self):
        commit="a"*40; branch="ci/authorized-"+commit
        old_api=MOD.PUB.gh_api; old_no=MOD.PUB.gh_api_no_content; old_list=MOD.api_list
        try:
            MOD.api_list=lambda route:[{"ref":"refs/heads/"+branch}]
            MOD.PUB.gh_api=lambda route:{
                "ref":"refs/heads/"+branch,
                "object":{"sha":"b"*40,"type":"commit"}
            }
            MOD.PUB.gh_api_no_content=lambda *args,**kwargs: self.fail("must not delete foreign authorization ref")
            with self.assertRaisesRegex(MOD.Denied,"authorization ref cleanup refused"):
                MOD.cleanup_exact_authorization_ref_if_present(commit)
        finally:
            MOD.PUB.gh_api=old_api; MOD.PUB.gh_api_no_content=old_no; MOD.api_list=old_list

    def test_payload_readback_rejects_duplicate_protected_entries(self):
        import hashlib
        r=receipt()
        provenance=json.dumps(r,sort_keys=True,separators=(",",":"))+"\n"
        raw=provenance.encode("utf-8")
        prov_sha=hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()
        rows=[
          {"path":MOD.PREFIX,"type":"tree","mode":"040000","sha":r["source_tree_sha"]},
          {"path":MOD.PREFIX,"type":"tree","mode":"040000","sha":r["source_tree_sha"]},
          {"path":MOD.PROVENANCE,"type":"blob","mode":"100644","sha":prov_sha},
        ]
        with self.assertRaisesRegex(MOD.Denied,"exact source/provenance"):
            MOD.verify_payload_root_entries(rows,r,provenance)

    def test_payload_readback_requires_exact_source_and_provenance_modes_and_hash(self):
        import hashlib
        r=receipt()
        provenance=json.dumps(r,sort_keys=True,separators=(",",":"))+"\n"
        raw=provenance.encode("utf-8")
        prov_sha=hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()
        good=[
          {"path":MOD.PREFIX,"type":"tree","mode":"040000","sha":r["source_tree_sha"]},
          {"path":MOD.PROVENANCE,"type":"blob","mode":"100644","sha":prov_sha},
        ]
        MOD.verify_payload_root_entries(good,r,provenance)
        for mutate in (
            lambda rows: rows[0].__setitem__("mode","100644"),
            lambda rows: rows[0].__setitem__("sha","f"*40),
            lambda rows: rows[1].__setitem__("mode","100755"),
            lambda rows: rows[1].__setitem__("sha","e"*40),
        ):
            rows=[dict(x) for x in good]; mutate(rows)
            with self.assertRaisesRegex(MOD.Denied,"exact source/provenance"):
                MOD.verify_payload_root_entries(rows,r,provenance)

    def test_root_payload_scope_is_only_source_and_provenance(self):
        r=receipt()
        with tempfile.TemporaryDirectory() as td:
            rp=Path(td)/"receipt.json"; rp.write_text(json.dumps(r))
            source=Path(td)/"src"; source.mkdir()
            saved={name:getattr(MOD,name) for name in
                   ("branch_absent","refresh_authorization","require_staged_source")}
            pub_saved={name:getattr(MOD.PUB,name) for name in
                       ("require_trusted_publisher_checkout","collect_source","source_tree_sha",
                        "prepare_source_tree_batches","require_private_ci_api_budget","gh_api","valid_sha")}
            refreshes=[0]; root_payload=[]
            try:
                MOD.branch_absent=lambda x:"ci/oreslang-"+x["source_tree_sha"]
                def refresh(_,require_new_ref=False):
                    refreshes[0]+=1
                    if refreshes[0]>=4: raise MOD.Denied("stop-before-commit")
                    return "a"*40
                MOD.refresh_authorization=refresh
                MOD.require_staged_source=lambda path:Path(path)
                MOD.PUB.require_trusted_publisher_checkout=lambda *args:None
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
