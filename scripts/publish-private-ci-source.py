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
REPOSITORY_ID = re.compile(r"^[1-9][0-9]*$")
BRANCH = re.compile(r"^ci/oreslang-[A-Za-z0-9][A-Za-z0-9_.-]{0,70}$")
SHA = re.compile(r"^[a-f0-9]{40}$")
MAX_FILES = 1024
MAX_BYTES = 40 * 1024 * 1024
MAX_SINGLE = 10 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
MAX_DIRECTORIES = 1024
PREFIX = "private-oreslang-source"
POLICY_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
POLICY_REF = "main"
POLICY_PATH = ".github/private-ci-approved-repositories.txt"
# Immutable Git blob reviewed in this security PR. Protected-main/ruleset
# introspection is not assumed to be available to the publisher.
EXPECTED_POLICY_BLOB_SHA = "7f06fd603fb385273ccd6ef47b3c7ec4c6a40604"
CARRIER_WORKFLOW_PATH = ".github/workflows/private-oreslang-exact-tree.yml"
# Independently reviewed destination-main workflow. The publisher overlays only
# PREFIX, so this exact workflow remains part of the carrier commit that runs.
EXPECTED_CARRIER_WORKFLOW_BLOB_SHA = "a32ca1b747aba32f66f44c2bb43a280663833166"
MAX_POLICY_BYTES = 16 * 1024


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
    if (not isinstance(result.get("full_name"), str)
            or result["full_name"].casefold() != repository.casefold()
            or not isinstance(actual_id, int)
            or actual_id != expected_id
            or result.get("visibility") != "private"
            or result.get("private") is not True):
        raise Denied("destination identity, repository ID, or private visibility changed")
    return result


def checked_carrier_workflow(repository, ref):
    response = gh_api(
        f"repos/{repository}/contents/{CARRIER_WORKFLOW_PATH}?ref={ref}")
    if response.get("type") != "file":
        raise Denied("reviewed carrier workflow has unexpected shape")
    workflow_blob = valid_sha(response.get("sha"))
    if workflow_blob != EXPECTED_CARRIER_WORKFLOW_BLOB_SHA:
        raise Denied("carrier exact-tree workflow differs from reviewed workflow blob")
    return workflow_blob


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


def _policy_authorization(contents, repository):
    if len(contents) > MAX_POLICY_BYTES:
        raise Denied("private CI approval policy exceeds size limit")
    try:
        lines = contents.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise Denied("private CI approval policy cannot be decoded") from exc

    approved_id = None
    for line in lines:
        if not line or line.startswith("#"):
            continue
        parts = line.split(" ")
        if (len(parts) != 2 or not NAME.fullmatch(parts[0])
                or not REPOSITORY_ID.fullmatch(parts[1])):
            raise Denied("private CI approval policy contains an invalid entry")
        if parts[0].casefold() == repository.casefold():
            if approved_id is not None:
                raise Denied("private CI approval policy contains duplicate destination entries")
            approved_id = int(parts[1])
    if approved_id is None:
        raise Denied("destination approval was revoked or absent")
    return hashlib.sha256(contents).digest(), approved_id

def checked_policy_fingerprint(root, repository):
    """Read the local policy through directory descriptors and O_NOFOLLOW."""
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
                    contents = stream.read(MAX_POLICY_BYTES + 1)
            finally:
                os.close(policy_dir_fd)
        finally:
            os.close(root_fd)
    except OSError as exc:
        raise Denied("private CI approval policy cannot be opened safely") from exc
    return _policy_authorization(contents, repository)


