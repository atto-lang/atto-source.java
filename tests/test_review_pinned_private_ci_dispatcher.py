#!/usr/bin/env python3
import base64
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


class DispatcherWriterTest(unittest.TestCase):
    def harness(self, live=None, bad_parent=False, bad_provenance=False):
        value=receipt()
        files=[("hello.txt",b"hello\n","100644")]
        calls=[]
        counters={"live":0,"repo":0,"branch":0,"carrier":0}
        provenance=MOD.canonical_provenance(value)
        provenance_sha=MOD.git_blob_sha(provenance)
        root_tree="9"*40
        payload_commit="a"*40

        def live_receipt():
            counters["live"]+=1
            return value if live is None else live(counters["live"])

        def checked_repo(repository,expected_id=None):
            counters["repo"]+=1
            return {"id":MOD.VERIFY.CARRIER_REPO_ID,
                    "full_name":MOD.VERIFY.CARRIER_REPO,
                    "private":True,"visibility":"private",
                    "archived":False,"disabled":False}

        def absent(repository,branch):
            counters["branch"]+=1

        def carrier_parent(_receipt):
            counters["carrier"]+=1
            return "b"*40

        def fake_git_output(root,*args):
            if args[:2]==("rev-parse","--verify") and args[2]=="HEAD":
                return PUB_HEAD
            if args and args[0]=="status":
                return ""
            raise AssertionError(args)

        def fake_api(route,method="GET",payload=None):
            calls.append((route,method,payload))
            if method=="POST" and route.endswith("/git/trees"):
                paths=[item.get("path") for item in payload.get("tree",[])]
                return {"sha":root_tree if MOD.PUB.PREFIX in paths else SOURCE_TREE}
            if method=="POST" and route.endswith("/git/blobs"):
                raw=base64.b64decode(payload["content"])
                return {"sha":MOD.git_blob_sha(raw)}
            if method=="POST" and route.endswith("/git/commits"):
                parents=([{"sha":CARRIER_HEAD},{"sha":"c"*40}]
                         if bad_parent else [{"sha":CARRIER_HEAD}])
                return {"sha":payload_commit,
                        "tree":{"sha":root_tree},"parents":parents}
            if method=="POST" and route.endswith("/git/refs"):
                return {"ref":"refs/heads/"+BRANCH,
                        "object":{"sha":payload_commit}}
            if method=="GET" and "/git/ref/heads/" in route:
                return {"ref":"refs/heads/"+BRANCH,
                        "object":{"sha":payload_commit,"type":"commit"}}
            if method=="GET" and route.endswith("/git/commits/"+payload_commit):
                return {"sha":payload_commit,"tree":{"sha":root_tree},
                        "parents":[{"sha":CARRIER_HEAD}]}
            if method=="GET" and route.endswith("/git/trees/"+root_tree):
                return {"sha":root_tree,"truncated":False,"tree":[
                    {"path":MOD.PUB.PREFIX,"type":"tree","mode":"040000",
                     "sha":SOURCE_TREE},
                    {"path":MOD.PROVENANCE_PATH,"type":"blob","mode":"100644",
                     "sha":provenance_sha}]}
            if method=="GET" and route.endswith("/git/blobs/"+provenance_sha):
                raw=b"{}\n" if bad_provenance else provenance
                return {"sha":provenance_sha,"encoding":"base64",
                        "content":base64.b64encode(raw).decode("ascii")}
            raise AssertionError((route,method,payload))

        patches=[
            mock.patch.object(MOD,"canonical_receipt_from_live",
                              side_effect=live_receipt),
            mock.patch.object(MOD.PUB,"checked_repo",side_effect=checked_repo),
            mock.patch.object(MOD,"require_branch_absent",side_effect=absent),
            mock.patch.object(MOD,"verify_carrier_parent",
                              side_effect=carrier_parent),
            mock.patch.object(MOD,"git_output",side_effect=fake_git_output),
            mock.patch.object(MOD,"require_clean_checkout",
                              return_value=Path("/source")),
            mock.patch.object(MOD.PUB,"collect_source",return_value=files),
            mock.patch.object(MOD.PUB,"source_tree_sha",
                              return_value=SOURCE_TREE),
            mock.patch.object(MOD.PUB,"prepare_source_tree_batches",
                              return_value=[[("hello.txt",b"hello\n",
                                             "100644","hello\n")]]),
            mock.patch.object(MOD,"require_dispatcher_api_budget"),
            mock.patch.object(MOD.PUB,"gh_api",side_effect=fake_api),
        ]
        return value,calls,counters,patches

    def execute(self,**kwargs):
        value,calls,counters,patches=self.harness(**kwargs)
        try:
            for patch in patches: patch.start()
            result=MOD.publish_review_pinned(value,Path("/source"),BRANCH)
            return result,calls,counters
        finally:
            for patch in reversed(patches): patch.stop()

    def test_success_is_one_parent_and_never_dispatches(self):
        (commit,count),calls,counters=self.execute()
        self.assertEqual(count,1)
        self.assertEqual(commit,"a"*40)
        commit_posts=[payload for route,method,payload in calls
                      if method=="POST" and route.endswith("/git/commits")]
        self.assertEqual(commit_posts[0]["parents"],[CARRIER_HEAD])
        self.assertFalse(any("dispatches" in route for route,_,_ in calls))
        self.assertGreaterEqual(counters["live"],7)

    def test_root_tree_changes_only_source_and_provenance(self):
        _result,calls,_counters=self.execute()
        roots=[payload for route,method,payload in calls
               if method=="POST" and route.endswith("/git/trees")
               and any(item.get("path")==MOD.PUB.PREFIX
                       for item in payload.get("tree",[]))]
        self.assertEqual(len(roots),1)
        self.assertEqual([item["path"] for item in roots[0]["tree"]],
                         [MOD.PUB.PREFIX,MOD.PROVENANCE_PATH])

    def test_created_two_parent_commit_is_denied(self):
        value,_calls,_counters,patches=self.harness(bad_parent=True)
        try:
            for patch in patches: patch.start()
            with self.assertRaises(MOD.Denied):
                MOD.publish_review_pinned(value,Path("/source"),BRANCH)
        finally:
            for patch in reversed(patches): patch.stop()

    def test_provenance_readback_mismatch_is_denied(self):
        value,_calls,_counters,patches=self.harness(bad_provenance=True)
        try:
            for patch in patches: patch.start()
            with self.assertRaises(MOD.Denied):
                MOD.publish_review_pinned(value,Path("/source"),BRANCH)
        finally:
            for patch in reversed(patches): patch.stop()

    def test_revocation_mid_transfer_stops_before_ref(self):
        stale=receipt(); stale["incident_resume_comment_id"]=100
        def live(n):
            return receipt() if n<4 else stale
        value,calls,_counters,patches=self.harness(live=live)
        try:
            for patch in patches: patch.start()
            with self.assertRaises(MOD.Denied):
                MOD.publish_review_pinned(value,Path("/source"),BRANCH)
        finally:
            for patch in reversed(patches): patch.stop()
        self.assertFalse(any(method=="POST" and route.endswith("/git/refs")
                             for route,method,_ in calls))

    def test_source_tree_mismatch_stops_all_writes(self):
        value,calls,_counters,patches=self.harness()
        patches[7]=mock.patch.object(MOD.PUB,"source_tree_sha",
                                     return_value="f"*40)
        try:
            for patch in patches: patch.start()
            with self.assertRaises(MOD.Denied):
                MOD.publish_review_pinned(value,Path("/source"),BRANCH)
        finally:
            for patch in reversed(patches): patch.stop()
        self.assertFalse(any(method=="POST" for _,method,_ in calls))

    def test_dirty_dispatcher_checkout_is_denied(self):
        with mock.patch.object(
                MOD,"git_output",
                side_effect=[PUB_HEAD,
                             " M scripts/dispatch-review-pinned-private-ci.py"]):
            with self.assertRaises(MOD.Denied):
                MOD.publish_review_pinned(receipt(),Path("/source"),BRANCH)



