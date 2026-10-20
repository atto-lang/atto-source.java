#!/usr/bin/env python3
"""Publish a snapshot branch ONLY into a reviewed private GitHub repository.

No arbitrary command execution or user-selectable GitHub API endpoints. All
writes target the exact repository checked by the private-source policy.
"""
import argparse
import base64
import hashlib
import json
import math
import os
import stat
from pathlib import Path
import re
import subprocess
import sys
import tempfile

NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$")
# An approved destination must include its stable numeric GitHub repository ID.
# This closes name reuse between security review and the first transfer.
POLICY_ENTRY = re.compile(r"^([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*) ([1-9][0-9]{0,19})$")
BRANCH = re.compile(r"^ci/oreslang-([a-f0-9]{40})-([a-f0-9]{40})$")
IMMUTABLE_CARRIER_BRANCH = re.compile(
    r"^(?:ci/oreslang-[a-f0-9]{40}-[a-f0-9]{40}|ci/authorized-[a-f0-9]{40})$"
)
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"
MAX_CARRIER_RETENTION_DAYS = 7
# This policy must be approved on the protected source main branch, not merely
# present in the caller's current PR/worktree.
SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPOSITORY_ID = 1400792274
PUBLISHER_PATH = "scripts/publish-private-ci-source.py"
SOURCE_METADATA_ROUTE = "repos/" + SOURCE_REPOSITORY
PROTECTED_BRANCH_ROUTE = SOURCE_METADATA_ROUTE + "/branches/main"
PROTECTED_POLICY_ROUTE = (
    SOURCE_METADATA_ROUTE + "/contents/"
    ".github/private-ci-approved-repositories.txt"
)
REVIEWED_SNAPSHOTS_ROUTE = (
    SOURCE_METADATA_ROUTE + "/contents/"
    ".github/private-ci-approved-source-snapshots.txt"
)
SOURCE_SNAPSHOT_ENTRY = re.compile(
    r"^([a-f0-9]{40}) ([a-f0-9]{40}) ([1-9][0-9]{0,19}) ([a-f0-9]{40}) ([a-f0-9]{40})$"
)
CARRIER_APPROVAL_PATH = ".github/private-oreslang-approved-source-snapshots.txt"
CARRIER_PUBLISHER_POLICY_PATH = ".github/oreslang-private-publisher.txt"
CARRIER_BUILD_IMAGES_PATH = ".github/oreslang-private-build-images.txt"
CARRIER_EXECUTION_PROFILE_PATH = ".github/oreslang-private-execution-profile.txt"
CARRIER_WORKFLOW_MANIFEST_PATH = ".github/oreslang-private-workflows.txt"
CARRIER_SOURCE_ENTRY = re.compile(
    r"^([a-f0-9]{40}) ([a-f0-9]{40}) ([1-9][0-9]{0,19}) ([1-9][0-9]{0,19})$"
)
CARRIER_PUBLISHER_ENTRY = re.compile(
    r"^([a-f0-9]{40}) ([a-f0-9]{40})$"
)
CARRIER_BUILD_IMAGE_ENTRY = re.compile(
    r"^(java21|java25) (sha256:[a-f0-9]{64})$"
)
CARRIER_WORKFLOW_ENTRY = re.compile(
    r"^(\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml) ([a-f0-9]{40})$"
)
CARRIER_EXECUTION_PROFILE = "bwrap-offline-maven-v1"
CARRIER_PRIVATE_WORKFLOW = ".github/workflows/oreslang-private-ci-gate.yml"
PROTECTED_REVIEWS_ROUTE = PROTECTED_BRANCH_ROUTE + "/protection/required_pull_request_reviews"
CODEOWNERS_ROUTE = SOURCE_METADATA_ROUTE + "/contents/.github/CODEOWNERS"
CODEOWNERS_ERRORS_ROUTE = SOURCE_METADATA_ROUTE + "/codeowners/errors"
CODEOWNER_PERMISSION_ROUTE = SOURCE_METADATA_ROUTE + "/collaborators/ORESoftware/permission"
# The last matching CODEOWNERS entry wins. Require a terminal set of exact
# entries so a later wildcard or missing entry cannot silently undo ownership.
REQUIRED_CODEOWNER_PATHS = (
    "/.github/CODEOWNERS",
    "/.github/private-ci-approved-repositories.txt",
    "/.github/private-ci-approved-source-snapshots.txt",
    "/scripts/check-private-ci-destination.sh",
    "/scripts/run-in-private-ci-destination.sh",
    "/scripts/test-private-ci-destination.sh",
    "/.github/workflows/private-ci-mirror-preflight.yml",
    "/docs/security/CI_PRIVATE_SOURCE_MIRRORING.md",
    "/scripts/publish-private-ci-source.py",
    "/scripts/test-publish-private-ci-source.py",
)
REQUIRED_CODEOWNER = "@ORESoftware"

PROTECTED_RULES_ROUTE = PROTECTED_BRANCH_ROUTE + "/protection"


class Denied(RuntimeError):
    pass


def require_hardened_branch_rules(rules, label):
    """Require strict, app-bound CI and immutable protected-branch semantics."""
    if not isinstance(rules, dict):
        raise Denied(label + " protection metadata is unavailable")
    if (not isinstance(rules.get("enforce_admins"), dict)
            or rules["enforce_admins"].get("enabled") is not True):
        raise Denied(label + " must enforce rules for administrators")
    required = rules.get("required_status_checks")
    if (not isinstance(required, dict)
            or required.get("strict") is not True
            or not isinstance(required.get("contexts"), list)
            or not required["contexts"]
            or not all(isinstance(c, str) and c for c in required["contexts"])):
        raise Denied(label + " must require strict CI checks")
    checks = required.get("checks")
    if (not isinstance(checks, list) or not checks
            or not all(isinstance(item, dict)
                       and isinstance(item.get("context"), str)
                       and item["context"]
                       and type(item.get("app_id")) is int
                       and item["app_id"] > 0
                       for item in checks)):
        raise Denied(label + " required checks must be bound to explicit GitHub App IDs")
    bound_contexts = [item["context"] for item in checks]
    if (len(bound_contexts) != len(set(bound_contexts))
            or set(bound_contexts) != set(required["contexts"])):
        raise Denied(label + " required check contexts are ambiguous or not app-bound")
    for key, description in (("allow_force_pushes", "force pushes"),
                             ("allow_deletions", "branch deletion")):
        control = rules.get(key)
        if not isinstance(control, dict) or control.get("enabled") is not False:
            raise Denied(label + " must explicitly disable " + description)
    return tuple(sorted((item["context"], item["app_id"]) for item in checks))



def valid_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise Denied("GitHub returned an invalid object SHA")
    return value


def nested_sha(response, member):
    """Reject malformed GitHub response objects without an uncaught traceback."""
    value = response.get(member)
    if not isinstance(value, dict):
        raise Denied("GitHub returned malformed object metadata")
    return valid_sha(value.get("sha"))


def at_ref(route, revision):
    """Bind security-sensitive GitHub content reads to one immutable commit."""
    return route + "?ref=" + valid_sha(revision)


def require_bootstrap_attestation(trusted_root):
    """Require the external bootstrap marker before policy/API/source access.

    These in-process globals are not themselves a root of trust: the external
    carrier bootstrap must establish them after authenticating the exact
    publisher bytes. Requiring them here prevents imported/direct calls to
    publish() from accidentally bypassing main()'s bootstrap gate, while the
    protected-checkout verifier below binds the supplied blob SHA to source
    protected main before any private source is read or written.
    """
    bootstrap_root = globals().get("_ORESLANG_VERIFIED_SOURCE_ROOT")
    bootstrap_blob = globals().get("_ORESLANG_VERIFIED_PUBLISHER_BLOB_SHA")
    if not isinstance(bootstrap_root, str) or not isinstance(bootstrap_blob, str):
        raise Denied("independent publisher bootstrap required")
    valid_sha(bootstrap_blob)
    root_path = Path(bootstrap_root)
    if not root_path.is_absolute():
        raise Denied("verified bootstrap source root must be absolute")
    try:
        attested_root = root_path.resolve(strict=True)
        requested_root = Path(trusted_root).resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise Denied("verified bootstrap source root is unavailable") from exc
    if requested_root != attested_root:
        raise Denied("publisher trusted root differs from independent bootstrap")
    return bootstrap_blob


# Executables are resolved only from controlled system directories; a caller's
# PATH must never select its own bash/git/gh helper during authorization.
SAFE_EXEC_PATH = "/usr/bin:/bin:/usr/local/bin"


def verified_child_env():
    """Exclude shell startup hooks, Git overrides, and dynamic loader injection."""
    env = os.environ.copy()
    for name in tuple(env):
        if (name in ("BASH_ENV", "ENV", "BASHOPTS", "SHELLOPTS",
                     "CDPATH", "GLOBIGNORE", "PROMPT_COMMAND",
                     "PYTHONPATH", "PYTHONHOME",
                     # Unreviewed proxy and root-CA overrides can redirect
                     # authenticated GitHub metadata and private blob uploads.
                     "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                     "http_proxy", "https_proxy", "all_proxy", "no_proxy",
                     "SSL_CERT_FILE", "SSL_CERT_DIR", "CURL_CA_BUNDLE",
                     "REQUESTS_CA_BUNDLE", "NODE_EXTRA_CA_CERTS",
                     # gh must not load a caller-selected config directory.
                     "GH_CONFIG_DIR", "XDG_CONFIG_HOME",
                     # The gh subprocess must not select an attacker-owned
                     # scratch directory for API request bodies or response data.
                     "TMPDIR", "TMP", "TEMP")
                or name.startswith(("BASH_FUNC_", "GIT_", "LD_", "DYLD_"))):
            env.pop(name, None)
    env["PATH"] = SAFE_EXEC_PATH
    env["GH_HOST"] = "github.com"
    env["GH_PROMPT_DISABLED"] = "1"
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
    return env


MAX_GITHUB_API_JSON_BYTES = 2 * 1024 * 1024


