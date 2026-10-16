#!/usr/bin/env python3
"""Publish a snapshot branch ONLY into a reviewed private GitHub repository.

No arbitrary command execution or user-selectable GitHub API endpoints. All
writes target the exact repository checked by the private-source policy.
"""
import argparse
import base64
import binascii
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
# The local checkout cannot grant itself a private-source transfer approval.
# Always confirm the exact same bytes exist on the protected source main ref.
REQUIRED_PRIVATE_CI_CONTEXT = "preflight-regressions"
# GitHub Actions is App ID 15368. Merely naming a required status context
# does not constrain its author; other actors can publish matching statuses.
REQUIRED_PRIVATE_CI_APP_ID = 15368
PROTECTED_POLICY_ROUTE = (
    "repos/ores-truffle-oreslang/oreslang-source.java/contents/"
    ".github/private-ci-approved-repositories.txt?ref=main"
)



# These IDs are bound to reviewed source code, never caller-controlled
# environment variables or workflow inputs. A reused owner/repo slug with a
# different immutable GitHub repository ID must not inherit export approval.
SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPOSITORY_ID = 1400792274
PINNED_DESTINATION_IDS = {
    "chapter-publishing-test/zed-pkg-integration-e2e": 1344442382,
    # Candidate identity only: not authorization without protected-main approval.
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
    """Preflight without executing checkout-controlled shell scripts.

    The separately tested Bash guard is for human/CI use only. Executing it
    from this privileged publisher would run arbitrary code supplied by a
    candidate PR *before* verifying protected-main source authorization.
    Keep the authoritative approval check in descriptor-safe Python, and
    revalidate the pinned destination's numeric GitHub identity here.
    """
    checked_policy_fingerprint(root, repository)
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

def checked_source_main_protection():
    """Require independently enforced source-main reviews before export.

    A policy on an unprotected main branch is not an authorization boundary.
    If the GitHub App lacks permission to inspect these rules, the API call
    fails and publishing must stop rather than assuming protection.
    """
    branch = gh_api(f"repos/{SOURCE_REPOSITORY}/branches/main")
    if branch.get("protected") is not True:
        raise Denied("source main branch is not protected")
    rules = gh_api(f"repos/{SOURCE_REPOSITORY}/branches/main/protection")
    reviews = rules.get("required_pull_request_reviews")
    checks = rules.get("required_status_checks")
    admins = rules.get("enforce_admins")
    if (not isinstance(reviews, dict) or not isinstance(checks, dict)
            or not isinstance(admins, dict)
            or reviews.get("require_code_owner_reviews") is not True
            or reviews.get("dismiss_stale_reviews") is not True
            or type(reviews.get("required_approving_review_count")) is not int
            or reviews["required_approving_review_count"] < 1
            or admins.get("enabled") is not True
            or checks.get("strict") is not True
            or not (isinstance(checks.get("contexts"), list) and checks["contexts"]
                    or isinstance(checks.get("checks"), list) and checks["checks"])):
        raise Denied("source main lacks mandatory independent security review and CI")
    # A legacy contexts-only requirement matches the *name*, not its author.
    # Require the GitHub Actions App ID in checks[] instead. Any second
    # source for this security context would dilute branch protection.
    app_checks = checks.get("checks")
    if not isinstance(app_checks, list):
        raise Denied("source main security CI check must be app-bound")
    relevant = [entry for entry in app_checks
                if isinstance(entry, dict)
                and entry.get("context") == REQUIRED_PRIVATE_CI_CONTEXT]
    if (not relevant or any(
            type(entry.get("app_id")) is not int
            or entry["app_id"] != REQUIRED_PRIVATE_CI_APP_ID
            for entry in relevant)):
        raise Denied("source main security CI check is not bound to trusted GitHub Actions app")
    # Explicit bypass allowances negate the code-owner review guarantee.
    bypass = reviews.get("bypass_pull_request_allowances")
    if bypass is not None:
        if (not isinstance(bypass, dict)
                or any(not isinstance(bypass.get(kind, []), list)
                       or bypass.get(kind, [])
                       for kind in ("users", "teams", "apps"))):
            raise Denied("source main permits unreviewed pull request bypass")
    for setting in ("allow_force_pushes", "allow_deletions"):
        entry = rules.get(setting)
        if not isinstance(entry, dict) or entry.get("enabled") is not False:
            raise Denied("source main permits unsafe history or deletion changes")


def checked_protected_main_policy(local_fingerprint):
    """Deny approvals present only in an unreviewed local working tree/PR.

    GitHub's source-repository protected main is independently authoritative;
    no checkout-controlled ref, environment variable, or destination response
    is permitted to replace it. This must be rechecked before every write.
    """
    metadata = gh_api(PROTECTED_POLICY_ROUTE)
    encoded = metadata.get("content")
    length = metadata.get("size")
    if (metadata.get("type") != "file"
            or metadata.get("encoding") != "base64"
            or not isinstance(encoded, str)
            or not isinstance(length, int)
            or isinstance(length, bool)
            or not 0 <= length <= 16 * 1024
            or len(encoded) > 24 * 1024):
        raise Denied("protected main private CI policy unavailable or invalid")
    try:
        remote = base64.b64decode("".join(encoded.splitlines()), validate=True)
    except (ValueError, binascii.Error) as exc:
        raise Denied("protected main private CI policy encoding invalid") from exc
    if len(remote) != length or hashlib.sha256(remote).digest() != local_fingerprint:
        raise Denied("local private CI approval differs from protected main")
    # Verify GitHub's reported blob identifier, guarding malformed responses.
    reported_sha = valid_sha(metadata.get("sha"))
    actual_sha = hashlib.sha1(
        b"blob " + str(length).encode("ascii") + bytes([0]) + remote
    ).hexdigest()
    if reported_sha != actual_sha:
        raise Denied("protected main policy blob integrity mismatch")


def collect_source(root, trusted_root, *, omit_checkout_metadata=False):
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
    # Synthetic/unit-test snapshots may use a subdirectory. They never gain
    # the Git metadata exception; the real provenance gate separately refuses
    # subdirectory exports. Retain strict no-follow traversal for those tests.
    omit_checkout_metadata = omit_checkout_metadata and not relative.parts

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
                # Git metadata is not part of HEAD^{tree}. Omit it only at
                # the exact checkout root; nested .git entries stay forbidden.
                if not (omit_checkout_metadata and depth == 0):
                    raise Denied("source path contains unsupported name")
                try:
                    metadata = os.stat(name, dir_fd=directory_fd,
                                       follow_symlinks=False)
                except OSError as exc:
                    raise Denied("source Git metadata cannot be inspected") from exc
                if not (stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode)):
                    raise Denied("source Git metadata must not be a symlink or special file")
                continue
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



