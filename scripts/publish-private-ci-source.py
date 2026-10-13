#!/usr/bin/env python3
"""Publish a snapshot branch ONLY into a reviewed private GitHub repository.

No arbitrary command execution or user-selectable GitHub API endpoints. All
writes target the exact repository checked by the private-source policy.
"""
import argparse
import base64
import json
import os
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
PREFIX = "private-oreslang-source"


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


def checked_repo(repository):
    result = gh_api("repos/" + repository)
    if (not isinstance(result.get("full_name"), str)
            or result["full_name"].casefold() != repository.casefold()
            or result.get("visibility") != "private"
            or result.get("private") is not True):
        raise Denied("destination identity or private visibility changed")
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
    checked_repo(repository)


def collect_source(root):
    if root.is_symlink() or not root.is_dir():
        raise Denied("source path must be a real directory")
    entries = []
    size = 0
    for current, dirs, files in os.walk(root, followlinks=False):
        for d in dirs:
            path = Path(current) / d
            if path.is_symlink() or d == ".git":
                raise Denied("source directory contains a symlink or .git directory")
        for name in files:
            path = Path(current) / name
            if path.is_symlink() or not path.is_file() or name == ".git":
                raise Denied("source contains symlink, special file, or .git object")
            rel = path.relative_to(root).as_posix()
            if any(ord(ch) < 32 for ch in rel) or "\\" in rel:
                raise Denied("source path contains unsupported characters")
            data = path.read_bytes()
            if len(data) > MAX_SINGLE:
                raise Denied("file exceeds source mirror limit")
            size += len(data)
            if size > MAX_BYTES or len(entries) >= MAX_FILES:
                raise Denied("source mirror exceeds bounded capacity")
            entries.append((rel, data, "100755" if os.access(path, os.X_OK) else "100644"))
    if not entries:
        raise Denied("source directory is empty")
    return sorted(entries, key=lambda x: x[0])


def publish(repository, source, branch, trusted_root):
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    # Approval comes before reading potentially sensitive source files.
    require_preflight(repository, trusted_root)
    files = collect_source(source)
    base = checked_repo(repository)
    default = base.get("default_branch")
    if not isinstance(default, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", default):
        raise Denied("destination default branch is invalid")
    base_ref = gh_api(f"repos/{repository}/git/ref/heads/{default}")
    base_sha = valid_sha(base_ref.get("object", {}).get("sha"))
    base_commit = gh_api(f"repos/{repository}/git/commits/{base_sha}")
    base_tree = valid_sha(base_commit.get("tree", {}).get("sha"))
    elements = []
    for rel, data, mode in files:
        checked_repo(repository)  # recheck immediately before each write
        blob = gh_api(f"repos/{repository}/git/blobs", method="POST", payload={
            "content": base64.b64encode(data).decode("ascii"), "encoding": "base64"
        })
        elements.append({"path": PREFIX + "/" + rel, "mode": mode,
                         "type": "blob", "sha": valid_sha(blob.get("sha"))})
    checked_repo(repository)
    new_tree = gh_api(f"repos/{repository}/git/trees", method="POST",
                      payload={"base_tree": base_tree, "tree": elements})
    new_tree_sha = valid_sha(new_tree.get("sha"))
    checked_repo(repository)
    commit = gh_api(f"repos/{repository}/git/commits", method="POST", payload={
        "message": "ci: source snapshot from approved private Oreslang checkout",
        "tree": new_tree_sha, "parents": [base_sha]
    })
    commit_sha = valid_sha(commit.get("sha"))
    checked_repo(repository)
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
    args = p.parse_args(argv)
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
    try:
        commit, count = publish(args.repository, args.source_dir, args.branch, root)
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_SNAPSHOT_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