def strict_github_api_json(raw, root_type=dict):
    """Deny ambiguous or nonstandard responses used for security decisions."""
    if not isinstance(raw, str):
        raise Denied("invalid GitHub API JSON response")
    try:
        size = len(raw.encode("utf-8"))
    except UnicodeError as exc:
        raise Denied("invalid GitHub API JSON encoding") from exc
    if size > MAX_GITHUB_API_JSON_BYTES:
        raise Denied("oversized GitHub API JSON response")

    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise Denied("ambiguous duplicate GitHub API JSON key")
            result[key] = value
        return result

    def reject_nonstandard_number(value):
        raise Denied("nonstandard GitHub API JSON number")

    def strict_int(value):
        # GitHub object IDs, counts and limits never need unbounded integers.
        if len(value) > 64:
            raise Denied("invalid GitHub API JSON oversized integer")
        return int(value)

    def strict_float(value):
        if len(value) > 128:
            raise Denied("invalid GitHub API JSON float")
        number = float(value)
        if not math.isfinite(number):
            raise Denied("nonfinite GitHub API JSON number")
        return number

    try:
        data = json.loads(raw, object_pairs_hook=unique_keys,
                          parse_constant=reject_nonstandard_number,
                          parse_int=strict_int, parse_float=strict_float)
    except (ValueError, RecursionError) as exc:
        raise Denied("invalid GitHub API JSON response") from exc
    if root_type not in (dict, list):
        raise Denied("invalid expected GitHub response shape")
    if not isinstance(data, root_type):
        raise Denied("unexpected GitHub response shape")
    return data


def gh_api(route, method="GET", payload=None):
    argv = ["gh", "api", "--method", method, route,
            "--header", "Accept: application/vnd.github+json"]
    if payload is not None:
        argv.extend(["--input", "-"])
    env = verified_child_env()
    try:
        # A caller-controlled HOME can select arbitrary ~/.config/gh hosts and
        # credential settings even after GH_CONFIG_DIR/XDG_CONFIG_HOME are
        # removed. Use an ephemeral private configuration root for each
        # authenticated request: only explicit noninteractive credentials from
        # the trusted runner environment can authenticate gh.
        # /tmp is a system-managed sticky directory on the supported Linux
        # runner. Never let inherited TMPDIR/TMP/TEMP choose the parent path.
        temp_parent = "/tmp"
        metadata = os.stat(temp_parent, follow_symlinks=False)
        if (not stat.S_ISDIR(metadata.st_mode)
                or not metadata.st_mode & stat.S_ISVTX
                or metadata.st_uid not in (0, os.getuid())):
            raise Denied("trusted system temporary directory is unavailable")
        with tempfile.TemporaryDirectory(prefix="ores-ci-gh-", dir=temp_parent) as isolated_home:
            env["HOME"] = isolated_home
            env["XDG_CONFIG_HOME"] = isolated_home
            env["TMPDIR"] = temp_parent
            p = subprocess.run(argv, input=json.dumps(payload) if payload is not None else None,
                               capture_output=True, text=True, env=env, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("authenticated GitHub API request unavailable") from exc
    if p.returncode != 0:
        # Never echo stderr: a client can include private file bytes in errors.
        raise Denied("authenticated GitHub API request failed")
    # Only the two narrowly allowlisted security reads below have documented
    # top-level array responses. Keep every other route object-only so an
    # unexpected response shape still fails closed.
    effective_rules_route = re.fullmatch(
        r"repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/rules/branches/"
        r"ci%2Foreslang-[a-f0-9]{40}-[a-f0-9]{40}\?per_page=100&page=1", route)
    matching_refs_route = re.fullmatch(
        r"repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/git/matching-refs/heads/"
        r"ci/oreslang-[a-f0-9]{40}-[a-f0-9]{40}\?per_page=1&page=1", route)
    array_route = effective_rules_route is not None or matching_refs_route is not None
    return strict_github_api_json(
        p.stdout, root_type=list if array_route else dict)


def checked_repo(repository, expected_id=None):
    """Verify the live private destination and pin its numeric ID for this transfer."""
    result = gh_api("repos/" + repository)
    if (not isinstance(result.get("full_name"), str)
            or result["full_name"].casefold() != repository.casefold()
            or result.get("visibility") != "private"
            or result.get("private") is not True):
        raise Denied("destination identity or private visibility changed")
    # A renamed/deleted/recreated repository can reuse a name. Reject an ID
    # change between any two GitHub writes, even when visibility is private.
    repository_id = result.get("id")
    if (type(repository_id) is not int or repository_id <= 0
            or (expected_id is not None and repository_id != expected_id)):
        raise Denied("destination numeric repository identity unavailable or changed")
    if result.get("archived") is not False or result.get("disabled") is not False:
        raise Denied("destination is archived, disabled, or its status is unknown")
    if result.get("fork") is not False:
        raise Denied("destination must be a standalone repository, not a fork")
    return result


def require_carrier_actions_posture(repository, expected_id):
    """Require least-privilege Actions and bounded retention before source reads.

    These repository-admin settings are independent of the carrier Git tree, so
    a protected workflow alone is insufficient. Any unreadable or permissive
    setting fails closed.
    """
    destination = checked_repo(repository, expected_id=expected_id)
    if destination.get("allow_forking") is not False:
        raise Denied("private carrier must disable private repository forking")

    permissions = gh_api(f"repos/{repository}/actions/permissions")
    if permissions.get("enabled") is not True:
        raise Denied("private carrier GitHub Actions is disabled or unavailable")
    if permissions.get("sha_pinning_required") is not True:
        raise Denied("private carrier must require immutable Action SHA pins")
    allowed = permissions.get("allowed_actions")
    if allowed == "selected":
        selected = gh_api(f"repos/{repository}/actions/permissions/selected-actions")
        if (selected.get("github_owned_allowed") is not True
                or selected.get("verified_allowed") is not False
                or selected.get("patterns_allowed") != []):
            raise Denied("private carrier selected Actions policy is too broad")
        action_policy = ("selected", True, False, ())
    elif allowed == "local_only":
        action_policy = ("local_only",)
    else:
        raise Denied("private carrier must not allow all third-party Actions")

    workflow = gh_api(f"repos/{repository}/actions/permissions/workflow")
    if (workflow.get("default_workflow_permissions") != "read"
            or workflow.get("can_approve_pull_request_reviews") is not False):
        raise Denied("private carrier GITHUB_TOKEN permissions are too broad")

    retention = gh_api(
        f"repos/{repository}/actions/permissions/artifact-and-log-retention")
    days = retention.get("days")
    maximum = retention.get("maximum_allowed_days")
    if (type(days) is not int or not 1 <= days <= MAX_CARRIER_RETENTION_DAYS
            or type(maximum) is not int or maximum < days):
        raise Denied("private carrier artifact/log retention exceeds security limit")

    return (
        destination["id"], permissions["sha_pinning_required"], action_policy,
        workflow["default_workflow_permissions"],
        workflow["can_approve_pull_request_reviews"], days,
    )


def require_preflight(repository, root):
    """Read trusted approval as data; never execute checkout-local Bash.

    A mutable path can be swapped after byte attestation but before Bash
    opens it. The publisher needs the same policy and live repository ID
    check, not a second executable interpreter entry point.
    """
    reviewed_policy = checked_policy_fingerprint(root, repository)
    destination = checked_repo(repository, expected_id=reviewed_policy[1])
    return destination["id"]


def checked_policy_fingerprint(root, repository):
    """Read approval only through directory descriptors and O_NOFOLLOW.

    A pathname symlink check followed by read_bytes() can be raced: the
    unapproved file can briefly be replaced with an approved symlink.
    """
    if (not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW")
            or not hasattr(os, "O_NONBLOCK")):
        raise Denied("OS cannot safely read the destination approval policy")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    policy_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    try:
        root_fd = os.open(root, directory_flags)
        try:
            policy_dir_fd = os.open(".github", directory_flags, dir_fd=root_fd)
            try:
                policy_fd = os.open("private-ci-approved-repositories.txt",
                                    policy_flags, dir_fd=policy_dir_fd)
                try:
                    stream = os.fdopen(policy_fd, "rb")
                except BaseException:
                    os.close(policy_fd)
                    raise
                with stream:
                    details = os.fstat(stream.fileno())
                    if not stat.S_ISREG(details.st_mode) or details.st_nlink != 1:
                        raise Denied("private CI approval policy is not a regular single-link file")
                    contents = stream.read(16 * 1024 + 1)
            finally:
                os.close(policy_dir_fd)
        finally:
            os.close(root_fd)
    except OSError as exc:
        raise Denied("private CI approval policy cannot be opened safely") from exc
    if len(contents) > 16 * 1024:
        raise Denied("private CI approval policy exceeds size limit")
    try:
        lines = contents.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise Denied("private CI approval policy cannot be decoded") from exc
    approved_id = None
    reviewed_names = set()
    reviewed_ids = set()
    for line in lines:
        if not line or line.startswith("#"):
            continue
        entry = POLICY_ENTRY.fullmatch(line)
        if entry is None:
            raise Denied("private CI approval policy requires exact owner/repo and numeric repository ID")
        name, repository_id = entry.groups()
        normalized = name.casefold()
        if normalized in reviewed_names or repository_id in reviewed_ids:
            raise Denied("private CI approval policy contains duplicate repository names or IDs")
        reviewed_names.add(normalized)
        reviewed_ids.add(repository_id)
        if normalized == repository.casefold():
            approved_id = int(repository_id)
    if approved_id is None:
        raise Denied("destination approval was revoked or absent")
    git_blob_sha = hashlib.sha1(
        b"blob " + str(len(contents)).encode("ascii") + b"\0" + contents
    ).hexdigest()
    return hashlib.sha256(contents).digest(), approved_id, git_blob_sha


def require_protected_source_codeowners(reviewed_main_sha):
    """Require terminal, exact security owners from the authenticated source commit.

    GitHub's require_code_owner_reviews=true is insufficient when no
    CODEOWNERS entry matches the private exporter or the snapshot policy.
    A final explicitly owned entry overrides any earlier wildcard.
    """
    metadata = gh_api(at_ref(CODEOWNERS_ROUTE, reviewed_main_sha))
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or metadata["size"] > 16 * 1024):
        raise Denied("protected source CODEOWNERS metadata unavailable")
    try:
        raw = base64.b64decode("".join(metadata["content"].splitlines()),
                               validate=True)
        text = raw.decode("utf-8")
    except (ValueError, UnicodeError) as exc:
        raise Denied("protected source CODEOWNERS content invalid") from exc
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                            + bytes([0]) + raw).hexdigest()
            != valid_sha(metadata.get("sha"))):
        raise Denied("protected source CODEOWNERS digest or size mismatch")
    effective = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            effective.append(stripped)
    required = [path + " " + REQUIRED_CODEOWNER
                for path in REQUIRED_CODEOWNER_PATHS]
    if effective[-len(required):] != required:
        raise Denied("private CI publisher and policies lack terminal CODEOWNER coverage")
    # The CODEOWNERS text can be syntactically well-formed yet ignored by
    # GitHub (for example, a non-existent owner or invalid pattern). Ask
    # GitHub's CODEOWNERS validator about the protected main revision.
    errors = gh_api(at_ref(CODEOWNERS_ERRORS_ROUTE, reviewed_main_sha))
    if not isinstance(errors.get("errors"), list) or errors["errors"]:
        raise Denied("protected source CODEOWNERS is invalid according to GitHub")
    # An @username entry counts only when the referenced GitHub user has
    # effective write access. Refuse a revoked or unavailable security owner.
    access = gh_api(CODEOWNER_PERMISSION_ROUTE)
    owner = access.get("user")
    if (not isinstance(owner, dict)
            or owner.get("login") != REQUIRED_CODEOWNER.lstrip("@")
            or owner.get("type") != "User"
            or access.get("permission") not in ("write", "admin")):
        raise Denied("CODEOWNER lacks independently verified repository write access")



