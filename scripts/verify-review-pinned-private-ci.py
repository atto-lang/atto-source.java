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
import math
import os
import re
import stat
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
MAX_LEDGER = 64 * 1024
MAX_REVIEWER_POLICY = 64 * 1024
MAX_CARRIER_WORKFLOW = 256 * 1024
# Match the strict private-ci-policy workflow grammar. Python int()/split()
# otherwise admit numeric aliases and whitespace not approved by reviewers.
DESTINATION_ROW = re.compile(
    r"([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*) ([1-9][0-9]{0,19})"
)
SNAPSHOT_ROW = re.compile(
    r"([0-9a-f]{40}) ([0-9a-f]{40}) ([1-9][0-9]{0,19})"
)

class Denied(RuntimeError):
    pass

def unique_object(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise Denied("duplicate JSON key: " + key)
        out[key] = value
    return out

def reject_nonstandard_constant(value):
    raise Denied("nonstandard JSON number: " + value)

def finite_json_float(value):
    number = float(value)
    if not math.isfinite(number):
        raise Denied("non-finite JSON number")
    return number

def bounded_bytes(path, limit, label):
    # Follow neither the final file nor any parent directory. O_NOFOLLOW on
    # just the leaf still permits an attacker-controlled parent symlink.
    # Keep open directory FDs throughout traversal, preventing path-swap races.
    if any(not hasattr(os, flag) for flag in
           ("O_NOFOLLOW", "O_DIRECTORY", "O_CLOEXEC", "O_NONBLOCK")):
        raise Denied("secure descriptor-relative file access is unsupported")
    target = Path(path)
    parts = target.parts
    if not parts or any(part in (".", "..", "") for part in parts):
        raise Denied("unsafe " + label + " path components")
    directory_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY | os.O_CLOEXEC
    file_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
    directory_fd = None
    try:
        if target.is_absolute():
            directory_fd = os.open(os.path.sep, directory_flags)
            parts = parts[1:]
        else:
            directory_fd = os.open(".", directory_flags)
        if not parts:
            raise Denied("unsafe empty " + label + " path")
        for component in parts[:-1]:
            next_fd = os.open(component, directory_flags, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        fd = os.open(parts[-1], file_flags, dir_fd=directory_fd)
        with os.fdopen(fd, "rb") as stream:
            before = os.fstat(stream.fileno())
            if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                    or not 0 < before.st_size <= limit):
                raise Denied("invalid " + label + " input file")
            raw = stream.read(limit + 1)
            after = os.fstat(stream.fileno())
    except OSError as exc:
        raise Denied("unsafe " + label + " input path") from exc
    finally:
        if directory_fd is not None:
            os.close(directory_fd)
    if (len(raw) != before.st_size or after.st_size != before.st_size
            or after.st_mtime_ns != before.st_mtime_ns
            or after.st_ctime_ns != before.st_ctime_ns
            or raw.startswith(b"\xef\xbb\xbf") or b"\x00" in raw):
        raise Denied("invalid or changed " + label + " input bytes")
    return raw

def load_json(path):
    raw = bounded_bytes(path, MAX_JSON, "JSON")
    try:
        return json.loads(
            raw.decode("utf-8"), object_pairs_hook=unique_object,
            parse_constant=reject_nonstandard_constant, parse_float=finite_json_float)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid JSON: " + str(path)) from exc

def require_sha(value, label):
    if type(value) is not str or not SHA.fullmatch(value):
        raise Denied("invalid SHA: " + label)
    return value

def reviewer_policy(path):
    value = load_json(path)
    required = {"schema", "enabled", "minimumPermission", "disallowedReviewerIds", "reviewer"}
    if (type(value) is not dict or set(value) != required
            or type(value.get("schema")) is not int or value["schema"] != 2):
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
    if (type(user) is not dict or user.get("login") != reviewer_login
            or type(user.get("id")) is not int or user["id"] != reviewer_id
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
    if type(pr.get("number")) is not int or pr["number"] != number:
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
    if pr.get("merged") is not False:
        raise Denied("security PR merge state missing or not explicitly false")
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
    if stamp.utcoffset() != dt.timedelta(0):
        raise Denied("exact-head review submitted_at must be UTC")
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
        if (type(user) is dict
                and user.get("login") == reviewer_login
                and type(user.get("id")) is int and user["id"] == reviewer_id
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
    if stamp.utcoffset() != dt.timedelta(0):
        raise Denied("incident authorization created_at must be UTC")
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
        if (type(user) is not dict or user.get("login") != reviewer_login
                or type(user.get("id")) is not int or user["id"] != reviewer_id
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
    try:
        text = bounded_bytes(path, MAX_LEDGER, "approval ledger").decode("utf-8")
    except UnicodeError as exc:
        raise Denied("invalid approval ledger encoding") from exc
    # Do not silently normalize caller-controlled policy text. This must
    # exactly match the checked-in private-ci-policy workflow: blank lines and
    # column-zero comments are the only ignored lines.
    return [line for line in text.splitlines()
            if line and not line.startswith("#")]

def parse_destination_entry(destination_file):
    rows = active_rows(destination_file)
    if len(rows) != 1:
        raise Denied("destination approval must contain exactly one active entry")
    match = DESTINATION_ROW.fullmatch(rows[0])
    if match is None or match.group(1) != CARRIER_REPO:
        raise Denied("invalid destination approval entry")
    if match.group(2) != str(CARRIER_REPO_ID):
        raise Denied("destination approval repository ID mismatch")
    return CARRIER_REPO_ID

def parse_snapshot_entry(snapshot_file):
    rows = active_rows(snapshot_file)
    if len(rows) != 1:
        raise Denied("snapshot approval must contain exactly one active tuple")
    match = SNAPSHOT_ROW.fullmatch(rows[0])
    if match is None:
        raise Denied("invalid snapshot tuple")
    source_commit = require_sha(match.group(1), "source commit")
    source_tree = require_sha(match.group(2), "source tree")
    if match.group(3) != str(CARRIER_REPO_ID):
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
    if type(commit) is not dict:
        raise Denied("invalid source commit metadata")
    source_object = commit.get("commit")
    if type(source_object) is not dict or type(source_object.get("tree")) is not dict:
        raise Denied("invalid source commit tree metadata")
    if require_sha(commit.get("sha"), "source commit metadata") != source_commit:
        raise Denied("source commit metadata mismatch")
    tree = require_sha(source_object["tree"].get("sha"), "source tree metadata")
    if tree != source_tree:
        raise Denied("approved source commit/tree mismatch")

    destination = load_json(args.destination_repo)
    if type(destination) is not dict:
        raise Denied("invalid carrier repository metadata")
    if type(destination.get("id")) is not int or destination["id"] != CARRIER_REPO_ID:
        raise Denied("carrier repository ID mismatch")
    if destination.get("full_name") != CARRIER_REPO:
        raise Denied("carrier repository name mismatch")
    if (destination.get("private") is not True
            or destination.get("archived") is not False
            or destination.get("disabled") is not False):
        raise Denied("carrier repository is not an active private repository")

    workflow_raw = bounded_bytes(args.carrier_workflow, MAX_CARRIER_WORKFLOW, "carrier workflow")
    workflow_blob = hashlib.sha1(
        b"blob " + str(len(workflow_raw)).encode("ascii") + b"\0" + workflow_raw
    ).hexdigest()

    reviewer_policy_raw = bounded_bytes(args.reviewer_policy, MAX_REVIEWER_POLICY, "reviewer policy")
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