class IncidentClosureTest(unittest.TestCase):
    def issue(self,state="closed",reason="completed",closed_at="2026-10-17T22:30:00Z"):
        return {
            "id":MOD.PUB.SOURCE_INCIDENT_ISSUE_ID,
            "number":MOD.PUB.SOURCE_INCIDENT_NUMBER,
            "state":state,
            "state_reason":reason,
            "closed_at":closed_at,
        }

    def test_closed_completed_incident_allowed(self):
        with mock.patch.object(MOD.PUB,"gh_api",return_value=self.issue()):
            self.assertEqual(
                MOD.require_incident_closed(),
                "2026-10-17T22:30:00Z")

    def test_open_incident_denied(self):
        with mock.patch.object(MOD.PUB,"gh_api",
                               return_value=self.issue(state="open",
                                                       reason=None,
                                                       closed_at=None)):
            with self.assertRaises(MOD.Denied):
                MOD.require_incident_closed()

    def test_closed_incident_without_valid_time_denied(self):
        with mock.patch.object(MOD.PUB,"gh_api",
                               return_value=self.issue(closed_at="yesterday")):
            with self.assertRaises(MOD.Denied):
                MOD.require_incident_closed()



class DispatcherBudgetTest(unittest.TestCase):
    def test_nonfinite_json_is_denied(self):
        with self.assertRaises(MOD.Denied):
            MOD.strict_json('{"x":NaN}',dict)

    def test_insufficient_dispatcher_budget_denied(self):
        response={"resources":{"core":{"remaining":1039}}}
        with mock.patch.object(MOD.PUB,"gh_api",return_value=response):
            with self.assertRaises(MOD.Denied):
                MOD.require_dispatcher_api_budget(1,20)

    def test_sufficient_dispatcher_budget_allowed(self):
        response={"resources":{"core":{"remaining":1040}}}
        with mock.patch.object(MOD.PUB,"gh_api",return_value=response):
            MOD.require_dispatcher_api_budget(1,20)

    def test_invalid_dispatcher_write_count_denied(self):
        with mock.patch.object(MOD.PUB,"gh_api") as api:
            with self.assertRaises(MOD.Denied):
                MOD.require_dispatcher_api_budget(1,0)
            api.assert_not_called()


if __name__=="__main__":
    unittest.main()
