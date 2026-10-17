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
from datetime import datetime, timezone

NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$")
# An approved destination must include its stable numeric GitHub repository ID.
# This closes name reuse between security review and the first transfer.
POLICY_ENTRY = re.compile(r"^([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*) ([1-9][0-9]{0,19})$")
BRANCH = re.compile(r"^ci/oreslang-[a-f0-9]{40}$")
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"
CARRIER_VALIDATION_WORKFLOW = "private-oreslang-java-validation.yml"
# This policy must be approved on the protected source main branch, not merely
# present in the caller's current PR/worktree.
SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPOSITORY_ID = 1400792274
SOURCE_METADATA_ROUTE = "repos/" + SOURCE_REPOSITORY
# GitHub incident closure is NECESSARY but not SUFFICIENT for source export.
# Even a closed incident never replaces protected-branch review, exact
# snapshot approval, destination privacy, or immutable-tree verification.
SOURCE_INCIDENT_REPOSITORY = "ores-truffle-oreslang/oreslang-platform-conformance"
SOURCE_INCIDENT_REPOSITORY_ID = 1413795502
SOURCE_INCIDENT_ISSUE_ID = 5832921751
SOURCE_INCIDENT_NUMBER = 22
SOURCE_INCIDENT_METADATA_ROUTE = "repos/" + SOURCE_INCIDENT_REPOSITORY
SOURCE_INCIDENT_ROUTE = (
    SOURCE_INCIDENT_METADATA_ROUTE + "/issues/" + str(SOURCE_INCIDENT_NUMBER)
)
PROTECTED_BRANCH_ROUTE = SOURCE_METADATA_ROUTE + "/branches/main"
PROTECTED_POLICY_ROUTE = (
    SOURCE_METADATA_ROUTE + "/contents/"
    ".github/private-ci-approved-repositories.txt?ref=main"
)
REVIEWED_SNAPSHOTS_ROUTE = (
    SOURCE_METADATA_ROUTE + "/contents/"
    ".github/private-ci-approved-source-snapshots.txt?ref=main"
)
SOURCE_SNAPSHOT_ENTRY = re.compile(r"^([a-f0-9]{40}) ([a-f0-9]{40}) ([1-9][0-9]{0,19})$")
PROTECTED_REVIEWS_ROUTE = PROTECTED_BRANCH_ROUTE + "/protection/required_pull_request_reviews"
CODEOWNERS_ROUTE = SOURCE_METADATA_ROUTE + "/contents/.github/CODEOWNERS?ref=main"
CODEOWNERS_ERRORS_ROUTE = SOURCE_METADATA_ROUTE + "/codeowners/errors?ref=main"
CODEOWNER_PERMISSION_ROUTE = SOURCE_METADATA_ROUTE + "/collaborators/ORESoftware/permission"
# The last matching CODEOWNERS entry wins. Require a terminal set of exact
# entries so a later wildcard or missing entry cannot silently undo ownership.
REQUIRED_CODEOWNER_PATHS = (
    "/.github/CODEOWNERS",
    "/.github/private-ci-approved-repositories.txt",
    "/.github/private-ci-approved-source-snapshots.txt",
    "/.github/private-ci-independent-reviewer.json",
    "/.github/private-ci-required-checks.json",
    "/.github/workflows/private-ci-mirror-preflight.yml",
    "/.github/workflows/private-ci-policy.yml",
    "/.github/workflows/private-exact-candidate-validation.yml",
    "/.github/workflows/private-exact-main-validation.yml",
    "/.github/workflows/private-exact-publisher-validation.yml",
    "/docs/security/CI_PRIVATE_SOURCE_MIRRORING.md",
    "/docs/security/PRIVATE_CI_POLICY_INTEGRATION_REVIEW_2026-10-16.md",
    "/docs/security/PRIVATE_EXACT_MAIN_VALIDATION.md",
    "/docs/security/PRIVATE_CI_REVIEW_PINNED_EXTERNAL_ADMISSION.md",
    "/scripts/check-private-ci-destination.sh",
    "/scripts/publish-private-ci-source.py",
    "/scripts/dispatch-review-pinned-private-ci.py",
    "/scripts/run-in-private-ci-destination.sh",
    "/scripts/test-private-ci-destination.sh",
    "/scripts/test-publish-private-ci-source.py",
    "/scripts/ci_private_mirror_preflight.py",
    "/scripts/verify-review-pinned-private-ci.py",
    "/tests/test_ci_private_mirror_preflight.py",
    "/tests/test_review_pinned_private_ci.py",
    "/tests/test_review_pinned_dispatcher.py",
    "/src/test/java/dev/oreslang/PrivateExactMainValidationWorkflowSecurityTest.java",
    "/src/test/java/dev/oreslang/PrivateExactPublisherAdmissionMutationTest.java",
    "/src/test/java/dev/oreslang/PrivateExactPublisherValidationWorkflowSecurityTest.java",
)
REQUIRED_CODEOWNER = "@ORESoftware"
# GitHub Actions is the sole accepted issuer for mandatory source/carrier checks.
# These are a minimum: administrators may require additional trusted checks.
GITHUB_ACTIONS_APP_ID = 15368
SOURCE_POLICY_CHECK = "private-ci-policy"
CARRIER_POLICY_CHECK = "carrier-policy"
CARRIER_POLICY_PATH = ".github/private-ci-carrier-policy.json"

