#!/usr/bin/env python3
"""Create one review-pinned private-CI payload branch after live authorization.

This is the source-writing companion to verify-review-pinned-private-ci.py.
It deliberately revalidates live authorization immediately before every Git
object/ref write. It never updates an existing ref and never dispatches a
source-bearing workflow: the carrier's push workflow executes the exact payload.
"""
import argparse, base64, datetime as dt, hashlib, importlib.util, json, math, re, subprocess, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
PUB_PATH=ROOT/"scripts/publish-private-ci-source.py"
SPEC=importlib.util.spec_from_file_location("private_publisher",PUB_PATH)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("cannot load trusted private publisher helpers")
PUB=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(PUB)

SOURCE_REPO="ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPO_ID=1400792274
CARRIER_REPO="sagitta-stack/sagitta-infra"
CARRIER_REPO_ID=1357615104
INCIDENT_REPO="ores-truffle-oreslang/oreslang-platform-conformance"
INCIDENT_NUMBER=22
PUBLISHER_PR=1217
DESTINATION_PR=1092
SNAPSHOT_PR=1189
CARRIER_PR=7
DISALLOWED_REVIEWER_IDS=frozenset({11139560,2148448})
PERMISSION_RANK={"none":0,"read":1,"triage":2,"write":3,"maintain":4,"admin":5}
SHA=re.compile(r"^[0-9a-f]{40}$")
LOGIN=re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
MAX_JSON=2*1024*1024
PREFIX="private-oreslang-source"
PROVENANCE=".private-ci-provenance.json"

class Denied(RuntimeError): pass

def unique_object(pairs):
    out={}
    for k,v in pairs:
        if k in out: raise Denied("duplicate JSON key: "+k)
        out[k]=v
    return out

def parse_json_bytes(raw,label,expect):
    if not isinstance(raw,(bytes,bytearray)) or not raw or len(raw)>MAX_JSON or raw.startswith(b"\xef\xbb\xbf"):
        raise Denied("invalid JSON bytes: "+label)
    def reject_constant(_): raise Denied("nonstandard JSON number: "+label)
    def strict_int(v):
        if len(v)>64: raise Denied("oversized JSON integer: "+label)
        return int(v)
    def strict_float(v):
        if len(v)>128: raise Denied("oversized JSON float: "+label)
        n=float(v)
        if not math.isfinite(n): raise Denied("nonfinite JSON number: "+label)
        return n
    try:
        value=json.loads(raw.decode("utf-8"),object_pairs_hook=unique_object,
                         parse_constant=reject_constant,parse_int=strict_int,parse_float=strict_float)
    except (UnicodeError,ValueError,RecursionError) as exc:
        raise Denied("invalid JSON: "+label) from exc
    if not isinstance(value,expect): raise Denied("unexpected JSON shape: "+label)
    return value

def load_json(path,expect=dict):
    return parse_json_bytes(Path(path).read_bytes(),str(path),expect)

def require_sha(v,label):
    if type(v) is not str or not SHA.fullmatch(v): raise Denied("invalid SHA: "+label)
    return v

def api_list(route):
    argv=["gh","api","--method","GET",route,"--header","Accept: application/vnd.github+json"]
    try:
        p=subprocess.run(argv,capture_output=True,env=PUB.verified_child_env(),timeout=45,check=False)
    except (OSError,subprocess.TimeoutExpired) as exc:
        raise Denied("authenticated GitHub list request unavailable") from exc
    if p.returncode!=0: raise Denied("authenticated GitHub list request failed")
    return parse_json_bytes(p.stdout,"GitHub list response",list)

def permission_at_least(actual,minimum):
    return actual in PERMISSION_RANK and PERMISSION_RANK[actual]>=PERMISSION_RANK[minimum]

