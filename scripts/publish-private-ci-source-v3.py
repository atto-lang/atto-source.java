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
import stat
import subprocess
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
LEGACY_PATH = SCRIPT_DIR / "publish-private-ci-source.py"
SPEC = importlib.util.spec_from_file_location("private_ci_legacy", LEGACY_PATH)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("PRIVATE_CI_DESTINATION_REJECTED: audited publisher unavailable")
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)

Denied = legacy.Denied
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HANDOFF_REF = re.compile(
    r"^ci/(?:oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}|authorized-[0-9a-f]{40})$"
)
CONTRACT_PATH = ".github/private-ci-carrier-contracts.json"
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
    if (not value.startswith(prefix) or not value.endswith(suffix)
            or not parts or ".." in parts or ".git" in parts
            or "\\" in value or value.startswith("/")):
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
    if (not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW")
            or not hasattr(os, "O_NONBLOCK")):
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
    if set(carrier) != {
        "repository_id", "base_sha", "workflow_path",
        "workflow_blob_sha", "test_plans"
    }:
        raise Denied("carrier contract fields are not exact")
    repo_id = carrier.get("repository_id")
    if (not isinstance(repo_id, int) or isinstance(repo_id, bool) or repo_id <= 0):
        raise Denied("carrier repository id invalid")
    base_sha = valid_sha(carrier.get("base_sha"), "carrier base SHA invalid")
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


def verify_protected_carrier(repository, expected_base_sha):
    """Require the destination main branch to enforce the reviewed carrier base.

    Pinning a carrier SHA is necessary but not sufficient: without branch
    protection, an unreviewed actor can replace trusted workflow/test-plan
    bytes on main and race a later contract refresh. Match the source-side
    review guarantees before any private source bytes are copied.
    """
    expected_base_sha = valid_sha(
        expected_base_sha, "carrier protected base SHA invalid"
    )
    branch = legacy.gh_api(f"repos/{repository}/branches/main")
    if (branch.get("name") != "main"
            or branch.get("protected") is not True
            or branch.get("commit", {}).get("sha") != expected_base_sha):
        raise Denied("carrier main is not protected at the reviewed base")

    protection = legacy.gh_api(f"repos/{repository}/branches/main/protection")
    reviews = protection.get("required_pull_request_reviews")
    administrators = protection.get("enforce_admins")
    force_pushes = protection.get("allow_force_pushes")
    deletions = protection.get("allow_deletions")
    if (not isinstance(reviews, dict)
            or reviews.get("require_code_owner_reviews") is not True
            or reviews.get("dismiss_stale_reviews") is not True
            or not isinstance(reviews.get("required_approving_review_count"), int)
            or isinstance(reviews["required_approving_review_count"], bool)
            or reviews["required_approving_review_count"] < 1
            or not isinstance(administrators, dict)
            or administrators.get("enabled") is not True
            or not isinstance(force_pushes, dict)
            or force_pushes.get("enabled") is not False
            or not isinstance(deletions, dict)
            or deletions.get("enabled") is not False):
        raise Denied(
            "carrier main must enforce CODEOWNER approvals, stale-review "
            "dismissal, administrator protection and deny forced pushes/deletions"
        )
    bypasses = reviews.get("bypass_pull_request_allowances")
    if bypasses is not None:
        if (not isinstance(bypasses, dict)
                or any(not isinstance(bypasses.get(category, []), list)
                       or bypasses.get(category)
                       for category in ("users", "teams", "apps"))):
            raise Denied("carrier main has PR review bypass allowances")
    return expected_base_sha


def checked_destination(repository, contract):
    repo = legacy.checked_repo(repository)
    if (repo.get("id") != contract["repository_id"]
            or repo.get("archived") is not False
            or repo.get("disabled") is not False
            or repo.get("fork") is not False
            or repo.get("default_branch") != "main"):
        raise Denied("carrier identity/state differs from protected contract")
    verify_protected_carrier(repository, contract["base_sha"])
    ref = legacy.gh_api(f"repos/{repository}/git/ref/heads/main")
    base_sha = valid_sha(ref.get("object", {}).get("sha"),
                         "carrier main ref invalid")
    if base_sha != contract["base_sha"]:
        raise Denied("carrier main advanced beyond reviewed protected base")
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