def checked_protected_policy_fingerprint(repository):
    """Verify approval against protected main, not merely the local checkout.

    This prevents an unreviewed branch from self-authorizing a destination by
    editing its local allowlist. The publisher still must itself come from a
    trusted checkout; a maliciously modified publisher cannot self-attest.
    """
    response = gh_api(
        f"repos/{POLICY_REPOSITORY}/contents/{POLICY_PATH}?ref={POLICY_REF}")
    if response.get("type") != "file" or response.get("encoding") != "base64":
        raise Denied("protected private CI approval policy has unexpected shape")
    policy_blob = valid_sha(response.get("sha"))
    if policy_blob != EXPECTED_POLICY_BLOB_SHA:
        raise Denied(
            "main private CI approval policy differs from reviewed policy blob")
    encoded = response.get("content")
    if not isinstance(encoded, str):
        raise Denied("protected private CI approval policy is missing content")
    try:
        contents = base64.b64decode("".join(encoded.splitlines()), validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise Denied("protected private CI approval policy is not valid base64") from exc
    return _policy_authorization(contents, repository)

def git_bytes(root, *args, timeout=45):
    """Read immutable objects from the trusted local Git database."""
    argv = ["git", "-C", str(root), *args]
    env = dict(
        os.environ,
        GIT_TERMINAL_PROMPT="0",
        GIT_CONFIG_NOSYSTEM="1",
        GIT_PAGER="cat",
    )
    try:
        p = subprocess.run(
            argv,
            capture_output=True,
            env=env,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("trusted Git object query unavailable") from exc
    if p.returncode != 0:
        raise Denied("trusted Git object query failed")
    return p.stdout


def git_text(root, *args):
    raw = git_bytes(root, *args)
    try:
        return raw.decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise Denied("trusted Git metadata is not UTF-8") from exc


def git_blob_sha(data):
    return hashlib.sha1(
        b"blob " + str(len(data)).encode("ascii") + bytes([0]) + data
    ).hexdigest()


def collect_git_tree(root, source_commit):
    """Read exactly one immutable Git commit tree, never the working directory.

    Untracked/generated/secret files cannot enter the mirror because bytes are
    read only through git cat-file for blob IDs named by git ls-tree.
    Symlinks and submodules fail closed because executing them below the
    destination prefix could escape the isolated source subtree.
    """
    if not SHA.fullmatch(source_commit):
        raise Denied("source commit must be an exact 40-character Git SHA")
    if root.is_symlink() or not root.is_dir():
        raise Denied("source repository must be a real directory")
    try:
        trusted = root.resolve(strict=True)
    except OSError as exc:
        raise Denied("source repository cannot be resolved") from exc

    top = git_text(trusted, "rev-parse", "--show-toplevel")
    try:
        top_path = Path(top).resolve(strict=True)
    except OSError as exc:
        raise Denied("trusted Git top-level cannot be resolved") from exc
    if top_path != trusted:
        raise Denied("source directory must be the trusted Git checkout root")

    resolved_commit = git_text(
        trusted, "rev-parse", "--verify", source_commit + "^{commit}")
    if resolved_commit != source_commit:
        raise Denied("source commit does not resolve to the requested exact SHA")
    source_tree = valid_sha(
        git_text(trusted, "rev-parse", source_commit + "^{tree}"))

    raw_tree = git_bytes(
        trusted, "ls-tree", "-r", "-z", "--full-tree", source_commit)
    records = raw_tree.split(bytes([0]))
    if records and records[-1] == b"":
        records.pop()
    if not records:
        raise Denied("source Git tree is empty")
    if len(records) > MAX_FILES:
        raise Denied("source Git tree exceeds file-count limit")

    entries = []
    seen_paths = set()
    total = 0
    for raw in records:
        try:
            metadata, raw_path = raw.split(b"\t", 1)
            mode_raw, object_type, sha_raw = metadata.split(b" ", 2)
            mode = mode_raw.decode("ascii")
            object_kind = object_type.decode("ascii")
            blob_sha = valid_sha(sha_raw.decode("ascii"))
            rel = raw_path.decode("utf-8")
        except (ValueError, UnicodeDecodeError) as exc:
            raise Denied("source Git tree contains unsupported metadata") from exc

        if object_kind != "blob" or mode not in {"100644", "100755"}:
            raise Denied("source Git tree contains symlink, submodule, or unsupported mode")
        parts = rel.split("/")
        if (not rel or rel.startswith("/") or any(
                not part or part in {".", ".."} for part in parts)
                or any(part.casefold() == ".git" for part in parts)
                or len(parts) > 64
                or "\\" in rel
                or any(ord(ch) < 32 or ord(ch) == 127 for ch in rel)):
            raise Denied("source Git tree contains unsupported path")
        if rel in seen_paths:
            raise Denied("source Git tree contains duplicate path")
        seen_paths.add(rel)

        size_text = git_text(trusted, "cat-file", "-s", blob_sha)
        if not size_text.isdigit():
            raise Denied("source Git blob size is invalid")
        size = int(size_text)
        if size > MAX_SINGLE:
            raise Denied("file exceeds source mirror limit")
        total += size
        if total > MAX_BYTES:
            raise Denied("source mirror exceeds bounded capacity")

        data = git_bytes(trusted, "cat-file", "blob", blob_sha)
        if len(data) != size or git_blob_sha(data) != blob_sha:
            raise Denied("local Git blob bytes do not match source object ID")
        entries.append((rel, data, mode, blob_sha))

    return entries, source_tree

def publish(repository, source, source_commit, branch, trusted_root):
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    if not SHA.fullmatch(source_commit):
        raise Denied("source commit must be an exact 40-character Git SHA")
    # Approval comes before reading potentially sensitive source files.
    require_preflight(repository, trusted_root)
    approved_policy, approved_repo_id = checked_policy_fingerprint(
        trusted_root, repository)
    protected_policy, protected_repo_id = checked_protected_policy_fingerprint(
        repository)
    if (approved_policy != protected_policy
            or approved_repo_id != protected_repo_id):
        raise Denied("local private CI approval policy differs from protected main")
    trusted = trusted_root.resolve(strict=True)
    checked_source = source.resolve(strict=True)
    if checked_source != trusted:
        raise Denied("source directory must be the trusted checkout root")

    # Verify the exact carrier code that will execute the mirror before reading
    # private source bytes. The new carrier commit uses this immutable base tree
    # and overlays only PREFIX, so source content cannot replace the workflow.
    base = checked_repo(repository, approved_repo_id)
    default = base.get("default_branch")
    if not isinstance(default, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", default):
        raise Denied("destination default branch is invalid")
    base_ref = gh_api(f"repos/{repository}/git/ref/heads/{default}")
    base_sha = valid_sha(base_ref.get("object", {}).get("sha"))
    base_commit = gh_api(f"repos/{repository}/git/commits/{base_sha}")
    base_tree = valid_sha(base_commit.get("tree", {}).get("sha"))
    checked_carrier_workflow(repository, base_sha)

    files, source_tree_sha = collect_git_tree(checked_source, source_commit)
    def authorize_write():
        local_policy, local_repo_id = checked_policy_fingerprint(
            trusted_root, repository)
        protected_now, protected_repo_id_now = checked_protected_policy_fingerprint(
            repository)
        if (local_policy != approved_policy
                or local_repo_id != approved_repo_id):
            raise Denied("local private CI destination approval policy changed during transfer")
        if (protected_now != protected_policy
                or protected_repo_id_now != approved_repo_id):
            raise Denied("protected private CI destination approval changed during transfer")
        checked_repo(repository, approved_repo_id)

    elements = []
    for rel, data, mode, source_blob_sha in files:
        authorize_write()  # recheck approval and identity before each write
        blob = gh_api(f"repos/{repository}/git/blobs", method="POST", payload={
            "content": base64.b64encode(data).decode("ascii"), "encoding": "base64"
        })
        blob_sha = valid_sha(blob.get("sha"))
        if blob_sha != source_blob_sha:
            raise Denied("uploaded blob digest does not match exact source Git blob")
        elements.append({"path": rel, "mode": mode,
                         "type": "blob", "sha": blob_sha})
    # Construct the source subtree from scratch, without base_tree. Overlaying
    # PREFIX/file onto the destination base tree retains old private files
    # that are no longer present in this snapshot.
    authorize_write()
    isolated = gh_api(f"repos/{repository}/git/trees", method="POST",
                      payload={"tree": elements})
    isolated_sha = valid_sha(isolated.get("sha"))
    if isolated_sha != source_tree_sha:
        raise Denied("destination source subtree does not match exact source Git tree")
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
            "ci: exact private Oreslang tree "
            + source_commit + " tree " + source_tree_sha
        ),
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
    return commit_sha, len(files), source_tree_sha


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--source-commit", required=True)
    p.add_argument("--branch", required=True)
    args = p.parse_args(argv)
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
    try:
        commit, count, source_tree = publish(
            args.repository,
            args.source_dir,
            args.source_commit,
            args.branch,
            root,
        )
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(
        "PRIVATE_CI_SNAPSHOT_CREATED: "
        f"files={count} source_commit={args.source_commit} "
        f"source_tree={source_tree} destination_commit={commit}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
