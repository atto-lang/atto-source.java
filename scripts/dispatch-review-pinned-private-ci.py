#!/usr/bin/env python3
"""Dispatch one review-pinned private-CI source snapshot.

This is the write-side bridge for the review-pinned admission model.  It never
uses protected-main state as authority.  A supplied schema-v2 receipt is treated
as an untrusted cache: mutable GitHub authorization state is fetched again and
the canonical receipt is recomputed before every Git-object write.

The script must itself run from the exact independently reviewed publisher PR
head recorded in the receipt.  Source bytes come from a separate clean checkout
of the approved immutable source commit/tree.
"""
import argparse
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PUBLISHER_PATH = ROOT / "scripts/publish-private-ci-source.py"
VERIFIER_PATH = ROOT / "scripts/verify-review-pinned-private-ci.py"
MAX_JSON = 2 * 1024 * 1024
MAX_CONTENT = 512 * 1024
PROVENANCE_PATH = ".private-ci-provenance.json"


def load_peer(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load required private-CI peer module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PUB = load_peer("private_ci_publisher", PUBLISHER_PATH)
VERIFY = load_peer("review_pinned_verifier", VERIFIER_PATH)
Denied = PUB.Denied


def strict_json(raw, expected_type):
    if not isinstance(raw, str):
        raise Denied("invalid GitHub JSON response")
    encoded = raw.encode("utf-8")
    if not encoded or len(encoded) > MAX_JSON or encoded.startswith(b"\xef\xbb\xbf"):
        raise Denied("invalid GitHub JSON response bytes")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise Denied("duplicate GitHub JSON key")
            result[key] = value
        return result

    def reject_constant(_value):
        raise Denied("nonstandard GitHub JSON number")

    def parse_int(value):
        if len(value) > 64:
            raise Denied("oversized GitHub JSON integer")
        return int(value)

    def parse_float(value):
        if len(value) > 128:
            raise Denied("oversized GitHub JSON float")
        number = float(value)
        if not (-float("inf") < number < float("inf")):
            raise Denied("nonfinite GitHub JSON number")
        return number

    try:
        value = json.loads(
            raw,
            object_pairs_hook=unique,
            parse_constant=reject_constant,
            parse_int=parse_int,
            parse_float=parse_float)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid GitHub JSON response") from exc
    if type(value) is not expected_type:
        raise Denied("unexpected GitHub JSON response shape")
    return value


def gh_value(route, expected_type):
    argv = [
        "gh", "api", "--method", "GET", route,
        "--header", "Accept: application/vnd.github+json",
    ]
    try:
        run = subprocess.run(
            argv, capture_output=True, text=True,
            env=PUB.verified_child_env(), timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("authenticated GitHub API request unavailable") from exc
    if run.returncode != 0:
        raise Denied("authenticated GitHub API request failed")
    return strict_json(run.stdout, expected_type)


def bounded_list(route, label):
    separator = "&" if "?" in route else "?"
    page1 = gh_value(route + separator + "per_page=100&page=1", list)
    page2 = gh_value(route + separator + "per_page=100&page=2", list)
    if page2:
        raise Denied(label + " exceeds bounded first page")
    return page1


def git_blob_sha(raw):
    return hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
    ).hexdigest()


def exact_file(repo, path, ref, limit=MAX_CONTENT):
    if not VERIFY.SHA.fullmatch(ref):
        raise Denied("invalid immutable content ref")
    meta = PUB.gh_api(f"repos/{repo}/contents/{path}?ref={ref}")
    if (meta.get("type") != "file" or meta.get("encoding") != "base64"
            or type(meta.get("size")) is not int
            or not 0 < meta["size"] <= limit):
        raise Denied("immutable reviewed file is absent or malformed")
    encoded = meta.get("content")
    if not isinstance(encoded, str) or len(encoded) > 2 * limit:
        raise Denied("immutable reviewed file encoding is invalid")
    try:
        raw = base64.b64decode("".join(encoded.splitlines()), validate=True)
    except ValueError as exc:
        raise Denied("immutable reviewed file is not valid base64") from exc
    if len(raw) != meta["size"] or len(raw) > limit:
        raise Denied("immutable reviewed file size mismatch")
    blob = git_blob_sha(raw)
    if PUB.valid_sha(meta.get("sha")) != blob:
        raise Denied("immutable reviewed file blob differs from returned bytes")
    return raw, blob



def require_incident_closed():
    issue = PUB.gh_api(PUB.SOURCE_INCIDENT_ROUTE)
    if (issue.get("id") != PUB.SOURCE_INCIDENT_ISSUE_ID
            or issue.get("number") != PUB.SOURCE_INCIDENT_NUMBER
            or issue.get("state") != "closed"
            or issue.get("state_reason") != "completed"):
        raise Denied("private-source exposure incident is not independently closed")
    closed_at = issue.get("closed_at")
    if not isinstance(closed_at, str):
        raise Denied("private-source exposure incident lacks valid closure time")
    try:
        stamp = dt.datetime.fromisoformat(closed_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise Denied("private-source exposure incident lacks valid closure time") from exc
    if (stamp.tzinfo is None or stamp.utcoffset() is None
            or stamp.utcoffset() != dt.timedelta(0)):
        raise Denied("private-source exposure incident closure time is not UTC")
    return closed_at

def write_temp(root, name, raw):
    path = root / name
    path.write_bytes(raw)
    return str(path)


def canonical_receipt_from_live():
    # PR metadata and review history.
    heads = {}
    review_ids = {}

    publisher_pr = PUB.gh_api(
        f"repos/{VERIFY.SOURCE_REPO}/pulls/{VERIFY.EXPECTED_PRS['publisher'][1]}")
    publisher_head, _ = VERIFY.pr_head(
        publisher_pr, VERIFY.SOURCE_REPO, VERIFY.EXPECTED_PRS["publisher"][1])

    policy_raw, policy_blob = exact_file(
        VERIFY.SOURCE_REPO,
        ".github/private-ci-independent-reviewer.json",
        publisher_head,
        limit=64 * 1024)

    with tempfile.TemporaryDirectory() as td:
        temp = Path(td)
        policy_path = write_temp(temp, "reviewer-policy.json", policy_raw)
        reviewer = VERIFY.reviewer_policy(policy_path)

        source_permission = PUB.gh_api(
            f"repos/{VERIFY.SOURCE_REPO}/collaborators/{reviewer[0]}/permission")
        carrier_permission = PUB.gh_api(
            f"repos/{VERIFY.CARRIER_REPO}/collaborators/{reviewer[0]}/permission")
        source_permission_value = VERIFY.live_permission(
            source_permission, reviewer, reviewer[2], "source")
        carrier_permission_value = VERIFY.live_permission(
            carrier_permission, reviewer, reviewer[2], "carrier")

        for key, (repo, number) in VERIFY.EXPECTED_PRS.items():
            pr = (publisher_pr if key == "publisher"
                  else PUB.gh_api(f"repos/{repo}/pulls/{number}"))
            reviews = bounded_list(
                f"repos/{repo}/pulls/{number}/reviews",
                key + " review history")
            head, author_id = VERIFY.pr_head(pr, repo, number)
            heads[key] = head
            review_ids[key] = VERIFY.exact_approval(
                reviews, head, reviewer, author_id)

        # The same reviewer policy bytes must be present in the exact reviewed
        # carrier parent.  This prevents the source receipt and carrier runtime
        # from interpreting different identities or minimum permissions.
        carrier_policy_raw, carrier_policy_blob = exact_file(
            VERIFY.CARRIER_REPO,
            ".github/private-ci-independent-reviewer.json",
            heads["carrier"],
            limit=64 * 1024)
        if carrier_policy_blob != policy_blob or carrier_policy_raw != policy_raw:
            raise Denied("source/carrier independent reviewer policy differs")

        destination_raw, _ = exact_file(
            VERIFY.SOURCE_REPO,
            ".github/private-ci-approved-repositories.txt",
            heads["destination"],
            limit=16 * 1024)
        snapshot_raw, _ = exact_file(
            VERIFY.SOURCE_REPO,
            ".github/private-ci-approved-source-snapshots.txt",
            heads["snapshot"],
            limit=16 * 1024)
        destination_path = write_temp(temp, "destination-ledger.txt", destination_raw)
        snapshot_path = write_temp(temp, "snapshot-ledger.txt", snapshot_raw)
        VERIFY.parse_destination_entry(destination_path)
        source_commit, source_tree = VERIFY.parse_snapshot_entry(snapshot_path)

        require_incident_closed()
        incident = bounded_list(
            "repos/ores-truffle-oreslang/oreslang-platform-conformance/issues/22/comments",
            "incident authorization history")
        resume_id = VERIFY.exact_resume_comment(
            incident, heads, source_commit, source_tree, reviewer)

        commit = PUB.gh_api(
            f"repos/{VERIFY.SOURCE_REPO}/commits/{source_commit}")
        if VERIFY.require_sha(commit.get("sha"), "source commit metadata") != source_commit:
            raise Denied("source commit metadata mismatch")
        tree = VERIFY.require_sha(
            (commit.get("commit") or {}).get("tree", {}).get("sha"),
            "source tree metadata")
        if tree != source_tree:
            raise Denied("approved source commit/tree mismatch")

        destination = PUB.checked_repo(
            VERIFY.CARRIER_REPO, expected_id=VERIFY.CARRIER_REPO_ID)

        workflow_raw, workflow_blob = exact_file(
            VERIFY.CARRIER_REPO,
            ".github/workflows/private-oreslang-java-validation.yml",
            heads["carrier"],
            limit=256 * 1024)


        # Re-read every PR after all authorization inputs have been gathered.
        # A concurrent head move invalidates this whole recomputation rather
        # than producing a receipt assembled from different moments in time.
        for key, (repo, number) in VERIFY.EXPECTED_PRS.items():
            latest = PUB.gh_api(f"repos/{repo}/pulls/{number}")
            latest_head, _ = VERIFY.pr_head(latest, repo, number)
            if latest_head != heads[key]:
                raise Denied("authorization PR head changed during receipt recomputation")

        return {
            "schema": 2,
            "source_repository": VERIFY.SOURCE_REPO,
            "source_repository_id": VERIFY.SOURCE_REPO_ID,
            "source_commit_sha": source_commit,
            "source_tree_sha": source_tree,
            "carrier_repository": VERIFY.CARRIER_REPO,
            "carrier_repository_id": VERIFY.CARRIER_REPO_ID,
            "carrier_base_sha": heads["carrier"],
            "carrier_workflow_blob_sha": workflow_blob,
            "independent_reviewer_login": reviewer[0],
            "independent_reviewer_id": reviewer[1],
            "independent_reviewer_policy_blob_sha": policy_blob,
            "independent_reviewer_source_permission": source_permission_value,
            "independent_reviewer_carrier_permission": carrier_permission_value,
            "publisher_policy_pr": 1123,
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
        }


def load_receipt(path):
    raw = Path(path).read_bytes()
    if not raw or len(raw) > 64 * 1024 or raw.startswith(b"\xef\xbb\xbf"):
        raise Denied("invalid review-pinned receipt bytes")
    try:
        receipt = strict_json(raw.decode("utf-8"), dict)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid review-pinned receipt") from exc
    # A live recomputation below is the canonical schema check.  Reject obvious
    # version confusion before any GitHub write or source traversal.
    if receipt.get("schema") != 2:
        raise Denied("unsupported review-pinned receipt schema")
    return receipt


def git_output(root, *args):
    try:
        run = subprocess.run(
            ["git", "-C", str(root), *args],
            capture_output=True, text=True, timeout=30,
            env=PUB.verified_child_env(), check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("local Git verification unavailable") from exc
    if run.returncode != 0:
        raise Denied("local Git verification failed")
    return run.stdout.strip()


def require_clean_checkout(root, expected_commit, expected_tree=None):
    root = Path(root).resolve(strict=True)
    if git_output(root, "rev-parse", "--verify", "HEAD") != expected_commit:
        raise Denied("checkout HEAD differs from reviewed immutable commit")
    if expected_tree is not None:
        if git_output(root, "rev-parse", "--verify", "HEAD^{tree}") != expected_tree:
            raise Denied("checkout root tree differs from reviewed immutable tree")
    if git_output(root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise Denied("checkout contains uncommitted or untracked files")
    return root


def matching_branch_refs(repository, branch):
    refs = bounded_list(
        f"repos/{repository}/git/matching-refs/heads/{branch}",
        "destination branch match set")
    expected = "refs/heads/" + branch
    for ref in refs:
        if not isinstance(ref, dict) or not isinstance(ref.get("ref"), str):
            raise Denied("malformed destination matching-ref response")
        if ref["ref"] == expected:
            return [ref]
    return []


def require_branch_absent(repository, branch):
    if matching_branch_refs(repository, branch):
        raise Denied("immutable private-CI branch already exists")


def canonical_provenance(receipt):
    return (json.dumps(receipt, sort_keys=True, separators=(",", ":"))
            + "\n").encode("utf-8")



def require_dispatcher_api_budget(file_count, estimated_writes):
    if type(file_count) is not int or not 1 <= file_count <= PUB.MAX_FILES:
        raise Denied("private CI snapshot file count invalid")
    if (type(estimated_writes) is not int or estimated_writes < 1
            or estimated_writes > 2 * PUB.MAX_FILES):
        raise Denied("review-pinned dispatcher write count invalid")
    budget = PUB.gh_api("rate_limit")
    resources = budget.get("resources") if isinstance(budget, dict) else None
    core = resources.get("core") if isinstance(resources, dict) else None
    remaining = core.get("remaining") if isinstance(core, dict) else None
    if type(remaining) is not int or remaining < 0:
        raise Denied("GitHub core API budget unavailable")
    # authorize_write() deliberately replays all mutable authority, then
    # rechecks destination privacy, branch absence, carrier parent/workflow
    # and reviewer-policy bytes. Reserve above the current call count rather
    # than inheriting the legacy protected-main publisher's smaller multiplier.
    required = 40 * estimated_writes + 240
    if remaining < required:
        raise Denied("insufficient GitHub core API budget for review-pinned export")

def compare_live_receipt(receipt):
    live = canonical_receipt_from_live()
    if live != receipt:
        raise Denied("live review-pinned authorization differs from supplied receipt")
    return live


def verify_carrier_parent(receipt):
    commit = PUB.gh_api(
        f"repos/{receipt['carrier_repository']}/git/commits/{receipt['carrier_base_sha']}")
    if commit.get("sha") != receipt["carrier_base_sha"]:
        raise Denied("reviewed carrier parent commit identity mismatch")
    tree = PUB.nested_sha(commit, "tree")

    _, workflow_blob = exact_file(
        receipt["carrier_repository"],
        ".github/workflows/private-oreslang-java-validation.yml",
        receipt["carrier_base_sha"],
        limit=256 * 1024)
    if workflow_blob != receipt["carrier_workflow_blob_sha"]:
        raise Denied("reviewed carrier workflow blob changed")

    _, policy_blob = exact_file(
        receipt["carrier_repository"],
        ".github/private-ci-independent-reviewer.json",
        receipt["carrier_base_sha"],
        limit=64 * 1024)
    if policy_blob != receipt["independent_reviewer_policy_blob_sha"]:
        raise Denied("reviewed carrier policy blob changed")
    return tree


def publish_review_pinned(receipt, source_checkout, branch):
    repository = receipt.get("carrier_repository")
    source_commit = receipt.get("source_commit_sha")
    source_tree = receipt.get("source_tree_sha")
    carrier_base = receipt.get("carrier_base_sha")

    if (repository != VERIFY.CARRIER_REPO
            or receipt.get("carrier_repository_id") != VERIFY.CARRIER_REPO_ID):
        raise Denied("receipt destination identity mismatch")
    for value in (source_commit, source_tree, carrier_base):
        PUB.valid_sha(value)
    if branch != "ci/oreslang-" + source_tree or not PUB.BRANCH.fullmatch(branch):
        raise Denied("immutable private-CI branch must equal approved source tree")
    if receipt.get("publisher_policy_head_sha") != git_output(ROOT, "rev-parse", "--verify", "HEAD"):
        raise Denied("dispatcher checkout is not the reviewed publisher head")
    if git_output(ROOT, "status", "--porcelain=v1", "--untracked-files=all"):
        raise Denied("dispatcher checkout is not clean")

    source_checkout = require_clean_checkout(
        source_checkout, source_commit, source_tree)

    # Fail closed before reading private source bytes.
    compare_live_receipt(receipt)
    PUB.checked_repo(repository, expected_id=VERIFY.CARRIER_REPO_ID)
    require_branch_absent(repository, branch)
    base_tree = verify_carrier_parent(receipt)

    files = PUB.collect_source(source_checkout, source_checkout)
    if PUB.source_tree_sha(files) != source_tree:
        raise Denied("local private source snapshot differs from approved tree")

    batches = PUB.prepare_source_tree_batches(files)
    binary_writes = sum(
        text is None for batch in batches for _, _, _, text in batch)
    # binary blobs + source-tree batches + provenance blob + root tree +
    # commit + ref.  Every one gets a fresh full authorization replay.
    estimated_writes = binary_writes + len(batches) + 4
    require_dispatcher_api_budget(len(files), estimated_writes)

    def authorize_write():
        compare_live_receipt(receipt)
        PUB.checked_repo(repository, expected_id=VERIFY.CARRIER_REPO_ID)
        require_branch_absent(repository, branch)
        if verify_carrier_parent(receipt) != base_tree:
            raise Denied("reviewed carrier parent tree changed")

    isolated_sha = None
    for batch in batches:
        elements = []
        for rel, data, mode, as_text in batch:
            entry = {"path": rel, "mode": mode, "type": "blob"}
            if as_text is not None:
                entry["content"] = as_text
            else:
                authorize_write()
                blob = PUB.gh_api(
                    f"repos/{repository}/git/blobs", method="POST",
                    payload={
                        "content": base64.b64encode(data).decode("ascii"),
                        "encoding": "base64",
                    })
                blob_sha = PUB.valid_sha(blob.get("sha"))
                if blob_sha != git_blob_sha(data):
                    raise Denied("uploaded source blob digest mismatch")
                entry["sha"] = blob_sha
            elements.append(entry)

        authorize_write()
        payload = {"tree": elements}
        if isolated_sha is not None:
            payload["base_tree"] = isolated_sha
        tree = PUB.gh_api(
            f"repos/{repository}/git/trees", method="POST", payload=payload)
        isolated_sha = PUB.valid_sha(tree.get("sha"))

    if isolated_sha != source_tree:
        raise Denied("uploaded private source subtree differs from approved tree")

    provenance = canonical_provenance(receipt)
    authorize_write()
    provenance_blob = PUB.gh_api(
        f"repos/{repository}/git/blobs", method="POST",
        payload={
            "content": base64.b64encode(provenance).decode("ascii"),
            "encoding": "base64",
        })
    provenance_sha = PUB.valid_sha(provenance_blob.get("sha"))
    if provenance_sha != git_blob_sha(provenance):
        raise Denied("uploaded provenance blob digest mismatch")

    authorize_write()
    root_tree = PUB.gh_api(
        f"repos/{repository}/git/trees", method="POST",
        payload={
            "base_tree": base_tree,
            "tree": [
                {
                    "path": PUB.PREFIX,
                    "mode": "040000",
                    "type": "tree",
                    "sha": source_tree,
                },
                {
                    "path": PROVENANCE_PATH,
                    "mode": "100644",
                    "type": "blob",
                    "sha": provenance_sha,
                },
            ],
        })
    root_tree_sha = PUB.valid_sha(root_tree.get("sha"))

    authorize_write()
    created_commit = PUB.gh_api(
        f"repos/{repository}/git/commits", method="POST",
        payload={
            "message": "ci: review-pinned private Oreslang source snapshot",
            "tree": root_tree_sha,
            "parents": [carrier_base],
        })
    commit_sha = PUB.valid_sha(created_commit.get("sha"))
    if PUB.nested_sha(created_commit, "tree") != root_tree_sha:
        raise Denied("created payload commit tree mismatch")
    parents = created_commit.get("parents")
    if (not isinstance(parents, list) or len(parents) != 1
            or not isinstance(parents[0], dict)
            or parents[0].get("sha") != carrier_base):
        raise Denied("created payload commit does not have exact sole carrier parent")

    authorize_write()
    created_ref = PUB.gh_api(
        f"repos/{repository}/git/refs", method="POST",
        payload={"ref": "refs/heads/" + branch, "sha": commit_sha})
    if (created_ref.get("ref") != "refs/heads/" + branch
            or not isinstance(created_ref.get("object"), dict)
            or created_ref["object"].get("type") != "commit"
            or PUB.nested_sha(created_ref, "object") != commit_sha):
        raise Denied("created immutable private-CI ref mismatch")

    # Read back the immutable ref, one-parent commit, root tree, source subtree,
    # provenance blob, and final live authorization.  There is intentionally no
    # workflow_dispatch: the exact reviewed carrier workflow is push-only.
    ref = PUB.gh_api(f"repos/{repository}/git/ref/heads/{branch}")
    if (ref.get("ref") != "refs/heads/" + branch
            or not isinstance(ref.get("object"), dict)
            or ref["object"].get("type") != "commit"
            or PUB.nested_sha(ref, "object") != commit_sha):
        raise Denied("immutable private-CI ref readback mismatch")

    commit = PUB.gh_api(f"repos/{repository}/git/commits/{commit_sha}")
    if commit.get("sha") != commit_sha or PUB.nested_sha(commit, "tree") != root_tree_sha:
        raise Denied("payload commit readback mismatch")
    parents = commit.get("parents")
    if (not isinstance(parents, list) or len(parents) != 1
            or not isinstance(parents[0], dict)
            or parents[0].get("sha") != carrier_base):
        raise Denied("payload commit readback parent mismatch")

    tree = PUB.gh_api(f"repos/{repository}/git/trees/{root_tree_sha}")
    items = tree.get("tree")
    if (tree.get("sha") != root_tree_sha or tree.get("truncated") is not False
            or not isinstance(items, list)):
        raise Denied("payload root tree readback malformed")
    source_matches = [
        item for item in items if isinstance(item, dict)
        and item.get("path") == PUB.PREFIX
        and item.get("type") == "tree"
        and item.get("sha") == source_tree
    ]
    provenance_matches = [
        item for item in items if isinstance(item, dict)
        and item.get("path") == PROVENANCE_PATH
        and item.get("type") == "blob"
        and item.get("sha") == provenance_sha
    ]
    if len(source_matches) != 1 or len(provenance_matches) != 1:
        raise Denied("payload root tree does not contain exact approved entries")

    blob = PUB.gh_api(f"repos/{repository}/git/blobs/{provenance_sha}")
    encoded = blob.get("content")
    if (blob.get("encoding") != "base64" or blob.get("sha") != provenance_sha
            or not isinstance(encoded, str) or len(encoded) > 128 * 1024):
        raise Denied("provenance blob readback malformed")
    try:
        readback = base64.b64decode(
            "".join(encoded.splitlines()), validate=True)
    except ValueError as exc:
        raise Denied("provenance blob readback is not valid base64") from exc
    if readback != provenance:
        raise Denied("provenance blob readback differs from canonical receipt")

    if canonical_receipt_from_live() != receipt:
        raise Denied("authorization changed immediately after payload publication")
    PUB.checked_repo(repository, expected_id=VERIFY.CARRIER_REPO_ID)
    return commit_sha, len(files)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--source-checkout", required=True, type=Path)
    parser.add_argument("--branch", required=True)
    args = parser.parse_args(argv)

    try:
        receipt = load_receipt(args.receipt)
        commit, count = publish_review_pinned(
            receipt, args.source_checkout, args.branch)
    except (OSError, Denied) as exc:
        print("PRIVATE_CI_REVIEW_PINNED_DISPATCH_DENIED: " + str(exc),
              file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_REVIEW_PINNED_PAYLOAD_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