RECEIPT_KEYS={
 "schema","source_repository","source_repository_id","source_commit_sha","source_tree_sha",
 "carrier_repository","carrier_repository_id","carrier_base_sha","carrier_workflow_blob_sha",
 "independent_reviewer_login","independent_reviewer_id","independent_reviewer_policy_blob_sha",
 "independent_reviewer_source_permission","independent_reviewer_carrier_permission",
 "publisher_policy_pr","publisher_policy_head_sha","publisher_review_id",
 "destination_approval_pr","destination_approval_head_sha","destination_review_id",
 "snapshot_approval_pr","snapshot_approval_head_sha","snapshot_review_id",
 "carrier_policy_pr","carrier_policy_head_sha","carrier_review_id","incident_resume_comment_id"
}

def validate_receipt(r):
    if type(r) is not dict or set(r)!=RECEIPT_KEYS or r.get("schema")!=2:
        raise Denied("unexpected review-pinned receipt schema")
    fixed={
      "source_repository":SOURCE_REPO,"source_repository_id":SOURCE_REPO_ID,
      "carrier_repository":CARRIER_REPO,"carrier_repository_id":CARRIER_REPO_ID,
      "publisher_policy_pr":PUBLISHER_PR,"destination_approval_pr":DESTINATION_PR,
      "snapshot_approval_pr":SNAPSHOT_PR,"carrier_policy_pr":CARRIER_PR,
    }
    for k,v in fixed.items():
        if r.get(k)!=v: raise Denied("unexpected receipt field: "+k)
    for k in ("source_commit_sha","source_tree_sha","carrier_base_sha","carrier_workflow_blob_sha",
              "independent_reviewer_policy_blob_sha","publisher_policy_head_sha",
              "destination_approval_head_sha","snapshot_approval_head_sha","carrier_policy_head_sha"):
        require_sha(r.get(k),k)
    if r["carrier_base_sha"]!=r["carrier_policy_head_sha"]:
        raise Denied("carrier base must equal reviewed carrier head")
    login=r.get("independent_reviewer_login"); rid=r.get("independent_reviewer_id")
    if type(login) is not str or not LOGIN.fullmatch(login) or type(rid) is not int or rid<=0:
        raise Denied("invalid independent reviewer identity")
    if rid in DISALLOWED_REVIEWER_IDS: raise Denied("operator-controlled reviewer is not independent")
    for k in ("independent_reviewer_source_permission","independent_reviewer_carrier_permission"):
        if not permission_at_least(r.get(k),"write"): raise Denied("reviewer permission below write: "+k)
    for k in ("publisher_review_id","destination_review_id","snapshot_review_id","carrier_review_id","incident_resume_comment_id"):
        if type(r.get(k)) is not int or r[k]<=0: raise Denied("invalid positive receipt field: "+k)
    return r

def repo_identity(repo,repo_id):
    d=PUB.gh_api("repos/"+repo)
    if (d.get("id")!=repo_id or d.get("full_name")!=repo or d.get("private") is not True
            or d.get("visibility")!="private" or d.get("archived") is not False or d.get("disabled") is not False):
        raise Denied("repository identity/private state changed: "+repo)
    return d

def exact_review(repo,number,head,r,expected_review_id):
    pr=PUB.gh_api(f"repos/{repo}/pulls/{number}")
    base=pr.get("base") or {}; h=pr.get("head") or {}; author=pr.get("user") or {}
    if (pr.get("number")!=number or pr.get("state")!="open" or pr.get("merged") is True
            or pr.get("draft") is not False or base.get("ref")!="main"
            or (base.get("repo") or {}).get("full_name")!=repo
            or (h.get("repo") or {}).get("full_name")!=repo or h.get("sha")!=head):
        raise Denied("security PR state/head changed: "+str(number))
    if author.get("id")==r["independent_reviewer_id"]:
        raise Denied("reviewer cannot approve own security PR")
    reviews=api_list(f"repos/{repo}/pulls/{number}/reviews?per_page=100&page=1")
    if api_list(f"repos/{repo}/pulls/{number}/reviews?per_page=100&page=2")!=[]:
        raise Denied("security PR review history exceeds bounded first page")
    exact=[]
    for review in reviews:
        u=review.get("user") or {}
        if (u.get("login")==r["independent_reviewer_login"] and
            u.get("id")==r["independent_reviewer_id"] and u.get("type")=="User" and
            review.get("commit_id")==head):
            submitted=review.get("submitted_at"); rid=review.get("id")
            if type(submitted) is not str or type(rid) is not int or rid<=0: raise Denied("invalid exact review metadata")
            try: stamp=dt.datetime.fromisoformat(submitted.replace("Z","+00:00"))
            except ValueError as exc: raise Denied("invalid exact review timestamp") from exc
            exact.append((stamp,rid,str(review.get("state") or "").upper()))
    if not exact: raise Denied("missing independent exact-head review: "+str(number))
    latest=max(exact)
    if latest[2]!="APPROVED" or latest[1]!=expected_review_id:
        raise Denied("latest exact-head review no longer matches receipt: "+str(number))

