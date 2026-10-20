#!/usr/bin/env python3
"""Publish a snapshot branch ONLY into a reviewed private GitHub repository.

No arbitrary command execution or user-selectable GitHub API endpoints. All
writes target the exact repository checked by the private-source policy.
"""
import argparse
import base64
from datetime import datetime
import hashlib
import json
import os
import stat
from pathlib import Path
import re
import subprocess
import sys

NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$")
BRANCH = re.compile(r"^ci/oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}$")
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"
# The protected source repository is fixed in reviewed code, not a caller input.
TRUSTED_SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
# Immutable repo ID; a renamed/deleted/reused owner/name is not the same authority.
TRUSTED_SOURCE_REPOSITORY_ID = 1400792274
TRUSTED_SOURCE_REF = "main"
TRUSTED_SOURCE_REVIEWER = "ORESoftware"
MAX_PR_REVIEWS = 100
MAX_GH_API_RESPONSE_CHARS = 24 * 1024 * 1024
# Every executable/policy file that can approve or attest private-source export
# must remain effectively owned by the independently reviewed source CODEOWNER.
# These paths mirror the source-free review-coverage inventory.
SOURCE_SECURITY_PATHS = (
    ".github/CODEOWNERS",
    ".github/private-ci-approved-repositories.txt",
    ".github/workflows/private-ci-mirror-preflight.yml",
    "docs/security/CI_PRIVATE_SOURCE_MIRRORING.md",
    "scripts/check-private-ci-destination.sh",
    "scripts/publish-private-ci-source.py",
    "scripts/run-in-private-ci-destination.sh",
    "scripts/run-private-ci-regressions-local.sh",
    "scripts/test-run-private-ci-regressions-local.sh",
    "scripts/test-private-ci-destination.sh",
    "scripts/test-private-ci-merged-main.py",
    "scripts/test-private-ci-review-coverage.py",
    "scripts/test-publish-private-ci-source.py",
    "tools/verify-exact-source-tree.sh",
    "tools/test-verify-exact-source-tree.sh",
)



class Denied(RuntimeError):
    pass


def valid_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise Denied("GitHub returned an invalid object SHA")
    return value


def canonical_security_lines(contents, label):
    """Security control files admit printable ASCII records delimited by LF only.

    str.splitlines() silently accepts CR, VT and Unicode line separators as
    separate authorization records; reject all noncanonical bytes instead.
    """
    if not isinstance(contents, bytes) or any(
            byte != 10 and not 32 <= byte <= 126 for byte in contents):
        raise Denied(label + " contains noncanonical ASCII LF bytes")
    return contents.decode("ascii").split("\n")


def trusted_github_cli():
    """Only execute the root-owned GitHub CLI and its root-owned path.

    GitHub credentials must never be handed to an executable found through
    a caller-controlled PATH (including a writable symlink or parent dir).
    The privileged publishing host needs the OS-packaged /usr/bin/gh.
    """
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
    # setuid/setgid executables are not an acceptable GitHub API client:
    # publication must not gain filesystem or process privileges on exec.
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0
            or info.st_mode & 0o6022 or not info.st_mode & 0o111):
        raise Denied("trusted GitHub CLI executable is not root-controlled")
    return target