def require_protected_carrier_codeowners(repository, carrier_main_sha):
    """Prove carrier workflows at the pinned main commit have a security owner.

    GitHub's require_code_owner_reviews flag can be true even when no
    CODEOWNERS rule covers the inherited Actions workflows. An unreviewed
    workflow would execute on a transferred private-source branch.
    """
    route = f"repos/{repository}"
    metadata = gh_api(at_ref(route + "/contents/.github/CODEOWNERS", carrier_main_sha))
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or not 0 <= metadata["size"] <= 16 * 1024):
        raise Denied("private carrier CODEOWNERS metadata is unavailable")
    try:
        raw = base64.b64decode("".join(metadata["content"].splitlines()),
                               validate=True)
        decoded = raw.decode("utf-8")
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier CODEOWNERS is not valid UTF-8/base64") from exc
    digest = hashlib.sha1(b"blob " + str(len(raw)).encode("ascii")
                          + bytes([0]) + raw).hexdigest()
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier CODEOWNERS blob integrity failed")
    effective = [line.strip() for line in decoded.splitlines()
                 if line.strip() and not line.strip().startswith("#")]
    # The last matching rule wins. The independent carrier source ledger,
    # CODEOWNERS itself, and all workflows must remain terminally owned.
    expected = [
        "/scripts/verify-oreslang-carrier-admin-policy.py " + REQUIRED_CODEOWNER,
        "/scripts/verify-oreslang-private-runner-attestation.py " + REQUIRED_CODEOWNER,
        "/" + CARRIER_APPROVAL_PATH + " " + REQUIRED_CODEOWNER,
        "/.github/CODEOWNERS " + REQUIRED_CODEOWNER,
        "/scripts/verify-oreslang-private-ci-snapshot.sh " + REQUIRED_CODEOWNER,
        "/scripts/launch-oreslang-private-ci-publisher.py " + REQUIRED_CODEOWNER,
        "/" + CARRIER_PUBLISHER_POLICY_PATH + " " + REQUIRED_CODEOWNER,
        "/" + CARRIER_BUILD_IMAGES_PATH + " " + REQUIRED_CODEOWNER,
        "/" + CARRIER_EXECUTION_PROFILE_PATH + " " + REQUIRED_CODEOWNER,
        "/" + CARRIER_WORKFLOW_MANIFEST_PATH + " " + REQUIRED_CODEOWNER,
        "/scripts/run-oreslang-private-build-in-sandbox.py " + REQUIRED_CODEOWNER,
        "/.github/workflows/ " + REQUIRED_CODEOWNER,
    ]
    if effective[-len(expected):] != expected:
        raise Denied("private carrier workflows lack terminal security CODEOWNER coverage")
    errors = gh_api(at_ref(route + "/codeowners/errors", carrier_main_sha))
    if not isinstance(errors.get("errors"), list) or errors["errors"]:
        raise Denied("private carrier CODEOWNERS has GitHub validation errors")
    access = gh_api(route + "/collaborators/ORESoftware/permission")
    owner = access.get("user")
    if (not isinstance(owner, dict)
            or owner.get("login") != REQUIRED_CODEOWNER.lstrip("@")
            or owner.get("type") != "User"
            or access.get("permission") not in ("write", "admin")):
        raise Denied("private carrier security CODEOWNER lacks write permission")


def require_carrier_publisher_policy(repository, carrier_main_sha,
                                     source_main_sha, publisher_blob_sha,
                                     reviewed_blob_sha=None):
    """Require carrier-reviewed executable identity for the source publisher."""
    valid_sha(carrier_main_sha)
    valid_sha(source_main_sha)
    valid_sha(publisher_blob_sha)
    metadata = gh_api(at_ref(
        f"repos/{repository}/contents/{CARRIER_PUBLISHER_POLICY_PATH}",
        carrier_main_sha))
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or not 0 < metadata["size"] <= 16 * 1024):
        raise Denied("private carrier publisher policy is unavailable")
    try:
        raw = base64.b64decode("".join(metadata["content"].splitlines()),
                               validate=True)
        lines = raw.decode("utf-8").splitlines()
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier publisher policy is invalid") from exc
    digest = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + bytes([0]) + raw
    ).hexdigest()
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier publisher policy integrity failed")
    if reviewed_blob_sha is not None and digest != reviewed_blob_sha:
        raise Denied("private carrier publisher policy changed during transfer")

    approvals = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = CARRIER_PUBLISHER_ENTRY.fullmatch(stripped)
        if match is None:
            raise Denied("invalid private carrier publisher policy entry")
        approvals.append(match.groups())
    if len(approvals) != 1:
        raise Denied("private carrier must approve exactly one publisher executable")
    if approvals[0] != (source_main_sha, publisher_blob_sha):
        raise Denied("private carrier publisher executable approval does not match bootstrap")
    return digest, source_main_sha, publisher_blob_sha


def require_carrier_source_approval(repository, expected_commit, expected_tree,
                                    carrier_main_sha, reviewed_blob_sha=None):
    """Require an exact source tuple from the authenticated carrier-main commit."""
    valid_sha(expected_commit)
    valid_sha(expected_tree)
    route = at_ref(
        f"repos/{repository}/contents/{CARRIER_APPROVAL_PATH}",
        carrier_main_sha)
    metadata = gh_api(route)
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or not 0 <= metadata["size"] <= 16 * 1024):
        raise Denied("private carrier source-approval ledger is unavailable")
    try:
        raw = base64.b64decode("".join(metadata["content"].splitlines()), validate=True)
        lines = raw.decode("utf-8").splitlines()
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier source-approval ledger is invalid") from exc
    digest = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + bytes([0]) + raw
    ).hexdigest()
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier source-approval ledger integrity failed")
    if reviewed_blob_sha is not None and digest != reviewed_blob_sha:
        raise Denied("private carrier source approval changed during transfer")

    approvals = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        match = CARRIER_SOURCE_ENTRY.fullmatch(line)
        if match is None:
            raise Denied("invalid private carrier source-approval entry")
        key = (match.group(1), match.group(2), int(match.group(3)))
        ruleset_id = int(match.group(4))
        if key in approvals:
            raise Denied("duplicate or ambiguous private carrier source approval")
        approvals[key] = ruleset_id
    key = (expected_commit, expected_tree, SOURCE_REPOSITORY_ID)
    ruleset_id = approvals.get(key)
    if ruleset_id is None:
        raise Denied("private carrier has not independently approved this source snapshot")
    return digest, ruleset_id


def require_carrier_sealed_build_images(repository, carrier_main_sha,
                                        reviewed_blob_sha=None):
    """Require exact preloaded, content-addressed images for offline source execution."""
    valid_sha(carrier_main_sha)
    route = at_ref(
        f"repos/{repository}/contents/{CARRIER_BUILD_IMAGES_PATH}",
        carrier_main_sha)
    metadata = gh_api(route)
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or not 0 <= metadata["size"] <= 16 * 1024):
        raise Denied("private carrier sealed build-image policy is unavailable")
    try:
        raw = base64.b64decode("".join(metadata["content"].splitlines()), validate=True)
        lines = raw.decode("utf-8").splitlines()
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier sealed build-image policy is invalid") from exc
    digest = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + bytes([0]) + raw
    ).hexdigest()
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier sealed build-image policy integrity failed")
    if reviewed_blob_sha is not None and digest != reviewed_blob_sha:
        raise Denied("private carrier sealed build-image policy changed during transfer")
    images = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        match = CARRIER_BUILD_IMAGE_ENTRY.fullmatch(line)
        if match is None or match.group(1) in images:
            raise Denied("private carrier sealed build-image policy is malformed or ambiguous")
        images[match.group(1)] = match.group(2)
    if set(images) != {"java21", "java25"}:
        raise Denied("private carrier sealed build images are not fully approved")
    return digest, images["java21"], images["java25"]