def snapshot_git_tree_sha(files):
    """Hash the exact source snapshot using Git tree/blob object encoding.

    Never infer source provenance merely from filenames or a successful GitHub
    upload. Git sorts directory entries as name + '/' and stores raw SHA bytes.
    Empty directories are omitted because Git cannot track them.
    """
    root = {}
    for rel, data, mode in files:
        parts = rel.split("/")
        if (not parts or any(not item or item in (".", "..") for item in parts)
                or mode not in ("100644", "100755")):
            raise Denied("snapshot contains unsupported Git tree entry")
        parent = root
        for component in parts[:-1]:
            existing = parent.setdefault(component, {})
            if not isinstance(existing, dict):
                raise Denied("snapshot file conflicts with directory")
            parent = existing
        if parts[-1] in parent:
            raise Denied("snapshot contains colliding Git tree entries")
        parent[parts[-1]] = (mode, data)
    if not root:
        raise Denied("empty Git source snapshot")

    def tree_digest(node):
        entries = []
        for name, content in node.items():
            try:
                encoded = name.encode("utf-8")
            except UnicodeError as exc:
                raise Denied("snapshot contains invalid Git filename") from exc
            if b"/" in encoded or bytes([0]) in encoded:
                raise Denied("snapshot contains invalid Git filename")
            is_tree = isinstance(content, dict)
            entries.append((encoded + (b"/" if is_tree else b""), encoded, content))
        payload = bytearray()
        for _, encoded, content in sorted(entries, key=lambda item: item[0]):
            if isinstance(content, dict):
                mode = b"40000"
                digest = tree_digest(content)
            else:
                file_mode, data = content
                mode = file_mode.encode("ascii")
                digest = hashlib.sha1(
                    b"blob " + str(len(data)).encode("ascii") + bytes([0]) + data
                ).digest()
            payload.extend(mode + b" " + encoded + bytes([0]) + digest)
        return hashlib.sha1(
            b"tree " + str(len(payload)).encode("ascii") + bytes([0]) + payload
        ).digest()

    return tree_digest(root).hex()


def checked_source_commit(commit_sha, source_tree_sha):
    """Bind the exact local snapshot to a commit object in the pinned source repo.

    A local Git repository (including one redirected via environment variables)
    is not proof that its HEAD belongs to SOURCE_REPOSITORY. Resolve the commit
    through the authenticated, fixed source-repository API before any export.
    """
    valid_sha(commit_sha)
    valid_sha(source_tree_sha)
    response = gh_api(f"repos/{SOURCE_REPOSITORY}/git/commits/{commit_sha}")
    tree = response.get("tree")
    if (response.get("sha") != commit_sha
            or not isinstance(tree, dict)
            or tree.get("sha") != source_tree_sha):
        raise Denied("source commit is not authenticated against pinned source Git tree")