def gh_api(route, method="GET", payload=None):
    argv = [trusted_github_cli(), "api", "--method", method, route,
            "--header", "Accept: application/vnd.github+json"]
    if payload is not None:
        argv.extend(["--input", "-"])
    # Never inherit the caller's arbitrary process environment: GH_TOKEN
    # can be exfiltrated through an inherited HTTPS_PROXY/SSL_CERT_FILE trust
    # override even when /usr/bin/gh itself is root-owned and pinned. Other
    # GitHub CLI knobs (GH_CONFIG_DIR, GH_DEBUG, GH_ENTERPRISE_HOST, etc.)
    # are equally outside this reviewed request contract.
    credential_names = ("GH_TOKEN", "GITHUB_TOKEN")
    provided = [key for key in credential_names if os.environ.get(key)]
    if len(provided) != 1:
        raise Denied("exactly one explicit GitHub API token is required")
    env = {
        provided[0]: os.environ[provided[0]],
        "GH_HOST": "github.com",
        "GH_PROMPT_DISABLED": "1",
        "PATH": "/usr/bin:/bin",
        "HOME": "/",
        "XDG_CONFIG_HOME": "/nonexistent",
        "LC_ALL": "C.UTF-8",
    }
    try:
        p = subprocess.run(argv, input=json.dumps(payload) if payload is not None else None,
                           capture_output=True, text=True, env=env, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("authenticated GitHub API request unavailable") from exc
    if p.returncode != 0:
        # Never echo stderr: a client can include private file bytes in errors.
        raise Denied("authenticated GitHub API request failed")
    if (not isinstance(p.stdout, str)
            or len(p.stdout) > MAX_GH_API_RESPONSE_CHARS):
        raise Denied("authenticated GitHub API response exceeds size limit")
    try:
        data = json.loads(p.stdout)
    except json.JSONDecodeError as exc:
        raise Denied("invalid GitHub API JSON response") from exc
    # The documented PR reviews endpoint returns a list, not a JSON object.
    # Admit it only at this fixed, trusted source-repository route.
    if (route.startswith(f"repos/{TRUSTED_SOURCE_REPOSITORY}/pulls/")
            and route.endswith("/reviews?per_page=100")):
        if not isinstance(data, list):
            raise Denied("invalid source PR review response shape")
        return {"reviews": data}
    if not isinstance(data, dict):
        raise Denied("unexpected GitHub response shape")
    return data


def checked_repo(repository):
    result = gh_api("repos/" + repository)
    if (not isinstance(result.get("id"), int)
            or isinstance(result["id"], bool) or result["id"] <= 0
            or not isinstance(result.get("full_name"), str)
            or result["full_name"].casefold() != repository.casefold()
            or result.get("visibility") != "private"
            or result.get("private") is not True):
        raise Denied("destination identity or private visibility changed")
    return result


def require_preflight(repository, root):
    """Deny unsafe destinations without executing checkout-supplied scripts.

    The root checkout may contain unreviewed changes. Invoking its Bash guard
    before authenticating protected-main policy would grant arbitrary command
    execution with the caller's GitHub credentials. Keep the CLI guard for
    interactive diagnostics, but make publisher admission entirely data-only.
    Protected-source authority is checked independently by the caller.
    """
    _, approved_id = checked_policy_fingerprint(root, repository)
    destination = checked_repo(repository)
    if destination["id"] != approved_id:
        raise Denied("destination immutable repository ID differs from reviewed policy")
    return destination

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
    lines = canonical_security_lines(contents, "private CI approval policy")
    # Every non-comment authorization record pins the immutable GitHub
    # repository ID alongside the human-readable owner/name. An owner/name
    # alone can be renamed, transferred or reused by a different repository.
    approved_id = None
    seen = set()
    for line in lines:
        if not line or line.startswith("#"):
            continue
        name, marker, numeric = line.rpartition("@")
        if (marker != "@" or not NAME.fullmatch(name)
                or not re.fullmatch(r"[1-9][0-9]{0,18}", numeric)):
            raise Denied("private CI approval record lacks canonical owner/repo@numeric-id")
        number = int(numeric)
        if number > (1 << 63) - 1:
            raise Denied("private CI repository ID exceeds signed 64-bit range")
        folded = name.casefold()
        if folded in seen:
            raise Denied("private CI approval policy duplicates a repository")
        seen.add(folded)
        if folded == repository.casefold():
            approved_id = number
    if approved_id is None:
        raise Denied("destination approval was revoked or absent")
    return hashlib.sha256(contents).digest(), approved_id


def require_trusted_branch_controls():
    """Fail closed unless canonical main enforces CODEOWNER reviews and no admin bypass.

    GitHub's CODEOWNERS file alone does not enforce review. The repository
    administrator must actually enable branch protection; ruleset-only
    configurations need a separately reviewed attestation implementation.
    """
    trusted = gh_api(f"repos/{TRUSTED_SOURCE_REPOSITORY}")
    if (trusted.get("id") != TRUSTED_SOURCE_REPOSITORY_ID
            or trusted.get("full_name") != TRUSTED_SOURCE_REPOSITORY
            or trusted.get("private") is not True
            or trusted.get("visibility") != "private"
            or trusted.get("default_branch") != TRUSTED_SOURCE_REF):
        raise Denied("trusted source repository immutable identity changed")
    prefix = f"repos/{TRUSTED_SOURCE_REPOSITORY}/branches/{TRUSTED_SOURCE_REF}"
    branch = gh_api(prefix)
    if branch.get("name") != TRUSTED_SOURCE_REF or branch.get("protected") is not True:
        raise Denied("source main branch is not protected")
    protection = gh_api(prefix + "/protection")
    reviews = protection.get("required_pull_request_reviews")
    admins = protection.get("enforce_admins")
    if not isinstance(reviews, dict):
        raise Denied("source main lacks required pull-request reviews")
    count = reviews.get("required_approving_review_count")
    if (reviews.get("require_code_owner_reviews") is not True
            or not isinstance(count, int) or isinstance(count, bool)
            or count < 1):
        raise Denied("source main lacks enforced CODEOWNER approval")
    if not isinstance(admins, dict) or admins.get("enabled") is not True:
        raise Denied("source main permits admin bypass of review controls")
    if reviews.get("dismiss_stale_reviews") is not True:
        raise Denied("source main permits approvals for outdated revisions")
    for setting in ("allow_force_pushes", "allow_deletions"):
        rule = protection.get(setting)
        if not isinstance(rule, dict) or rule.get("enabled") is not False:
            raise Denied("source main permits force-push, deletion or unknown bypass settings")
    # Classic branch protection permits named teams/users/apps to bypass
    # mandatory PR review. Admin enforcement alone does not eliminate these
    # exceptions; fail closed rather than trusting a nominal CODEOWNER rule.
    bypass = reviews.get("bypass_pull_request_allowances", {})
    if not isinstance(bypass, dict) or any(
            key not in ("users", "teams", "apps") for key in bypass):
        raise Denied("source main review bypass allowances are malformed")
    for actors in bypass.values():
        if not isinstance(actors, list) or actors:
            raise Denied("source main allows actor bypass of security reviews")
    require_source_codeowner_coverage()



def require_source_codeowner_coverage():
    """Check effective source security ownership from authenticated main bytes.

    Enforced CODEOWNER reviews alone do not secure an unowned script, or a
    later rule that redirects ownership to a different principal.
    """
    route = (f"repos/{TRUSTED_SOURCE_REPOSITORY}/contents/"
             f".github/CODEOWNERS?ref={TRUSTED_SOURCE_REF}")
    response = gh_api(route)
    encoded = response.get("content")
    declared = response.get("size")
    if (response.get("type") != "file"
            or response.get("encoding") != "base64"
            or not isinstance(encoded, str) or len(encoded) > 24 * 1024
            or not isinstance(declared, int) or isinstance(declared, bool)
            or declared < 1 or declared > 16 * 1024):
        raise Denied("source main CODEOWNERS file is missing or malformed")
    try:
        raw = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("source main CODEOWNERS cannot be decoded") from exc
    if len(raw) != declared:
        raise Denied("source main CODEOWNERS size differs")
    blob = hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + bytes([0]) + raw
    ).hexdigest()
    if valid_sha(response.get("sha")) != blob:
        raise Denied("source main CODEOWNERS Git blob differs")
    rules = []
    for line in canonical_security_lines(raw, "source main CODEOWNERS"):
        fields = line.split()
        if not fields or line.lstrip().startswith("#"):
            continue
        if (len(fields) != 2 or not fields[1].startswith("@")
                or fields[0] != "*"
                and (not fields[0].startswith("/")
                     or any(char in fields[0] for char in "*?![]\\")
                     or ".." in fields[0].split("/"))):
            raise Denied("source main CODEOWNERS contains unsupported security rule")
        rules.append((fields[0], fields[1]))
    if not rules:
        raise Denied("source main CODEOWNERS trust root is empty")
    for path in SOURCE_SECURITY_PATHS:
        applicable = [
            principal for pattern, principal in rules
            if (pattern == "*" or pattern == "/" + path
                or (pattern.endswith("/") and
                    path.startswith(pattern.lstrip("/"))))
        ]
        if not applicable or applicable[-1].casefold() != (
                "@" + TRUSTED_SOURCE_REVIEWER).casefold():
            raise Denied("source main security file lacks effective CODEOWNER: " + path)


