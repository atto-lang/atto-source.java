#!/usr/bin/env python3
"""Review-pinned private-CI source dispatcher.

Consumes one canonical schema-v2 authorization receipt, rebuilds that receipt
from fresh GitHub state before source reads and before every destination write,
and creates one immutable source-bearing commit in the reviewed private carrier.

The older protected-main publisher remains a separate entry point. This script
reuses only its bounded source traversal, Git-tree construction, strict GitHub
API transport and destination-identity helpers.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parent
PUBLISHER_PATH = SCRIPT_DIR / "publish-private-ci-source.py"
VERIFIER_PATH = SCRIPT_DIR / "verify-review-pinned-private-ci.py"

_spec = importlib.util.spec_from_file_location("private_ci_publisher", PUBLISHER_PATH)
if _spec is None or _spec.loader is None:
    raise SystemExit("review-pinned dispatcher cannot load bounded publisher helpers")
pub = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pub)

SOURCE_REPO = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPO_ID = 1400792274
CARRIER_REPO = "sagitta-stack/sagitta-infra"
CARRIER_REPO_ID = 1357615104
INCIDENT_REPO = "ores-truffle-oreslang/oreslang-platform-conformance"
INCIDENT_NUMBER = 22
PREFIX = "private-oreslang-source"
PROVENANCE_PATH = ".private-ci-provenance.json"
WORKFLOW_PATH = ".github/workflows/private-oreslang-java-validation.yml"
REVIEWER_POLICY_PATH = ".github/private-ci-independent-reviewer.json"
DESTINATION_LEDGER_PATH = ".github/private-ci-approved-repositories.txt"
SNAPSHOT_LEDGER_PATH = ".github/private-ci-approved-source-snapshots.txt"
SHA = re.compile(r"^[0-9a-f]{40}$")
LOGIN = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
MAX_JSON = 2 * 1024 * 1024
MAX_FILE = 256 * 1024
LOCAL_REVIEWED_FILES = (
    "scripts/dispatch-review-pinned-private-ci.py",
    "scripts/verify-review-pinned-private-ci.py",
    "scripts/publish-private-ci-source.py",
)

RECEIPT_KEYS = {
    "schema", "source_repository", "source_repository_id",
    "source_commit_sha", "source_tree_sha",
    "carrier_repository", "carrier_repository_id",
    "carrier_base_sha", "carrier_workflow_blob_sha",
    "independent_reviewer_login", "independent_reviewer_id",
    "independent_reviewer_policy_blob_sha",
    "independent_reviewer_source_permission",
    "independent_reviewer_carrier_permission",
    "publisher_policy_pr", "publisher_policy_head_sha", "publisher_review_id",
    "destination_approval_pr", "destination_approval_head_sha",
    "destination_review_id", "snapshot_approval_pr",
    "snapshot_approval_head_sha", "snapshot_review_id",
    "carrier_policy_pr", "carrier_policy_head_sha", "carrier_review_id",
    "incident_resume_comment_id",
}
EXPECTED_PRS = {
    "publisher": (SOURCE_REPO, "publisher_policy_pr", "publisher_policy_head_sha"),
    "destination": (SOURCE_REPO, "destination_approval_pr", "destination_approval_head_sha"),
    "snapshot": (SOURCE_REPO, "snapshot_approval_pr", "snapshot_approval_head_sha"),
    "carrier": (CARRIER_REPO, "carrier_policy_pr", "carrier_policy_head_sha"),
}


class Denied(RuntimeError):
    pass


def unique_object(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise Denied("duplicate JSON key: " + key)
        out[key] = value
    return out


def parse_json_bytes(raw, label, require_object=True):
    if not isinstance(raw, (bytes, bytearray)) or not raw or len(raw) > MAX_JSON:
        raise Denied("invalid JSON bytes: " + label)
    raw = bytes(raw)
    if raw.startswith(b"\xef\xbb\xbf"):
        raise Denied("BOM is not allowed: " + label)
    def reject_constant(value):
        raise Denied("nonstandard JSON number: " + label)

    try:
        value = json.loads(
            raw.decode("utf-8"), object_pairs_hook=unique_object,
            parse_constant=reject_constant)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid JSON: " + label) from exc
    if require_object and type(value) is not dict:
        raise Denied("expected JSON object: " + label)
    return value


def require_sha(value, label):
    if type(value) is not str or not SHA.fullmatch(value):
        raise Denied("invalid SHA: " + label)
    return value


def validate_receipt(doc):
    if type(doc) is not dict or set(doc) != RECEIPT_KEYS:
        raise Denied("unexpected authorization receipt schema")
    if doc.get("schema") != 2:
        raise Denied("unsupported authorization receipt schema")
    if (doc.get("source_repository") != SOURCE_REPO
            or doc.get("source_repository_id") != SOURCE_REPO_ID):
        raise Denied("unexpected source repository receipt")
    if (doc.get("carrier_repository") != CARRIER_REPO
            or doc.get("carrier_repository_id") != CARRIER_REPO_ID):
        raise Denied("unexpected carrier repository receipt")
    for key in (
        "source_commit_sha", "source_tree_sha", "carrier_base_sha",
        "carrier_workflow_blob_sha", "independent_reviewer_policy_blob_sha",
        "publisher_policy_head_sha", "destination_approval_head_sha",
        "snapshot_approval_head_sha", "carrier_policy_head_sha",
    ):
        require_sha(doc.get(key), key)
    if doc["carrier_base_sha"] != doc["carrier_policy_head_sha"]:
        raise Denied("carrier parent and reviewed carrier head differ")
    for _, (_, pr_key, _) in EXPECTED_PRS.items():
        if type(doc.get(pr_key)) is not int or doc[pr_key] <= 0:
            raise Denied("invalid security PR number: " + pr_key)
    for key in (
        "publisher_review_id", "destination_review_id", "snapshot_review_id",
        "carrier_review_id", "incident_resume_comment_id",
        "independent_reviewer_id",
    ):
        if type(doc.get(key)) is not int or doc[key] <= 0:
            raise Denied("invalid positive integer field: " + key)
    if type(doc.get("independent_reviewer_login")) is not str or not LOGIN.fullmatch(
            doc["independent_reviewer_login"]):
        raise Denied("invalid independent reviewer login")
    return doc


def canonical_receipt_bytes(doc):
    validate_receipt(doc)
    return json.dumps(doc, sort_keys=True, separators=(",", ":")).encode("utf-8")


def load_receipt(path):
    raw = Path(path).read_bytes()
    doc = parse_json_bytes(raw, "authorization receipt")
    canonical = canonical_receipt_bytes(doc)
    if raw not in (canonical, canonical + b"\n"):
        raise Denied("authorization receipt is not canonical")
    return doc, canonical


def git_blob_sha(raw):
    return hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
    ).hexdigest()


def api_list(route):
    argv = ["gh", "api", "--method", "GET", route,
            "--header", "Accept: application/vnd.github+json"]
    try:
        p = subprocess.run(
            argv, capture_output=True, text=True, env=pub.verified_child_env(),
            timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("authenticated GitHub list request unavailable") from exc
    if p.returncode != 0:
        raise Denied("authenticated GitHub list request failed")
    try:
        raw = p.stdout.encode("utf-8")
    except UnicodeError as exc:
        raise Denied("invalid GitHub list response encoding") from exc
    value = parse_json_bytes(raw, "GitHub list response", require_object=False)
    if type(value) is not list:
        raise Denied("expected GitHub list response")
    return value


def content_bytes(repo, path, ref):
    require_sha(ref, "reviewed file ref")
    doc = pub.gh_api(f"repos/{repo}/contents/{path}?ref={ref}")
    if (doc.get("type") != "file" or doc.get("encoding") != "base64"
            or type(doc.get("content")) is not str
            or type(doc.get("size")) is not int
            or doc["size"] < 1 or doc["size"] > MAX_FILE):
        raise Denied("reviewed file metadata is invalid: " + path)
    encoded = doc["content"]
    if any(ch not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\r\n"
           for ch in encoded):
        raise Denied("reviewed file content is not strict base64: " + path)
    compact = encoded.replace("\r", "").replace("\n", "")
    try:
        raw = base64.b64decode(compact, validate=True)
    except (ValueError, TypeError) as exc:
        raise Denied("reviewed file content is not strict base64: " + path) from exc
    if len(raw) != doc["size"]:
        raise Denied("reviewed file size mismatch: " + path)
    sha = require_sha(doc.get("sha"), "reviewed file blob")
    if git_blob_sha(raw) != sha:
        raise Denied("reviewed file blob digest mismatch: " + path)
    return raw, sha


def write_temp(path, raw):
    p = Path(path)
    p.write_bytes(raw)
    os.chmod(p, 0o600)
    return str(p)


def review_page(repo, number, page):
    reviews = api_list(
        f"repos/{repo}/pulls/{number}/reviews?per_page=100&page={page}")
    if page == 2 and reviews:
        raise Denied("security PR review history exceeds bounded first page")
    return reviews


def fresh_receipt(expected):
    """Rebuild the canonical verifier receipt from fresh GitHub state."""
    validate_receipt(expected)
    with tempfile.TemporaryDirectory(prefix="ores-review-pinned-") as td:
        root = Path(td)
        files = {}
        for key, (repo, pr_key, head_key) in EXPECTED_PRS.items():
            number = expected[pr_key]
            pr = pub.gh_api(f"repos/{repo}/pulls/{number}")
            if ((pr.get("head") or {}).get("sha")) != expected[head_key]:
                raise Denied("security PR head changed: " + key)
            reviews = review_page(repo, number, 1)
            review_page(repo, number, 2)
            files[key + "_pr"] = write_temp(
                root / (key + "-pr.json"),
                json.dumps(pr, separators=(",", ":")).encode("utf-8"))
            files[key + "_reviews"] = write_temp(
                root / (key + "-reviews.json"),
                json.dumps(reviews, separators=(",", ":")).encode("utf-8"))

        policy_raw, policy_sha = content_bytes(
            SOURCE_REPO, REVIEWER_POLICY_PATH, expected["publisher_policy_head_sha"])
        carrier_policy_raw, carrier_policy_sha = content_bytes(
            CARRIER_REPO, REVIEWER_POLICY_PATH, expected["carrier_policy_head_sha"])
        if policy_raw != carrier_policy_raw or policy_sha != carrier_policy_sha:
            raise Denied("source/carrier reviewer policy bytes differ")
        if policy_sha != expected["independent_reviewer_policy_blob_sha"]:
            raise Denied("reviewer policy blob changed")
        policy = parse_json_bytes(policy_raw, "reviewer policy")
        reviewer = policy.get("reviewer")
        if (type(reviewer) is not dict
                or reviewer.get("login") != expected["independent_reviewer_login"]
                or reviewer.get("id") != expected["independent_reviewer_id"]):
            raise Denied("reviewer policy identity changed")
        reviewer_login = reviewer["login"]
        files["reviewer_policy"] = write_temp(root / "reviewer-policy.json", policy_raw)

        src_perm = pub.gh_api(
            f"repos/{SOURCE_REPO}/collaborators/{reviewer_login}/permission")
        carrier_perm = pub.gh_api(
            f"repos/{CARRIER_REPO}/collaborators/{reviewer_login}/permission")
        files["source_reviewer_permission"] = write_temp(
            root / "source-permission.json",
            json.dumps(src_perm, separators=(",", ":")).encode("utf-8"))
        files["carrier_reviewer_permission"] = write_temp(
            root / "carrier-permission.json",
            json.dumps(carrier_perm, separators=(",", ":")).encode("utf-8"))

        incident = api_list(
            f"repos/{INCIDENT_REPO}/issues/{INCIDENT_NUMBER}/comments?per_page=100&page=1")
        if api_list(
                f"repos/{INCIDENT_REPO}/issues/{INCIDENT_NUMBER}/comments?per_page=100&page=2"):
            raise Denied("incident authorization history exceeds bounded first page")
        files["incident_comments"] = write_temp(
            root / "incident-comments.json",
            json.dumps(incident, separators=(",", ":")).encode("utf-8"))

        source_commit = pub.gh_api(
            f"repos/{SOURCE_REPO}/commits/{expected['source_commit_sha']}")
        files["source_commit"] = write_temp(
            root / "source-commit.json",
            json.dumps(source_commit, separators=(",", ":")).encode("utf-8"))

        destination = pub.gh_api(f"repos/{CARRIER_REPO}")
        files["destination_repo"] = write_temp(
            root / "destination.json",
            json.dumps(destination, separators=(",", ":")).encode("utf-8"))

        destination_raw, _ = content_bytes(
            SOURCE_REPO, DESTINATION_LEDGER_PATH,
            expected["destination_approval_head_sha"])
        snapshot_raw, _ = content_bytes(
            SOURCE_REPO, SNAPSHOT_LEDGER_PATH,
            expected["snapshot_approval_head_sha"])
        workflow_raw, workflow_sha = content_bytes(
            CARRIER_REPO, WORKFLOW_PATH, expected["carrier_policy_head_sha"])
        if workflow_sha != expected["carrier_workflow_blob_sha"]:
            raise Denied("carrier workflow blob changed")
        files["destination_ledger"] = write_temp(
            root / "destination-ledger.txt", destination_raw)
        files["snapshot_ledger"] = write_temp(
            root / "snapshot-ledger.txt", snapshot_raw)
        files["carrier_workflow"] = write_temp(
            root / "carrier-workflow.yml", workflow_raw)

        argv = [sys.executable, "-I", str(VERIFIER_PATH)]
        for key, value in files.items():
            argv += ["--" + key.replace("_", "-"), value]
        try:
            p = subprocess.run(
                argv, capture_output=True, text=True, env=pub.verified_child_env(),
                timeout=45, check=False)
        except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
            raise Denied("review-pinned verifier unavailable") from exc
        if p.returncode != 0:
            raise Denied("review-pinned verifier denied current live state")
        try:
            raw = p.stdout.encode("utf-8").strip()
        except UnicodeError as exc:
            raise Denied("review-pinned verifier emitted invalid text") from exc
        current = parse_json_bytes(raw, "review-pinned verifier receipt")
        canonical = canonical_receipt_bytes(current)
        if raw != canonical:
            raise Denied("review-pinned verifier did not emit canonical receipt")
        if canonical != canonical_receipt_bytes(expected):
            raise Denied("authorization receipt is stale")
        return current


def source_repository_live():
    doc = pub.gh_api(f"repos/{SOURCE_REPO}")
    if (doc.get("id") != SOURCE_REPO_ID or doc.get("full_name") != SOURCE_REPO
            or doc.get("private") is not True or doc.get("visibility") != "private"
            or doc.get("archived") is not False or doc.get("disabled") is not False):
        raise Denied("source repository identity/privacy changed")
    return doc


def matching_payload_refs(tree_sha):
    require_sha(tree_sha, "payload branch tree")
    refs = api_list(
        f"repos/{CARRIER_REPO}/git/matching-refs/heads/ci/oreslang-{tree_sha}")
    for item in refs:
        if type(item) is not dict or type(item.get("ref")) is not str:
            raise Denied("malformed matching-ref response")
    return refs


def git_output(root, *args):
    if root.is_symlink() or not root.is_dir():
        raise Denied("source checkout must be a real directory")
    try:
        p = subprocess.run(
            ["git", "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
             "-c", "core.hooksPath=/dev/null", "-C", str(root), *args],
            capture_output=True, text=True, env=pub.verified_child_env(),
            timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("local source Git proof unavailable") from exc
    if p.returncode != 0:
        raise Denied("local source Git proof failed")
    return p.stdout.strip()


def verify_dispatcher_checkout(receipt):
    """Prove all locally executed security code is exactly the reviewed publisher head."""
    validate_receipt(receipt)
    candidate = SCRIPT_DIR.parent
    if candidate.is_symlink():
        raise Denied("dispatcher checkout path cannot be a symlink")
    root = candidate.resolve(strict=True)
    if git_output(root, "rev-parse", "--show-toplevel") != str(root):
        raise Denied("dispatcher must execute from the repository checkout root")
    if git_output(root, "rev-parse", "--verify", "HEAD") != receipt["publisher_policy_head_sha"]:
        raise Denied("dispatcher checkout is not the reviewed publisher head")
    if git_output(root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise Denied("dispatcher checkout is not clean")
    for relative in LOCAL_REVIEWED_FILES:
        local = root / relative
        if local.is_symlink() or not local.is_file():
            raise Denied("reviewed dispatcher file is missing or symlinked: " + relative)
        raw = local.read_bytes()
        if not raw or len(raw) > MAX_JSON:
            raise Denied("reviewed dispatcher file has invalid size: " + relative)
        expected_blob = require_sha(
            git_output(root, "rev-parse", "--verify", "HEAD:" + relative),
            "reviewed local file blob")
        if git_blob_sha(raw) != expected_blob:
            raise Denied("reviewed dispatcher file differs from Git index: " + relative)
    return root


def verify_local_checkout(root, receipt):
    candidate = Path(root)
    if candidate.is_symlink():
        raise Denied("source checkout path cannot be a symlink")
    root = candidate.resolve(strict=True)
    if git_output(root, "rev-parse", "--show-toplevel") != str(root):
        raise Denied("source directory must be the checkout root")
    if git_output(root, "rev-parse", "--verify", "HEAD") != receipt["source_commit_sha"]:
        raise Denied("local source checkout commit differs from approval")
    if git_output(root, "rev-parse", "--verify", "HEAD^{tree}") != receipt["source_tree_sha"]:
        raise Denied("local source checkout tree differs from approval")
    if git_output(root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise Denied("local source checkout is not clean")
    return root


def full_authorize(receipt, require_branch_absent=True):
    """Revalidate revocable authority and live confidentiality state."""
    verify_dispatcher_checkout(receipt)
    fresh_receipt(receipt)
    source_repository_live()
    destination = pub.checked_repo(CARRIER_REPO, expected_id=CARRIER_REPO_ID)
    if destination.get("default_branch") != "main":
        raise Denied("carrier default branch changed")
    if require_branch_absent and matching_payload_refs(receipt["source_tree_sha"]):
        raise Denied("review-pinned payload branch already exists")
    commit = pub.gh_api(
        f"repos/{CARRIER_REPO}/git/commits/{receipt['carrier_base_sha']}")
    if commit.get("sha") != receipt["carrier_base_sha"]:
        raise Denied("reviewed carrier parent is unavailable")
    return receipt["carrier_base_sha"]


def payload_branch(receipt):
    return "ci/oreslang-" + receipt["source_tree_sha"]


def write_payload(files, receipt, authorize=full_authorize):
    """Create one source-bearing commit after a fresh authorization per write."""
    validate_receipt(receipt)
    if pub.source_tree_sha(files) != receipt["source_tree_sha"]:
        raise Denied("source snapshot differs from approved Git tree")
    branch = payload_branch(receipt)
    authorize(receipt, True)

    base = pub.gh_api(
        f"repos/{CARRIER_REPO}/git/commits/{receipt['carrier_base_sha']}")
    if base.get("sha") != receipt["carrier_base_sha"]:
        raise Denied("carrier base commit identity mismatch")
    base_tree = pub.nested_sha(base, "tree")
    base_root = pub.gh_api(f"repos/{CARRIER_REPO}/git/trees/{base_tree}")
    base_items = base_root.get("tree")
    if (base_root.get("sha") != base_tree
            or base_root.get("truncated") is not False
            or type(base_items) is not list):
        raise Denied("reviewed carrier base tree readback unavailable")
    if any(type(item) is dict and item.get("path") in (PREFIX, PROVENANCE_PATH)
           for item in base_items):
        raise Denied("reviewed carrier parent already contains payload paths")

    batches = pub.prepare_source_tree_batches(files)
    binary_writes = sum(text is None for batch in batches for _, _, _, text in batch)
    pub.require_private_ci_api_budget(
        len(files), max(1, binary_writes + len(batches) + 4))

    isolated_sha = None
    for batch in batches:
        elements = []
        for rel, data, mode, as_text in batch:
            entry = {"path": rel, "mode": mode, "type": "blob"}
            if as_text is not None:
                entry["content"] = as_text
            else:
                authorize(receipt, True)
                blob = pub.gh_api(
                    f"repos/{CARRIER_REPO}/git/blobs", method="POST",
                    payload={
                        "content": base64.b64encode(data).decode("ascii"),
                        "encoding": "base64",
                    })
                blob_sha = require_sha(blob.get("sha"), "uploaded source blob")
                if blob_sha != git_blob_sha(data):
                    raise Denied("uploaded source blob digest mismatch")
                entry["sha"] = blob_sha
            elements.append(entry)
        authorize(receipt, True)
        request = {"tree": elements}
        if isolated_sha is not None:
            request["base_tree"] = isolated_sha
        tree = pub.gh_api(
            f"repos/{CARRIER_REPO}/git/trees", method="POST", payload=request)
        isolated_sha = require_sha(tree.get("sha"), "isolated source tree")

    if isolated_sha != receipt["source_tree_sha"]:
        raise Denied("destination isolated source tree differs from approval")

    provenance = canonical_receipt_bytes(receipt).decode("utf-8") + "\n"
    authorize(receipt, True)
    root_tree = pub.gh_api(
        f"repos/{CARRIER_REPO}/git/trees", method="POST",
        payload={
            "base_tree": base_tree,
            "tree": [
                {"path": PREFIX, "mode": "040000", "type": "tree",
                 "sha": isolated_sha},
                {"path": PROVENANCE_PATH, "mode": "100644", "type": "blob",
                 "content": provenance},
            ],
        })
    root_tree_sha = require_sha(root_tree.get("sha"), "payload root tree")

    authorize(receipt, True)
    commit = pub.gh_api(
        f"repos/{CARRIER_REPO}/git/commits", method="POST",
        payload={
            "message": "ci: review-pinned private Oreslang source snapshot",
            "tree": root_tree_sha,
            "parents": [receipt["carrier_base_sha"]],
        })
    commit_sha = require_sha(commit.get("sha"), "payload commit")

    authorize(receipt, True)
    created = pub.gh_api(
        f"repos/{CARRIER_REPO}/git/refs", method="POST",
        payload={"ref": "refs/heads/" + branch, "sha": commit_sha})
    if created.get("ref") != "refs/heads/" + branch:
        raise Denied("GitHub did not confirm payload ref creation")
    if pub.nested_sha(created, "object") != commit_sha:
        raise Denied("created payload ref points to unexpected commit")

    read_ref = pub.gh_api(f"repos/{CARRIER_REPO}/git/ref/heads/{branch}")
    if (read_ref.get("ref") != "refs/heads/" + branch
            or pub.nested_sha(read_ref, "object") != commit_sha):
        raise Denied("payload ref readback mismatch")
    read_commit = pub.gh_api(f"repos/{CARRIER_REPO}/git/commits/{commit_sha}")
    parents = read_commit.get("parents")
    if (read_commit.get("sha") != commit_sha or type(parents) is not list
            or len(parents) != 1 or type(parents[0]) is not dict
            or parents[0].get("sha") != receipt["carrier_base_sha"]):
        raise Denied("payload commit is not exact single-parent")
    if pub.nested_sha(read_commit, "tree") != root_tree_sha:
        raise Denied("payload commit tree readback mismatch")
    read_tree = pub.gh_api(f"repos/{CARRIER_REPO}/git/trees/{root_tree_sha}")
    items = read_tree.get("tree")
    if (read_tree.get("sha") != root_tree_sha
            or read_tree.get("truncated") is not False or type(items) is not list):
        raise Denied("payload root tree readback unavailable")
    source_matches = [
        x for x in items if type(x) is dict and x.get("path") == PREFIX
        and x.get("type") == "tree" and x.get("sha") == receipt["source_tree_sha"]
    ]
    provenance_sha = git_blob_sha(provenance.encode("utf-8"))
    provenance_matches = [
        x for x in items if type(x) is dict and x.get("path") == PROVENANCE_PATH
        and x.get("type") == "blob" and x.get("sha") == provenance_sha
    ]
    if len(source_matches) != 1 or len(provenance_matches) != 1:
        raise Denied("payload tree lacks exact source/provenance entries")

    authorize(receipt, False)
    return commit_sha, len(files)


def dispatch(source_dir, receipt):
    full_authorize(receipt, True)
    root = verify_local_checkout(Path(source_dir), receipt)
    files = pub.collect_source(root, root)
    if pub.source_tree_sha(files) != receipt["source_tree_sha"]:
        raise Denied("descriptor snapshot differs from approved source tree")
    full_authorize(receipt, True)
    return write_payload(files, receipt)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source-dir", required=True, type=Path)
    ap.add_argument("--authorization-receipt", required=True, type=Path)
    args = ap.parse_args(argv)
    try:
        receipt, _ = load_receipt(args.authorization_receipt)
        commit, count = dispatch(args.source_dir, receipt)
    except (OSError, Denied, pub.Denied) as exc:
        print("PRIVATE_CI_REVIEW_PINNED_DISPATCH_DENIED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_REVIEW_PINNED_PAYLOAD_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