def require_carrier_execution_profile(repository, carrier_main_sha,
                                      expected_workflow_sha,
                                      reviewed_blob_sha=None):
    """Bind the actual funded carrier sandbox model to one reviewed workflow blob."""
    valid_sha(carrier_main_sha)
    valid_sha(expected_workflow_sha)
    metadata = gh_api(at_ref(
        f"repos/{repository}/contents/{CARRIER_EXECUTION_PROFILE_PATH}",
        carrier_main_sha))
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or not 0 <= metadata["size"] <= 16 * 1024):
        raise Denied("private carrier execution profile is unavailable")
    try:
        raw = base64.b64decode(
            "".join(metadata["content"].splitlines()), validate=True)
        lines = raw.decode("utf-8").splitlines()
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier execution profile is invalid") from exc
    digest = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + bytes([0]) + raw
    ).hexdigest()
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier execution profile integrity failed")
    if reviewed_blob_sha is not None and digest != reviewed_blob_sha:
        raise Denied("private carrier execution profile changed during transfer")

    meaningful = [line for line in lines if line and not line.startswith("#")]
    if len(meaningful) != 3:
        raise Denied("private carrier execution profile must contain exactly three directives")
    profile = meaningful[0].split(" ", 1)
    pom = meaningful[1].split(" ", 1)
    workflow = meaningful[2].split(" ", 2)
    if profile != ["profile", CARRIER_EXECUTION_PROFILE]:
        raise Denied("private carrier execution profile type is not approved")
    if (len(pom) != 2 or pom[0] != "trusted-pom-blob"
            or SHA.fullmatch(pom[1]) is None):
        raise Denied("private carrier trusted POM binding is invalid")
    if (len(workflow) != 3 or workflow[0] != "workflow"
            or workflow[1] != CARRIER_PRIVATE_WORKFLOW
            or workflow[2] != expected_workflow_sha):
        raise Denied("private carrier execution profile workflow binding changed")
    return digest, pom[1], workflow[2]


def require_source_pom_blob(expected_source_tree, trusted_pom_sha):
    """Require the approved source root to contain the exact trusted Maven POM."""
    valid_sha(expected_source_tree)
    valid_sha(trusted_pom_sha)
    tree = gh_api(
        f"repos/{SOURCE_REPOSITORY}/git/trees/{expected_source_tree}")
    entries = tree.get("tree") if isinstance(tree, dict) else None
    if (tree.get("sha") != expected_source_tree
            or tree.get("truncated") is not False
            or not isinstance(entries, list)):
        raise Denied("source root tree is unavailable for trusted POM verification")
    matches = [
        entry for entry in entries
        if isinstance(entry, dict) and entry.get("path") == "pom.xml"
    ]
    if (len(matches) != 1
            or matches[0].get("type") != "blob"
            or matches[0].get("mode") != "100644"
            or matches[0].get("sha") != trusted_pom_sha):
        raise Denied("source pom.xml differs from reviewed private-CI POM blob")
    return trusted_pom_sha