def require_source_tree_match(source, trusted_root, files):
    """Fail closed unless the snapshot is the *entire committed checkout tree*.

    A subdirectory copy or dirty checkout is not an exact-tree CI mirror.
    The source snapshot is matched to Git's committed HEAD tree *before*
    any source bytes are published, without network or caller-provided SHAs.
    """
    try:
        source_root = source.resolve(strict=True)
        trusted = trusted_root.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise Denied("exact-tree source root unavailable") from exc
    if source_root != trusted:
        raise Denied("exact-tree transfer requires the checkout root")
    try:
        verify = subprocess.run(
            ["git", "-C", str(trusted), "rev-parse",
             "--show-toplevel", "HEAD", "HEAD^{tree}"],
            capture_output=True, text=True, timeout=20, check=False,
            env={**{key: value for key, value in os.environ.items()
                     if not key.startswith("GIT_")},
                 "GIT_NO_REPLACE_OBJECTS": "1",
                 "GIT_CONFIG_NOSYSTEM": "1",
                 "GIT_CONFIG_GLOBAL": os.devnull},
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Denied("exact-tree Git provenance unavailable") from exc
    if verify.returncode != 0:
        raise Denied("exact-tree Git provenance unavailable")
    lines = verify.stdout.splitlines()
    if len(lines) != 3:
        raise Denied("exact-tree Git response malformed")
    checkout_root, commit_sha, expected_tree = lines
    if (Path(checkout_root).resolve() != trusted
            or not SHA.fullmatch(commit_sha)
            or not SHA.fullmatch(expected_tree)):
        raise Denied("exact-tree checkout identity malformed")
    actual_tree = snapshot_git_tree_sha(files)
    if actual_tree != expected_tree:
        raise Denied("source snapshot differs from committed Git HEAD tree")
    return commit_sha, expected_tree


def publish(repository, source, branch, trusted_root,
            source_commit, source_tree):
    # Caller-selected expected commit/tree must be explicit and immutable.
    # The live checkout and pinned private source repository independently
    # attest these exact values before any source-bearing write.
    valid_sha(source_commit)
    valid_sha(source_tree)
    if not NAME.fullmatch(repository) or not BRANCH.fullmatch(branch):
        raise Denied("invalid destination or isolated CI branch name")
    # Approval comes before reading potentially sensitive source files.
    require_preflight(repository, trusted_root)
    approved_policy = checked_policy_fingerprint(trusted_root, repository)
    # Validate the pinned source and independently enforced source-main policy
    # before touching any source bytes or creating Git objects.
    checked_source()
    checked_source_main_protection()
    checked_protected_main_policy(approved_policy)
    checked_repo(repository)
    # Trust the publisher's checkout for code/policy, not the candidate tree.
    # The candidate may be an entirely separate Git checkout: it provides
    # source BYTES only, never scripts or source-side authorization decisions.
    # collect_source opens files with no-follow directory descriptors, and
    # require_source_tree_match checks the candidate's complete committed tree.
    if source.is_symlink():
        raise Denied("candidate source checkout cannot be a symlink")
    files = collect_source(source, source, omit_checkout_metadata=True)
    actual_commit, exact_source_tree = require_source_tree_match(source, source, files)
    if actual_commit != source_commit or exact_source_tree != source_tree:
        raise Denied("candidate checkout differs from explicitly reviewed source commit/tree")
    checked_source_commit(source_commit, source_tree)
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
        checked_source()
        checked_source_main_protection()
        checked_protected_main_policy(approved_policy)
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
    if isolated_sha != exact_source_tree:
        raise Denied("destination source subtree differs from committed Git HEAD tree")
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
        "message": ("ci: source snapshot from approved private Oreslang checkout\n\n"
                    f"Source-Commit: {source_commit}\nSource-Tree: {exact_source_tree}"),
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
    return commit_sha, len(files)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("repository", help="approved private owner/repo")
    p.add_argument("--source-dir", required=True, type=Path)
    p.add_argument("--branch", required=True)
    p.add_argument("--source-commit", required=True,
                   help="independently reviewed exact source Git commit SHA")
    p.add_argument("--source-tree", required=True,
                   help="independently reviewed complete source Git tree SHA")
    args = p.parse_args(argv)
    # Policy and code are taken from the same trusted checkout as this script.
    root = Path(__file__).resolve().parent.parent
    try:
        commit, count = publish(args.repository, args.source_dir, args.branch,
                                root, args.source_commit, args.source_tree)
    except Denied as exc:
        print("PRIVATE_CI_DESTINATION_REJECTED: " + str(exc), file=sys.stderr)
        return 1
    print(f"PRIVATE_CI_SNAPSHOT_CREATED: files={count} commit={commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