PROTECTED_RULES_ROUTE = PROTECTED_BRANCH_ROUTE + "/protection"




def safe_descriptor_flags_available():
    """Fail closed if secure open flags are absent, unusable, or overlapping.

    Checking for a nonzero value alone is insufficient: a shim that aliases
    O_NOFOLLOW to O_DIRECTORY would silently omit a distinct no-follow
    restriction even though every flag has a positive integer value.
    """
    flags = tuple(getattr(os, name, None)
                  for name in ("O_DIRECTORY", "O_NOFOLLOW", "O_NONBLOCK"))
    if not all(type(value) is int and value > 0 for value in flags):
        return False
    return all((left & right) == 0
               for index, left in enumerate(flags)
               for right in flags[index + 1:])



class Denied(RuntimeError):
    pass


def require_pinned_actions_check(required_checks, expected_context):
    """Do not trust a status name unless the required check is issuer-bound.

    GitHub required_status_checks.contexts alone accepts statuses spoofed by
    other apps. The branch-protection API also exposes the issuing app_id
    under required_status_checks.checks; fail closed if missing/ambiguous.
    """
    if not isinstance(required_checks, dict):
        raise Denied("required status-check policy missing")
    checks = required_checks.get("checks")
    contexts = required_checks.get("contexts")
    if not isinstance(checks, list) or not checks:
        raise Denied("required status-check issuer binding missing")
    # Legacy contexts can be satisfied by any status publisher and therefore
    # cannot establish issuer provenance. Bootstrap policy requires these to
    # be absent: only the app-bound checks[] representation is admissible.
    if contexts is not None and (not isinstance(contexts, list) or contexts):
        raise Denied("legacy required status-check contexts must be empty")
    seen = set()
    for check in checks:
        if not isinstance(check, dict):
            raise Denied("malformed required status-check issuer")
        name, app_id = check.get("context"), check.get("app_id")
        if (not isinstance(name, str) or not name
                or name in seen or type(app_id) is not int or app_id <= 0):
            raise Denied("ambiguous or unbound required status-check issuer")
        seen.add(name)
        if name == expected_context and app_id != GITHUB_ACTIONS_APP_ID:
            raise Denied("required security status check has incorrect issuer")
    if expected_context not in seen:
        raise Denied("required issuer-bound security status check missing")


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


# Executables are resolved only from controlled system directories; a caller's
# PATH must never select its own bash/git/gh helper during authorization.
SAFE_EXEC_PATH = "/usr/bin:/bin:/usr/local/bin"