def exact_permission(repo,r,receipt_key):
    doc=PUB.gh_api(f"repos/{repo}/collaborators/{r['independent_reviewer_login']}/permission")
    user=doc.get("user") or {}; permission=str(doc.get("permission") or "").lower()
    if (user.get("login")!=r["independent_reviewer_login"] or
        user.get("id")!=r["independent_reviewer_id"] or user.get("type")!="User"):
        raise Denied("live reviewer identity changed: "+repo)
    if not permission_at_least(permission,"write") or permission!=r[receipt_key]:
        raise Denied("live reviewer permission changed: "+repo)

def decode_contents_api_file(d,label,max_size):
    if (d.get("type")!="file" or d.get("encoding")!="base64" or type(d.get("content")) is not str
            or type(d.get("size")) is not int or d["size"]<1 or d["size"]>max_size):
        raise Denied("reviewed file unavailable: "+label)
    encoded=d["content"]
    if any(ch.isspace() and ch not in "\\r\\n" for ch in encoded):
        raise Denied("reviewed file has noncanonical base64 whitespace: "+label)
    compact=encoded.replace("\\r","").replace("\\n","")
    try: raw=base64.b64decode(compact,validate=True)
    except Exception as exc: raise Denied("reviewed file is not canonical base64: "+label) from exc
    if base64.b64encode(raw).decode("ascii")!=compact:
        raise Denied("reviewed file has noncanonical base64 encoding: "+label)
    if len(raw)!=d["size"]: raise Denied("reviewed file size mismatch: "+label)
    expected=hashlib.sha1(b"blob "+str(len(raw)).encode("ascii")+b"\\0"+raw).hexdigest()
    if require_sha(d.get("sha"),label+" blob")!=expected: raise Denied("reviewed file blob mismatch: "+label)
    return raw,expected

def content_file(repo,path,ref,max_size=256*1024):
    d=PUB.gh_api(f"repos/{repo}/contents/{path}?ref={ref}")
    return decode_contents_api_file(d,path,max_size)

def content_blob(repo,path,ref):
    return content_file(repo,path,ref)[1]

def require_live_reviewer_policy(r):
    source_raw,source_blob=content_file(SOURCE_REPO,".github/private-ci-independent-reviewer.json",
                                        r["publisher_policy_head_sha"],64*1024)
    carrier_raw,carrier_blob=content_file(CARRIER_REPO,".github/private-ci-independent-reviewer.json",
                                          r["carrier_policy_head_sha"],64*1024)
    if source_raw!=carrier_raw or source_blob!=carrier_blob or source_blob!=r["independent_reviewer_policy_blob_sha"]:
        raise Denied("source/carrier reviewer policy mismatch")
    policy=parse_json_bytes(source_raw,"reviewer policy",dict)
    if (set(policy)!={"schema","enabled","minimumPermission","disallowedReviewerIds","reviewer"}
            or policy.get("schema")!=2 or policy.get("enabled") is not True):
        raise Denied("independent reviewer policy is not enabled")
    if set(policy.get("disallowedReviewerIds") or [])!=DISALLOWED_REVIEWER_IDS:
        raise Denied("independent reviewer denylist changed")
    reviewer=policy.get("reviewer")
    if (type(reviewer) is not dict or set(reviewer)!={"login","id"}
            or reviewer.get("login")!=r["independent_reviewer_login"]
            or reviewer.get("id")!=r["independent_reviewer_id"]):
        raise Denied("receipt reviewer does not match live reviewer policy")
    minimum=policy.get("minimumPermission")
    if minimum not in ("write","maintain","admin"):
        raise Denied("invalid reviewer minimum permission")
    for key in ("independent_reviewer_source_permission","independent_reviewer_carrier_permission"):
        if not permission_at_least(r[key],minimum):
            raise Denied("receipt permission below live reviewer policy")
    return minimum

