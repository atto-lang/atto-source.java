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
BRANCH = re.compile(r"^ci/oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}$")
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"

# These IDs are bound to reviewed source code, never caller-controlled
# environment variables or workflow inputs. A reused owner/repo slug with a
# different immutable GitHub repository ID must not inherit export approval.
SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPOSITORY_ID = 1400792274
PINNED_DESTINATION_IDS = {
    # Identity pins alone do NOT approve any source export. The separate,
    # security-reviewed policy file remains empty until authorization.
    "chapter-publishing-test/zed-pkg-integration-e2e": 1344442382,
    "ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra": 1412142815,
}


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


def checked_identity(repository, expected_id):
    """Recheck immutable numeric identity as well as private visibility."""
    result = gh_api("repos/" + repository)
    if (type(result.get("id")) is not int
            or result["id"] != expected_id
            or not isinstance(result.get("full_name"), str)
            or result["full_name"].casefold() != repository.casefold()
            or result.get("visibility") != "private"
            or result.get("private") is not True
            or result.get("archived") is not False
            or result.get("disabled") is not False):
        raise Denied("repository numeric identity or private visibility changed")
    return result


def checked_source():
    return checked_identity(SOURCE_REPOSITORY, SOURCE_REPOSITORY_ID)


def checked_repo(repository):
    expected_id = PINNED_DESTINATION_IDS.get(repository.casefold())
    if type(expected_id) is not int:
        raise Denied("destination repository ID is not approved in reviewed code")
    return checked_identity(repository, expected_id)


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
    checked_repo(repository)


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
    approved = False
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if not NAME.fullmatch(line):
            raise Denied("private CI approval policy contains an invalid entry")
        if line.casefold() == repository.casefold():
            approved = True
    if not approved:
        raise Denied("destination approval was revoked or absent")
    return hashlib.sha256(contents).digest()

def protected_main_commit():
    """Pin a protected original-source mainline for export authorization."""
    data = gh_api(f"repos/{SOURCE_REPOSITORY}/branches/main")
    commit_ref = data.get("commit")
    if data.get("protected") is not True or not isinstance(commit_ref, dict):
        raise Denied("original source main is not protected")
    return valid_sha(commit_ref.get("sha"))