def require_carrier_workflow_manifest(repository, carrier_main_sha, carrier_tree_sha,
                                      reviewed_blob_sha=None):
    """Bind the complete carrier Actions workflow surface to reviewed blob SHAs."""
    valid_sha(carrier_main_sha)
    valid_sha(carrier_tree_sha)
    metadata = gh_api(at_ref(
        f"repos/{repository}/contents/{CARRIER_WORKFLOW_MANIFEST_PATH}",
        carrier_main_sha))
    if (metadata.get("type") != "file" or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 48 * 1024
            or type(metadata.get("size")) is not int
            or not 0 <= metadata["size"] <= 32 * 1024):
        raise Denied("private carrier workflow manifest is unavailable")
    try:
        raw = base64.b64decode("".join(metadata["content"].splitlines()), validate=True)
        lines = raw.decode("utf-8").splitlines()
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier workflow manifest is invalid") from exc
    digest = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + bytes([0]) + raw
    ).hexdigest()
    if (len(raw) != metadata["size"] or bytes([0]) in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier workflow manifest integrity failed")
    if reviewed_blob_sha is not None and digest != reviewed_blob_sha:
        raise Denied("private carrier workflow manifest changed during transfer")

    approved = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        match = CARRIER_WORKFLOW_ENTRY.fullmatch(line)
        if match is None or match.group(1) in approved:
            raise Denied("private carrier workflow manifest is malformed or ambiguous")
        approved[match.group(1)] = match.group(2)
    if not approved or len(approved) > 16:
        raise Denied("private carrier workflow manifest must list 1..16 workflows")

    tree = gh_api(f"repos/{repository}/git/trees/{carrier_tree_sha}?recursive=1")
    if (tree.get("sha") != carrier_tree_sha or tree.get("truncated") is not False
            or not isinstance(tree.get("tree"), list)):
        raise Denied("private carrier recursive tree is unavailable or truncated")
    actual = {}
    for entry in tree["tree"]:
        if not isinstance(entry, dict):
            raise Denied("private carrier recursive tree contains malformed entries")
        path = entry.get("path")
        if (isinstance(path, str)
                and path.startswith(".github/workflows/")
                and (path.endswith(".yml") or path.endswith(".yaml"))):
            if (entry.get("type") != "blob" or entry.get("mode") not in ("100644", "100755")
                    or path in actual):
                raise Denied("private carrier workflow tree entry is invalid")
            actual[path] = valid_sha(entry.get("sha"))
    if actual != approved:
        raise Denied("private carrier workflow surface differs from reviewed manifest")
    return digest, tuple(sorted(actual.items()))


def require_carrier_source_ruleset(repository, ruleset_id):
    """Require immutable ci/oreslang-* refs before any source bytes are sent."""
    if type(ruleset_id) is not int or ruleset_id <= 0:
        raise Denied("private carrier source ruleset ID is invalid")
    ruleset = gh_api(f"repos/{repository}/rulesets/{ruleset_id}")
    if (type(ruleset.get("id")) is not int or ruleset["id"] != ruleset_id
            or ruleset.get("target") != "branch"
            or ruleset.get("enforcement") != "active"
            or ruleset.get("source_type") != "Repository"
            or not isinstance(ruleset.get("source"), str)
            or ruleset["source"].casefold() != repository.casefold()):
        raise Denied("private carrier source-branch ruleset identity is not enforced")
    bypass = ruleset.get("bypass_actors")
    if not isinstance(bypass, list) or bypass:
        raise Denied("private carrier source-branch ruleset permits bypass")
    conditions = ruleset.get("conditions")
    ref_name = conditions.get("ref_name") if isinstance(conditions, dict) else None
    if (not isinstance(ref_name, dict)
            or ref_name.get("include") != [
                "refs/heads/ci/authorized-*",
                "refs/heads/ci/oreslang-*",
            ]
            or ref_name.get("exclude") != []):
        raise Denied("private carrier ruleset does not exclusively target Oreslang CI refs")
    rules = ruleset.get("rules")
    if not isinstance(rules, list) or not all(isinstance(rule, dict) for rule in rules):
        raise Denied("private carrier source-branch rules are unavailable")
    types = [rule.get("type") for rule in rules]
    if "creation" in types:
        raise Denied("private carrier ruleset unexpectedly blocks reviewed branch creation")
    if types.count("update") != 1 or types.count("deletion") != 1:
        raise Denied("private carrier Oreslang refs must forbid updates and deletion")
    update = next(rule for rule in rules if rule.get("type") == "update")
    params = update.get("parameters")
    if (not isinstance(params, dict)
            or params.get("update_allows_fetch_and_merge") is not False):
        raise Denied("private carrier Oreslang ref update rule is not fully immutable")
    return ruleset_id


def require_effective_carrier_source_rules(repository, branch, ruleset_id):
    """Prove GitHub applies the reviewed immutable ruleset to this exact CI ref."""
    if not isinstance(branch, str) or IMMUTABLE_CARRIER_BRANCH.fullmatch(branch) is None:
        raise Denied("private carrier effective-rules branch is invalid")
    if type(ruleset_id) is not int or ruleset_id <= 0:
        raise Denied("private carrier source ruleset ID is invalid")
    # Branch is validated above and contains only one slash plus hex/hyphen.
    # Encode the slash so GitHub interprets the complete ref name as one path parameter.
    encoded_branch = branch.replace("/", "%2F")
    effective = gh_api(
        f"repos/{repository}/rules/branches/{encoded_branch}?per_page=100&page=1")
    # A full first page may conceal another page with a conflicting rule.
    # Never authorize partial active-rule evidence to export private files.
    if (not isinstance(effective, list) or not effective
            or len(effective) >= 100
            or not all(isinstance(rule, dict) for rule in effective)):
        raise Denied("effective private carrier source-branch rules are unavailable or incomplete")
    # A second active ruleset can independently reject the new branch or
    # commit even when the reviewed immutable ruleset itself allows creation.
    # Fail closed on rule semantics this publisher has not explicitly audited:
    # otherwise GitHub can reject the final ref only after private blobs/trees
    # have already been uploaded. Linear history is compatible with the
    # publisher's single-parent snapshot commit; update/deletion are the exact
    # immutable-ref controls required below.
    allowed_effective_types = {"update", "deletion", "required_linear_history"}
    unexpected_types = {
        rule.get("type") for rule in effective
        if rule.get("type") not in allowed_effective_types
    }
    if unexpected_types:
        raise Denied("effective private carrier policy has unsupported branch rules")
    matched = [
        rule for rule in effective
        if type(rule.get("ruleset_id")) is int
        and rule["ruleset_id"] == ruleset_id
        and rule.get("ruleset_source_type") == "Repository"
        and isinstance(rule.get("ruleset_source"), str)
        and rule["ruleset_source"].casefold() == repository.casefold()
    ]
    types = [rule.get("type") for rule in matched]
    if types.count("update") != 1 or types.count("deletion") != 1:
        raise Denied("reviewed carrier ruleset is not effective for the exact CI branch")
    update = next(rule for rule in matched if rule.get("type") == "update")
    params = update.get("parameters")
    if (not isinstance(params, dict)
            or params.get("update_allows_fetch_and_merge") is not False):
        raise Denied("effective private carrier update rule is not immutable")
    return ruleset_id


def require_absent_carrier_source_ref(repository, branch):
    """Reject a pre-existing or prefix-colliding CI ref before private writes."""
    if not isinstance(branch, str) or IMMUTABLE_CARRIER_BRANCH.fullmatch(branch) is None:
        raise Denied("private carrier source-ref branch is invalid")
    matches = gh_api(
        f"repos/{repository}/git/matching-refs/heads/{branch}?per_page=1&page=1")
    if not isinstance(matches, list):
        raise Denied("private carrier source-ref lookup is unavailable")
    if matches:
        raise Denied("private carrier source ref already exists or collides with reserved prefix")


def require_protected_main_policy(approved_policy):
    """Require a known private source and protected main, not just its blob.

    A matching policy blob on an unprotected branch cannot prove approval:
    direct pushes could change that branch without independent review.
    """
    source = gh_api(SOURCE_METADATA_ROUTE)
    if (source.get("full_name") != SOURCE_REPOSITORY
            or type(source.get("id")) is not int
            or source["id"] != SOURCE_REPOSITORY_ID
            or source.get("private") is not True
            or source.get("visibility") != "private"
            or source.get("archived") is not False
            or source.get("disabled") is not False
            or source.get("default_branch") != "main"):
        raise Denied("source repository identity or private status is not verified")
    branch = gh_api(PROTECTED_BRANCH_ROUTE)
    if (branch.get("name") != "main"
            or branch.get("protected") is not True
            or not isinstance(branch.get("commit"), dict)):
        raise Denied("source main branch is not protected")
    protected_main_sha = valid_sha(branch["commit"].get("sha"))
    rules = gh_api(PROTECTED_RULES_ROUTE)
    require_hardened_branch_rules(rules, "protected source main")
    # An unresolved security review thread must be able to block the policy
    # change before the private publisher trusts it. Do not infer this from
    # required approving reviews: GitHub exposes the gate separately.
    conversation_gate = rules.get("required_conversation_resolution")
    if (not isinstance(conversation_gate, dict)
            or conversation_gate.get("enabled") is not True):
        raise Denied("protected main must require resolved review conversations")
    reviews = gh_api(PROTECTED_REVIEWS_ROUTE)
    count = reviews.get("required_approving_review_count")
    if (type(count) is not int or count < 1
            or reviews.get("require_code_owner_reviews") is not True
            or reviews.get("dismiss_stale_reviews") is not True):
        raise Denied("enforced independent code-owner review policy is unavailable")
    allowances = reviews.get("bypass_pull_request_allowances")
    if allowances is not None:
        if not isinstance(allowances, dict) or any(
                not isinstance(allowances.get(key, []), list)
                or allowances.get(key) for key in ("users", "teams", "apps")):
            raise Denied("branch review bypass allowances are not permitted")
    require_protected_source_codeowners(protected_main_sha)
    metadata = gh_api(at_ref(PROTECTED_POLICY_ROUTE, protected_main_sha))
    if (metadata.get("type") != "file"
            or valid_sha(metadata.get("sha")) != approved_policy[2]):
        raise Denied("destination policy is not approved on protected source main")
    return protected_main_sha

def require_trusted_publisher_checkout(
        root, reviewed_main_sha, expected_publisher_blob_sha=None):
    """Enforce execution from the independently authenticated source main commit.

    A developer's PR checkout cannot self-authorize its publisher or policy.
    The untrusted test snapshot must be staged separately under this trusted
    checkout and its complete bytes checked against the reviewed Git tree.
    """
    valid_sha(reviewed_main_sha)
    if expected_publisher_blob_sha is not None:
        valid_sha(expected_publisher_blob_sha)
    base = Path(root).resolve(strict=True)
    def git(*args):
        try:
            response = subprocess.run(
                ["git", "--no-replace-objects", "-c", "core.fsmonitor=false",
                 "-c", "core.hooksPath=/dev/null", "-C", str(base), *args],
                capture_output=True, text=True, env=verified_child_env(),
                timeout=12, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise Denied("trusted source checkout Git proof unavailable") from exc
        if response.returncode != 0:
            raise Denied("trusted source checkout Git proof failed")
        return response.stdout.strip()
    if git("rev-parse", "--show-toplevel") != str(base):
        raise Denied("publisher is not running from trusted source repository root")
    if git("rev-parse", "HEAD") != reviewed_main_sha:
        raise Denied("publisher is not executing reviewed protected-main commit")
    protected_paths = (
        PUBLISHER_PATH,
        "scripts/run-in-private-ci-destination.sh",
        "scripts/check-private-ci-destination.sh",
        ".github/private-ci-approved-repositories.txt",
        ".github/private-ci-approved-source-snapshots.txt",
    )
    # Git status is NOT an integrity proof. A caller can mark an index entry
    # --skip-worktree or --assume-unchanged and edit its executable bytes while
    # status --porcelain reports a clean checkout. Compare the real file
    # bytes with the blob named by the pinned, non-replaced HEAD tree.
    if not all(hasattr(os, flag) for flag in ("O_DIRECTORY", "O_NOFOLLOW", "O_NONBLOCK")):
        raise Denied("OS cannot safely authenticate publisher files")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    file_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    try:
        root_fd = os.open(base, directory_flags)
        try:
            for rel in protected_paths:
                tracked = git("ls-tree", "HEAD", "--", rel)
                metadata, separator, actual_path = tracked.partition("\t")
                fields = metadata.split(" ")
                if (not separator or actual_path != rel or len(fields) != 3
                        or fields[0] not in ("100644", "100755")
                        or fields[1] != "blob"):
                    raise Denied("publisher file is not a regular tracked Git blob")
                expected_sha = valid_sha(fields[2])
                if (rel == PUBLISHER_PATH
                        and expected_publisher_blob_sha is not None
                        and expected_sha != expected_publisher_blob_sha):
                    raise Denied(
                        "bootstrap publisher blob differs from protected source main")
                parent_fd = os.dup(root_fd)
                try:
                    components = rel.split("/")
                    for component in components[:-1]:
                        next_fd = os.open(component, directory_flags, dir_fd=parent_fd)
                        os.close(parent_fd)
                        parent_fd = next_fd
                    leaf_fd = os.open(components[-1], file_flags, dir_fd=parent_fd)
                    try:
                        before = os.fstat(leaf_fd)
                        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                                or before.st_size > 2 * 1024 * 1024
                                or ("100755" if before.st_mode & stat.S_IXUSR
                                    else "100644") != fields[0]):
                            raise Denied("publisher file type, permissions or size changed")
                        with os.fdopen(leaf_fd, "rb", closefd=False) as stream:
                            raw = stream.read(2 * 1024 * 1024 + 1)
                        after = os.fstat(leaf_fd)
                        if (len(raw) != before.st_size
                                or (before.st_dev, before.st_ino, before.st_size,
                                    before.st_mtime_ns, before.st_ctime_ns)
                                != (after.st_dev, after.st_ino, after.st_size,
                                    after.st_mtime_ns, after.st_ctime_ns)):
                            raise Denied("publisher file changed during authentication")
                    finally:
                        os.close(leaf_fd)
                finally:
                    os.close(parent_fd)
                actual_sha = hashlib.sha1(
                    b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
                ).hexdigest()
                if actual_sha != expected_sha:
                    raise Denied("reviewed publisher code or policy bytes differ from HEAD")
        finally:
            os.close(root_fd)
    except OSError as exc:
        raise Denied("publisher file cannot be opened without following links") from exc
    return reviewed_main_sha


def verify_source_commit(expected_source_commit, expected_source_tree):
    """Bind the exact private source repository commit to its Git tree.

    A matching arbitrary worktree digest is not independently authenticated
    source provenance. Refuse before reading or publishing source bytes if the
    immutable commit is absent, forged, or points to another tree.
    """
    valid_sha(expected_source_commit)
    valid_sha(expected_source_tree)
    commit = gh_api(
        f"repos/{SOURCE_REPOSITORY}/git/commits/{expected_source_commit}"
    )
    if commit.get("sha") != expected_source_commit:
        raise Denied("source commit identity differs from pinned immutable SHA")
    if nested_sha(commit, "tree") != expected_source_tree:
        raise Denied("source commit tree differs from pinned expected SHA")


def require_reviewed_source_snapshot(expected_commit, expected_tree, destination_id,
                                     reviewed_main_sha, reviewed_blob_sha=None):
    """Require source + destination + carrier revision from one reviewed source commit.

    Source authorization is invalid if the carrier workflows/root tree change:
    a new protected carrier main commit/tree requires fresh source-side review.
    """
    valid_sha(expected_commit)
    valid_sha(expected_tree)
    if type(destination_id) is not int or destination_id <= 0:
        raise Denied("expected numeric destination repository ID is invalid")
    metadata = gh_api(at_ref(REVIEWED_SNAPSHOTS_ROUTE, reviewed_main_sha))
    if metadata.get("type") != "file" or metadata.get("encoding") != "base64":
        raise Denied("reviewed source-snapshot policy is absent or malformed")
    encoded = metadata.get("content")
    if not isinstance(encoded, str) or len(encoded) > 24 * 1024:
        raise Denied("reviewed source-snapshot policy is too large")
    try:
        raw = base64.b64decode("".join(encoded.splitlines()), validate=True)
        lines = raw.decode("utf-8").splitlines()
    except (ValueError, UnicodeError) as exc:
        raise Denied("reviewed source-snapshot policy is not valid UTF-8/base64") from exc
    if len(raw) > 16 * 1024 or metadata.get("size") != len(raw):
        raise Denied("reviewed source-snapshot policy has invalid size")
    digest = hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()
    if valid_sha(metadata.get("sha")) != digest:
        raise Denied("reviewed source-snapshot policy blob SHA does not match its bytes")
    if reviewed_blob_sha is not None and digest != reviewed_blob_sha:
        raise Denied("reviewed source-snapshot approval changed during transfer")
    approvals = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        match = SOURCE_SNAPSHOT_ENTRY.fullmatch(line)
        if match is None:
            raise Denied("invalid reviewed source-snapshot policy entry")
        key = (match.group(1), match.group(2), int(match.group(3)))
        carrier = (match.group(4), match.group(5))
        if key in approvals:
            raise Denied("duplicate or ambiguous source-snapshot approval")
        approvals[key] = carrier
    key = (expected_commit, expected_tree, destination_id)
    carrier = approvals.get(key)
    if carrier is None:
        raise Denied("immutable source snapshot is not independently approved")
    valid_sha(carrier[0])
    valid_sha(carrier[1])
    return digest, carrier[0], carrier[1]


def collect_source(root, trusted_root):
    """Snapshot regular files using descriptor-relative, no-follow traversal.

    O_NOFOLLOW on the final file is insufficient when a parent directory can
    be replaced with a symlink between path validation and file open. All
    descendants are opened relative to directory descriptors anchored at
    the trusted checkout, so a rename cannot redirect them outside it.
    """
    if (not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY")
            or not hasattr(os, "O_NONBLOCK")):
        raise Denied("OS cannot enforce descriptor-relative no-follow reads")
    if root.is_symlink() or not root.is_dir():
        raise Denied("source path must be a real directory")
    try:
        trusted = trusted_root.resolve(strict=True)
        source = root.resolve(strict=True)
        relative = source.relative_to(trusted)
    except (OSError, RuntimeError, ValueError) as exc:
        raise Denied("source directory escapes trusted checkout") from exc

    directories = []
    entries = []
    total = 0
    visited = 0
    entered_dirs = 0
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    file_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK

    def walk(directory_fd, prefix, depth):
        nonlocal total, visited, entered_dirs
        entered_dirs += 1
        directory_before = os.fstat(directory_fd)
        if not stat.S_ISDIR(directory_before.st_mode):
            raise Denied("source directory changed during traversal")
        if depth > 64 or entered_dirs > MAX_DIRECTORIES:
            raise Denied("source directory nesting or count exceeds safety limit")
        # scandir() can stream entries from an opened descriptor. Do not use
        # os.listdir(), which materializes an unbounded directory before any
        # quota can be checked.
        names = []
        try:
            with os.scandir(directory_fd) as scan:
                for entry in scan:
                    visited += 1
                    if visited > MAX_DIRECTORY_ENTRIES:
                        raise Denied("source directory entry count exceeds safety limit")
                    names.append(entry.name)
        except OSError as exc:
            raise Denied("source directory cannot be listed safely") from exc
        names.sort()
        for name in names:
            if (name in (".", "..", ".git")
                    or any(ord(ch) < 32 or ord(ch) == 127 for ch in name)
                    or "\\" in name):
                raise Denied("source path contains unsupported name")
            try:
                name.encode("utf-8", errors="strict")
            except UnicodeEncodeError as exc:
                raise Denied("source path contains non-UTF-8 name") from exc
            rel = prefix + name
            try:
                metadata = os.stat(name, dir_fd=directory_fd,
                                   follow_symlinks=False)
                if stat.S_ISDIR(metadata.st_mode):
                    child_fd = os.open(name, directory_flags, dir_fd=directory_fd)
                    try:
                        opened_dir = os.fstat(child_fd)
                        if (not stat.S_ISDIR(opened_dir.st_mode)
                                or (metadata.st_dev, metadata.st_ino,
                                    metadata.st_mtime_ns, metadata.st_ctime_ns)
                                != (opened_dir.st_dev, opened_dir.st_ino,
                                    opened_dir.st_mtime_ns, opened_dir.st_ctime_ns)):
                            raise Denied("source directory changed during traversal")
                        walk(child_fd, rel + "/", depth + 1)
                    finally:
                        os.close(child_fd)
                elif stat.S_ISREG(metadata.st_mode):
                    file_fd = os.open(name, file_flags, dir_fd=directory_fd)
                    try:
                        stream = os.fdopen(file_fd, "rb")
                    except BaseException:
                        os.close(file_fd)
                        raise
                    with stream:
                        opened_file = os.fstat(stream.fileno())
                        if (not stat.S_ISREG(opened_file.st_mode)
                                or (metadata.st_dev, metadata.st_ino,
                                    metadata.st_size, metadata.st_mtime_ns,
                                    metadata.st_ctime_ns, metadata.st_mode)
                                != (opened_file.st_dev, opened_file.st_ino,
                                    opened_file.st_size, opened_file.st_mtime_ns,
                                    opened_file.st_ctime_ns, opened_file.st_mode)):
                            raise Denied("source file changed during traversal")
                        # A hardlink may refer to outside-checkout bytes.
                        if opened_file.st_nlink != 1:
                            raise Denied("hardlinked source files are not allowed")
                        if opened_file.st_size > MAX_SINGLE:
                            raise Denied("file exceeds source mirror limit")
                        data = stream.read(MAX_SINGLE + 1)
                        after_read = os.fstat(stream.fileno())
                        if (len(data) != opened_file.st_size
                                or (opened_file.st_size, opened_file.st_mtime_ns,
                                    opened_file.st_ctime_ns, opened_file.st_mode)
                                != (after_read.st_size, after_read.st_mtime_ns,
                                    after_read.st_ctime_ns, after_read.st_mode)):
                            raise Denied("source file changed while snapshotting")
                    if len(data) > MAX_SINGLE:
                        raise Denied("file exceeds source mirror limit")
                    total += len(data)
                    if total > MAX_BYTES or len(entries) >= MAX_FILES:
                        raise Denied("source mirror exceeds bounded capacity")
                    mode = "100755" if opened_file.st_mode & stat.S_IXUSR else "100644"
                    entries.append((rel, data, mode))
                else:
                    raise Denied("source contains symlink or special file")
            except OSError as exc:
                raise Denied("source path changed or cannot be opened safely") from exc
        directory_after = os.fstat(directory_fd)
        if ((directory_before.st_dev, directory_before.st_ino,
                directory_before.st_mtime_ns, directory_before.st_ctime_ns)
                != (directory_after.st_dev, directory_after.st_ino,
                    directory_after.st_mtime_ns, directory_after.st_ctime_ns)):
            raise Denied("source directory changed during traversal")

    try:
        anchor_fd = os.open(trusted, directory_flags)
        directories.append(anchor_fd)
        for component in relative.parts:
            child_fd = os.open(component, directory_flags,
                               dir_fd=directories[-1])
            directories.append(child_fd)
        walk(directories[-1], "", 0)
    except OSError as exc:
        raise Denied("source checkout path changed during traversal") from exc
    finally:
        for fd in reversed(directories):
            os.close(fd)
    if not entries:
        raise Denied("source directory is empty")
    return sorted(entries, key=lambda item: item[0])


def source_tree_sha(files):
    """Compute the exact Git SHA-1 tree for the frozen source bytes and modes."""
    root = {}
    for relative, data, mode in files:
        # This helper also has direct callers in tests and tooling. Refuse
        # impossible file/directory aliasing regardless of input order:
        # silently replacing a directory with a file loses snapshot bytes.
        if (not isinstance(relative, str)
                or not relative
                or not isinstance(data, bytes)
                or mode not in ("100644", "100755")):
            raise Denied("source snapshot contains invalid Git tree entry")
        parts = relative.split("/")
        # Match collect_source(): Git paths cannot contain controls, DEL,
        # backslashes or invalid UTF-8, even when supplied directly through
        # a manifest rather than discovered from the filesystem.
        if any(part in ("", ".", "..", ".git")
               or "\\" in part
               or any(ord(char) < 32 or ord(char) == 127 for char in part)
               for part in parts):
            raise Denied("source snapshot contains invalid Git tree path")
        try:
            relative.encode("utf-8", errors="strict")
        except UnicodeEncodeError as exc:
            raise Denied("source snapshot contains invalid UTF-8 Git tree path") from exc
        node = root
        for part in parts[:-1]:
            existing = node.get(part)
            if existing is None:
                existing = {}
                node[part] = existing
            if not isinstance(existing, dict):
                raise Denied("source snapshot contains conflicting file/directory names")
            node = existing
        if parts[-1] in node:
            raise Denied("source snapshot contains conflicting file/directory names")
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii")
                            + b"\0" + data).digest()
        node[parts[-1]] = (mode, blob)

    def hash_directory(directory):
        entries = []
        for name, value in sorted(
                directory.items(),
                key=lambda item: (item[0] + ("/" if isinstance(item[1], dict) else "")).encode("utf-8")):
            if isinstance(value, dict):
                mode, digest = b"40000", hash_directory(value)
            else:
                mode, digest = value[0].encode("ascii"), value[1]
            entries.append(mode + b" " + name.encode("utf-8") + b"\0" + digest)
        raw = b"".join(entries)
        return hashlib.sha1(b"tree " + str(len(raw)).encode("ascii")
                            + b"\0" + raw).digest()

    return hash_directory(root).hex()