def verified_child_env():
    """Build a deterministic child environment for local Git and authenticated gh.

    Authorization must not depend on caller-controlled shell hooks, Git/GitHub
    configuration, network proxies, TLS trust roots, or dynamic loader state.
    Authentication tokens remain available only through GH_TOKEN/GITHUB_TOKEN.
    """
    env = os.environ.copy()
    gh_token = env.get("GH_TOKEN")
    actions_token = env.get("GITHUB_TOKEN")
    if (gh_token and actions_token and gh_token != actions_token):
        raise Denied("conflicting GitHub authentication token variables")
    canonical_token = gh_token or actions_token

    exact_untrusted = {
        "BASH_ENV", "ENV", "BASHOPTS", "SHELLOPTS",
        "CDPATH", "GLOBIGNORE", "PROMPT_COMMAND",
        "PYTHONPATH", "PYTHONHOME",
        "GH_CONFIG_DIR", "XDG_CONFIG_HOME",
        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
        "http_proxy", "https_proxy", "all_proxy", "no_proxy",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "SSLKEYLOGFILE",
        "CURL_CA_BUNDLE", "REQUESTS_CA_BUNDLE", "NODE_EXTRA_CA_CERTS",
        "GODEBUG", "GITHUB_API_URL", "GITHUB_GRAPHQL_URL", "GITHUB_SERVER_URL",
    }
    for name in tuple(env):
        if (name in exact_untrusted
                or (name.startswith("GH_") and name != "GH_TOKEN")
                or name.startswith(("BASH_FUNC_", "GIT_", "LD_", "DYLD_"))):
            env.pop(name, None)
    env["PATH"] = SAFE_EXEC_PATH
    # Never consult a caller-selected home/config tree. gh api authenticates
    # from GH_TOKEN/GITHUB_TOKEN; local Git has its global/system config disabled.
    env["HOME"] = "/nonexistent"
    env["XDG_CONFIG_HOME"] = "/nonexistent"
    env["GH_HOST"] = "github.com"
    env["GH_PROMPT_DISABLED"] = "1"
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
    # gh prefers GH_TOKEN over GITHUB_TOKEN. Never leave precedence dependent
    # on two caller-provided variables: normalize one reviewed credential name.
    env.pop("GITHUB_TOKEN", None)
    if canonical_token:
        env["GH_TOKEN"] = canonical_token
    else:
        env.pop("GH_TOKEN", None)
    return env


MAX_GITHUB_API_JSON_BYTES = 2 * 1024 * 1024


def strict_github_api_json(raw):
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
    if not isinstance(data, dict):
        raise Denied("unexpected GitHub response shape")
    return data