def require_destination_workflow_owners(repository, expected_head):
    """Verify that the reviewed destination commit actually owns CI workflows.

    Required CODEOWNER review is a no-op if no effective CODEOWNERS pattern
    matches the workflow files. Conservatively require these two final rules,
    in order, so no later pattern can override either ownership assignment.
    """
    route = f"repos/{repository}/contents/.github/CODEOWNERS?ref={expected_head}"
    response = gh_api(route)
    if (response.get("type") != "file"
            or response.get("encoding") != "base64"):
        raise Denied("destination workflow CODEOWNERS file is unavailable")
    declared = response.get("size")
    encoded = response.get("content")
    if (not isinstance(declared, int) or isinstance(declared, bool)
            or declared < 1 or declared > 4096
            or not isinstance(encoded, str)):
        raise Denied("destination workflow CODEOWNERS file has invalid size")
    try:
        data = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("destination workflow CODEOWNERS cannot be decoded") from exc
    if len(data) != declared:
        raise Denied("destination workflow CODEOWNERS byte length differs")
    actual_blob = hashlib.sha1(
        b"blob " + str(len(data)).encode("ascii") + bytes([0]) + data
    ).hexdigest()
    if valid_sha(response.get("sha")) != actual_blob:
        raise Denied("destination workflow CODEOWNERS Git blob differs")
    rules = [line.strip()
             for line in canonical_security_lines(data, "destination workflow CODEOWNERS")
             if line.strip() and not line.lstrip().startswith("#")]
    # Matching the last two rules is insufficient: earlier rules can
    # override the global owner for security-sensitive scripts and policy.
    # Require the exact reviewed three-rule trust root.  This is the
    # compatible policy proposed by the destination CODEOWNERS PR.
    expected = [
        f"* @{TRUSTED_SOURCE_REVIEWER}",
        f"/.github/ @{TRUSTED_SOURCE_REVIEWER}",
        f"/.github/CODEOWNERS @{TRUSTED_SOURCE_REVIEWER}",
    ]
    if rules != expected:
        raise Denied("destination CODEOWNERS trust root has missing or override rules")