# Inline only bounded, valid UTF-8 file contents in the Git Trees API. GitHub
# creates the corresponding blob objects atomically with each tree POST.
# Binary and large files still use individually verified Git blob uploads.
TREE_INLINE_MIN_FILES = 16
TREE_INLINE_MAX_BYTES = 128 * 1024
TREE_BATCH_MAX_ENTRIES = 64
TREE_BATCH_MAX_BYTES = 256 * 1024


def prepare_source_tree_batches(files):
    """Bound request size and preserve per-write admission for every batch.

    Partial trees are constructed only from prior *isolated source trees*,
    never from the carrier's main tree. The final SHA must still equal the
    independently pinned full source Git tree before a branch is created.
    """
    allow_inline = len(files) >= TREE_INLINE_MIN_FILES
    batches = []
    batch = []
    size = 0
    for rel, data, mode in files:
        as_text = None
        if allow_inline and len(data) <= TREE_INLINE_MAX_BYTES and b"\x00" not in data:
            try:
                as_text = data.decode("utf-8")
            except UnicodeDecodeError:
                pass
        # Charge the serialized JSON payload rather than raw UTF-8 bytes.
        # Escaped control characters and non-ASCII text can multiply size.
        path_bytes = len(json.dumps(rel).encode("utf-8"))
        content_bytes = (len(json.dumps(as_text).encode("utf-8"))
                         if as_text is not None else 40)
        item_size = content_bytes + path_bytes + 128
        if item_size > TREE_BATCH_MAX_BYTES and as_text is not None:
            as_text = None
            item_size = 40 + path_bytes + 128
        if item_size > TREE_BATCH_MAX_BYTES:
            raise Denied("private CI Git tree entry exceeds request size limit")
        if batch and (len(batch) >= TREE_BATCH_MAX_ENTRIES
                      or size + item_size > TREE_BATCH_MAX_BYTES):
            batches.append(batch)
            batch = []
            size = 0
        batch.append((rel, data, mode, as_text))
        size += item_size
    if batch:
        batches.append(batch)
    return batches


