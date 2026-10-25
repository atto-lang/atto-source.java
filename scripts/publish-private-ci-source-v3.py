#!/usr/bin/env python3
"""Publish an exact private Oreslang PR tree to a pinned reviewed carrier.

This v3 dispatcher composes the already-audited source traversal / exact-tree
checks from publish-private-ci-source.py with a protected carrier contract.
It never checks the source repository out from the carrier and never accepts
caller-selected GitHub API routes, carrier base commits, workflow blobs, or
test-plan bytes.
"""
import argparse
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import resource
import stat
import subprocess
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parent
LEGACY_PATH = SCRIPT_DIR / "publish-private-ci-source.py"
SPEC = importlib.util.spec_from_file_location("private_ci_legacy", LEGACY_PATH)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("PRIVATE_CI_DESTINATION_REJECTED: audited publisher unavailable")
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)

Denied = legacy.Denied
HEX40 = re.compile(r"^[0-9a-f]{40}$")
MAVEN_ARTIFACT = re.compile(
    r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+"
    r"(?::[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?)?$"
)
MAX_MAVEN_PREFETCH_ARTIFACTS = 16
SAFE_EXECUTABLE = re.compile(
    r"^(?:python3|mvn|cmake|ctest|g\+\+|clang\+\+|/tmp/ores-[A-Za-z0-9_.-]+)$"
)
HANDOFF_REF = re.compile(
    r"^ci/(?:oreslang-[0-9a-f]{40}|authorized-[0-9a-f]{40})$"
)
CONTRACT_PATH = ".github/private-ci-carrier-contracts.json"
REVIEW_AUTHORITY_PR = 2010
REVIEW_AUTHORITY_LOGIN = "ORESoftware"
REVIEW_AUTHORITY_USER_ID = 11139560
CARRIER_REVIEW_LOGIN = "ORESoftware"
CARRIER_REVIEW_USER_ID = 11139560
MAX_GOVERNANCE_FILE = 1024 * 1024
REVIEWED_GOVERNANCE_PATHS = (
    ".github/CODEOWNERS",
    ".github/private-ci-approved-repositories.txt",
    ".github/private-ci-carrier-contracts.json",
    ".github/workflows/private-ci-mirror-preflight.yml",
    "docs/security/CI_PRIVATE_SOURCE_MIRRORING.md",
    "scripts/check-private-ci-destination.sh",
    "scripts/run-in-private-ci-destination.sh",
    "scripts/publish-private-ci-source.py",
    "scripts/publish-private-ci-source-v3.py",
)
RECEIPT_PATH = "ci/oreslang-receipt.json"
RECEIPT_SCHEMA = "ores-private-ci-receipt-v3"
CONTRACT_SCHEMA = 1
MAX_CONTRACT = 64 * 1024
MAX_PLAN = 64 * 1024
RECEIPT_TTL = dt.timedelta(hours=24)
SOURCE_REPOSITORY_ID = 1400792274
CONTROL_PLANE_PATHS = (
    "scripts/check-private-ci-destination.sh",
    "scripts/run-in-private-ci-destination.sh",
    "scripts/publish-private-ci-source.py",
    "scripts/publish-private-ci-source-v3.py",
)
MAX_CONTROL_PLANE_FILE = 512 * 1024
MAX_GH_API_RESPONSE_BYTES = 24 * 1024 * 1024


def valid_sha(value, label="invalid SHA"):
    if not isinstance(value, str) or not HEX40.fullmatch(value):
        raise Denied(label)
    return value


def canonical_bytes(value):
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("ascii")