def require_destination_branch_controls(repository, default, expected_head):
    """Require independently reviewed destination workflows at the pinned HEAD."""
    branch_route = f"repos/{repository}/branches/{default}"
    branch = gh_api(branch_route)
    if (branch.get("name") != default or branch.get("protected") is not True
            or not isinstance(branch.get("commit"), dict)
            or branch["commit"].get("sha") != expected_head):
        raise Denied("destination default branch is unprotected or changed")
    protection = gh_api(branch_route + "/protection")
    reviews = protection.get("required_pull_request_reviews")
    admins = protection.get("enforce_admins")
    if not isinstance(reviews, dict):
        raise Denied("destination workflows lack required pull-request review")
    count = reviews.get("required_approving_review_count")
    if (reviews.get("require_code_owner_reviews") is not True
            or reviews.get("dismiss_stale_reviews") is not True
            or not isinstance(count, int) or isinstance(count, bool) or count < 1
            or not isinstance(admins, dict)
            or admins.get("enabled") is not True):
        raise Denied("destination workflow review protection is insufficient")
    for setting in ("allow_force_pushes", "allow_deletions"):
        rule = protection.get(setting)
        if not isinstance(rule, dict) or rule.get("enabled") is not False:
            raise Denied("destination default branch permits unsafe writes")
    bypass = reviews.get("bypass_pull_request_allowances", {})
    if (not isinstance(bypass, dict)
            or any(k not in ("users", "teams", "apps") for k in bypass)):
        raise Denied("destination review bypass data is malformed")
    for actors in bypass.values():
        if not isinstance(actors, list) or actors:
            raise Denied("destination allows bypass of mandatory workflow review")

    require_destination_workflow_owners(repository, expected_head)