def require_private_ci_api_budget(file_count, estimated_writes=None):
    """Reserve per-write authorization calls, including partial tree batches."""
    if type(file_count) is not int or file_count < 1 or file_count > MAX_FILES:
        raise Denied("private CI snapshot file count invalid")
    if estimated_writes is None:
        estimated_writes = file_count
    if (type(estimated_writes) is not int or estimated_writes < 1
            or estimated_writes > 2 * MAX_FILES):
        raise Denied("private CI estimated write count invalid")
    budget = gh_api("rate_limit")
    if not isinstance(budget, dict):
        raise Denied("GitHub core API budget unavailable")
    resources = budget.get("resources")
    core = resources.get("core") if isinstance(resources, dict) else None
    remaining = core.get("remaining") if isinstance(core, dict) else None
    if type(remaining) is not int or remaining < 0:
        raise Denied("GitHub core API budget unavailable")
    # Each write revalidates both source and carrier CODEOWNERS plus all
    # immutable approvals. Allow headroom for those extra core API reads.
    # Actions/retention posture adds up to five authenticated reads to every
    # per-write admission. Reserve them rather than discovering rate exhaustion
    # after private Git objects have already been created.
    required = 30 * estimated_writes + 192
    if remaining < required:
        raise Denied("insufficient GitHub core API budget for safe private CI export")


