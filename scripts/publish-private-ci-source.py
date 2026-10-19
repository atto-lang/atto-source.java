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
POLICY_ENTRY = re.compile(
    r"^([A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*)@([1-9][0-9]*)$")
BRANCH = re.compile(r"^ci/oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}$")
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"
MIRROR_DESTINATION = "ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra"
DEST_BASE_COMMIT = "054b65982f0950a4e2f3fb4752e78f585198c2ff"
DEST_BASE_TREE = "09ccea168b563ee91f9cd7a938ddabb5a33078ec"


class Denied(RuntimeError):
    pass


def valid_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise Denied("GitHub returned an invalid object SHA")
    return value


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


def checked_repo(repository, expected_id):
    result = gh_api("repos/" + repository)
    actual_id = result.get("id")
    if isinstance(actual_id, bool) or not isinstance(actual_id, (int, str)):
        raise Denied("destination repository ID is missing or invalid")
    actual_id = str(actual_id)
    if (not actual_id.isdigit() or actual_id == "0"
            or actual_id != expected_id
            or not isinstance(result.get("full_name"), str)
            or result["full_name"].casefold() != repository.casefold()
            or result.get("visibility") != "private"
            or result.get("private") is not True
            or result.get("permissions", {}).get("push") is not True):
        raise Denied(
            "destination identity, immutable ID, private visibility, or write permission changed")
    return result


def checked_carrier(repository):
    """Resolve only the immutable reviewed carrier object, never a mutable ref."""
    result = gh_api(f"repos/{repository}/git/commits/{DEST_BASE_COMMIT}")
    commit_sha = valid_sha(result.get("sha"))
    tree_sha = valid_sha(result.get("tree", {}).get("sha"))
    if commit_sha != DEST_BASE_COMMIT or tree_sha != DEST_BASE_TREE:
        raise Denied("reviewed destination carrier commit/tree mismatch")
    return commit_sha, tree_sha