def require_protected_main_policy(repository, local_policy_digest):
    """Require the checked-out policy to match authoritative trusted main.

    An unreviewed checkout must never self-authorize by adding a destination
    to its local allowlist. The GitHub API response is authenticated via gh.
    Recheck before creating the destination ref to catch policy revocation.
    """
    require_trusted_branch_controls()
    route = (f"repos/{TRUSTED_SOURCE_REPOSITORY}/contents/"
             f".github/private-ci-approved-repositories.txt"
             f"?ref={TRUSTED_SOURCE_REF}")
    result = gh_api(route)
    if result.get("type") != "file" or result.get("encoding") != "base64":
        raise Denied("protected main approval policy unavailable")
    encoded = result.get("content")
    declared_size = result.get("size")
    if (not isinstance(encoded, str)
            or not isinstance(declared_size, int)
            or isinstance(declared_size, bool)
            or declared_size < 0 or declared_size > 16 * 1024):
        raise Denied("protected main policy payload is invalid")
    try:
        contents = base64.b64decode("".join(encoded.split()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("protected main policy cannot be decoded") from exc
    if len(contents) != declared_size:
        raise Denied("protected main policy size mismatch")
    expected_blob = hashlib.sha1(
        b"blob " + str(len(contents)).encode("ascii") + bytes([0]) + contents
    ).hexdigest()
    if valid_sha(result.get("sha")) != expected_blob:
        raise Denied("protected main policy blob digest mismatch")
    if (not isinstance(local_policy_digest, tuple)
            or len(local_policy_digest) != 2
            or hashlib.sha256(contents).digest() != local_policy_digest[0]):
        raise Denied("local approval policy differs from protected source main")
    # checked_policy_fingerprint validates the exact local policy's entries,
    # including this repository; matching bytes pins that authorization to
    # the protected source main branch rather than an unreviewed PR branch.



def require_source_codeowner_approval(pr_number, review_commit, author):
    """Require a live independent CODEOWNER approval of an exact source commit."""
    review_commit = valid_sha(review_commit)
    # An open PR need not have been reviewed. Never treat an attacker-created
    # same-repository PR and a caller-supplied matching SHA as source authority.
    # Require an explicit current CODEOWNER approval for this exact HEAD.
    reviews_response = gh_api(
        f"repos/{TRUSTED_SOURCE_REPOSITORY}/pulls/{pr_number}/reviews?per_page=100")
    reviews = reviews_response.get("reviews")
    if (not isinstance(reviews, list) or len(reviews) >= MAX_PR_REVIEWS):
        raise Denied("source PR reviews are missing or pagination is incomplete")
    if author.casefold() == TRUSTED_SOURCE_REVIEWER.casefold():
        raise Denied("source PR author cannot self-authorize CI export")
    owner_reviews = []
    for review in reviews:
        if not isinstance(review, dict):
            raise Denied("source PR review data is malformed")
        user = review.get("user")
        if not isinstance(user, dict) or not isinstance(user.get("login"), str):
            raise Denied("source PR reviewer identity is missing")
        if user["login"].casefold() == TRUSTED_SOURCE_REVIEWER.casefold():
            if not isinstance(review.get("id"), int) or isinstance(review["id"], bool):
                raise Denied("source PR CODEOWNER review identity is invalid")
            stamp = review.get("submitted_at")
            if not isinstance(stamp, str):
                raise Denied("source PR CODEOWNER review chronology is invalid")
            try:
                instant = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
            except ValueError as exc:
                raise Denied("source PR CODEOWNER review timestamp is malformed") from exc
            if instant.tzinfo is None or instant.utcoffset() is None:
                raise Denied("source PR CODEOWNER review timestamp lacks timezone")
            owner_reviews.append((instant, review["id"], review))
    if not owner_reviews:
        raise Denied("source PR has no independent CODEOWNER approval")
    # Compare parsed instants: lexicographic ISO strings are unsafe when
    # fractional seconds or timezone offsets differ.
    if len({item[1] for item in owner_reviews}) != len(owner_reviews):
        raise Denied("source PR CODEOWNER review ids are duplicated")
    owner_reviews.sort(key=lambda item: (item[0], item[1]))
    current = owner_reviews[-1][2]
    if current.get("state") != "APPROVED" or current.get("commit_id") != review_commit:
        raise Denied("source PR lacks current exact-HEAD CODEOWNER approval")
    return owner_reviews[-1][0]


def require_source_pr_attestation(pr_number, source_commit, expected_tree):
    """Bind the offered source Git tree to a live same-repo PR HEAD.

    Self-consistent source bytes and a caller-provided Git tree SHA do not
    establish which reviewed source change is being exported. Query the
    canonical private source repository and fail closed if the branch moves.
    """
    if (not isinstance(pr_number, int) or isinstance(pr_number, bool)
            or pr_number < 1 or pr_number > 1_000_000_000):
        raise Denied("source PR number is invalid")
    source_commit = valid_sha(source_commit)
    expected_tree = valid_sha(expected_tree)
    pr = gh_api(f"repos/{TRUSTED_SOURCE_REPOSITORY}/pulls/{pr_number}")
    head = pr.get("head")
    base = pr.get("base")
    if (pr.get("number") != pr_number
            or pr.get("state") != "open"
            or pr.get("draft") is not False
            or not isinstance(pr.get("user"), dict)
            or not isinstance(pr["user"].get("login"), str)
            or not isinstance(head, dict)
            or not isinstance(base, dict)
            or head.get("sha") != source_commit
            or not isinstance(head.get("repo"), dict)
            or head["repo"].get("full_name") != TRUSTED_SOURCE_REPOSITORY
            or not isinstance(base.get("repo"), dict)
            or base["repo"].get("full_name") != TRUSTED_SOURCE_REPOSITORY
            or base.get("ref") != TRUSTED_SOURCE_REF):
        raise Denied("source PR provenance is missing, foreign or changed")
    commit = gh_api(f"repos/{TRUSTED_SOURCE_REPOSITORY}/git/commits/{source_commit}")
    if (commit.get("sha") != source_commit
            or not isinstance(commit.get("tree"), dict)
            or commit["tree"].get("sha") != expected_tree):
        raise Denied("source PR HEAD commit/tree does not match attested snapshot")

    require_source_codeowner_approval(pr_number, source_commit, pr["user"]["login"])




def require_merged_main_attestation(pr_number, merge_commit, expected_tree):
    """Only a reviewed merged PR at the current protected-main HEAD qualifies."""
    if (not isinstance(pr_number, int) or isinstance(pr_number, bool)
            or pr_number < 1 or pr_number > 1_000_000_000):
        raise Denied("merged source PR number is invalid")
    merge_commit = valid_sha(merge_commit)
    expected_tree = valid_sha(expected_tree)
    pr = gh_api(f"repos/{TRUSTED_SOURCE_REPOSITORY}/pulls/{pr_number}")
    head = pr.get("head")
    base = pr.get("base")
    if (pr.get("number") != pr_number
            or pr.get("state") != "closed"
            or pr.get("merged") is not True
            or pr.get("draft") is not False
            or not isinstance(pr.get("merged_at"), str)
            or pr.get("merge_commit_sha") != merge_commit
            or not isinstance(pr.get("user"), dict)
            or not isinstance(pr["user"].get("login"), str)
            or not isinstance(head, dict) or not isinstance(base, dict)
            or not isinstance(head.get("repo"), dict)
            or head["repo"].get("full_name") != TRUSTED_SOURCE_REPOSITORY
            or not isinstance(base.get("repo"), dict)
            or base["repo"].get("full_name") != TRUSTED_SOURCE_REPOSITORY
            or base.get("ref") != TRUSTED_SOURCE_REF):
        raise Denied("merged source PR provenance is missing or changed")
    reviewed_head = valid_sha(head.get("sha"))
    approval_at = require_source_codeowner_approval(
        pr_number, reviewed_head, pr["user"]["login"])
    try:
        merged_at = datetime.fromisoformat(pr["merged_at"].replace("Z", "+00:00"))
    except ValueError as exc:
        raise Denied("merged source PR timestamp is invalid") from exc
    if (merged_at.tzinfo is None or merged_at.utcoffset() is None
            or approval_at > merged_at):
        raise Denied("source CODEOWNER approval must precede PR merge")
    current = gh_api(f"repos/{TRUSTED_SOURCE_REPOSITORY}/branches/{TRUSTED_SOURCE_REF}")
    if (current.get("name") != TRUSTED_SOURCE_REF
            or current.get("protected") is not True
            or not isinstance(current.get("commit"), dict)
            or current["commit"].get("sha") != merge_commit):
        raise Denied("protected main HEAD no longer matches approved merge")
    commit = gh_api(f"repos/{TRUSTED_SOURCE_REPOSITORY}/git/commits/{merge_commit}")
    if (commit.get("sha") != merge_commit
            or not isinstance(commit.get("tree"), dict)
            or commit["tree"].get("sha") != expected_tree):
        raise Denied("merged source main commit/tree does not match snapshot")



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
            if (name in (".", "..") or name.casefold() == ".git"
                    or any(ord(ch) < 32 or ord(ch) == 127 for ch in name)
                    or "\\" in name):
                raise Denied("source path contains unsupported name")
            rel = prefix + name
            try:
                metadata = os.stat(name, dir_fd=directory_fd,
                                   follow_symlinks=False)
                if stat.S_ISDIR(metadata.st_mode):
                    child_fd = os.open(name, directory_flags, dir_fd=directory_fd)
                    try:
                        if not stat.S_ISDIR(os.fstat(child_fd).st_mode):
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
                        metadata = os.fstat(stream.fileno())
                        if not stat.S_ISREG(metadata.st_mode):
                            raise Denied("source file changed type during traversal")
                        # A hardlink may refer to outside-checkout bytes.
                        if metadata.st_nlink != 1:
                            raise Denied("hardlinked source files are not allowed")
                        if metadata.st_size > MAX_SINGLE:
                            raise Denied("file exceeds source mirror limit")
                        data = stream.read(MAX_SINGLE + 1)
                    if len(data) > MAX_SINGLE:
                        raise Denied("file exceeds source mirror limit")
                    total += len(data)
                    if total > MAX_BYTES or len(entries) >= MAX_FILES:
                        raise Denied("source mirror exceeds bounded capacity")
                    mode = "100755" if metadata.st_mode & stat.S_IXUSR else "100644"
                    entries.append((rel, data, mode))
                else:
                    raise Denied("source contains symlink or special file")
            except OSError as exc:
                raise Denied("source path changed or cannot be opened safely") from exc

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



def git_tree_digest(files):
    """Reconstruct the canonical Git tree SHA from source bytes and file modes.

    A Git SHA for each uploaded blob alone does not prove that the *complete*
    directory snapshot matches the reviewed source commit. Git tree entries
    sort directories as names with a trailing slash, so use that ordering.
    """
    root = {}
    for rel, data, mode in files:
        if mode not in ("100644", "100755"):
            raise Denied("source snapshot contains unsupported Git file mode")
        if not isinstance(rel, str) or not isinstance(data, bytes):
            raise Denied("source snapshot contains invalid entry types")
        components = rel.split("/")
        if (not components
                or any(not component or component in (".", "..")
                       or component.casefold() == ".git"
                       or "\\" in component
                       or any(ord(char) < 32 or ord(char) == 127
                              for char in component)
                       for component in components)):
            raise Denied("source snapshot contains noncanonical path")
        node = root
        for component in components[:-1]:
            child = node.setdefault(component, {})
            if not isinstance(child, dict):
                raise Denied("source snapshot file/directory collision")
            node = child
        basename = components[-1]
        if basename in node:
            raise Denied("duplicate source snapshot path")
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii")
                            + bytes([0]) + data).digest()
        node[basename] = (mode, blob)

    def digest(directory):
        encoded = []
        for name, entry in directory.items():
            try:
                name_bytes = name.encode("utf-8")
            except UnicodeError as exc:
                raise Denied("source path cannot be represented as UTF-8") from exc
            if isinstance(entry, dict):
                mode = b"40000"
                sha = digest(entry)
                sort_key = name_bytes + b"/"
            else:
                mode = entry[0].encode("ascii")
                sha = entry[1]
                sort_key = name_bytes
            encoded.append((sort_key, mode + b" " + name_bytes
                            + bytes([0]) + sha))
        body = b"".join(record for _, record in sorted(encoded,
                                                       key=lambda item: item[0]))
        return hashlib.sha1(b"tree " + str(len(body)).encode("ascii")
                            + bytes([0]) + body).digest()

    return digest(root).hex()

def verified_root_tree(repository, tree_sha):
    """Read back a complete stored root tree; reject truncation and ambiguity.

    GitHub's tree creation acknowledgement alone cannot prove that workflows
    and non-source files were preserved. Compare the actual stored root entries
    against the independently read destination baseline before commit/ref.
    """
    tree_sha = valid_sha(tree_sha)
    observed = gh_api(f"repos/{repository}/git/trees/{tree_sha}")
    entries = observed.get("tree")
    if (observed.get("sha") != tree_sha
            or observed.get("truncated") is not False
            or not isinstance(entries, list)
            or len(entries) > MAX_DIRECTORY_ENTRIES):
        raise Denied("destination root tree read-back is missing or truncated")
    result = {}
    modes = {
        "tree": ("040000",),
        "blob": ("100644", "100755", "120000"),
        "commit": ("160000",),
    }
    for entry in entries:
        if not isinstance(entry, dict):
            raise Denied("destination root tree read-back contains invalid entries")
        path, mode, kind = (entry.get("path"), entry.get("mode"),
                            entry.get("type"))
        if (not isinstance(path, str) or not path or "/" in path
                or path in (".", "..") or "\x00" in path
                or path in result or not isinstance(kind, str)
                or not isinstance(mode, str) or kind not in modes
                or mode not in modes[kind]):
            raise Denied("destination root tree read-back has ambiguous entries")
        result[path] = (mode, kind, valid_sha(entry.get("sha")))
    return result


def publish(repository, source, branch, trusted_root, expected_tree=None,
            source_pr=None, source_commit=None, merged_pr=None):
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    # Reject an absent, malformed, or caller-substituted proof before reading
    # sensitive source bytes or invoking the destination preflight.
    expected_tree = valid_sha(expected_tree)
    selected = merged_pr if merged_pr is not None else source_pr
    if ((source_pr is None) == (merged_pr is None)
            or not isinstance(selected, int) or isinstance(selected, bool)
            or selected < 1 or selected > 1_000_000_000):
        raise Denied("specify exactly one open or merged PR source")
    source_commit = valid_sha(source_commit)
    def verify_source():
        if merged_pr is not None:
            require_merged_main_attestation(merged_pr, source_commit, expected_tree)
        else:
            require_source_pr_attestation(source_pr, source_commit, expected_tree)
    # Approval comes before reading potentially sensitive source files.
    preflight_destination = require_preflight(repository, trusted_root)
    if (not isinstance(preflight_destination, dict)
            or not isinstance(preflight_destination.get("id"), int)
            or isinstance(preflight_destination["id"], bool)
            or preflight_destination["id"] <= 0):
        raise Denied("initial destination preflight lacks immutable repository identity")
    preflight_destination_id = preflight_destination["id"]
    approved_policy = checked_policy_fingerprint(trusted_root, repository)
    if approved_policy[1] != preflight_destination_id:
        raise Denied("preflight destination ID differs from reviewed policy")
    require_protected_main_policy(repository, approved_policy)
    verify_source()
    try:
        trusted = trusted_root.resolve(strict=True)
        checked_source = source.resolve(strict=True)
        checked_source.relative_to(trusted)
    except (OSError, RuntimeError, ValueError) as exc:
        raise Denied("source directory escapes trusted checkout or is unavailable") from exc
    # Revalidate immutable destination identity before any private source
    # bytes are collected. Otherwise an owner/repo substitution between
    # preflight and collection can silently become the publication target.
    base = checked_repo(repository)
    if base["id"] != preflight_destination_id:
        raise Denied("destination repository ID changed after reviewed preflight")
    destination_repo_id = base["id"]
    files = collect_source(source, trusted_root)
    actual_source_tree = git_tree_digest(files)
    if actual_source_tree != expected_tree:
        raise Denied("source snapshot does not match the independently attested Git tree")
    default = base.get("default_branch")
    if not isinstance(default, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", default):
        raise Denied("destination default branch is invalid")
    base_ref = gh_api(f"repos/{repository}/git/ref/heads/{default}")
    if (base_ref.get("ref") != "refs/heads/" + default):
        raise Denied("destination default branch ref identity is inconsistent")
    base_object = base_ref.get("object")
    if (not isinstance(base_object, dict)
            or base_object.get("type") != "commit"):
        raise Denied("destination default ref must target a commit")
    base_sha = valid_sha(base_object.get("sha"))
    require_destination_branch_controls(repository, default, base_sha)
    base_commit = gh_api(f"repos/{repository}/git/commits/{base_sha}")
    if base_commit.get("sha", base_sha) != base_sha:
        raise Denied("destination default commit identity changed")
    base_tree_object = base_commit.get("tree")
    if not isinstance(base_tree_object, dict):
        raise Denied("destination default commit tree is malformed")
    base_tree = valid_sha(base_tree_object.get("sha"))
    # Read the pinned parent tree before sending a single private source blob.
    base_root_entries = verified_root_tree(repository, base_tree)
    def authorize_write():
        # Re-check the independently protected policy BEFORE each blob write:
        # revocation after the first upload must stop further orphaned blobs,
        # not only the eventual creation of the CI branch reference.
        if checked_policy_fingerprint(trusted_root, repository) != approved_policy:
            raise Denied("private CI destination approval policy changed during transfer")
        require_protected_main_policy(repository, approved_policy)
        # Source PR HEAD/commit can move *after* the first private blob was
        # uploaded. Abort before the next Git write, not just before the ref.
        verify_source()
        # The destination's workflow/tooling base is pinned too. A move of
        # default HEAD (or a default-branch switch) must stop additional
        # source blob uploads before creating a stale CI branch.
        current_destination = checked_repo(repository)
        if current_destination["id"] != destination_repo_id:
            raise Denied("destination immutable repository identity changed during transfer")
        if current_destination.get("default_branch") != default:
            raise Denied("destination default branch changed during transfer")
        live_ref = gh_api(f"repos/{repository}/git/ref/heads/{default}")
        if live_ref.get("ref") != "refs/heads/" + default:
            raise Denied("destination default ref identity changed during transfer")
        live_object = live_ref.get("object")
        if (not isinstance(live_object, dict)
                or live_object.get("type") != "commit"):
            raise Denied("destination default ref must target a commit")
        if valid_sha(live_object.get("sha")) != base_sha:
            raise Denied("destination default HEAD changed during transfer")
        require_destination_branch_controls(repository, default, base_sha)

    elements = []
    for rel, data, mode in files:
        authorize_write()  # recheck approval and identity before each write
        blob = gh_api(f"repos/{repository}/git/blobs", method="POST", payload={
            "content": base64.b64encode(data).decode("ascii"), "encoding": "base64"
        })
        blob_sha = valid_sha(blob.get("sha"))
        expected = hashlib.sha1(b"blob " + str(len(data)).encode("ascii")
                                + bytes([0]) + data).hexdigest()
        if blob_sha != expected:
            raise Denied("uploaded blob digest does not match local source bytes")
        elements.append({"path": rel, "mode": mode,
                         "type": "blob", "sha": blob_sha})
    # Construct the source subtree from scratch, without base_tree. Overlaying
    # PREFIX/file onto the destination base tree retains old private files
    # that are no longer present in this snapshot.
    authorize_write()
    isolated = gh_api(f"repos/{repository}/git/trees", method="POST",
                      payload={"tree": elements})
    isolated_sha = valid_sha(isolated.get("sha"))
    if isolated_sha != expected_tree:
        raise Denied("destination source subtree does not match attested Git tree")
    # Replace the entire PREFIX subtree atomically in the new root tree.
    # Preserve unrelated destination files and CI workflows from base_tree.
    authorize_write()
    new_tree = gh_api(f"repos/{repository}/git/trees", method="POST",
                      payload={"base_tree": base_tree, "tree": [
                          {"path": PREFIX, "mode": "040000", "type": "tree",
                           "sha": isolated_sha}]})
    new_tree_sha = valid_sha(new_tree.get("sha"))
    # Read the actual stored root tree, not just the POST acknowledgement.
    # No non-source root path (notably .github/workflows) may be added, deleted,
    # or silently replaced when the isolated source subtree is grafted in.
    published_root_entries = verified_root_tree(repository, new_tree_sha)
    expected_root_entries = dict(base_root_entries)
    expected_root_entries[PREFIX] = ("040000", "tree", isolated_sha)
    if published_root_entries != expected_root_entries:
        raise Denied("destination root tree differs from the reviewed base")
    authorize_write()
    commit = gh_api(f"repos/{repository}/git/commits", method="POST", payload={
        "message": "ci: source snapshot from approved private Oreslang checkout",
        "tree": new_tree_sha, "parents": [base_sha]
    })
    commit_sha = valid_sha(commit.get("sha"))
    # A successful POST response does not establish what tree and parent were
    # actually stored in GitHub's commit database. Independently read the
    # created commit before attaching any branch ref to it. A wrong tree here
    # could run unreviewed destination workflows or publish stale source.
    committed = gh_api(f"repos/{repository}/git/commits/{commit_sha}")
    committed_tree = committed.get("tree")
    committed_parents = committed.get("parents")
    if (committed.get("sha") != commit_sha
            or not isinstance(committed_tree, dict)
            or committed_tree.get("sha") != new_tree_sha
            or not isinstance(committed_parents, list)
            or len(committed_parents) != 1
            or not isinstance(committed_parents[0], dict)
            or committed_parents[0].get("sha") != base_sha):
        raise Denied("created CI commit read-back tree or parent differs from attestation")
    authorize_write()
    require_protected_main_policy(repository, approved_policy)
    verify_source()
    # CREATE a new CI ref, never UPDATE an existing branch, tag, or main.
    created = gh_api(f"repos/{repository}/git/refs", method="POST", payload={
        "ref": "refs/heads/" + branch, "sha": commit_sha
    })
    if created.get("ref") != "refs/heads/" + branch:
        raise Denied("GitHub did not confirm creation of requested isolated CI branch")
    # A created branch name alone is not proof that it targets the attested
    # commit. Reject malformed, missing, or wrong Git object acknowledgements.
    confirmed = created.get("object")
    if (not isinstance(confirmed, dict)
            or confirmed.get("type") != "commit"
            or confirmed.get("sha") != commit_sha):
        raise Denied("GitHub did not confirm the attested CI commit at the created ref")
    # Read the newly created branch independently. A successful POST response
    # does not by itself prove that the live ref targets this exact commit.
    live_created = gh_api(f"repos/{repository}/git/ref/heads/{branch}")
    live_object = live_created.get("object")
    if (live_created.get("ref") != "refs/heads/" + branch
            or not isinstance(live_object, dict)
            or live_object.get("type") != "commit"
            or live_object.get("sha") != commit_sha):
        raise Denied("created CI ref read-back differs from attested commit")
    return commit_sha, len(files)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--branch", required=True)
    p.add_argument("--expected-source-tree", required=True,
                   help="40-character independently attested Git tree SHA")
    origin = p.add_mutually_exclusive_group(required=True)
    origin.add_argument("--source-pr", type=int,
                        help="approved open source PR")
    origin.add_argument("--source-merged-pr", type=int,
                        help="reviewed PR merged as protected main HEAD")
    p.add_argument("--expected-source-commit", required=True,
                   help="40-character exact source PR HEAD commit SHA")
    args = p.parse_args(argv)
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
    try:
        commit, count = publish(args.repository, args.source_dir, args.branch,
                                root, args.expected_source_tree,
                                args.source_pr, args.expected_source_commit,
                                args.source_merged_pr)
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_SNAPSHOT_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
