#!/usr/bin/env python3
"""Verify review-pinned private-CI authority without relying on branch protection.

This checker is intentionally read-only. It does not publish source or dispatch
CI. A trusted external dispatcher supplies live GitHub API JSON for the four
security PRs, their reviews, the incident comments, live reviewer permissions,
source commit metadata, destination repository metadata, and carrier workflow
metadata. The checker fails closed unless every approval is for the exact
current PR head and the configured reviewer is a distinct, currently-authorized
human identity.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{40}$")
REVIEWER_LOGIN = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
PERMISSION_RANK = {"none": 0, "read": 1, "triage": 2, "write": 3, "maintain": 4, "admin": 5}
SOURCE_REPO = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPO_ID = 1400792274
CARRIER_REPO = "sagitta-stack/sagitta-infra"
CARRIER_REPO_ID = 1357615104
DISALLOWED_REVIEWER_IDS = frozenset({11139560, 2148448})
EXPECTED_PRS = {
    "publisher": (SOURCE_REPO, 1217),
    "destination": (SOURCE_REPO, 1092),
    "snapshot": (SOURCE_REPO, 1189),
    "carrier": (CARRIER_REPO, 7),
}
RESUME_MARKER = "PRIVATE_CI_RESUME_APPROVED"
RESUME_REVOKE_MARKER = "PRIVATE_CI_RESUME_REVOKED"
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

def reviewer_policy(path):
    value = load_json(path)
    required = {"schema", "enabled", "minimumPermission", "disallowedReviewerIds", "reviewer"}
    if type(value) is not dict or set(value) != required or value.get("schema") != 2:
        raise Denied("invalid independent reviewer policy")
    minimum = value.get("minimumPermission")
    if minimum not in ("write", "maintain", "admin"):
        raise Denied("invalid minimum reviewer permission")
    blocked = value.get("disallowedReviewerIds")
    if (type(blocked) is not list or not blocked
            or any(type(item) is not int or item <= 0 for item in blocked)
            or len(set(blocked)) != len(blocked)
            or set(blocked) != DISALLOWED_REVIEWER_IDS):
        raise Denied("invalid disallowed reviewer IDs")
    if value.get("enabled") is not True:
        raise Denied("independent reviewer is not configured")
    reviewer = value.get("reviewer")
    if (type(reviewer) is not dict
            or set(reviewer) != {"login", "id"}
            or type(reviewer.get("login")) is not str
            or not REVIEWER_LOGIN.fullmatch(reviewer["login"])
            or type(reviewer.get("id")) is not int
            or reviewer["id"] <= 0):
        raise Denied("invalid independent reviewer identity")
    if reviewer["id"] in blocked:
        raise Denied("configured reviewer is an operator-controlled identity")
    return reviewer["login"], reviewer["id"], minimum

def live_permission(doc, reviewer, minimum, label):
    if type(doc) is not dict:
        raise Denied("malformed reviewer permission: " + label)
    user = doc.get("user") or {}
    reviewer_login, reviewer_id, _ = reviewer
    if (user.get("login") != reviewer_login or user.get("id") != reviewer_id
            or user.get("type") != "User"):
        raise Denied("reviewer permission identity mismatch: " + label)
    permission = str(doc.get("permission") or "").lower()
    if permission not in PERMISSION_RANK:
        raise Denied("unknown reviewer permission: " + label)
    if PERMISSION_RANK[permission] < PERMISSION_RANK[minimum]:
        raise Denied("reviewer lacks live repository permission: " + label)
    return permission

def pr_head(pr, repo, number):
    if type(pr) is not dict:
        raise Denied("malformed PR metadata")
    if pr.get("number") != number:
        raise Denied("unexpected PR number")
    base = pr.get("base") or {}
    head = pr.get("head") or {}
    base_repo = (base.get("repo") or {}).get("full_name")
    head_repo = (head.get("repo") or {}).get("full_name")
    if base_repo != repo or head_repo != repo:
        raise Denied("unexpected PR repository")
    if base.get("ref") != "main":
        raise Denied("security PR must target main")
    if pr.get("state") != "open":
        raise Denied("security PR is not open")
    if pr.get("merged") is True:
        raise Denied("security PR unexpectedly merged")
    if pr.get("draft") is not False:
        raise Denied("security PR must be ready for review")
    author = pr.get("user") or {}
    if (type(author.get("id")) is not int or author["id"] <= 0
            or author.get("type") != "User"):
        raise Denied("security PR author identity is unavailable")
    return (require_sha(head.get("sha"), "PR head"), author["id"])

def submitted_key(review):
    value = review.get("submitted_at")
    if type(value) is not str:
        raise Denied("exact-head review lacks submitted_at")
    try:
        stamp = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise Denied("invalid exact-head review submitted_at") from exc
    rid = review.get("id")
    if type(rid) is not int or rid <= 0:
        raise Denied("invalid exact-head review ID")
    return stamp, rid

def exact_approval(reviews, head, reviewer, author_id):
    if type(reviews) is not list:
        raise Denied("malformed review list")
    reviewer_login, reviewer_id, _ = reviewer
    if reviewer_id == author_id:
        raise Denied("independent reviewer cannot be the PR author")
    exact = []
    for review in reviews:
        if type(review) is not dict:
            continue
        user = review.get("user") or {}
        if (user.get("login") == reviewer_login
                and user.get("id") == reviewer_id
                and user.get("type") == "User"
                and review.get("commit_id") == head):
            exact.append(review)
    if not exact:
        raise Denied("require configured independent exact-head review")
    latest = max(exact, key=submitted_key)
    if str(latest.get("state") or "").upper() != "APPROVED":
        raise Denied("latest independent exact-head review is not approved")
    return latest["id"]

def comment_key(comment):
    created = comment.get("created_at")
    if type(created) is not str:
        raise Denied("incident authorization comment lacks created_at")
    try:
        stamp = dt.datetime.fromisoformat(created.replace("Z", "+00:00"))
    except ValueError as exc:
        raise Denied("invalid incident authorization created_at") from exc
    cid = comment.get("id")
    if type(cid) is not int or cid <= 0:
        raise Denied("invalid incident authorization comment ID")
    return stamp, cid

def exact_resume_comment(incident, heads, source_commit, source_tree, reviewer):
    if type(incident) is not list:
        raise Denied("malformed incident comments")
    suffix = (
        " publisher=" + heads["publisher"]
        + " destination=" + heads["destination"]
        + " snapshot=" + heads["snapshot"]
        + " carrier=" + heads["carrier"]
        + " source_commit=" + source_commit
        + " source_tree=" + source_tree
        + " carrier_repo_id=" + str(CARRIER_REPO_ID)
    )
    approve = RESUME_MARKER + suffix
    revoke = RESUME_REVOKE_MARKER + suffix
    reviewer_login, reviewer_id, _ = reviewer
    events = []
    for comment in incident:
        if type(comment) is not dict:
            continue
        user = comment.get("user") or {}
        body = comment.get("body")
        if (user.get("login") != reviewer_login or user.get("id") != reviewer_id
                or user.get("type") != "User" or type(body) is not str):
            continue
        lines = [line.strip() for line in body.splitlines() if line.strip()]
        marker = None
        if approve in lines:
            marker = "APPROVED"
        if revoke in lines:
            if marker is not None:
                raise Denied("incident comment cannot both approve and revoke")
            marker = "REVOKED"
        if marker is not None:
            events.append((comment_key(comment), marker, comment["id"]))
    if not events:
        raise Denied("missing independently bound incident resume approval")
    events.sort(key=lambda item: item[0])
    previous = None
    for _, marker, _ in events:
        if marker == previous:
            raise Denied("duplicate incident authorization state transition")
        previous = marker
    if events[-1][1] != "APPROVED":
        raise Denied("incident resume authorization is revoked")
    return events[-1][2]

def active_rows(path):
    text = Path(path).read_text(encoding="utf-8")
    return [line.strip() for line in text.splitlines()
            if line.strip() and not line.lstrip().startswith("#")]

def parse_destination_entry(destination_file):
    rows = active_rows(destination_file)
    if len(rows) != 1:
        raise Denied("destination approval must contain exactly one active entry")
    parts = rows[0].split()
    if len(parts) != 2 or parts[0] != CARRIER_REPO:
        raise Denied("invalid destination approval entry")
    try:
        destination_id = int(parts[1])
    except ValueError as exc:
        raise Denied("invalid destination approval repository ID") from exc
    if destination_id != CARRIER_REPO_ID:
        raise Denied("destination approval repository ID mismatch")
    return destination_id

def parse_snapshot_entry(snapshot_file):
    rows = active_rows(snapshot_file)
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
    ap.add_argument("--reviewer-policy", required=True)
    ap.add_argument("--source-reviewer-permission", required=True)
    ap.add_argument("--carrier-reviewer-permission", required=True)
    ap.add_argument("--incident-comments", required=True)
    ap.add_argument("--carrier-incident-comments", required=True)
    ap.add_argument("--source-commit", required=True)
    ap.add_argument("--destination-repo", required=True)
    ap.add_argument("--destination-ledger", required=True)
    ap.add_argument("--carrier-workflow", required=True)
    ap.add_argument("--snapshot-ledger", required=True)
    args = ap.parse_args()

    reviewer = reviewer_policy(args.reviewer_policy)
    source_permission = live_permission(
        load_json(args.source_reviewer_permission), reviewer, reviewer[2], "source")
    carrier_permission = live_permission(
        load_json(args.carrier_reviewer_permission), reviewer, reviewer[2], "carrier")

    heads = {}
    review_ids = {}
    for key, (repo, number) in EXPECTED_PRS.items():
        pr = load_json(getattr(args, key + "_pr"))
        reviews = load_json(getattr(args, key + "_reviews"))
        head, author_id = pr_head(pr, repo, number)
        heads[key] = head
        review_ids[key] = exact_approval(reviews, head, reviewer, author_id)

    parse_destination_entry(args.destination_ledger)
    source_commit, source_tree = parse_snapshot_entry(args.snapshot_ledger)
    incident = load_json(args.incident_comments)
    resume_id = exact_resume_comment(
        incident, heads, source_commit, source_tree, reviewer)
    carrier_incident = load_json(args.carrier_incident_comments)
    carrier_resume_id = exact_resume_comment(
        carrier_incident, heads, source_commit, source_tree, reviewer)

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

    reviewer_policy_raw = Path(args.reviewer_policy).read_bytes()
    if not reviewer_policy_raw or len(reviewer_policy_raw) > 64 * 1024:
        raise Denied("invalid reviewer policy bytes")
    reviewer_policy_blob = hashlib.sha1(
        b"blob " + str(len(reviewer_policy_raw)).encode("ascii")
        + b"\0" + reviewer_policy_raw
    ).hexdigest()

    receipt = {
        "schema": 3,
        "source_repository": SOURCE_REPO,
        "source_repository_id": SOURCE_REPO_ID,
        "source_commit_sha": source_commit,
        "source_tree_sha": source_tree,
        "carrier_repository": CARRIER_REPO,
        "carrier_repository_id": CARRIER_REPO_ID,
        "carrier_base_sha": heads["carrier"],
        "carrier_workflow_blob_sha": workflow_blob,
        "independent_reviewer_login": reviewer[0],
        "independent_reviewer_id": reviewer[1],
        "independent_reviewer_policy_blob_sha": reviewer_policy_blob,
        "independent_reviewer_source_permission": source_permission,
        "independent_reviewer_carrier_permission": carrier_permission,
        "publisher_policy_pr": 1217,
        "publisher_policy_head_sha": heads["publisher"],
        "publisher_review_id": review_ids["publisher"],
        "destination_approval_pr": 1092,
        "destination_approval_head_sha": heads["destination"],
        "destination_review_id": review_ids["destination"],
        "snapshot_approval_pr": 1189,
        "snapshot_approval_head_sha": heads["snapshot"],
        "snapshot_review_id": review_ids["snapshot"],
        "carrier_policy_pr": 7,
        "carrier_policy_head_sha": heads["carrier"],
        "carrier_review_id": review_ids["carrier"],
        "incident_resume_comment_id": resume_id,
        "carrier_incident_resume_comment_id": carrier_resume_id,
    }
    print(json.dumps(receipt, sort_keys=True, separators=(",", ":")))

if __name__ == "__main__":
    try:
        main()
    except (OSError, Denied) as exc:
        print("PRIVATE_CI_REVIEW_PINNED_DENIED: " + str(exc))
        raise SystemExit(1)