def publish(repository, source, branch, trusted_root, expected_source_tree,
            expected_source_commit):
    # Git ref-format restrictions are stricter than the character allowlist.
    # Reject impossible refs before reading private source or creating blobs:
    # Git disallows '..', a trailing '.', and the '.lock' suffix.
    branch_match = BRANCH.fullmatch(branch) if isinstance(branch, str) else None
    if not NAME.fullmatch(repository) or branch_match is None:
        raise Denied("invalid destination or isolated CI branch name")
    valid_sha(expected_source_tree)
    valid_sha(expected_source_commit)
    bootstrap_blob_sha = require_bootstrap_attestation(trusted_root)
    if branch_match.group(1) != expected_source_commit:
        raise Denied("isolated CI branch must encode the full approved source commit SHA")
    # The exact ref also binds the carrier-main revision, which is authenticated
    # below. Malformed refs and source-SHA mismatches fail before policy/source reads.
    # This function previously ran the checkout-local Bash preflight BEFORE
    # authenticating the checkout. A modified PR checkout could execute an
    # attacker-controlled guard even though source export would later deny.
    # Read policy strictly as data and authenticate the protected source first.
    approved_policy = checked_policy_fingerprint(trusted_root, repository)
    # A PR branch can contain an allowlist entry without security approval.
    # Require the exact policy Git blob to exist on protected source main.
    reviewed_main_sha = require_protected_main_policy(approved_policy)
    require_trusted_publisher_checkout(
        trusted_root, reviewed_main_sha, bootstrap_blob_sha)
    # No checkout-local shell runs here. Revalidate the approved policy data
    # and pinned private destination identity without interpreting local code.
    # All failures above occur before private source reads or Git writes.
    approved_destination_id = require_preflight(repository, trusted_root)
    if approved_policy[1] != approved_destination_id:
        raise Denied("destination numeric ID differs from reviewed approval policy")
    reviewed_snapshot_sha, approved_carrier_main_sha, approved_carrier_tree = (
        require_reviewed_source_snapshot(
            expected_source_commit, expected_source_tree, approved_destination_id,
            reviewed_main_sha)
    )
    verify_source_commit(expected_source_commit, expected_source_tree)
    trusted = trusted_root.resolve(strict=True)
    checked_source = source.resolve(strict=True)
    try:
        checked_source.relative_to(trusted)
    except ValueError as exc:
        raise Denied("source directory escapes the trusted checkout") from exc
    base = checked_repo(repository, expected_id=approved_destination_id)
    carrier_actions_posture = require_carrier_actions_posture(
        repository, approved_destination_id)
    # Never inherit a caller-selected or unreviewed carrier branch: inherited
    # workflow definitions execute with the destination repository's token.
    if base.get("default_branch") != "main":
        raise Denied("private carrier default branch is not trusted main")
    def require_pinned_carrier_main(expected_sha=None, expected_tree=None):
        ref = gh_api(f"repos/{repository}/git/ref/heads/main")
        current_sha = nested_sha(ref, "object")
        if (ref.get("ref") != "refs/heads/main"
                or ref["object"].get("type") != "commit"):
            raise Denied("private carrier main ref is not a verified commit")
        if expected_sha is not None and current_sha != expected_sha:
            raise Denied("private carrier main changed during source transfer")
        # The carrier main tree supplies CI workflows executed with the
        # carrier's own token. Never inherit workflows from an unprotected
        # branch that could change without an independently reviewed PR.
        carrier_main = gh_api(f"repos/{repository}/branches/main")
        if (carrier_main.get("name") != "main"
                or carrier_main.get("protected") is not True
                or not isinstance(carrier_main.get("commit"), dict)
                or valid_sha(carrier_main["commit"].get("sha")) != current_sha):
            raise Denied("private carrier main is not protected and pinned")
        # A protected=true flag alone does not guarantee that the destination
        # workflow definitions were independently reviewed. The carrier's
        # inherited root .github/workflows can read the private source on a
        # CI branch, so require an enforced two-party review boundary before
        # reading or uploading even one private source byte.
        carrier_rules = gh_api(f"repos/{repository}/branches/main/protection")
        require_hardened_branch_rules(carrier_rules, "private carrier main")
        conversations = carrier_rules.get("required_conversation_resolution")
        if (not isinstance(conversations, dict)
                or conversations.get("enabled") is not True):
            raise Denied("private carrier main must enforce resolved reviews")
        carrier_reviews = gh_api(
            f"repos/{repository}/branches/main/protection/required_pull_request_reviews")
        approvals = carrier_reviews.get("required_approving_review_count")
        if (type(approvals) is not int or approvals < 1
                or carrier_reviews.get("require_code_owner_reviews") is not True
                or carrier_reviews.get("dismiss_stale_reviews") is not True):
            raise Denied("private carrier main lacks independent code-owner approval")
        bypass = carrier_reviews.get("bypass_pull_request_allowances")
        if bypass is not None:
            if not isinstance(bypass, dict) or any(
                    not isinstance(bypass.get(key, []), list) or bypass.get(key)
                    for key in ("users", "teams", "apps")):
                raise Denied("private carrier main allows review bypass")
        require_protected_carrier_codeowners(repository, current_sha)
        if expected_tree is not None:
            valid_sha(expected_tree)
            carrier_commit = gh_api(f"repos/{repository}/git/commits/{current_sha}")
            if (carrier_commit.get("sha") != current_sha
                    or nested_sha(carrier_commit, "tree") != expected_tree):
                raise Denied("private carrier main tree differs from reviewed source approval")
        return current_sha
    base_sha = require_pinned_carrier_main(
        approved_carrier_main_sha, approved_carrier_tree)
    carrier_publisher = require_carrier_publisher_policy(
        repository, base_sha, reviewed_main_sha, bootstrap_blob_sha)
    carrier_images = require_carrier_sealed_build_images(repository, base_sha)
    expected_branch = "ci/oreslang-" + expected_source_commit + "-" + base_sha
    if branch != expected_branch:
        raise Denied(
            "isolated CI branch must encode the full approved source and carrier-main SHAs")
    carrier_approval_sha, carrier_ruleset_id = require_carrier_source_approval(
        repository, expected_source_commit, expected_source_tree, base_sha)
    carrier_workflows = require_carrier_workflow_manifest(
        repository, base_sha, approved_carrier_tree)
    workflow_map = dict(carrier_workflows[1])
    private_workflow_sha = workflow_map.get(CARRIER_PRIVATE_WORKFLOW)
    if private_workflow_sha is None:
        raise Denied("private carrier workflow manifest omits source execution workflow")
    carrier_execution = require_carrier_execution_profile(
        repository, base_sha, private_workflow_sha)
    require_source_pom_blob(expected_source_tree, carrier_execution[1])
    require_carrier_source_ruleset(repository, carrier_ruleset_id)
    require_effective_carrier_source_rules(
        repository, branch, carrier_ruleset_id)
    require_absent_carrier_source_ref(repository, branch)
    # The ledger read itself must come from the same carrier main revision
    # that the source-side approval pinned.
    require_pinned_carrier_main(base_sha, approved_carrier_tree)
    base_commit = gh_api(f"repos/{repository}/git/commits/{base_sha}")
    base_tree = nested_sha(base_commit, "tree")
    if base_commit.get("sha") != base_sha:
        raise Denied("private carrier main commit identity differs from pinned ref")
    files = collect_source(source, trusted_root)
    if source_tree_sha(files) != expected_source_tree:
        raise Denied("source snapshot Git tree differs from pinned expected SHA")
    # Fail before any mutable GitHub object is created rather than leaving
    # partial private blobs after a predictable core-rate-limit exhaustion.
    batches = prepare_source_tree_batches(files)
    # First isolated tree and the final root/commit/ref writes fit in the
    # fixed reserve. Remaining source-tree batches and binary blob writes
    # incur a complete fresh admission before every POST.
    blob_writes = sum(text is None for batch in batches for _, _, _, text in batch)
    reserved_writes = max(1, blob_writes + len(batches) - 1)
    require_private_ci_api_budget(len(files), reserved_writes)
    def authorize_write(require_absent_ref=True):
        if checked_policy_fingerprint(trusted_root, repository) != approved_policy:
            raise Denied("private CI destination approval policy changed during transfer")
        if require_protected_main_policy(approved_policy) != reviewed_main_sha:
            raise Denied("reviewed protected source main changed during transfer")
        snapshot = require_reviewed_source_snapshot(
            expected_source_commit, expected_source_tree, approved_destination_id,
            reviewed_main_sha, reviewed_snapshot_sha)
        if snapshot[1:] != (approved_carrier_main_sha, approved_carrier_tree):
            raise Denied("reviewed carrier revision changed during transfer")
        verify_source_commit(expected_source_commit, expected_source_tree)
        current = checked_repo(repository, expected_id=approved_destination_id)
        if require_carrier_actions_posture(
                repository, approved_destination_id) != carrier_actions_posture:
            raise Denied("private carrier Actions security posture changed during transfer")
        if current.get("default_branch") != "main":
            raise Denied("private carrier default branch changed during transfer")
        carrier_approval = require_carrier_source_approval(
            repository, expected_source_commit, expected_source_tree,
            base_sha, carrier_approval_sha)
        if carrier_approval[1] != carrier_ruleset_id:
            raise Denied("private carrier source ruleset approval changed during transfer")
        current_workflows = require_carrier_workflow_manifest(
            repository, base_sha, approved_carrier_tree, carrier_workflows[0])
        if current_workflows[1:] != carrier_workflows[1:]:
            raise Denied("private carrier workflow surface changed during transfer")
        current_publisher = require_carrier_publisher_policy(
            repository, base_sha, reviewed_main_sha, bootstrap_blob_sha,
            carrier_publisher[0])
        if current_publisher[1:] != carrier_publisher[1:]:
            raise Denied("private carrier publisher executable approval changed during transfer")
        current_images = require_carrier_sealed_build_images(
            repository, base_sha, carrier_images[0])
        if current_images[1:] != carrier_images[1:]:
            raise Denied("private carrier sealed build-image policy changed during transfer")
        current_execution = require_carrier_execution_profile(
            repository, base_sha, private_workflow_sha, carrier_execution[0])
        if current_execution[1:] != carrier_execution[1:]:
            raise Denied("private carrier execution profile changed during transfer")
        require_carrier_source_ruleset(repository, carrier_ruleset_id)
        require_effective_carrier_source_rules(
            repository, branch, carrier_ruleset_id)
        if require_absent_ref:
            require_absent_carrier_source_ref(repository, branch)
        require_pinned_carrier_main(base_sha, approved_carrier_tree)

    isolated_sha = None
    for batch in batches:
        elements = []
        for rel, data, mode, as_text in batch:
            entry = {"path": rel, "mode": mode, "type": "blob"}
            if as_text is not None:
                # GitHub creates this UTF-8 blob as part of the upcoming
                # single reviewed tree write; no separate blob POST.
                entry["content"] = as_text
            else:
                authorize_write()  # recheck before each binary/large blob POST
                blob = gh_api(f"repos/{repository}/git/blobs", method="POST", payload={
                    "content": base64.b64encode(data).decode("ascii"), "encoding": "base64"
                })
                blob_sha = valid_sha(blob.get("sha"))
                expected = hashlib.sha1(b"blob " + str(len(data)).encode("ascii")
                                        + bytes([0]) + data).hexdigest()
                if blob_sha != expected:
                    raise Denied("uploaded blob digest does not match local source bytes")
                entry["sha"] = blob_sha
            elements.append(entry)
        # Each tree POST creates blobs as well as the tree. Reconfirm every
        # protection/approval immediately before sending any private bytes.
        authorize_write()
        isolated_payload = {"tree": elements}
        if isolated_sha is not None:
            isolated_payload["base_tree"] = isolated_sha
        isolated = gh_api(f"repos/{repository}/git/trees", method="POST",
                          payload=isolated_payload)
        isolated_sha = valid_sha(isolated.get("sha"))
    # Intermediate trees are not approval evidence. Only the final complete
    # Git tree digest can authenticate the snapshot.
    if isolated_sha != expected_source_tree:
        raise Denied("destination source subtree differs from pinned expected SHA")
    # Replace the entire PREFIX subtree atomically in the new root tree.
    # Preserve unrelated destination files and CI workflows from base_tree.
    authorize_write()
    new_tree = gh_api(f"repos/{repository}/git/trees", method="POST",
                      payload={"base_tree": base_tree, "tree": [
                          {"path": PREFIX, "mode": "040000", "type": "tree",
                           "sha": isolated_sha}]})
    new_tree_sha = valid_sha(new_tree.get("sha"))
    authorize_write()
    provenance_message = "\n".join([
        f"ci: exact private Oreslang tree {expected_source_commit} tree {expected_source_tree}",
        f"carrier-base {base_sha} tree {base_tree}",
        f"source-approval-blob {reviewed_snapshot_sha}",
        f"carrier-approval-blob {carrier_approval_sha}",
        f"carrier-ruleset-id {carrier_ruleset_id}",
    ])
    commit = gh_api(f"repos/{repository}/git/commits", method="POST", payload={
        "message": provenance_message,
        "tree": new_tree_sha, "parents": [base_sha]
    })
    commit_sha = valid_sha(commit.get("sha"))
    authorize_write()
    # CREATE a new CI ref, never UPDATE an existing branch, tag, or main.
    created = gh_api(f"repos/{repository}/git/refs", method="POST", payload={
        "ref": "refs/heads/" + branch, "sha": commit_sha
    })
    if created.get("ref") != "refs/heads/" + branch:
        raise Denied("GitHub did not confirm creation of requested isolated CI branch")
    if nested_sha(created, "object") != commit_sha:
        raise Denied("created CI branch does not reference the expected commit")

    # The create response is not evidence that the branch resolves to the
    # intended source. Read back the ref and its committed tree before
    # reporting success. Destination workflows independently check the
    # subtree SHA again before executing any private source.
    verified_ref = gh_api(f"repos/{repository}/git/ref/heads/{branch}")
    readback_sha = nested_sha(verified_ref, "object")
    if (verified_ref.get("ref") != "refs/heads/" + branch
            or verified_ref["object"].get("type") != "commit"):
        raise Denied("CI branch readback identity or object type is invalid")
    if readback_sha != commit_sha:
        raise Denied("CI branch readback points to a different commit")
    verified_commit = gh_api(f"repos/{repository}/git/commits/{commit_sha}")
    verified_commit_tree_sha = nested_sha(verified_commit, "tree")
    if verified_commit.get("sha") != commit_sha:
        raise Denied("CI branch commit readback identity is invalid")
    if verified_commit_tree_sha != new_tree_sha:
        raise Denied("CI branch commit tree differs from published snapshot")
    verified_tree = gh_api(f"repos/{repository}/git/trees/{new_tree_sha}")
    if verified_tree.get("sha") != new_tree_sha:
        raise Denied("committed CI root tree readback identity is invalid")
    tree_items = verified_tree.get("tree")
    if (verified_tree.get("truncated") is not False
            or not isinstance(tree_items, list)
            or sum(item.get("path") == PREFIX for item in tree_items
                   if isinstance(item, dict)) != 1
            or not any(isinstance(item, dict) and item.get("path") == PREFIX
                       and item.get("type") == "tree"
                       and item.get("mode") in ("040000", "40000")
                       and item.get("sha") == expected_source_tree
                       for item in tree_items)):
        raise Denied("committed CI source subtree differs from pinned source SHA")
    # Readback may take time: check protected-main approval, destination
    # repository identity and private visibility again before success. The
    # payload ref must exist now, so retain every other authorization check
    # but skip only its pre-write absence assertion.
    authorize_write(require_absent_ref=False)

    authorization_branch = "ci/authorized-" + commit_sha
    require_effective_carrier_source_rules(
        repository, authorization_branch, carrier_ruleset_id)
    require_absent_carrier_source_ref(repository, authorization_branch)
    authorize_write(require_absent_ref=False)
    require_effective_carrier_source_rules(
        repository, authorization_branch, carrier_ruleset_id)
    require_absent_carrier_source_ref(repository, authorization_branch)
    authorized = gh_api(f"repos/{repository}/git/refs", method="POST", payload={
        "ref": "refs/heads/" + authorization_branch, "sha": commit_sha
    })
    if authorized.get("ref") != "refs/heads/" + authorization_branch:
        raise Denied("GitHub did not confirm creation of private CI authorization ref")
    if nested_sha(authorized, "object") != commit_sha:
        raise Denied("private CI authorization ref does not reference payload commit")
    authorization_readback = gh_api(
        f"repos/{repository}/git/ref/heads/{authorization_branch}")
    if (authorization_readback.get("ref") != "refs/heads/" + authorization_branch
            or authorization_readback.get("object", {}).get("type") != "commit"
            or nested_sha(authorization_readback, "object") != commit_sha):
        raise Denied("private CI authorization ref readback is invalid")
    authorize_write(require_absent_ref=False)
    require_effective_carrier_source_rules(
        repository, authorization_branch, carrier_ruleset_id)
    return commit_sha, len(files)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--branch", required=True)
    p.add_argument("--expected-source-tree", required=True, help="pinned complete source Git tree SHA")
    p.add_argument("--expected-source-commit", required=True, help="pinned immutable private source commit SHA")
    args = p.parse_args(argv)
    # Real publication is authorized only when these exact publisher bytes
    # were launched by the independently reviewed external bootstrap. A
    # checkout-local wrapper/script cannot establish its own integrity before
    # execution, so direct invocation deliberately fails closed.
    bootstrap_root = globals().get("_ORESLANG_VERIFIED_SOURCE_ROOT")
    bootstrap_blob = globals().get("_ORESLANG_VERIFIED_PUBLISHER_BLOB_SHA")
    if not isinstance(bootstrap_root, str) or not isinstance(bootstrap_blob, str):
        print("PRIVATE_CI_DESTINATION_REJECTED: independent publisher bootstrap required",
              file=sys.stderr)
        return 1
    try:
        valid_sha(bootstrap_blob)
        root = Path(bootstrap_root).resolve(strict=True)
    except (Denied, OSError, RuntimeError):
        print("PRIVATE_CI_DESTINATION_REJECTED: verified bootstrap attestation is invalid",
              file=sys.stderr)
        return 1
    try:
        commit, count = publish(args.repository, args.source_dir, args.branch, root,
                                args.expected_source_tree, args.expected_source_commit)
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_SNAPSHOT_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