def gh_api(route, method="GET", payload=None):
    argv = ["gh", "api", "--method", method, route,
            "--header", "Accept: application/vnd.github+json"]
    if payload is not None:
        argv.extend(["--input", "-"])
    env = verified_child_env()
    try:
        p = subprocess.run(argv, input=json.dumps(payload) if payload is not None else None,
                           capture_output=True, text=True, env=env, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("authenticated GitHub API request unavailable") from exc
    if p.returncode != 0:
        # Never echo stderr: a client can include private file bytes in errors.
        raise Denied("authenticated GitHub API request failed")
    return strict_github_api_json(p.stdout)


def gh_api_no_content(route, method="POST", payload=None):
    """Authenticated GitHub API call that must succeed with an empty body.

    Workflow-dispatch returns HTTP 204. Keep this separate from security
    decision reads so an empty response can never be mistaken for JSON.
    """
    argv = ["gh", "api", "--method", method, route,
            "--header", "Accept: application/vnd.github+json"]
    if payload is not None:
        argv.extend(["--input", "-"])
    env = verified_child_env()
    try:
        p = subprocess.run(argv, input=json.dumps(payload) if payload is not None else None,
                           capture_output=True, text=True, env=env, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        raise Denied("authenticated GitHub API action unavailable") from exc
    if p.returncode != 0 or p.stdout.strip():
        # Never echo response text: dispatch failures can include sensitive refs.
        raise Denied("authenticated GitHub API action failed")


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
    return result


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
    if not safe_descriptor_flags_available():
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


def require_protected_source_codeowners():
    """Require terminal, exact security owners from the source's protected main.

    GitHub's require_code_owner_reviews=true is insufficient when no
    CODEOWNERS entry matches the private exporter or the snapshot policy.
    A final explicitly owned entry overrides any earlier wildcard.
    """
    metadata = gh_api(CODEOWNERS_ROUTE)
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
    # All workflows (including future names) must require the same reviewer.
    # Otherwise an unreviewed workflow can emit the same Actions check name.
    if (len(effective) <= len(required)
            or effective[-len(required)-1] != "/.github/workflows/ @ORESoftware"):
        raise Denied("source workflow directory lacks terminal CODEOWNER coverage")
    # The CODEOWNERS text can be syntactically well-formed yet ignored by
    # GitHub (for example, a non-existent owner or invalid pattern). Ask
    # GitHub's CODEOWNERS validator about the protected main revision.
    errors = gh_api(CODEOWNERS_ERRORS_ROUTE)
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



def require_protected_carrier_codeowners(repository):
    """Prove carrier workflows have an effective independent security owner.

    GitHub's require_code_owner_reviews flag can be true even when no
    CODEOWNERS rule covers the inherited Actions workflows. An unreviewed
    workflow would execute on a transferred private-source branch.
    """
    route = f"repos/{repository}"
    metadata = gh_api(route + "/contents/.github/CODEOWNERS?ref=main")
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
    # The last matching rule wins. Require the same terminal trust root
    # carried by approved private CI destinations: future workflow files,
    # CODEOWNERS itself, and the issuer policy declaring carrier-policy.
    expected = ["/.github/workflows/ " + REQUIRED_CODEOWNER,
                "/.github/CODEOWNERS " + REQUIRED_CODEOWNER,
                "/" + CARRIER_POLICY_PATH + " " + REQUIRED_CODEOWNER]
    if effective[-len(expected):] != expected:
        raise Denied("private carrier security files lack terminal CODEOWNER coverage")
    errors = gh_api(route + "/codeowners/errors?ref=main")
    if not isinstance(errors.get("errors"), list) or errors["errors"]:
        raise Denied("private carrier CODEOWNERS has GitHub validation errors")
    access = gh_api(route + "/collaborators/ORESoftware/permission")
    owner = access.get("user")
    if (not isinstance(owner, dict)
            or owner.get("login") != REQUIRED_CODEOWNER.lstrip("@")
            or owner.get("type") != "User"
            or access.get("permission") not in ("write", "admin")):
        raise Denied("private carrier security CODEOWNER lacks write permission")


def require_protected_carrier_policy(repository):
    """Bind carrier-policy required-check provenance to reviewed main bytes."""
    route = (
        f"repos/{repository}/contents/" + CARRIER_POLICY_PATH + "?ref=main"
    )
    metadata = gh_api(route)
    if (metadata.get("type") != "file"
            or metadata.get("encoding") != "base64"
            or not isinstance(metadata.get("content"), str)
            or len(metadata["content"]) > 24 * 1024
            or type(metadata.get("size")) is not int
            or not 1 <= metadata["size"] <= 16 * 1024):
        raise Denied("private carrier issuer policy metadata is unavailable")
    try:
        encoded = "".join(metadata["content"].splitlines())
        raw = base64.b64decode(encoded, validate=True)
        decoded = raw.decode("utf-8")
    except (ValueError, UnicodeError) as exc:
        raise Denied("private carrier issuer policy is not valid UTF-8/base64") from exc
    if base64.b64encode(raw).decode("ascii") != encoded:
        raise Denied("private carrier issuer policy base64 is noncanonical")
    digest = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
    ).hexdigest()
    if (len(raw) != metadata["size"]
            or b"\0" in raw
            or valid_sha(metadata.get("sha")) != digest):
        raise Denied("private carrier issuer policy blob integrity failed")
    try:
        policy = json.loads(decoded)
    except (TypeError, ValueError) as exc:
        raise Denied("private carrier issuer policy is not valid JSON") from exc
    expected = {
        "schema": 1,
        "carrier": {
            "context": CARRIER_POLICY_CHECK,
            "appId": GITHUB_ACTIONS_APP_ID,
        },
    }
    if (not isinstance(policy, dict)
            or policy != expected
            or list(policy.keys()) != ["schema", "carrier"]
            or not isinstance(policy.get("carrier"), dict)
            or list(policy["carrier"].keys()) != ["context", "appId"]):
        raise Denied("private carrier issuer policy identity mismatch")
    return digest


def require_incident_containment():
    """Require the exact independently observable completed incident.

    Repository path + issue number are not durable identities: a repository
    can be renamed/deleted/recreated and a pull request also occupies the
    issues API namespace. Bind both objects to immutable numeric GitHub IDs,
    require the incident repository to remain private, and reject PR-shaped
    payloads before treating closure as containment.
    """
    incident_repository = gh_api(SOURCE_INCIDENT_METADATA_ROUTE)
    if (incident_repository.get("full_name") != SOURCE_INCIDENT_REPOSITORY
            or type(incident_repository.get("id")) is not int
            or incident_repository["id"] != SOURCE_INCIDENT_REPOSITORY_ID
            or incident_repository.get("private") is not True
            or incident_repository.get("visibility") != "private"
            or incident_repository.get("archived") is not False
            or incident_repository.get("disabled") is not False):
        raise Denied("private-source incident repository identity is not verified")
    issue = gh_api(SOURCE_INCIDENT_ROUTE)
    canonical_url = "https://api.github.com/" + SOURCE_INCIDENT_ROUTE
    canonical_repository_url = (
        "https://api.github.com/" + SOURCE_INCIDENT_METADATA_ROUTE
    )
    closed_at = issue.get("closed_at")
    if (type(issue.get("id")) is not int
            or issue["id"] != SOURCE_INCIDENT_ISSUE_ID
            or type(issue.get("number")) is not int
            or issue["number"] != SOURCE_INCIDENT_NUMBER
            or issue.get("url") != canonical_url
            or issue.get("repository_url") != canonical_repository_url
            or issue.get("pull_request") is not None
            or issue.get("state") != "closed"
            or issue.get("state_reason") != "completed"
            or not isinstance(closed_at, str)):
        raise Denied("private-source exposure incident #22 remains unresolved")
    try:
        parsed_closed_at = datetime.strptime(
            closed_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except (TypeError, ValueError) as exc:
        raise Denied("private-source exposure incident closure timestamp is invalid") from exc
    if parsed_closed_at.year < 2026:
        raise Denied("private-source exposure incident closure timestamp is invalid")


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
    if (not isinstance(rules.get("enforce_admins"), dict)
            or rules["enforce_admins"].get("enabled") is not True):
        raise Denied("protected main must enforce rules for administrators")
    required_checks = rules.get("required_status_checks")
    # The only trusted required status entries are checks[] with explicit
    # issuer identities. Legacy contexts[] MUST stay empty: those are not
    # independently app-bound. require_pinned_actions_check enforces both.
    if (not isinstance(required_checks, dict)
            or required_checks.get("strict") is not True):
        raise Denied("protected main must require strict source CI checks")
    require_pinned_actions_check(required_checks, SOURCE_POLICY_CHECK)
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
    # This is a necessary additional gate, never sufficient authorization.
    # require_protected_main_policy runs before source reads AND each Git write.
    require_incident_containment()
    require_protected_source_codeowners()
    metadata = gh_api(PROTECTED_POLICY_ROUTE)
    if (metadata.get("type") != "file"
            or valid_sha(metadata.get("sha")) != approved_policy[2]):
        raise Denied("destination policy is not approved on protected source main")
    return protected_main_sha

def require_trusted_publisher_checkout(root, reviewed_main_sha):
    """Enforce execution from the independently authenticated source main commit.

    A developer's PR checkout cannot self-authorize its publisher or policy.
    The untrusted test snapshot must be staged separately under this trusted
    checkout and its complete bytes checked against the reviewed Git tree.
    """
    valid_sha(reviewed_main_sha)
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
        "scripts/publish-private-ci-source.py",
        "scripts/run-in-private-ci-destination.sh",
        "scripts/check-private-ci-destination.sh",
        ".github/private-ci-approved-repositories.txt",
        ".github/private-ci-approved-source-snapshots.txt",
    )
    # Git status is NOT an integrity proof. A caller can mark an index entry
    # --skip-worktree or --assume-unchanged and edit its executable bytes while
    # status --porcelain reports a clean checkout. Compare the real file
    # bytes with the blob named by the pinned, non-replaced HEAD tree.
    if not safe_descriptor_flags_available():
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
                                     reviewed_blob_sha=None):
    """Require exact immutable source tuple from a protected-main approval file.

    An approved destination is NOT blanket permission to export arbitrary
    branch contents. The requested source commit/tree must have its own
    independently code-owner-reviewed entry on protected source main.
    """
    valid_sha(expected_commit)
    valid_sha(expected_tree)
    if type(destination_id) is not int or destination_id <= 0:
        raise Denied("expected numeric destination repository ID is invalid")
    metadata = gh_api(REVIEWED_SNAPSHOTS_ROUTE)
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
    approved = set()
    for line in lines:
        if not line or line.startswith("#"):
            continue
        match = SOURCE_SNAPSHOT_ENTRY.fullmatch(line)
        if match is None:
            raise Denied("invalid reviewed source-snapshot policy entry")
        entry = (match.group(1), match.group(2), int(match.group(3)))
        if entry in approved:
            raise Denied("duplicate source-snapshot approval")
        approved.add(entry)
    if (expected_commit, expected_tree, destination_id) not in approved:
        raise Denied("immutable source snapshot is not independently approved")
    return digest