def require_exact_ledger(repo,path,ref,expected):
    d=PUB.gh_api(f"repos/{repo}/contents/{path}?ref={ref}")
    raw,_=decode_contents_api_file(d,path,64*1024)
    try: text=raw.decode("utf-8")
    except UnicodeDecodeError as exc: raise Denied("reviewed ledger is not UTF-8: "+path) from exc
    rows=[x.strip() for x in text.splitlines() if x.strip() and not x.lstrip().startswith("#")]
    if rows!=[expected]: raise Denied("reviewed ledger entry changed: "+path)

def incident_resume(r):
    comments=api_list(f"repos/{INCIDENT_REPO}/issues/{INCIDENT_NUMBER}/comments?per_page=100&page=1")
    if api_list(f"repos/{INCIDENT_REPO}/issues/{INCIDENT_NUMBER}/comments?per_page=100&page=2")!=[]:
        raise Denied("incident comment history exceeds bounded first page")
    suffix=(f" publisher={r['publisher_policy_head_sha']} destination={r['destination_approval_head_sha']}"
            f" snapshot={r['snapshot_approval_head_sha']} carrier={r['carrier_policy_head_sha']}"
            f" source_commit={r['source_commit_sha']} source_tree={r['source_tree_sha']}"
            f" carrier_repo_id={CARRIER_REPO_ID}")
    approve="PRIVATE_CI_RESUME_APPROVED"+suffix; revoke="PRIVATE_CI_RESUME_REVOKED"+suffix
    events=[]
    for c in comments:
        u=c.get("user") or {}; body=c.get("body")
        if (u.get("login")!=r["independent_reviewer_login"] or u.get("id")!=r["independent_reviewer_id"]
                or u.get("type")!="User" or type(body) is not str): continue
        lines=[x.strip() for x in body.splitlines() if x.strip()]
        state=None
        if approve in lines: state="APPROVED"
        if revoke in lines:
            if state: raise Denied("incident comment both approves and revokes")
            state="REVOKED"
        if state:
            cid=c.get("id"); created=c.get("created_at")
            if type(cid) is not int or cid<=0 or type(created) is not str: raise Denied("invalid incident authorization metadata")
            try: stamp=dt.datetime.fromisoformat(created.replace("Z","+00:00"))
            except ValueError as exc: raise Denied("invalid incident authorization timestamp") from exc
            events.append((stamp,cid,state))
    if not events: raise Denied("missing incident resume authorization")
    events.sort()
    previous=None
    for _,_,state in events:
        if state==previous: raise Denied("duplicate incident authorization transition")
        previous=state
    latest=events[-1]
    if latest[2]!="APPROVED" or latest[1]!=r["incident_resume_comment_id"]:
        raise Denied("incident resume authorization changed or revoked")

def branch_absent(r):
    branch="ci/oreslang-"+r["source_tree_sha"]
    refs=api_list(f"repos/{CARRIER_REPO}/git/matching-refs/heads/{branch}")
    if any(isinstance(x,dict) and x.get("ref")=="refs/heads/"+branch for x in refs):
        raise Denied("private CI branch already exists")
    return branch

