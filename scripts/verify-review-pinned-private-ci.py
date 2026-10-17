#!/usr/bin/env python3
"""Verify review-pinned private-CI authority without relying on branch protection.

This checker is intentionally read-only. It does not publish source or dispatch
CI. A trusted external dispatcher supplies live GitHub API JSON for the four
security PRs, their reviews, the incident comments, source commit metadata,
destination repository metadata, and carrier workflow metadata. The checker
fails closed unless every approval is for the exact current PR head.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{40}$")
REVIEWER = "ORESoftware"
SOURCE_REPO = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPO_ID = 1400792274
CARRIER_REPO = "sagitta-stack/sagitta-infra"
CARRIER_REPO_ID = 1357615104
EXPECTED_PRS = {
    "publisher": (SOURCE_REPO, 1123),
    "destination": (SOURCE_REPO, 1092),
    "snapshot": (SOURCE_REPO, 1173),
    "carrier": (CARRIER_REPO, 7),
}
RESUME_MARKER = "PRIVATE_CI_RESUME_APPROVED"
MAX_JSON = 2 * 1024 * 1024

class Denied(RuntimeError):
    pass

def unique_object(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise Denied("duplicate JSON key: " + key)
        out[key] = value
    return out

def load_json(path):
    raw = Path(path).read_bytes()
    if not raw or len(raw) > MAX_JSON or raw.startswith(b"\xef\xbb\xbf"):
        raise Denied("invalid JSON input bytes: " + str(path))
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid JSON: " + str(path)) from exc

def require_sha(value, label):
    if type(value) is not str or not SHA.fullmatch(value):
        raise Denied("invalid SHA: " + label)
    return value

def pr_head(pr, repo, number):
    if type(pr) is not dict:
        raise Denied("malformed PR metadata")
    if pr.get("number") != number:
        raise Denied("unexpected PR number")
    base_repo = ((pr.get("base") or {}).get("repo") or {}).get("full_name")
    head_repo = ((pr.get("head") or {}).get("repo") or {}).get("full_name")
    if base_repo != repo or head_repo != repo:
        raise Denied("unexpected PR repository")
    if pr.get("state") != "open":
        raise Denied("security PR is not open")
    if pr.get("merged") is True:
        raise Denied("security PR unexpectedly merged")
    return require_sha((pr.get("head") or {}).get("sha"), "PR head")

def exact_approval(reviews, head):
    if type(reviews) is not list:
        raise Denied("malformed review list")
    approvals = []
    for review in reviews:
        if type(review) is not dict:
            continue
        user = (review.get("user") or {}).get("login")
        state = str(review.get("state") or "").upper()
        commit = review.get("commit_id")
        if user == REVIEWER and state == "APPROVED" and commit == head:
            approvals.append(review)
    if len(approvals) != 1:
        raise Denied("require exactly one independent exact-head approval by @" + REVIEWER)
    review_id = approvals[0].get("id")
    if type(review_id) is not int or review_id <= 0:
        raise Denied("invalid approval review ID")
    return review_id

def parse_snapshot_entry(snapshot_file):
    text = Path(snapshot_file).read_text(encoding="utf-8")
    rows = [line.strip() for line in text.splitlines()
            if line.strip() and not line.lstrip().startswith("#")]
    if len(rows) != 1:
        raise Denied("snapshot approval must contain exactly one active tuple")
    parts = rows[0].split()
    if len(parts) != 3:
        raise Denied("invalid snapshot tuple")
    source_commit = require_sha(parts[0], "source commit")
    source_tree = require_sha(parts[1], "source tree")
    try:
        destination_id = int(parts[2])
    except ValueError as exc:
        raise Denied("invalid destination repository ID") from exc
    if destination_id != CARRIER_REPO_ID:
        raise Denied("snapshot tuple destination mismatch")
    return source_commit, source_tree

def main():
    ap = argparse.ArgumentParser()
    for key in EXPECTED_PRS:
        ap.add_argument("--" + key + "-pr", required=True)
        ap.add_argument("--" + key + "-reviews", required=True)
    ap.add_argument("--incident-comments", required=True)
    ap.add_argument("--source-commit", required=True)
    ap.add_argument("--destination-repo", required=True)
    ap.add_argument("--carrier-workflow", required=True)
    ap.add_argument("--snapshot-ledger", required=True)
    args = ap.parse_args()

    heads = {}
    review_ids = {}
    for key, (repo, number) in EXPECTED_PRS.items():
        pr = load_json(getattr(args, key + "_pr"))
        reviews = load_json(getattr(args, key + "_reviews"))
        head = pr_head(pr, repo, number)
        heads[key] = head
        review_ids[key] = exact_approval(reviews, head)

    incident = load_json(args.incident_comments)
    if type(incident) is not list:
        raise Denied("malformed incident comments")
    resume = []
    for comment in incident:
        if type(comment) is not dict:
            continue
        user = (comment.get("user") or {}).get("login")
        body = comment.get("body")
        if user == REVIEWER and type(body) is str and RESUME_MARKER in body:
            cid = comment.get("id")
            if type(cid) is int and cid > 0:
                resume.append(cid)
    if len(resume) != 1:
        raise Denied("require exactly one independent incident resume approval")

    source_commit, source_tree = parse_snapshot_entry(args.snapshot_ledger)
    commit = load_json(args.source_commit)
    if require_sha(commit.get("sha"), "source commit metadata") != source_commit:
        raise Denied("source commit metadata mismatch")
    tree = require_sha((commit.get("commit") or {}).get("tree", {}).get("sha"), "source tree metadata")
    if tree != source_tree:
        raise Denied("approved source commit/tree mismatch")

    destination = load_json(args.destination_repo)
    if destination.get("id") != CARRIER_REPO_ID:
        raise Denied("carrier repository ID mismatch")
    if destination.get("full_name") != CARRIER_REPO:
        raise Denied("carrier repository name mismatch")
    if destination.get("private") is not True or destination.get("archived") is True or destination.get("disabled") is True:
        raise Denied("carrier repository is not an active private repository")

    workflow_raw = Path(args.carrier_workflow).read_bytes()
    if not workflow_raw or len(workflow_raw) > 256 * 1024:
        raise Denied("invalid carrier workflow bytes")
    workflow_blob = hashlib.sha1(
        b"blob " + str(len(workflow_raw)).encode("ascii") + b"\0" + workflow_raw
    ).hexdigest()

    receipt = {
        "schema": 1,
        "source_repository": SOURCE_REPO,
        "source_repository_id": SOURCE_REPO_ID,
        "source_commit_sha": source_commit,
        "source_tree_sha": source_tree,
        "carrier_repository": CARRIER_REPO,
        "carrier_repository_id": CARRIER_REPO_ID,
        "carrier_base_sha": heads["carrier"],
        "carrier_workflow_blob_sha": workflow_blob,
        "publisher_policy_pr": 1123,
        "publisher_policy_head_sha": heads["publisher"],
        "publisher_review_id": review_ids["publisher"],
        "destination_approval_pr": 1092,
        "destination_approval_head_sha": heads["destination"],
        "destination_review_id": review_ids["destination"],
        "snapshot_approval_pr": 1173,
        "snapshot_approval_head_sha": heads["snapshot"],
        "snapshot_review_id": review_ids["snapshot"],
        "carrier_policy_pr": 7,
        "carrier_policy_head_sha": heads["carrier"],
        "carrier_review_id": review_ids["carrier"],
        "incident_resume_comment_id": resume[0],
    }
    print(json.dumps(receipt, sort_keys=True, separators=(",", ":")))

if __name__ == "__main__":
    try:
        main()
    except (OSError, Denied) as exc:
        print("PRIVATE_CI_REVIEW_PINNED_DENIED: " + str(exc))
        raise SystemExit(1)