def canonical_digest(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def git_blob_sha(data):
    return hashlib.sha1(
        b"blob " + str(len(data)).encode("ascii") + bytes([0]) + data
    ).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Denied("carrier contract contains duplicate JSON key")
        result[key] = value
    return result


def safe_policy_path(value, prefix, suffix):
    if not isinstance(value, str):
        raise Denied("invalid protected carrier path")
    parts = PurePosixPath(value).parts
    if (not re.fullmatch(r"[A-Za-z0-9._/-]+", value)
            or PurePosixPath(value).as_posix() != value
            or not value.startswith(prefix) or not value.endswith(suffix)
            or not parts or value.startswith("/")
            or any(part in (".", "..") for part in parts)
            or any(part.casefold() == ".git" for part in parts)
            or "\\" in value):
        raise Denied("invalid protected carrier path")
    return value


def read_local_protected_file(root, path, max_bytes):
    """Read one protected control-plane file without following any symlink."""
    if (not isinstance(path, str) or not isinstance(max_bytes, int)
            or isinstance(max_bytes, bool) or max_bytes <= 0):
        raise Denied("invalid protected local file request")
    parts = PurePosixPath(path).parts
    if (not parts or path.startswith("/") or "\\" in path
            or any(part in ("", ".", "..", ".git") for part in parts)):
        raise Denied("invalid protected local file path")
    if not legacy.safe_descriptor_flags_available():
        raise Denied("OS cannot safely read protected local files")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    file_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    root = root.resolve(strict=True)
    opened = []
    try:
        current = os.open(root, directory_flags)
        opened.append(current)
        for component in parts[:-1]:
            current = os.open(component, directory_flags, dir_fd=current)
            opened.append(current)
        file_fd = os.open(parts[-1], file_flags, dir_fd=current)
        try:
            stream = os.fdopen(file_fd, "rb")
        except BaseException:
            os.close(file_fd)
            raise
        with stream:
            meta = os.fstat(stream.fileno())
            if not stat.S_ISREG(meta.st_mode) or meta.st_nlink != 1:
                raise Denied("protected local file is not a regular single-link file")
            raw = stream.read(max_bytes + 1)
    except OSError as exc:
        raise Denied("protected local file cannot be opened safely") from exc
    finally:
        for fd in reversed(opened):
            os.close(fd)
    if not raw or len(raw) > max_bytes:
        raise Denied("protected local file missing or oversized")
    return raw


def read_local_contract(root):
    """Read the local protected contract through no-follow descriptors."""
    return read_local_protected_file(root, CONTRACT_PATH, MAX_CONTRACT)


def gh_api_list(route):
    """Bounded authenticated GET for GitHub list endpoints."""
    argv = [
        trusted_github_cli(), "api", "--method", "GET", route,
        "--header", "Accept: application/vnd.github+json",
    ]
    completed = run_bounded_gh(
        argv, env=github_api_environment(), timeout=45
    )
    if completed.returncode != 0:
        raise Denied("authenticated GitHub list request failed")
    try:
        data = json.loads(completed.stdout, object_pairs_hook=unique_object)
    except (json.JSONDecodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid GitHub list JSON response") from exc
    if not isinstance(data, list):
        raise Denied("unexpected GitHub list response shape")
    return data


def github_timestamp(value, label):
    if not isinstance(value, str):
        raise Denied(label)
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise Denied(label) from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise Denied(label)
    return parsed.astimezone(dt.timezone.utc)


def latest_authority_review():
    reviews = gh_api_list(
        f"repos/{legacy.SOURCE_REPO}/pulls/{REVIEW_AUTHORITY_PR}/reviews?per_page=100"
    )
    if len(reviews) >= 100:
        raise Denied("private-CI authority review history exceeds bounded page")
    matching = []
    seen_ids = set()
    for review in reviews:
        if not isinstance(review, dict):
            raise Denied("private-CI authority review history is malformed")
        review_id = review.get("id")
        if (not isinstance(review_id, int) or isinstance(review_id, bool)
                or review_id <= 0 or review_id in seen_ids):
            raise Denied("private-CI authority review history has invalid IDs")
        seen_ids.add(review_id)
        user = review.get("user")
        if (isinstance(user, dict)
                and user.get("id") == REVIEW_AUTHORITY_USER_ID
                and isinstance(user.get("login"), str)
                and user["login"].casefold() == REVIEW_AUTHORITY_LOGIN.casefold()):
            valid_sha(
                review.get("commit_id"),
                "private-CI authority review commit SHA invalid",
            )
            submitted = github_timestamp(
                review.get("submitted_at"),
                "private-CI authority review timestamp invalid",
            )
            if not isinstance(review.get("state"), str):
                raise Denied("private-CI authority review state invalid")
            matching.append((submitted, review_id, review))
    if not matching:
        raise Denied("independent private-CI authority review is missing")
    matching.sort(key=lambda item: (item[0], item[1]))
    return matching[-1][2]


def governance_tree_entries(commit_sha):
    commit_sha = valid_sha(commit_sha, "source governance commit SHA invalid")
    commit = legacy.gh_api(
        f"repos/{legacy.SOURCE_REPO}/git/commits/{commit_sha}"
    )
    if commit.get("sha") != commit_sha:
        raise Denied("source governance commit identity mismatch")
    tree_sha = valid_sha(
        commit.get("tree", {}).get("sha"),
        "source governance root tree SHA invalid",
    )
    listing = legacy.gh_api(
        f"repos/{legacy.SOURCE_REPO}/git/trees/{tree_sha}?recursive=1"
    )
    if (listing.get("sha") != tree_sha
            or listing.get("truncated") is not False
            or not isinstance(listing.get("tree"), list)):
        raise Denied("source governance tree listing is incomplete")

    wanted = set(REVIEWED_GOVERNANCE_PATHS)
    found = {}
    for entry in listing["tree"]:
        if not isinstance(entry, dict):
            raise Denied("source governance tree entry is malformed")
        governance_path = entry.get("path")
        if governance_path not in wanted:
            continue
        if governance_path in found:
            raise Denied("source governance tree contains duplicate reviewed path")
        mode = entry.get("mode")
        kind = entry.get("type")
        if kind != "blob" or mode not in ("100644", "100755"):
            raise Denied(
                "source governance path has unsafe Git type/mode: " + governance_path
            )
        found[governance_path] = (
            mode,
            kind,
            valid_sha(entry.get("sha"), "source governance blob SHA invalid"),
        )
    if set(found) != wanted:
        raise Denied("source governance tree is missing reviewed paths")
    return found


def review_attested_main_sha(
        repository, expected_policy_digest, expected_repository_id):
    """Authorize only governance blobs exactly reviewed on merged authority PR."""
    origin = legacy.gh_api(f"repos/{legacy.SOURCE_REPO}")
    if (origin.get("id") != SOURCE_REPOSITORY_ID
            or origin.get("full_name") != legacy.SOURCE_REPO
            or origin.get("private") is not True
            or origin.get("visibility") != "private"):
        raise Denied("source origin identity/privacy invalid")

    branch = legacy.gh_api(
        f"repos/{legacy.SOURCE_REPO}/branches/{legacy.SOURCE_PROTECTED_BRANCH}"
    )
    if branch.get("name") != legacy.SOURCE_PROTECTED_BRANCH:
        raise Denied("source main branch identity changed")
    main_sha = valid_sha(
        branch.get("commit", {}).get("sha"), "source main SHA invalid"
    )

    authority = legacy.gh_api(
        f"repos/{legacy.SOURCE_REPO}/pulls/{REVIEW_AUTHORITY_PR}"
    )
    head = authority.get("head")
    base = authority.get("base")
    author = authority.get("user")
    if (authority.get("number") != REVIEW_AUTHORITY_PR
            or authority.get("state") != "closed"
            or authority.get("merged_at") is None
            or not isinstance(author, dict)
            or author.get("id") == REVIEW_AUTHORITY_USER_ID
            or not isinstance(head, dict)
            or not isinstance(head.get("repo"), dict)
            or head["repo"].get("full_name") != legacy.SOURCE_REPO
            or head["repo"].get("id") != SOURCE_REPOSITORY_ID
            or not isinstance(base, dict)
            or not isinstance(base.get("repo"), dict)
            or base["repo"].get("full_name") != legacy.SOURCE_REPO
            or base["repo"].get("id") != SOURCE_REPOSITORY_ID
            or base.get("ref") != legacy.SOURCE_PROTECTED_BRANCH):
        raise Denied(
            "private-CI authority PR is not an independent merged source-main review"
        )
    reviewed_head = valid_sha(
        head.get("sha"), "private-CI authority PR head SHA invalid"
    )

    review = latest_authority_review()
    submitted_at = github_timestamp(
        review.get("submitted_at"),
        "private-CI authority review timestamp invalid",
    )
    merged_at = github_timestamp(
        authority.get("merged_at"),
        "private-CI authority merge timestamp invalid",
    )
    if (review.get("state") != "APPROVED"
            or review.get("commit_id") != reviewed_head
            or submitted_at > merged_at):
        raise Denied(
            "independent private-CI review is stale, late, or not approved"
        )

    reviewed_tree = governance_tree_entries(reviewed_head)
    main_tree = governance_tree_entries(main_sha)
    policy_raw = None
    codeowners_raw = None
    for governance_path in REVIEWED_GOVERNANCE_PATHS:
        if main_tree[governance_path] != reviewed_tree[governance_path]:
            raise Denied(
                "source governance Git entry drifted after independent review: "
                + governance_path
            )
        reviewed_raw, reviewed_blob = remote_file_at_source_main(
            governance_path, reviewed_head, MAX_GOVERNANCE_FILE
        )
        main_raw, main_blob = remote_file_at_source_main(
            governance_path, main_sha, MAX_GOVERNANCE_FILE
        )
        if (main_blob != reviewed_blob
                or reviewed_blob != reviewed_tree[governance_path][2]
                or main_blob != main_tree[governance_path][2]
                or main_raw != reviewed_raw):
            raise Denied(
                "source governance bytes drifted after independent review: "
                + governance_path
            )
        if governance_path == legacy.POLICY_PATH:
            policy_raw = main_raw
        elif governance_path == legacy.CODEOWNERS_PATH:
            codeowners_raw = main_raw

    if policy_raw is None or codeowners_raw is None:
        raise Denied("review-attested governance is incomplete")
    if hashlib.sha256(policy_raw).digest() != expected_policy_digest:
        raise Denied("source approval policy diverges from reviewed authority")
    reviewed_repository_id = legacy._parse_policy_approval(
        policy_raw, repository, "review-attested source approval policy"
    )
    if reviewed_repository_id != expected_repository_id:
        raise Denied(
            "review-attested destination repository ID differs from local approval"
        )
    legacy.require_security_codeowners(codeowners_raw)
    return main_sha


def authorized_main_sha(
        repository, expected_policy_digest, expected_repository_id):
    """Prefer enforced protected-main governance; otherwise exact review attestation."""
    branch = legacy.gh_api(
        f"repos/{legacy.SOURCE_REPO}/branches/{legacy.SOURCE_PROTECTED_BRANCH}"
    )
    if branch.get("name") != legacy.SOURCE_PROTECTED_BRANCH:
        raise Denied("source main branch identity changed")
    if branch.get("protected") is True:
        return legacy.verify_protected_origin(
            repository, expected_policy_digest, expected_repository_id
        )
    return review_attested_main_sha(
        repository, expected_policy_digest, expected_repository_id
    )


def remote_file_at_source_main(path, main_sha, max_bytes):
    item = legacy.gh_api(
        f"repos/{legacy.SOURCE_REPO}/contents/{path}?ref={main_sha}"
    )
    if (item.get("path") != path or item.get("type") != "file"
            or item.get("encoding") != "base64"):
        raise Denied("protected source policy file has invalid metadata")
    encoded = item.get("content")
    if not isinstance(encoded, str):
        raise Denied("protected source policy file has no content")
    try:
        raw = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("protected source policy file has invalid base64") from exc
    if not raw or len(raw) > max_bytes:
        raise Denied("protected source policy file missing or oversized")
    blob_sha = valid_sha(item.get("sha"), "protected policy blob SHA invalid")
    if git_blob_sha(raw) != blob_sha:
        raise Denied("protected source file blob digest mismatch")
    return raw, blob_sha


def parse_contract(raw, repository, source_pr, test_plan):
    try:
        doc = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid carrier contract JSON") from exc
    if not isinstance(doc, dict) or doc.get("schema") != CONTRACT_SCHEMA:
        raise Denied("unsupported carrier contract schema")
    carriers = doc.get("carriers")
    if not isinstance(carriers, dict):
        raise Denied("carrier contract missing carriers")
    carrier = carriers.get(repository)
    if not isinstance(carrier, dict):
        raise Denied("destination absent from protected carrier contract")
    required_carrier_fields = {
        "repository_id", "base_sha", "workflow_path",
        "workflow_blob_sha", "test_plans"
    }
    allowed_carrier_fields = required_carrier_fields | {"review_pr"}
    if (not required_carrier_fields.issubset(carrier)
            or not set(carrier).issubset(allowed_carrier_fields)):
        raise Denied("carrier contract fields are not exact")
    repo_id = carrier.get("repository_id")
    if (not isinstance(repo_id, int) or isinstance(repo_id, bool) or repo_id <= 0):
        raise Denied("carrier repository id invalid")
    base_sha = valid_sha(carrier.get("base_sha"), "carrier base SHA invalid")
    review_pr = carrier.get("review_pr")
    if (review_pr is not None
            and (not isinstance(review_pr, int) or isinstance(review_pr, bool)
                 or review_pr <= 0)):
        raise Denied("carrier review PR invalid")
    workflow_path = safe_policy_path(
        carrier.get("workflow_path"), ".github/workflows/", ".yml"
    )
    workflow_blob = valid_sha(
        carrier.get("workflow_blob_sha"), "carrier workflow blob invalid"
    )
    plans = carrier.get("test_plans")
    if not isinstance(plans, dict):
        raise Denied("carrier test plans missing")
    plan_path = safe_policy_path(test_plan, "ci/test-plans/", ".json")
    plan = plans.get(plan_path)
    if not isinstance(plan, dict) or set(plan) != {"blob_sha", "allowed_prs"}:
        raise Denied("test plan absent from protected carrier contract")
    plan_blob = valid_sha(plan.get("blob_sha"), "test plan blob invalid")
    allowed = plan.get("allowed_prs")
    if (not isinstance(allowed, list) or not allowed
            or any(not isinstance(x, int) or isinstance(x, bool) or x <= 0
                   for x in allowed)
            or len(set(allowed)) != len(allowed)
            or source_pr not in allowed):
        raise Denied("source PR is not authorized by protected test plan")
    return {
        "repository_id": repo_id,
        "base_sha": base_sha,
        "workflow_path": workflow_path,
        "workflow_blob_sha": workflow_blob,
        "test_plan_path": plan_path,
        "test_plan_blob_sha": plan_blob,
        "review_pr": review_pr,
    }


def require_control_plane_location(root):
    """Require the executing modules to be the scripts under this trusted root."""
    root = root.resolve(strict=True)
    expected_v3 = (root / "scripts" / "publish-private-ci-source-v3.py").resolve(strict=True)
    expected_legacy = (root / "scripts" / "publish-private-ci-source.py").resolve(strict=True)
    if (Path(__file__).resolve(strict=True) != expected_v3
            or LEGACY_PATH.resolve(strict=True) != expected_legacy):
        raise Denied("executing private CI publisher is outside the trusted control plane")


def checked_control_plane(root, main_sha):
    """Bind the executing security programs byte-for-byte to protected main."""
    root = root.resolve(strict=True)
    require_control_plane_location(root)
    for path in CONTROL_PLANE_PATHS:
        local = read_local_protected_file(root, path, MAX_CONTROL_PLANE_FILE)
        remote, _ = remote_file_at_source_main(path, main_sha, MAX_CONTROL_PLANE_FILE)
        if local != remote:
            raise Denied("local private CI control plane diverges from protected source main")


def checked_contract(root, repository, source_pr, test_plan, main_sha):
    local = read_local_contract(root)
    remote, _ = remote_file_at_source_main(CONTRACT_PATH, main_sha, MAX_CONTRACT)
    if local != remote:
        raise Denied("local carrier contract diverges from protected source main")
    return parse_contract(remote, repository, source_pr, test_plan)


def _carrier_security_lines(contents):
    if (not isinstance(contents, bytes)
            or any(byte != 10 and not 32 <= byte <= 126 for byte in contents)):
        raise Denied("carrier CODEOWNERS contains noncanonical bytes")
    return contents.decode("ascii").split("\n")


def require_carrier_security_codeowners(contents):
    rules = [
        line.strip() for line in _carrier_security_lines(contents)
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if rules != [
        "* @ORESoftware",
        "/.github/ @ORESoftware",
        "/.github/CODEOWNERS @ORESoftware",
    ]:
        raise Denied("carrier CODEOWNERS must be fully security-owned")


def remote_carrier_file(repository, path, ref, max_bytes):
    item = legacy.gh_api(f"repos/{repository}/contents/{path}?ref={ref}")
    if (item.get("path") != path or item.get("type") != "file"
            or item.get("encoding") != "base64"):
        raise Denied("carrier reviewed file metadata invalid: " + path)
    encoded = item.get("content")
    if not isinstance(encoded, str):
        raise Denied("carrier reviewed file content missing: " + path)
    try:
        raw = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("carrier reviewed file base64 invalid: " + path) from exc
    if not raw or len(raw) > max_bytes:
        raise Denied("carrier reviewed file missing or oversized: " + path)
    blob = valid_sha(item.get("sha"), "carrier reviewed file blob invalid")
    if git_blob_sha(raw) != blob:
        raise Denied("carrier reviewed file blob digest mismatch: " + path)
    return raw, blob


def carrier_commit_tree(repository, commit_sha):
    commit_sha = valid_sha(commit_sha, "carrier commit SHA invalid")
    commit = legacy.gh_api(f"repos/{repository}/git/commits/{commit_sha}")
    if commit.get("sha") != commit_sha:
        raise Denied("carrier commit identity mismatch")
    return valid_sha(
        commit.get("tree", {}).get("sha"), "carrier root tree SHA invalid"
    )


def latest_carrier_review(repository, review_pr):
    reviews = gh_api_list(
        f"repos/{repository}/pulls/{review_pr}/reviews?per_page=100"
    )
    if len(reviews) >= 100:
        raise Denied("carrier review history exceeds bounded page")
    matching = []
    seen_ids = set()
    for review in reviews:
        if not isinstance(review, dict):
            raise Denied("carrier review history is malformed")
        review_id = review.get("id")
        if (not isinstance(review_id, int) or isinstance(review_id, bool)
                or review_id <= 0 or review_id in seen_ids):
            raise Denied("carrier review history has invalid IDs")
        seen_ids.add(review_id)
        user = review.get("user")
        if (isinstance(user, dict)
                and user.get("id") == CARRIER_REVIEW_USER_ID
                and isinstance(user.get("login"), str)
                and user["login"].casefold() == CARRIER_REVIEW_LOGIN.casefold()):
            valid_sha(
                review.get("commit_id"), "carrier review commit SHA invalid"
            )
            submitted = github_timestamp(
                review.get("submitted_at"), "carrier review timestamp invalid"
            )
            if not isinstance(review.get("state"), str):
                raise Denied("carrier review state invalid")
            matching.append((submitted, review_id, review))
    if not matching:
        raise Denied("independent carrier review is missing")
    matching.sort(key=lambda item: (item[0], item[1]))
    return matching[-1][2]


def review_attested_carrier_main(repository, contract, branch):
    review_pr = contract.get("review_pr")
    if not isinstance(review_pr, int) or isinstance(review_pr, bool) or review_pr <= 0:
        raise Denied("unprotected carrier requires reviewed carrier PR")
    base_sha = valid_sha(
        branch.get("commit", {}).get("sha"), "carrier main SHA invalid"
    )
    if base_sha != contract["base_sha"]:
        raise Denied("carrier main advanced beyond reviewed base")

    pr = legacy.gh_api(f"repos/{repository}/pulls/{review_pr}")
    head = pr.get("head")
    base = pr.get("base")
    author = pr.get("user")
    if (pr.get("number") != review_pr
            or pr.get("state") != "closed"
            or pr.get("merged_at") is None
            or not isinstance(author, dict)
            or author.get("id") == CARRIER_REVIEW_USER_ID
            or not isinstance(head, dict)
            or not isinstance(head.get("repo"), dict)
            or head["repo"].get("full_name") != repository
            or head["repo"].get("id") != contract["repository_id"]
            or not isinstance(base, dict)
            or base.get("ref") != "main"
            or not isinstance(base.get("repo"), dict)
            or base["repo"].get("full_name") != repository
            or base["repo"].get("id") != contract["repository_id"]):
        raise Denied("carrier review PR is not an independent merged same-repo main review")

    reviewed_head = valid_sha(
        head.get("sha"), "carrier review PR head SHA invalid"
    )
    review = latest_carrier_review(repository, review_pr)
    submitted_at = github_timestamp(
        review.get("submitted_at"), "carrier review timestamp invalid"
    )
    merged_at = github_timestamp(
        pr.get("merged_at"), "carrier merge timestamp invalid"
    )
    if (review.get("state") != "APPROVED"
            or review.get("commit_id") != reviewed_head
            or submitted_at > merged_at):
        raise Denied("independent carrier review is stale, late, or not approved")

    reviewed_tree = carrier_commit_tree(repository, reviewed_head)
    main_tree = carrier_commit_tree(repository, base_sha)
    if reviewed_tree != main_tree:
        raise Denied("carrier main tree differs from independently reviewed PR head")

    codeowners, _ = remote_carrier_file(
        repository, legacy.CODEOWNERS_PATH, base_sha, MAX_GOVERNANCE_FILE
    )
    require_carrier_security_codeowners(codeowners)
    return base_sha


def checked_carrier_main(repository, contract):
    branch = legacy.gh_api(f"repos/{repository}/branches/main")
    if branch.get("name") != "main":
        raise Denied("carrier main branch identity changed")
    base_sha = valid_sha(
        branch.get("commit", {}).get("sha"), "carrier main SHA invalid"
    )
    if base_sha != contract["base_sha"]:
        raise Denied("carrier main advanced beyond reviewed base")

    if branch.get("protected") is not True:
        return review_attested_carrier_main(repository, contract, branch)

    protection = legacy.gh_api(f"repos/{repository}/branches/main/protection")
    reviews = protection.get("required_pull_request_reviews")
    if (not isinstance(reviews, dict)
            or reviews.get("require_code_owner_reviews") is not True
            or reviews.get("dismiss_stale_reviews") is not True
            or not isinstance(reviews.get("required_approving_review_count"), int)
            or isinstance(reviews.get("required_approving_review_count"), bool)
            or reviews.get("required_approving_review_count", 0) < 1):
        raise Denied("carrier main does not enforce independent CODEOWNER review")
    admins = protection.get("enforce_admins")
    force = protection.get("allow_force_pushes")
    delete = protection.get("allow_deletions")
    if (not isinstance(admins, dict) or admins.get("enabled") is not True
            or not isinstance(force, dict) or force.get("enabled") is not False
            or not isinstance(delete, dict) or delete.get("enabled") is not False):
        raise Denied("carrier main admin/force/delete protection incomplete")
    bypasses = reviews.get("bypass_pull_request_allowances")
    if bypasses is not None:
        if (not isinstance(bypasses, dict)
                or any(not isinstance(bypasses.get(key, []), list)
                       or bypasses.get(key) for key in ("users", "teams", "apps"))):
            raise Denied("carrier main has pull-request review bypasses")

    codeowners, _ = remote_carrier_file(
        repository, legacy.CODEOWNERS_PATH, base_sha, MAX_GOVERNANCE_FILE
    )
    require_carrier_security_codeowners(codeowners)
    return base_sha


def checked_destination(repository, contract):
    repo = legacy.checked_repo(repository, contract["repository_id"])
    if (repo.get("id") != contract["repository_id"]
            or repo.get("archived") is not False
            or repo.get("disabled") is not False
            or repo.get("fork") is not False
            or repo.get("default_branch") != "main"):
        raise Denied("carrier identity/state differs from protected contract")
    base_sha = checked_carrier_main(repository, contract)
    commit = legacy.gh_api(f"repos/{repository}/git/commits/{base_sha}")
    if commit.get("sha") != base_sha:
        raise Denied("carrier base commit mismatch")
    base_tree = valid_sha(commit.get("tree", {}).get("sha"),
                          "carrier base tree invalid")
    base_listing = legacy.gh_api(
        f"repos/{repository}/git/trees/{base_tree}?recursive=1"
    )
    if (base_listing.get("sha") != base_tree
            or base_listing.get("truncated") is not False
            or not isinstance(base_listing.get("tree"), list)):
        raise Denied("carrier base tree listing is incomplete")
    for entry in base_listing["tree"]:
        path = entry.get("path") if isinstance(entry, dict) else None
        if (path == legacy.PREFIX
                or isinstance(path, str) and path.startswith(legacy.PREFIX + "/")
                or path == RECEIPT_PATH):
            raise Denied("reviewed carrier base is not source-free")

    workflow = legacy.gh_api(
        f"repos/{repository}/contents/{contract['workflow_path']}?ref={base_sha}"
    )
    if (workflow.get("path") != contract["workflow_path"]
            or workflow.get("type") != "file"
            or workflow.get("sha") != contract["workflow_blob_sha"]):
        raise Denied("carrier workflow differs from protected contract")

    plan = legacy.gh_api(
        f"repos/{repository}/contents/{contract['test_plan_path']}?ref={base_sha}"
    )
    if (plan.get("path") != contract["test_plan_path"]
            or plan.get("type") != "file"
            or plan.get("encoding") != "base64"
            or plan.get("sha") != contract["test_plan_blob_sha"]):
        raise Denied("carrier test plan differs from protected contract")
    encoded = plan.get("content")
    if not isinstance(encoded, str):
        raise Denied("carrier test plan content missing")
    try:
        plan_bytes = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("carrier test plan has invalid base64") from exc
    if not plan_bytes or len(plan_bytes) > MAX_PLAN:
        raise Denied("carrier test plan missing or oversized")
    return repo, base_tree, plan_bytes


def verify_source_pr(source_pr, source_commit):
    origin = legacy.gh_api(f"repos/{legacy.SOURCE_REPO}")
    repo_id = origin.get("id")
    if (origin.get("full_name") != legacy.SOURCE_REPO
            or repo_id != SOURCE_REPOSITORY_ID
            or origin.get("private") is not True
            or origin.get("visibility") != "private"
            or origin.get("archived") is not False
            or origin.get("disabled") is not False
            or origin.get("fork") is not False):
        raise Denied("source repository identity/state invalid")
    pr = legacy.gh_api(f"repos/{legacy.SOURCE_REPO}/pulls/{source_pr}")
    head = pr.get("head")
    base = pr.get("base")
    if (pr.get("number") != source_pr
            or pr.get("state") != "open"
            or not isinstance(head, dict)
            or head.get("sha") != source_commit
            or not isinstance(head.get("repo"), dict)
            or head["repo"].get("full_name") != legacy.SOURCE_REPO
            or not isinstance(base, dict)
            or base.get("ref") != legacy.SOURCE_PROTECTED_BRANCH):
        raise Denied("source commit is not the exact open PR head against main")
    return repo_id


def _validate_maven_prefetch_artifacts(plan, uses_maven):
    extras = plan.get("maven_prefetch_artifacts", [])
    if extras is None:
        extras = []
    if (not isinstance(extras, list)
            or len(extras) > MAX_MAVEN_PREFETCH_ARTIFACTS
            or any(not isinstance(value, str)
                   or len(value) > 256
                   or not MAVEN_ARTIFACT.fullmatch(value)
                   for value in extras)
            or len(set(extras)) != len(extras)):
        raise Denied("reviewed test plan Maven prefetch artifacts invalid")
    if not uses_maven and extras:
        raise Denied("non-Maven test plan cannot prefetch Maven artifacts")
    return list(extras)


def _validate_maven_argv(argv):
    if "-o" not in argv and "--offline" not in argv:
        raise Denied("Maven carrier commands must run offline")
    short_with_values = ("-s", "-gs", "-f")
    long_with_values = ("--settings", "--global-settings", "--file")
    dangerous_props = (
        "-Dmaven.repo.local", "-DskipTests", "-Dmaven.test.skip", "-DskipITs",
    )
    for arg in argv[1:]:
        if (any(arg == flag or (arg.startswith(flag) and arg != flag)
                for flag in short_with_values)
                or any(arg == flag or arg.startswith(flag + "=")
                       for flag in long_with_values)
                or any(arg == prop or arg.startswith(prop + "=")
                       for prop in dangerous_props)):
            raise Denied("Maven carrier command overrides trusted build controls")


def validate_test_plan(plan_bytes, source_pr):
    try:
        plan = json.loads(plan_bytes.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid reviewed carrier test plan") from exc
    if (not isinstance(plan, dict)
            or plan.get("schema") != "ores-private-ci-test-plan-v1"
            or plan.get("source_repo") != legacy.SOURCE_REPO):
        raise Denied("reviewed test plan source/schema mismatch")
    allowed_fields = {
        "schema", "source_repo", "allowed_prs", "commands",
        "required_source_blobs", "maven_pom_blob_sha",
        "maven_prefetch_artifacts",
    }
    if not set(plan).issubset(allowed_fields):
        raise Denied("reviewed test plan has unexpected fields")
    allowed = plan.get("allowed_prs")
    if (not isinstance(allowed, list) or not allowed
            or source_pr not in allowed
            or any(not isinstance(x, int) or isinstance(x, bool) or x <= 0
                   for x in allowed)
            or len(set(allowed)) != len(allowed)):
        raise Denied("reviewed test plan does not authorize source PR")

    required = plan.get("required_source_blobs", {})
    if not isinstance(required, dict):
        raise Denied("reviewed test plan required source blobs invalid")
    checked_required = {}
    for source_path, source_sha in required.items():
        if (not isinstance(source_path, str) or not source_path
                or source_path.startswith("/") or "\\" in source_path
                or ".." in PurePosixPath(source_path).parts
                or ".git" in PurePosixPath(source_path).parts):
            raise Denied("reviewed test plan has unsafe required source path")
        checked_required[source_path] = valid_sha(
            source_sha, "reviewed test plan required source blob invalid"
        )

    commands = plan.get("commands")
    if not isinstance(commands, list) or not 1 <= len(commands) <= 16:
        raise Denied("reviewed test plan commands missing or excessive")
    uses_maven = False
    for command in commands:
        if not isinstance(command, dict):
            raise Denied("reviewed test plan command shape invalid")
        if (not {"argv", "cwd", "timeout_seconds"}.issubset(command)
                or not set(command).issubset({
                    "argv", "cwd", "timeout_seconds", "stdout_contains",
                })):
            raise Denied("reviewed test plan command shape invalid")
        argv = command.get("argv")
        cwd = command.get("cwd")
        timeout = command.get("timeout_seconds")
        expected = command.get("stdout_contains")
        if (not isinstance(argv, list) or not argv or len(argv) > 64
                or any(not isinstance(arg, str) or not arg or len(arg) > 4096
                       or "\x00" in arg for arg in argv)
                or not SAFE_EXECUTABLE.fullmatch(argv[0])
                or not isinstance(cwd, str) or not cwd
                or cwd.startswith("/") or "\\" in cwd
                or ".." in PurePosixPath(cwd).parts
                or ".git" in PurePosixPath(cwd).parts
                or not isinstance(timeout, int) or isinstance(timeout, bool)
                or not 1 <= timeout <= 1800
                or (expected is not None
                    and (not isinstance(expected, str)
                         or not expected or len(expected) > 4096))):
            raise Denied("reviewed test plan command invalid")
        if argv[0] == "mvn":
            uses_maven = True
            _validate_maven_argv(argv)

    pom_pin = plan.get("maven_pom_blob_sha")
    if pom_pin is not None:
        pom_pin = valid_sha(pom_pin, "reviewed Maven pom blob invalid")
        old_pin = checked_required.get("pom.xml")
        if old_pin is not None and old_pin != pom_pin:
            raise Denied("reviewed Maven pom pins disagree")
        checked_required["pom.xml"] = pom_pin

    extras = _validate_maven_prefetch_artifacts(plan, uses_maven)
    if uses_maven and "pom.xml" not in checked_required:
        raise Denied("Maven test plan must bind exact pom.xml blob")
    if not uses_maven and pom_pin is not None:
        raise Denied("non-Maven test plan cannot bind Maven pom")

    return {
        "sha256": hashlib.sha256(plan_bytes).hexdigest(),
        "required_source_blobs": checked_required,
        "uses_maven": uses_maven,
        "maven_pom_blob_sha": checked_required.get("pom.xml"),
        "maven_prefetch_artifacts": extras,
    }


def verify_required_source_blobs(files, required):
    if not isinstance(required, dict):
        raise Denied("required source blob contract invalid")
    actual = {path: git_blob_sha(data) for path, data, _mode in files}
    for source_path, expected_sha in required.items():
        if actual.get(source_path) != expected_sha:
            raise Denied(
                "exact source tree differs from reviewed required blob: "
                + source_path
            )


def manifest(files):
    rows = []
    for path, data, mode in files:
        rows.append([path, mode, git_blob_sha(data), len(data)])
    rows.sort(key=lambda row: row[0])
    return rows, hashlib.sha256(canonical_bytes(rows)).hexdigest()


def authorize_write(root, repository, approved_policy, contract):
    """Per-write fail-closed guard: incident closure, policy and carrier identity.

    Protected-source main and the full immutable carrier contract are checked
    before reading source bytes and again before refs are attached. Repeating
    those multi-endpoint reads for every blob would multiply API traffic by
    the file count without adding integrity: the reviewed source commit and
    pinned destination base SHA are immutable. Privacy and repository identity
    are the state that can change between individual writes, so those are
    rechecked immediately before every write. P0 incident status can also
    change after preflight, so recheck it before EVERY source-bearing write
    rather than relying on a single initial closure observation.
    """
    # The incidents may be reopened after initial preflight. Never upload
    # another Git blob/tree/ref while either P0 remains unresolved.
    legacy.require_resolved_blocking_incidents()
    current_policy, current_repository_id = legacy.checked_policy_approval(
        root, repository
    )
    if (current_policy != approved_policy
            or current_repository_id != contract["repository_id"]):
        raise Denied(
            "private CI destination approval or repository identity changed during transfer"
        )
    repo = legacy.checked_repo(repository, contract["repository_id"])
    if (repo.get("id") != contract["repository_id"]
            or repo.get("archived") is not False
            or repo.get("disabled") is not False
            or repo.get("fork") is not False
            or repo.get("default_branch") != "main"):
        raise Denied("carrier identity/state changed during transfer")
    branch = legacy.gh_api(f"repos/{repository}/branches/main")
    if (branch.get("name") != "main"
            or branch.get("commit", {}).get("sha") != contract["base_sha"]):
        raise Denied("carrier main changed during source-bearing write")


def trusted_github_cli():
    """Resolve ref API access only through a root-controlled OS binary."""
    target = "/usr/bin/gh"
    for directory in ("/", "/usr", "/usr/bin"):
        try:
            info = os.lstat(directory)
        except OSError as exc:
            raise Denied("trusted GitHub CLI directory is unavailable") from exc
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid != 0
                or info.st_mode & 0o022):
            raise Denied("trusted GitHub CLI directory is not root-controlled")
    try:
        info = os.lstat(target)
    except OSError as exc:
        raise Denied("trusted GitHub CLI is unavailable") from exc
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0
            or info.st_mode & 0o6022 or not info.st_mode & 0o111):
        raise Denied("trusted GitHub CLI executable is not root-controlled")
    return target


def github_api_environment():
    """Use one explicit token and no ambient credential/config environment."""
    provided = [
        name for name in ("GH_TOKEN", "GITHUB_TOKEN")
        if os.environ.get(name)
    ]
    if len(provided) != 1:
        raise Denied("exactly one explicit GitHub API token is required")
    return {
        provided[0]: os.environ[provided[0]],
        "GH_HOST": "github.com",
        "GH_PROMPT_DISABLED": "1",
        "PATH": "/usr/bin:/bin",
        "HOME": "/",
        "XDG_CONFIG_HOME": "/nonexistent",
        "LC_ALL": "C.UTF-8",
    }


def run_bounded_gh(argv, *, env, timeout=30):
    """Run GitHub CLI with bounded output and an isolated process session."""
    limit = MAX_GH_API_RESPONSE_BYTES
    if (not hasattr(resource, "RLIMIT_FSIZE")
            or not isinstance(limit, int) or isinstance(limit, bool)
            or limit < 2):
        raise Denied("OS cannot safely bound GitHub CLI output")

    def limit_child_files():
        resource.setrlimit(resource.RLIMIT_FSIZE, (limit, limit))

    try:
        with tempfile.TemporaryFile(mode="w+b") as stdout_file, \
                tempfile.TemporaryFile(mode="w+b") as stderr_file:
            proc = subprocess.run(
                argv,
                stdin=subprocess.DEVNULL,
                stdout=stdout_file,
                stderr=stderr_file,
                env=env,
                timeout=timeout,
                check=False,
                start_new_session=True,
                preexec_fn=limit_child_files,
            )
            stdout_file.seek(0)
            stderr_file.seek(0)
            stdout_bytes = stdout_file.read(limit + 1)
            stderr_bytes = stderr_file.read(limit + 1)
    except (OSError, subprocess.SubprocessError) as exc:
        raise Denied("carrier ref GitHub request is unavailable") from exc

    if len(stdout_bytes) >= limit or len(stderr_bytes) >= limit:
        raise Denied("carrier ref GitHub response exceeds size limit")
    try:
        stdout = stdout_bytes.decode("utf-8")
        stderr = stderr_bytes.decode("utf-8")
    except UnicodeError as exc:
        raise Denied("carrier ref GitHub response is not UTF-8") from exc
    return subprocess.CompletedProcess(argv, proc.returncode, stdout, stderr)


def _gh_ref_request(repository, ref_name, method):
    if not isinstance(ref_name, str) or not HANDOFF_REF.fullmatch(ref_name):
        raise Denied("ref cleanup target is invalid")
    if method not in ("GET", "DELETE"):
        raise Denied("ref cleanup method is invalid")
    encoded = ref_name.replace("/", "%2F")
    family = "ref" if method == "GET" else "refs"
    argv = [
        trusted_github_cli(), "api", "--method", method,
        f"repos/{repository}/git/{family}/heads/{encoded}",
        "--header", "Accept: application/vnd.github+json",
    ]
    return run_bounded_gh(
        argv,
        env=github_api_environment(),
        timeout=30,
    )


def _is_http_status(stderr, status):
    if not isinstance(stderr, str):
        return False
    first_line = stderr.split("\n", 1)[0]
    return re.fullmatch(
        rf"gh: [^\r\n]{{1,4096}} \(HTTP {status}\)", first_line
    ) is not None


def live_ref_sha(repository, ref_name, expected_repository_id):
    repo = legacy.checked_repo(repository, expected_repository_id)
    if (repo.get("id") != expected_repository_id
            or repo.get("full_name") != repository
            or repo.get("private") is not True
            or repo.get("visibility") != "private"):
        raise Denied("carrier repository identity changed during ref verification")
    result = _gh_ref_request(repository, ref_name, "GET")
    if result.returncode != 0:
        if _is_http_status(result.stderr, 404):
            # Private-resource 404 is accepted only after the exact numeric
            # repository identity/privacy was revalidated above.
            return None
        raise Denied("carrier ref lookup failed")
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise Denied("carrier ref lookup returned invalid JSON") from exc
    if (not isinstance(payload, dict)
            or payload.get("ref") != "refs/heads/" + ref_name
            or payload.get("object", {}).get("type") != "commit"):
        raise Denied("carrier ref lookup returned unexpected identity")
    return valid_sha(
        payload.get("object", {}).get("sha"),
        "carrier ref lookup returned invalid SHA",
    )


def require_exact_ref(repository, ref_name, expected_sha, expected_repository_id):
    actual = live_ref_sha(repository, ref_name, expected_repository_id)
    if actual != expected_sha:
        raise Denied("carrier ref does not point to exact handoff commit")


def delete_exact_ref(repository, ref_name, expected_sha, expected_repository_id):
    expected_sha = valid_sha(expected_sha, "invalid rollback commit SHA")
    actual = live_ref_sha(repository, ref_name, expected_repository_id)
    if actual is None:
        return False
    if actual != expected_sha:
        raise Denied("carrier ref moved; refusing rollback")

    result = _gh_ref_request(repository, ref_name, "DELETE")
    if result.returncode != 0 and not _is_http_status(result.stderr, 404):
        raise Denied("carrier ref rollback failed")
    if live_ref_sha(repository, ref_name, expected_repository_id) is not None:
        raise Denied("carrier ref still exists after rollback")
    return True


def publish(repository, source, source_commit, source_pr, test_plan, trusted_root, now=None):
    if not legacy.NAME.fullmatch(repository):
        raise Denied("invalid destination repository")
    source_commit = valid_sha(source_commit, "invalid source commit")
    if not isinstance(source_pr, int) or isinstance(source_pr, bool) or source_pr <= 0:
        raise Denied("invalid source PR number")

    # Authorize before reading private source bytes.
    legacy.require_preflight(repository, trusted_root)
    approved_policy, approved_repository_id = legacy.checked_policy_approval(
        trusted_root, repository
    )
    authority_sha = authorized_main_sha(
        repository, approved_policy, approved_repository_id
    )
    checked_control_plane(trusted_root, authority_sha)
    contract = checked_contract(
        trusted_root, repository, source_pr, test_plan, authority_sha
    )
    if contract["repository_id"] != approved_repository_id:
        raise Denied(
            "carrier contract repository ID differs from independently reviewed approval"
        )
    source_repo_id = verify_source_pr(source_pr, source_commit)
    destination, base_tree, plan_bytes = checked_destination(repository, contract)
    plan_meta = validate_test_plan(plan_bytes, source_pr)

    trusted = trusted_root.resolve(strict=True)
    checked_source = source.resolve(strict=True)
    try:
        checked_source.relative_to(trusted)
    except ValueError as exc:
        raise Denied("source directory escapes trusted checkout") from exc
    files = legacy.collect_source(source, trusted_root)
    verify_required_source_blobs(files, plan_meta["required_source_blobs"])
    source_tree = legacy.verified_exact_source_tree(files, source_commit)
    rows, manifest_sha256 = manifest(files)
    branch = "ci/oreslang-" + source_tree

    def auth():
        authorize_write(trusted_root, repository, approved_policy, contract)

    elements = []
    for rel, data, mode in files:
        auth()
        blob = legacy.gh_api(
            f"repos/{repository}/git/blobs", method="POST",
            payload={
                "content": base64.b64encode(data).decode("ascii"),
                "encoding": "base64",
            },
        )
        uploaded = valid_sha(blob.get("sha"), "uploaded blob SHA invalid")
        if uploaded != git_blob_sha(data):
            raise Denied("uploaded blob digest differs from exact source")
        elements.append({
            "path": rel, "mode": mode, "type": "blob", "sha": uploaded
        })

    auth()
    isolated = legacy.gh_api(
        f"repos/{repository}/git/trees", method="POST",
        payload={"tree": elements},
    )
    isolated_sha = valid_sha(
        isolated.get("sha"), "uploaded source tree SHA invalid"
    )
    if isolated_sha != source_tree:
        raise Denied("uploaded source tree differs from pinned PR tree")

    now = now or dt.datetime.now(dt.timezone.utc)
    if now.tzinfo is None or now.utcoffset() is None:
        raise Denied("publisher clock must be timezone-aware")
    expires = (now.astimezone(dt.timezone.utc) + RECEIPT_TTL).replace(microsecond=0)
    receipt = {
        "schema": RECEIPT_SCHEMA,
        "expires_at": expires.isoformat().replace("+00:00", "Z"),
        "carrier": {
            "workflow_blob_sha": contract["workflow_blob_sha"],
        },
        "destination": {
            "repo": repository,
            "repo_id": contract["repository_id"],
            "base_sha": contract["base_sha"],
        },
        "mirror": {
            "root_tree_sha": source_tree,
        },
        "source": {
            "repo": legacy.SOURCE_REPO,
            "repo_id": source_repo_id,
            "pr": source_pr,
            "head_sha": source_commit,
            "root_tree_sha": source_tree,
            "manifest_sha256": manifest_sha256,
        },
        "test_plan": {
            "path": contract["test_plan_path"],
            "sha256": plan_meta["sha256"],
        },
    }
    receipt_bytes = canonical_bytes(receipt) + b"\n"
    receipt_digest = canonical_digest(receipt)

    auth()
    receipt_blob = legacy.gh_api(
        f"repos/{repository}/git/blobs", method="POST",
        payload={
            "content": base64.b64encode(receipt_bytes).decode("ascii"),
            "encoding": "base64",
        },
    )
    receipt_blob_sha = valid_sha(
        receipt_blob.get("sha"), "uploaded receipt blob SHA invalid"
    )
    if receipt_blob_sha != git_blob_sha(receipt_bytes):
        raise Denied("uploaded receipt digest mismatch")

    auth()
    root_tree = legacy.gh_api(
        f"repos/{repository}/git/trees", method="POST",
        payload={
            "base_tree": base_tree,
            "tree": [
                {
                    "path": legacy.PREFIX,
                    "mode": "040000",
                    "type": "tree",
                    "sha": source_tree,
                },
                {
                    "path": RECEIPT_PATH,
                    "mode": "100644",
                    "type": "blob",
                    "sha": receipt_blob_sha,
                },
            ],
        },
    )
    root_tree_sha = valid_sha(
        root_tree.get("sha"), "carrier root tree SHA invalid"
    )

    auth()
    commit_message = (
        f"ci: exact private Oreslang tree {source_commit} tree {source_tree}\n"
        f"carrier-base {contract['base_sha']} tree {base_tree}\n"
        f"connector-authorized source-repository "
        f"{legacy.SOURCE_REPO} id {source_repo_id}\n"
        f"Oreslang-CI-Receipt-SHA256: {receipt_digest}"
    )
    commit = legacy.gh_api(
        f"repos/{repository}/git/commits", method="POST",
        payload={
            "message": commit_message,
            "tree": root_tree_sha,
            "parents": [contract["base_sha"]],
        },
    )
    commit_sha = valid_sha(commit.get("sha"), "carrier commit SHA invalid")

    # Recheck both source policy/protection and the immutable carrier contract
    # immediately before making the source-bearing commit executable.
    if authorized_main_sha(
            repository, approved_policy, approved_repository_id) != authority_sha:
        raise Denied("source governance authority advanced before publication")
    checked_control_plane(trusted_root, authority_sha)
    checked_contract(
        trusted_root, repository, source_pr,
        contract["test_plan_path"], authority_sha
    )
    checked_destination(repository, contract)
    if verify_source_pr(source_pr, source_commit) != source_repo_id:
        raise Denied("source repository identity changed before publication")

    # Create the source-bearing branch first. The carrier workflow fails closed
    # while it waits for the exact authorization ref below. Treat a create call
    # as potentially successful before it returns: transport ambiguity must not
    # strand a source-bearing ref.
    authorization = "ci/authorized-" + commit_sha
    payload_attempted = False
    authorization_attempted = False
    try:
        auth()
        payload_attempted = True
        created = legacy.gh_api(
            f"repos/{repository}/git/refs", method="POST",
            payload={"ref": "refs/heads/" + branch, "sha": commit_sha},
        )
        if (created.get("ref") != "refs/heads/" + branch
                or created.get("object", {}).get("sha") not in (None, commit_sha)):
            raise Denied("carrier did not confirm exact-tree CI branch creation")
        require_exact_ref(
            repository, branch, commit_sha, contract["repository_id"]
        )

        # The payload ref is inert without the authorization ref. Re-run every
        # mutable governance check in that safe window so protection, policy,
        # contract or carrier-base drift cannot be authorized.
        if authorized_main_sha(
                repository, approved_policy, approved_repository_id) != authority_sha:
            raise Denied("source governance authority advanced before authorization")
        checked_control_plane(trusted_root, authority_sha)
        checked_contract(
            trusted_root, repository, source_pr,
            contract["test_plan_path"], authority_sha
        )
        checked_destination(repository, contract)

        # Revalidate the exact open PR head last, immediately before making the
        # carrier executable.
        if verify_source_pr(source_pr, source_commit) != source_repo_id:
            raise Denied("source repository identity changed before authorization")

        auth()
        authorization_attempted = True
        approved = legacy.gh_api(
            f"repos/{repository}/git/refs", method="POST",
            payload={"ref": "refs/heads/" + authorization, "sha": commit_sha},
        )
        if (approved.get("ref") != "refs/heads/" + authorization
                or approved.get("object", {}).get("sha") not in (None, commit_sha)):
            raise Denied("carrier did not confirm dispatcher authorization ref")

        # Do not trust create responses alone. Re-read both live refs before
        # reporting a successful handoff.
        require_exact_ref(
            repository, branch, commit_sha, contract["repository_id"]
        )
        require_exact_ref(
            repository, authorization, commit_sha, contract["repository_id"]
        )
    except Denied as exc:
        rollback_error = None
        if authorization_attempted:
            try:
                delete_exact_ref(
                    repository, authorization, commit_sha,
                    contract["repository_id"],
                )
            except Denied as rollback_exc:
                rollback_error = rollback_exc
        if payload_attempted:
            try:
                delete_exact_ref(
                    repository, branch, commit_sha,
                    contract["repository_id"],
                )
            except Denied as rollback_exc:
                rollback_error = rollback_error or rollback_exc
        if rollback_error is not None:
            raise Denied(
                "private CI handoff failed and carrier ref rollback was incomplete"
            ) from rollback_error
        raise exc

    return {
        "commit": commit_sha,
        "branch": branch,
        "authorization_ref": authorization,
        "source_tree": source_tree,
        "manifest_sha256": manifest_sha256,
        "receipt_sha256": receipt_digest,
        "files": len(files),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", help="approved private carrier owner/repo")
    parser.add_argument("--source-dir", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--source-pr", required=True, type=int)
    parser.add_argument("--test-plan", required=True)
    args = parser.parse_args(argv)
    root = SCRIPT_DIR.parent
    try:
        result = publish(
            args.repository,
            args.source_dir,
            args.source_commit,
            args.source_pr,
            args.test_plan,
            root,
        )
    except (Denied, OSError, ValueError) as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(
        "PRIVATE_CI_V3_CREATED: "
        + " ".join(f"{key}={value}" for key, value in result.items())
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