def refresh_authorization(r, require_new_ref=False):
    validate_receipt(r)
    repo_identity(SOURCE_REPO,SOURCE_REPO_ID); repo_identity(CARRIER_REPO,CARRIER_REPO_ID)
    exact_review(SOURCE_REPO,PUBLISHER_PR,r["publisher_policy_head_sha"],r,r["publisher_review_id"])
    exact_review(SOURCE_REPO,DESTINATION_PR,r["destination_approval_head_sha"],r,r["destination_review_id"])
    exact_review(SOURCE_REPO,SNAPSHOT_PR,r["snapshot_approval_head_sha"],r,r["snapshot_review_id"])
    exact_review(CARRIER_REPO,CARRIER_PR,r["carrier_policy_head_sha"],r,r["carrier_review_id"])
    minimum=require_live_reviewer_policy(r)
    exact_permission(SOURCE_REPO,r,"independent_reviewer_source_permission")
    exact_permission(CARRIER_REPO,r,"independent_reviewer_carrier_permission")
    if (not permission_at_least(r["independent_reviewer_source_permission"],minimum)
            or not permission_at_least(r["independent_reviewer_carrier_permission"],minimum)):
        raise Denied("live reviewer permission below policy minimum")
    if content_blob(CARRIER_REPO,".github/workflows/private-oreslang-java-validation.yml",r["carrier_policy_head_sha"])!=r["carrier_workflow_blob_sha"]:
        raise Denied("carrier validation workflow changed")
    require_exact_ledger(SOURCE_REPO,".github/private-ci-approved-repositories.txt",r["publisher_policy_head_sha"],
                         f"{CARRIER_REPO} {CARRIER_REPO_ID}")
    require_exact_ledger(SOURCE_REPO,".github/private-ci-approved-source-snapshots.txt",r["publisher_policy_head_sha"],
                         f"{r['source_commit_sha']} {r['source_tree_sha']} {CARRIER_REPO_ID}")
    commit=PUB.gh_api(f"repos/{SOURCE_REPO}/git/commits/{r['source_commit_sha']}")
    if commit.get("sha")!=r["source_commit_sha"] or PUB.nested_sha(commit,"tree")!=r["source_tree_sha"]:
        raise Denied("approved source commit/tree changed")
    incident_resume(r)
    parent=PUB.gh_api(f"repos/{CARRIER_REPO}/git/commits/{r['carrier_base_sha']}")
    if parent.get("sha")!=r["carrier_base_sha"]: raise Denied("carrier parent changed")
    parent_tree=PUB.nested_sha(parent,"tree")
    tree=PUB.gh_api(f"repos/{CARRIER_REPO}/git/trees/{parent_tree}")
    items=tree.get("tree")
    if tree.get("sha")!=parent_tree or tree.get("truncated") is not False or not isinstance(items,list):
        raise Denied("carrier parent tree unavailable")
    if any(isinstance(x,dict) and x.get("path") in (PREFIX,PROVENANCE) for x in items):
        raise Denied("reviewed carrier parent already contains private payload paths")
    if require_new_ref: branch_absent(r)
    return parent_tree

def git_identity(source,r):
    try:
        env=PUB.verified_child_env()
        head=subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD^{commit}"],
                                     text=True,timeout=20,env=env).strip()
        tree=subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD^{tree}"],
                                     text=True,timeout=20,env=env).strip()
    except (OSError,subprocess.SubprocessError) as exc: raise Denied("source checkout Git identity unavailable") from exc
    if head!=r["source_commit_sha"] or tree!=r["source_tree_sha"]: raise Denied("source checkout is not approved commit/tree")