def collect_source(root, trusted_root):
    """Snapshot regular files using descriptor-relative, no-follow traversal.

    O_NOFOLLOW on the final file is insufficient when a parent directory can
    be replaced with a symlink between path validation and file open. All
    descendants are opened relative to directory descriptors anchored at
    the trusted checkout, so a rename cannot redirect them outside it.
    """
    if not safe_descriptor_flags_available():
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
    # Each write revalidates both source and carrier CODEOWNERS, the pinned
    # carrier validation-workflow blob, and all immutable approvals. Reserve
    # three extra reads per write: pinned carrier workflow, incident repository
    # identity, and exact incident state.
    required = 27 * estimated_writes + 160
    if remaining < required:
        raise Denied("insufficient GitHub core API budget for safe private CI export")


def publish(repository, source, branch, trusted_root, expected_source_tree,
            expected_source_commit):
    # Git ref-format restrictions are stricter than the character allowlist.
    # Reject impossible refs before reading private source or creating blobs:
    # Git disallows '..', a trailing '.', and the '.lock' suffix.
    if (not NAME.fullmatch(repository)
            or not BRANCH.fullmatch(branch)
            or ".." in branch
            or branch.endswith((".", ".lock"))):
        raise Denied("invalid destination or isolated CI branch name")
    valid_sha(expected_source_tree)
    valid_sha(expected_source_commit)
    # A carrier branch name is an immutable, independently checkable source
    # tree identity. A generic or foreign SHA suffix is not provenance.
    # Fail *before* policy requests, source reads, or destination Git writes.
    if branch != "ci/oreslang-" + expected_source_tree:
        raise Denied("isolated CI branch must match pinned source Git tree SHA")
    # This function previously ran the checkout-local Bash preflight BEFORE
    # authenticating the checkout. A modified PR checkout could execute an
    # attacker-controlled guard even though source export would later deny.
    # Read policy strictly as data and authenticate the protected source first.
    approved_policy = checked_policy_fingerprint(trusted_root, repository)
    # A PR branch can contain an allowlist entry without security approval.
    # Require the exact policy Git blob to exist on protected source main.
    reviewed_main_sha = require_protected_main_policy(approved_policy)
    require_trusted_publisher_checkout(trusted_root, reviewed_main_sha)
    # No checkout-local shell runs here. Revalidate the approved policy data
    # and pinned private destination identity without interpreting local code.
    # All failures above occur before private source reads or Git writes.
    approved_destination_id = require_preflight(repository, trusted_root)
    if approved_policy[1] != approved_destination_id:
        raise Denied("destination numeric ID differs from reviewed approval policy")
    reviewed_snapshot_sha = require_reviewed_source_snapshot(
        expected_source_commit, expected_source_tree, approved_destination_id)
    verify_source_commit(expected_source_commit, expected_source_tree)
    trusted = trusted_root.resolve(strict=True)
    checked_source = source.resolve(strict=True)
    try:
        checked_source.relative_to(trusted)
    except ValueError as exc:
        raise Denied("source directory escapes the trusted checkout") from exc
    base = checked_repo(repository, expected_id=approved_destination_id)
    # Never inherit a caller-selected or unreviewed carrier branch: inherited
    # workflow definitions execute with the destination repository's token.
    if base.get("default_branch") != "main":
        raise Denied("private carrier default branch is not trusted main")
    def require_pinned_carrier_main(expected_sha=None):
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
        checks = carrier_rules.get("required_status_checks")
        if (not isinstance(carrier_rules.get("enforce_admins"), dict)
                or carrier_rules["enforce_admins"].get("enabled") is not True
                or not isinstance(checks, dict)
                or checks.get("strict") is not True):
            raise Denied("private carrier main lacks enforced administrator and CI protections")
        require_pinned_actions_check(checks, CARRIER_POLICY_CHECK)
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
        require_protected_carrier_codeowners(repository)
        require_protected_carrier_policy(repository)
        workflow = gh_api(
            f"repos/{repository}/contents/.github/workflows/"
            + CARRIER_VALIDATION_WORKFLOW + "?ref=main")
        if (workflow.get("type") != "file"
                or type(workflow.get("size")) is not int
                or not 1 <= workflow["size"] <= 128 * 1024):
            raise Denied("private carrier validation workflow is unavailable")
        workflow_sha = valid_sha(workflow.get("sha"))
        return current_sha, workflow_sha
    base_sha, carrier_workflow_sha = require_pinned_carrier_main()
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
    def authorize_write():
        if checked_policy_fingerprint(trusted_root, repository) != approved_policy:
            raise Denied("private CI destination approval policy changed during transfer")
        if require_protected_main_policy(approved_policy) != reviewed_main_sha:
            raise Denied("reviewed protected source main changed during transfer")
        require_reviewed_source_snapshot(expected_source_commit, expected_source_tree,
                                         approved_destination_id, reviewed_snapshot_sha)
        verify_source_commit(expected_source_commit, expected_source_tree)
        current = checked_repo(repository, expected_id=approved_destination_id)
        if current.get("default_branch") != "main":
            raise Denied("private carrier default branch changed during transfer")
        current_base, current_workflow = require_pinned_carrier_main(base_sha)
        if current_base != base_sha or current_workflow != carrier_workflow_sha:
            raise Denied("private carrier validation workflow changed during transfer")

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
    commit = gh_api(f"repos/{repository}/git/commits", method="POST", payload={
        "message": "ci: source snapshot from approved private Oreslang checkout",
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
    # repository identity and private visibility again before success.
    authorize_write()

    # Never execute workflow policy from the source-bearing CI branch itself.
    # Dispatch the independently protected carrier-main workflow and make it
    # checkout the immutable destination commit by SHA. A later branch rewrite
    # therefore cannot change either the workflow definition or tested source.
    dispatch_route = (
        f"repos/{repository}/actions/workflows/"
        + CARRIER_VALIDATION_WORKFLOW + "/dispatches")
    gh_api_no_content(dispatch_route, payload={
        "ref": "main",
        "inputs": {
            "carrier_commit_sha": commit_sha,
            "carrier_base_sha": base_sha,
            "source_commit_sha": expected_source_commit,
            "source_tree_sha": expected_source_tree,
        },
    })
    return commit_sha, len(files)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--branch", required=True)
    p.add_argument("--expected-source-tree", required=True, help="pinned complete source Git tree SHA")
    p.add_argument("--expected-source-commit", required=True, help="pinned immutable private source commit SHA")
    args = p.parse_args(argv)
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
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