def validate_test_plan(plan_bytes, source_pr):
    try:
        plan = json.loads(plan_bytes.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise Denied("invalid reviewed carrier test plan") from exc
    if (not isinstance(plan, dict)
            or plan.get("schema") != "ores-private-ci-test-plan-v1"
            or plan.get("source_repo") != legacy.SOURCE_REPO):
        raise Denied("reviewed test plan source/schema mismatch")
    allowed = plan.get("allowed_prs")
    if (not isinstance(allowed, list)
            or source_pr not in allowed
            or any(not isinstance(x, int) or isinstance(x, bool) or x <= 0
                   for x in allowed)):
        raise Denied("reviewed test plan does not authorize source PR")
    return hashlib.sha256(plan_bytes).hexdigest()


def manifest(files):
    rows = []
    for path, data, mode in files:
        rows.append([path, mode, git_blob_sha(data), len(data)])
    rows.sort(key=lambda row: row[0])
    return rows, hashlib.sha256(canonical_bytes(rows)).hexdigest()


def authorize_write(root, repository, approved_policy, contract):
    """Cheap per-write guard: local approval bytes plus live carrier identity.

    Protected-source main and the full immutable carrier contract are checked
    before reading source bytes and again before refs are attached. Repeating
    those multi-endpoint reads for every blob would multiply API traffic by
    the file count without adding integrity: the reviewed source commit and
    pinned destination base SHA are immutable. Privacy and repository identity
    are the state that can change between individual writes, so those are
    rechecked immediately before every write.
    """
    if legacy.checked_policy_fingerprint(root, repository) != approved_policy:
        raise Denied("private CI destination approval policy changed during transfer")
    repo = legacy.checked_repo(repository)
    if (repo.get("id") != contract["repository_id"]
            or repo.get("archived") is not False
            or repo.get("disabled") is not False
            or repo.get("fork") is not False
            or repo.get("default_branch") != "main"):
        raise Denied("carrier identity/state changed during transfer")


def _gh_ref_request(repository, ref_name, method):
    if not isinstance(ref_name, str) or not HANDOFF_REF.fullmatch(ref_name):
        raise Denied("ref cleanup target is invalid")
    if method not in ("GET", "DELETE"):
        raise Denied("ref cleanup method is invalid")
    encoded = ref_name.replace("/", "%2F")
    family = "ref" if method == "GET" else "refs"
    argv = [
        "gh", "api", "--method", method,
        f"repos/{repository}/git/{family}/heads/{encoded}",
        "--header", "Accept: application/vnd.github+json",
    ]
    env = dict(os.environ, GH_HOST="github.com", GH_PROMPT_DISABLED="1")
    try:
        return subprocess.run(
            argv, capture_output=True, text=True, env=env,
            timeout=30, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("carrier ref rollback is unavailable") from exc


def live_ref_sha(repository, ref_name, expected_repository_id):
    repo = legacy.checked_repo(repository)
    if repo.get("id") != expected_repository_id:
        raise Denied("carrier repository identity changed during ref verification")
    result = _gh_ref_request(repository, ref_name, "GET")
    if result.returncode != 0:
        if "HTTP 404" in result.stderr:
            # GitHub can mask private-resource authorization failures as 404.
            # The numeric carrier identity was just revalidated above, so this
            # 404 can be treated as evidence that this exact ref is absent.
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
    if result.returncode != 0 and "HTTP 404" not in result.stderr:
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
    approved_policy = legacy.checked_policy_fingerprint(trusted_root, repository)
    protected_sha = legacy.verify_protected_origin(repository, approved_policy)
    checked_control_plane(trusted_root, protected_sha)
    contract = checked_contract(
        trusted_root, repository, source_pr, test_plan, protected_sha
    )
    source_repo_id = verify_source_pr(source_pr, source_commit)
    destination, base_tree, plan_bytes = checked_destination(repository, contract)
    plan_sha256 = validate_test_plan(plan_bytes, source_pr)

    trusted = trusted_root.resolve(strict=True)
    checked_source = source.resolve(strict=True)
    try:
        checked_source.relative_to(trusted)
    except ValueError as exc:
        raise Denied("source directory escapes trusted checkout") from exc
    files = legacy.collect_source(source, trusted_root)
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
            "sha256": plan_sha256,
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
        f"{legacy.SOURCE_REPO} id {source_repo_id}"
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
    if legacy.verify_protected_origin(repository, approved_policy) != protected_sha:
        raise Denied("source protected main advanced before publication")
    checked_control_plane(trusted_root, protected_sha)
    checked_contract(
        trusted_root, repository, source_pr,
        contract["test_plan_path"], protected_sha
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
        if legacy.verify_protected_origin(repository, approved_policy) != protected_sha:
            raise Denied("source protected main advanced before authorization")
        checked_control_plane(trusted_root, protected_sha)
        checked_contract(
            trusted_root, repository, source_pr,
            contract["test_plan_path"], protected_sha
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