def dispatch(receipt_path,source_dir):
    r=validate_receipt(load_json(receipt_path))
    branch_absent(r)
    refresh_authorization(r,True)
    source=Path(source_dir).resolve(strict=True); git_identity(source,r)
    files=PUB.collect_source(source,ROOT)
    if PUB.source_tree_sha(files)!=r["source_tree_sha"]: raise Denied("source bytes do not match approved Git tree")
    batches=PUB.prepare_source_tree_batches(files)
    blob_writes=sum(text is None for batch in batches for _,_,_,text in batch)
    PUB.require_private_ci_api_budget(len(files),max(1,blob_writes+len(batches)+3))
    isolated=None
    for batch in batches:
        elements=[]
        for rel,data,mode,as_text in batch:
            entry={"path":rel,"mode":mode,"type":"blob"}
            if as_text is not None: entry["content"]=as_text
            else:
                refresh_authorization(r,True)
                blob=PUB.gh_api(f"repos/{CARRIER_REPO}/git/blobs",method="POST",
                                payload={"content":base64.b64encode(data).decode("ascii"),"encoding":"base64"})
                got=PUB.valid_sha(blob.get("sha"))
                expected=hashlib.sha1(b"blob "+str(len(data)).encode("ascii")+b"\0"+data).hexdigest()
                if got!=expected: raise Denied("uploaded blob digest mismatch")
                entry["sha"]=got
            elements.append(entry)
        refresh_authorization(r,True)
        payload={"tree":elements}
        if isolated is not None: payload["base_tree"]=isolated
        isolated=PUB.valid_sha(PUB.gh_api(f"repos/{CARRIER_REPO}/git/trees",method="POST",payload=payload).get("sha"))
    if isolated!=r["source_tree_sha"]: raise Denied("carrier source subtree differs from approved tree")
    base_tree=refresh_authorization(r,True)
    provenance=json.dumps(r,sort_keys=True,separators=(",",":"))+"\n"
    root_tree=PUB.gh_api(f"repos/{CARRIER_REPO}/git/trees",method="POST",payload={
      "base_tree":base_tree,
      "tree":[
        {"path":PREFIX,"mode":"040000","type":"tree","sha":isolated},
        {"path":PROVENANCE,"mode":"100644","type":"blob","content":provenance},
      ]})
    root_sha=PUB.valid_sha(root_tree.get("sha"))
    refresh_authorization(r,True)
    commit=PUB.gh_api(f"repos/{CARRIER_REPO}/git/commits",method="POST",payload={
      "message":"ci: exact approved private Oreslang snapshot "+r["source_tree_sha"],
      "tree":root_sha,"parents":[r["carrier_base_sha"]]
    })
    commit_sha=PUB.valid_sha(commit.get("sha"))
    refresh_authorization(r,True); branch=branch_absent(r)
    created=PUB.gh_api(f"repos/{CARRIER_REPO}/git/refs",method="POST",
                       payload={"ref":"refs/heads/"+branch,"sha":commit_sha})
    if created.get("ref")!="refs/heads/"+branch or PUB.nested_sha(created,"object")!=commit_sha:
        raise Denied("carrier ref creation response mismatch")
    ref=PUB.gh_api(f"repos/{CARRIER_REPO}/git/ref/heads/{branch}")
    if ref.get("ref")!="refs/heads/"+branch or PUB.nested_sha(ref,"object")!=commit_sha:
        raise Denied("carrier ref readback mismatch")
    committed=PUB.gh_api(f"repos/{CARRIER_REPO}/git/commits/{commit_sha}")
    parents=committed.get("parents")
    if (committed.get("sha")!=commit_sha or PUB.nested_sha(committed,"tree")!=root_sha or
        not isinstance(parents,list) or len(parents)!=1 or parents[0].get("sha")!=r["carrier_base_sha"]):
        raise Denied("payload commit parent/tree readback mismatch")
    tree=PUB.gh_api(f"repos/{CARRIER_REPO}/git/trees/{root_sha}")
    entries=tree.get("tree")
    if tree.get("truncated") is not False or not isinstance(entries,list): raise Denied("payload root tree readback invalid")
    by_path={x.get("path"):x for x in entries if isinstance(x,dict)}
    if (by_path.get(PREFIX,{}).get("sha")!=r["source_tree_sha"] or
        by_path.get(PREFIX,{}).get("type")!="tree" or PROVENANCE not in by_path):
        raise Denied("payload root tree does not contain exact source/provenance")
    refresh_authorization(r)
    return commit_sha,len(files)

def main(argv=None):
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--receipt",required=True)
    ap.add_argument("--source-dir",required=True)
    args=ap.parse_args(argv)
    try:
        commit,count=dispatch(args.receipt,args.source_dir)
    except (OSError,Denied,PUB.Denied) as exc:
        print("PRIVATE_CI_REVIEW_PINNED_DISPATCH_DENIED: "+str(exc),file=sys.stderr); return 1
    print(f"PRIVATE_CI_REVIEW_PINNED_DISPATCH_CREATED: files={count} commit={commit}")
    return 0

if __name__=="__main__": sys.exit(main())