def verified_protected_main_policy(local_policy_fingerprint):
    """Require exact local approval bytes on protected original-source main."""
    approved_main = protected_main_commit()
    route = (f"repos/{SOURCE_REPOSITORY}/contents/"
             f".github/private-ci-approved-repositories.txt?ref={approved_main}")
    file_metadata = gh_api(route)
    if (file_metadata.get("type") != "file"
            or file_metadata.get("encoding") != "base64"
            or not isinstance(file_metadata.get("content"), str)):
        raise Denied("protected-main source policy is missing or malformed")
    try:
        policy_bytes = base64.b64decode(
            file_metadata["content"].replace("\n", ""), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("protected-main source policy cannot be decoded") from exc
    if (len(policy_bytes) > 16 * 1024
            or hashlib.sha256(policy_bytes).digest() != local_policy_fingerprint):
        raise Denied("local destination policy differs from protected source main")
    expected_blob = hashlib.sha1(
        b"blob " + str(len(policy_bytes)).encode("ascii")
        + b"\0" + policy_bytes).hexdigest()
    if file_metadata.get("sha") != expected_blob:
        raise Denied("protected-main policy blob SHA does not match contents")
    return approved_main


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
            if (name in (".", "..")
                    or any(ord(ch) < 32 or ord(ch) == 127 for ch in name)
                    or "\\" in name):
                raise Denied("source path contains unsupported name")
            if name == ".git":
                # Exactly the trusted repository root may contain Git's own
                # metadata. Never export it, follow a symlink to it, or ignore
                # another directory's nested .git entry.
                if prefix or source != trusted:
                    raise Denied("nested or untrusted Git metadata is forbidden")
                try:
                    marker = os.stat(name, dir_fd=directory_fd,
                                     follow_symlinks=False)
                except OSError as exc:
                    raise Denied("trusted Git metadata changed") from exc
                # A regular .git pointer file can redirect the checkout
                # to Git metadata outside trusted_root. Reject linked-worktree
                # indirection: exact-tree admission requires an anchored,
                # ordinary Git metadata directory at the trusted root.
                if not stat.S_ISDIR(marker.st_mode):
                    raise Denied("trusted Git metadata must be a real directory")
                continue
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


def git_snapshot_output(root, *arguments):
    """Read immutable Git metadata only through the trusted checkout root."""
    marker = root / ".git"
    # Without a real in-root Git directory, git -C silently discovers parent
    # repositories or follows worktree pointer files outside the checkout.
    if marker.is_symlink() or not marker.is_dir():
        raise Denied("trusted source root needs an anchored Git metadata directory")
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_")}
    env["GIT_NO_REPLACE_OBJECTS"] = "1"
    env["GIT_CONFIG_NOSYSTEM"] = "1"
    env["GIT_CONFIG_GLOBAL"] = os.devnull
    try:
        process = subprocess.run(["git", "-C", str(root), *arguments],
                                 env=env, capture_output=True, timeout=20,
                                 check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("trusted source Git metadata unavailable") from exc
    if process.returncode != 0 or len(process.stdout) > 2 * 1024 * 1024:
        raise Denied("trusted source Git snapshot cannot be verified")
    return process.stdout


def verify_exact_source_snapshot(source, trusted_root, files, commit, tree):
    """Require the entire collected snapshot to match an explicit Git commit/tree.

    Compare every path, mode, and content blob of the in-memory snapshot to
    Git's committed tree. This catches missing, untracked, modified, and
    post-checkout swapped files before any private bytes reach an API write.
    The GitHub destination's new subtree must separately equal this tree SHA.
    """
    if not isinstance(commit, str) or not SHA.fullmatch(commit):
        raise Denied("exact reviewed source commit SHA is required")
    if not isinstance(tree, str) or not SHA.fullmatch(tree):
        raise Denied("exact reviewed source Git tree SHA is required")
    root = trusted_root.resolve(strict=True)
    target = source.resolve(strict=True)
    try:
        relative = target.relative_to(root)
    except ValueError as exc:
        raise Denied("source directory escapes trusted checkout") from exc
    relative_name = relative.as_posix()
    if relative_name == ".":
        relative_name = ""
    # A directory snapshot must have a concrete committed tree object.
    current_commit = git_snapshot_output(root, "rev-parse", "--verify",
                                         "HEAD^{commit}").strip().decode("ascii")
    if current_commit != commit:
        raise Denied("source checkout commit differs from reviewed source commit")
    object_name = "HEAD:" + relative_name if relative_name else "HEAD^{tree}"
    current_tree = git_snapshot_output(root, "rev-parse", "--verify",
                                       object_name).strip().decode("ascii")
    if current_tree != tree:
        raise Denied("source Git tree differs from reviewed source tree")
    pathspec = [relative_name] if relative_name else []
    records = git_snapshot_output(root, "ls-tree", "-r", "-z", "--full-tree",
                                  "HEAD", "--", *pathspec).split(b"\0")
    tracked = {}
    for record in records:
        if not record:
            continue
        try:
            metadata, name_bytes = record.split(b"\t", 1)
            mode, kind, blob = metadata.decode("ascii").split()
            name = name_bytes.decode("utf-8")
        except (ValueError, UnicodeDecodeError) as exc:
            raise Denied("source Git tree contains unrepresentable entries") from exc
        if kind != "blob" or mode not in ("100644", "100755") or not SHA.fullmatch(blob):
            raise Denied("source Git tree contains unexportable entry")
        prefix = relative_name + "/" if relative_name else ""
        if not name.startswith(prefix):
            raise Denied("source Git tree contains an unexpected path")
        name = name[len(prefix):]
        if not name or name in tracked:
            raise Denied("source Git tree contains ambiguous paths")
        tracked[name] = (mode, blob)
        if len(tracked) > MAX_FILES:
            raise Denied("source Git tree exceeds file count limit")
    if not tracked or len(tracked) != len(files):
        raise Denied("source snapshot is missing or contains untracked files")
    for name, data, mode in files:
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii")
                            + b"\0" + data).hexdigest()
        if tracked.pop(name, None) != (mode, blob):
            raise Denied("source snapshot diverges from the reviewed Git tree")
    if tracked:
        raise Denied("source snapshot omits reviewed Git files")
    return tree


def verify_upstream_commit_tree(commit, expected_tree, relative_directory):
    """Bind the local snapshot to the immutable original private GitHub tree."""
    valid_sha(commit)
    valid_sha(expected_tree)
    commit_data = gh_api(f"repos/{SOURCE_REPOSITORY}/git/commits/{commit}")
    source_tree_ref = commit_data.get("tree")
    if not isinstance(source_tree_ref, dict):
        raise Denied("original source commit has no verifiable Git tree")
    root_tree = valid_sha(source_tree_ref.get("sha"))
    if commit_data.get("sha") != commit:
        raise Denied("original source GitHub commit identity differs from approved SHA")
    current = root_tree
    if relative_directory:
        for name in relative_directory.split("/"):
            if not name or name in (".", ".."):
                raise Denied("invalid source subtree path")
            listing = gh_api(f"repos/{SOURCE_REPOSITORY}/git/trees/{current}")
            entries = listing.get("tree")
            if listing.get("truncated") is not False or not isinstance(entries, list):
                raise Denied("original source tree listing unavailable or truncated")
            if listing.get("sha") != current:
                raise Denied("original source GitHub tree identity differs from requested SHA")
            candidates = [x for x in entries if isinstance(x, dict)
                          and x.get("path") == name]
            if len(candidates) != 1 or candidates[0].get("type") != "tree":
                raise Denied("source subtree absent from original private repository")
            if candidates[0].get("mode") != "040000":
                raise Denied("source subtree has unexpected Git mode")
            current = valid_sha(candidates[0].get("sha"))
    if current != expected_tree:
        raise Denied("source subtree not attested by original GitHub commit")


def verify_private_ci_ref(repository, branch, commit_sha, created):
    """Verify both creation acknowledgement and a fresh GitHub ref read-back."""
    expected_ref = "refs/heads/" + branch
    if not isinstance(created, dict):
        raise Denied("GitHub did not confirm the exact new CI ref and commit")
    created_object = created.get("object")
    if (created.get("ref") != expected_ref
            or not isinstance(created_object, dict)
            or created_object.get("type") != "commit"
            or created_object.get("sha") != commit_sha):
        raise Denied("GitHub did not confirm the exact new CI ref and commit")
    readback = gh_api(f"repos/{repository}/git/ref/heads/{branch}")
    if not isinstance(readback, dict):
        raise Denied("new private CI ref does not point to the verified commit")
    pointed = readback.get("object")
    if (readback.get("ref") != expected_ref
            or not isinstance(pointed, dict)
            or pointed.get("type") != "commit"
            or pointed.get("sha") != commit_sha):
        raise Denied("new private CI ref does not point to the verified commit")


def publish(repository, source, branch, trusted_root,
            source_commit=None, source_tree=None):
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    # Approval comes before reading potentially sensitive source files.
    require_preflight(repository, trusted_root)
    approved_policy = checked_policy_fingerprint(trusted_root, repository)
    # Check both original private source and approved carrier identities before
    # accessing source bytes; neither the slug nor visibility alone is enough.
    checked_source()
    checked_repo(repository)
    approved_source_main = verified_protected_main_policy(approved_policy)
    trusted = trusted_root.resolve(strict=True)
    resolved_source = source.resolve(strict=True)
    try:
        resolved_source.relative_to(trusted)
    except ValueError as exc:
        raise Denied("source directory escapes the trusted checkout") from exc
    files = collect_source(source, trusted_root)
    expected_source_tree = verify_exact_source_snapshot(
        source, trusted_root, files, source_commit, source_tree)
    relative_source = resolved_source.relative_to(trusted).as_posix()
    verify_upstream_commit_tree(
        source_commit, expected_source_tree,
        "" if relative_source == "." else relative_source)
    base = checked_repo(repository)
    default = base.get("default_branch")
    if not isinstance(default, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", default):
        raise Denied("destination default branch is invalid")
    base_ref = gh_api(f"repos/{repository}/git/ref/heads/{default}")
    base_sha = valid_sha(base_ref.get("object", {}).get("sha"))
    base_commit = gh_api(f"repos/{repository}/git/commits/{base_sha}")
    base_tree = valid_sha(base_commit.get("tree", {}).get("sha"))
    def authorize_write():
        if checked_policy_fingerprint(trusted_root, repository) != approved_policy:
            raise Denied("private CI destination approval policy changed during transfer")
        if protected_main_commit() != approved_source_main:
            raise Denied("approved protected source main changed during transfer")
        checked_source()
        checked_repo(repository)

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
        raise Denied("destination subtree differs from reviewed source Git tree")
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
    # Confirm the exact created object by independent GitHub ref read-back.
    verify_private_ci_ref(repository, branch, commit_sha, created)
    return commit_sha, len(files)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--branch", required=True)
    p.add_argument("--source-commit", required=True,
                   help="explicitly reviewed source Git commit SHA")
    p.add_argument("--source-tree", required=True,
                   help="immutable Git tree SHA for --source-dir at that commit")
    args = p.parse_args(argv)
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
    try:
        commit, count = publish(args.repository, args.source_dir, args.branch,
                                root, args.source_commit, args.source_tree)
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_SNAPSHOT_CREATED: files={count} source_commit={args.source_commit} "
          f"source_tree={args.source_tree} destination_commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