def require_preflight(repository, branch, root, source_pr, source_commit, source_tree,
                      maintainer_comment=None, security_comment=None):
    """Run the authoritative export preflight before any source-byte read.

    This publisher is intentionally destination-bound while the reviewed policy
    contains a single approved mirror target. The shell preflight independently
    revalidates the live PR/head/tree, authoritative main blobs, dual
    attestations when needed, and destination identity.
    """
    if repository != MIRROR_DESTINATION:
        raise Denied("publisher is bound to the reviewed private mirror destination")
    if not isinstance(branch, str) or not BRANCH.fullmatch(branch):
        raise Denied("publisher requires one isolated reviewed CI branch")
    if (not isinstance(source_pr, int) or source_pr <= 0
            or not isinstance(source_commit, str) or not SHA.fullmatch(source_commit)
            or not isinstance(source_tree, str) or not SHA.fullmatch(source_tree)):
        raise Denied("exact source PR, commit and tree authorization is required")
    if (maintainer_comment is None) != (security_comment is None):
        raise Denied("both review-attested comment IDs must be supplied together")
    for value in (maintainer_comment, security_comment):
        if value is not None and (not isinstance(value, int) or value <= 0):
            raise Denied("invalid review-attested comment ID")

    preflight = root / "scripts" / "preflight-private-ci-mirror.sh"
    guard = root / "scripts" / "check-private-ci-destination.sh"
    for program in (preflight, guard):
        if program.is_symlink() or not program.is_file():
            raise Denied("trusted private CI authorization program missing")

    argv = ["bash", str(preflight), str(source_pr), source_commit, source_tree, branch]
    if maintainer_comment is not None:
        argv.extend([str(maintainer_comment), str(security_comment)])
    try:
        p = subprocess.run(argv, cwd=root, capture_output=True, text=True,
                           timeout=45, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("private source export preflight unavailable") from exc
    if p.returncode != 0:
        raise Denied("private source export preflight denied")

    try:
        p = subprocess.run(["bash", str(guard), repository], cwd=root,
                           capture_output=True, text=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("destination policy check unavailable") from exc
    if p.returncode != 0:
        raise Denied("destination not approved by checked-in private policy")

def checked_policy_fingerprint(root, repository):
    """Read and bind approval to one immutable GitHub repository ID.

    The policy is opened descriptor-relative with O_NOFOLLOW to resist
    symlink/ancestor swaps. Every active row is owner/repo@numeric-id; duplicate
    repository names, CRLF, malformed UTF-8, or a changed ID fail closed.
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
                        raise Denied(
                            "private CI approval policy is not a regular single-link file")
                    contents = stream.read(16 * 1024 + 1)
            finally:
                os.close(policy_dir_fd)
        finally:
            os.close(root_fd)
    except OSError as exc:
        raise Denied("private CI approval policy cannot be opened safely") from exc

    if len(contents) > 16 * 1024:
        raise Denied("private CI approval policy exceeds size limit")
    if b"\r" in contents:
        raise Denied("private CI approval policy must use LF line endings only")
    try:
        text = contents.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise Denied("private CI approval policy cannot be decoded") from exc

    expected_id = None
    seen = set()
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = POLICY_ENTRY.fullmatch(line)
        if match is None:
            raise Denied("private CI approval policy contains an invalid entry")
        approved_repo, approved_id = match.groups()
        key = approved_repo.casefold()
        if key in seen:
            raise Denied("private CI approval policy contains a duplicate destination")
        seen.add(key)
        if key == repository.casefold():
            expected_id = approved_id

    if expected_id is None:
        raise Denied("destination approval was revoked or absent")
    return hashlib.sha256(contents).digest(), expected_id

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
    # A real Git checkout necessarily has repository metadata at its root.
    # Exact-tree publication must be able to snapshot that checkout root while
    # excluding metadata that is not part of HEAD^{tree}. A nested .git path
    # remains forbidden so a caller cannot hide arbitrary content inside a
    # partial source directory.
    skip_root_git_metadata = source == trusted

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
            if name == ".git":
                if depth == 0 and prefix == "" and skip_root_git_metadata:
                    continue
                raise Denied("source path contains unsupported .git metadata")
            if (name in (".", "..")
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


def publish(repository, source, branch, trusted_root, source_pr=None,
            source_commit=None, source_tree=None, maintainer_comment=None,
            security_comment=None):
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    # Approval comes before reading potentially sensitive source files.
    require_preflight(repository, branch, trusted_root, source_pr, source_commit,
                      source_tree, maintainer_comment, security_comment)
    approved_policy, expected_id = checked_policy_fingerprint(
        trusted_root, repository)
    trusted = trusted_root.resolve(strict=True)
    checked_source = source.resolve(strict=True)
    try:
        checked_source.relative_to(trusted)
    except ValueError as exc:
        raise Denied("source directory escapes the trusted checkout") from exc
    files = collect_source(source, trusted_root)
    # Approval can be revoked while a bounded source tree is being read.
    # Revalidate before the first private byte is uploaded.
    require_preflight(repository, branch, trusted_root, source_pr, source_commit,
                      source_tree, maintainer_comment, security_comment)
    checked_repo(repository, expected_id)
    base_sha, base_tree = checked_carrier(repository)
    def authorize_write():
        current_policy, current_id = checked_policy_fingerprint(
            trusted_root, repository)
        if current_policy != approved_policy or current_id != expected_id:
            raise Denied("private CI destination approval policy changed during transfer")
        checked_repo(repository, expected_id)

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
    # This is the exact-tree oracle: the destination source subtree must be
    # byte/mode/path-identical to the authorized source HEAD^{tree}. A partial
    # source-dir, stale read, omitted file, or mode drift must never become a
    # reachable carrier branch under an exact-tree authorization.
    if isolated_sha != source_tree:
        raise Denied("destination source subtree does not match authorized source tree")
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
        "message": (
            f"ci: exact private Oreslang tree {source_commit} tree {source_tree}"
        ),
        "tree": new_tree_sha, "parents": [base_sha]
    })
    commit_sha = valid_sha(commit.get("sha"))
    authorize_write()
    # A deleted/edited attestation or changed PR head must prevent publication
    # even if private unreferenced blobs/trees were already uploaded.
    require_preflight(repository, branch, trusted_root, source_pr, source_commit,
                      source_tree, maintainer_comment, security_comment)
    authorize_write()
    final_base_sha, final_base_tree = checked_carrier(repository)
    if final_base_sha != base_sha or final_base_tree != base_tree:
        raise Denied("reviewed destination carrier changed during transfer")
    # CREATE a new CI ref, never UPDATE an existing branch, tag, or main.
    created = gh_api(f"repos/{repository}/git/refs", method="POST", payload={
        "ref": "refs/heads/" + branch, "sha": commit_sha
    })
    if created.get("ref") != "refs/heads/" + branch:
        raise Denied("GitHub did not confirm creation of requested isolated CI branch")
    return commit_sha, len(files)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--branch", required=True)
    p.add_argument("--source-pr", required=True, type=int)
    p.add_argument("--source-commit", required=True)
    p.add_argument("--source-tree", required=True)
    p.add_argument("--maintainer-approval-comment", type=int)
    p.add_argument("--security-approval-comment", type=int)
    args = p.parse_args(argv)
    if ((args.maintainer_approval_comment is None)
            != (args.security_approval_comment is None)):
        p.error("both approval comment IDs must be supplied together")
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
    try:
        commit, count = publish(
            args.repository, args.source_dir, args.branch, root,
            args.source_pr, args.source_commit, args.source_tree,
            args.maintainer_approval_comment, args.security_approval_comment)
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_SNAPSHOT_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
