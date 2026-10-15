#!/usr/bin/env python3
"""Publish a snapshot branch ONLY into a reviewed private GitHub repository.

No arbitrary command execution or user-selectable GitHub API endpoints. All
writes target the exact repository checked by the private-source policy.
"""
import argparse
import base64
import hashlib
import json
import os
import stat
from pathlib import Path
import re
import subprocess
import sys

NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$")
# An approved destination must include its stable numeric GitHub repository ID.
# This closes name reuse between security review and the first transfer.
POLICY_ENTRY = re.compile(r"^([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*) ([1-9][0-9]{0,19})$")
BRANCH = re.compile(r"^ci/oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}$")
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"
# This policy must be approved on the protected source main branch, not merely
# present in the caller's current PR/worktree.
SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPOSITORY_ID = 1400792274
SOURCE_METADATA_ROUTE = "repos/" + SOURCE_REPOSITORY
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
PROTECTED_RULES_ROUTE = PROTECTED_BRANCH_ROUTE + "/protection"



class Denied(RuntimeError):
    pass


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


def gh_api(route, method="GET", payload=None):
    argv = ["gh", "api", "--method", method, route,
            "--header", "Accept: application/vnd.github+json"]
    if payload is not None:
        argv.extend(["--input", "-"])
    env = dict(os.environ, GH_HOST="github.com", GH_PROMPT_DISABLED="1")
    try:
        p = subprocess.run(argv, input=json.dumps(payload) if payload is not None else None,
                           capture_output=True, text=True, env=env, timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("authenticated GitHub API request unavailable") from exc
    if p.returncode != 0:
        # Never echo stderr: a client can include private file bytes in errors.
        raise Denied("authenticated GitHub API request failed")
    try:
        data = json.loads(p.stdout)
    except json.JSONDecodeError as exc:
        raise Denied("invalid GitHub API JSON response") from exc
    if not isinstance(data, dict):
        raise Denied("unexpected GitHub response shape")
    return data


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
    guard = root / "scripts" / "check-private-ci-destination.sh"
    if guard.is_symlink() or not guard.is_file():
        raise Denied("trusted destination approval guard missing")
    try:
        p = subprocess.run(["bash", str(guard), repository],
                           capture_output=True, text=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("destination policy check unavailable") from exc
    if p.returncode != 0:
        raise Denied("destination not approved by checked-in private policy")
    return checked_repo(repository)["id"]


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
    if (not isinstance(required_checks, dict)
            or required_checks.get("strict") is not True
            or not isinstance(required_checks.get("contexts"), list)
            or not required_checks["contexts"]
            or not all(isinstance(c, str) and c for c in required_checks["contexts"])):
        raise Denied("protected main must require strict source CI checks")
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
            response = subprocess.run(["git", "-C", str(base), *args],
                                      capture_output=True, text=True,
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
    for rel in protected_paths:
        # A tracked, unmodified file is required. Untracked replacement and
        # staged/unstaged edits both fail before opening any private source.
        tracked = git("ls-files", "--error-unmatch", "--", rel)
        if tracked != rel:
            raise Denied("reviewed publisher code or policy is not Git-tracked")
    if git("status", "--porcelain", "--untracked-files=no", "--", *protected_paths):
        raise Denied("reviewed publisher code or source policy was modified")
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
        parts = relative.split("/")
        node = root
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii")
                            + b"\0" + data).digest()
        if parts[-1] in node:
            raise Denied("source snapshot contains conflicting file names")
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

def publish(repository, source, branch, trusted_root, expected_source_tree,
            expected_source_commit):
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    valid_sha(expected_source_tree)
    valid_sha(expected_source_commit)
    # This function previously ran the checkout-local Bash preflight BEFORE
    # authenticating the checkout. A modified PR checkout could execute an
    # attacker-controlled guard even though source export would later deny.
    # Read policy strictly as data and authenticate the protected source first.
    approved_policy = checked_policy_fingerprint(trusted_root, repository)
    # A PR branch can contain an allowlist entry without security approval.
    # Require the exact policy Git blob to exist on protected source main.
    reviewed_main_sha = require_protected_main_policy(approved_policy)
    require_trusted_publisher_checkout(trusted_root, reviewed_main_sha)
    # Only a pinned, clean protected-main checkout may execute its own guard.
    # All failures above occur before launching any checkout-local code,
    # inspecting private source files, or writing destination Git objects.
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
    files = collect_source(source, trusted_root)
    if source_tree_sha(files) != expected_source_tree:
        raise Denied("source snapshot Git tree differs from pinned expected SHA")
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
        return current_sha
    base_sha = require_pinned_carrier_main()
    base_commit = gh_api(f"repos/{repository}/git/commits/{base_sha}")
    base_tree = nested_sha(base_commit, "tree")
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
        require_pinned_carrier_main(base_sha)

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
    if nested_sha(verified_ref, "object") != commit_sha:
        raise Denied("CI branch readback points to a different commit")
    verified_commit = gh_api(f"repos/{repository}/git/commits/{commit_sha}")
    if nested_sha(verified_commit, "tree") != new_tree_sha:
        raise Denied("CI branch commit tree differs from published snapshot")
    verified_tree = gh_api(f"repos/{repository}/git/trees/{new_tree_sha}")
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
