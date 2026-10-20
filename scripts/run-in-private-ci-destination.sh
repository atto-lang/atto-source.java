#!/usr/bin/python3 -I
"""Trusted entry point for the reviewed private CI publisher.

The .sh path is retained for policy compatibility, but this is intentionally a
Python launcher so shell startup hooks such as BASH_ENV cannot execute first.
"""
import os
from pathlib import Path
import sys

USAGE = (
    "PRIVATE_CI_DESTINATION_REJECTED: usage: "
    "run-in-private-ci-destination.sh owner/repo "
    "--source-dir CHECKOUT --branch ci/oreslang-NAME "
    "--source-selector pr:NUMBER|main --source-commit SHA --source-tree SHA "
    "[--maintainer-approval-comment ID --security-approval-comment ID]"
)


def reject(message=None):
    print(message or USAGE, file=sys.stderr)
    raise SystemExit(1)


args = sys.argv[1:]
if len(args) not in (11, 15):
    reject()
if not (
    args[1] == "--source-dir"
    and args[3] == "--branch"
    and args[5] == "--source-selector"
    and args[7] == "--source-commit"
    and args[9] == "--source-tree"
):
    reject()
if len(args) == 15 and not (
    args[11] == "--maintainer-approval-comment"
    and args[13] == "--security-approval-comment"
):
    reject()

token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
if not token:
    reject("PRIVATE_CI_DESTINATION_REJECTED: trusted GitHub token is required")

launcher = Path(__file__).resolve(strict=True)
publisher = launcher.with_name("publish-private-ci-source.py")
if publisher.is_symlink() or not publisher.is_file():
    reject("PRIVATE_CI_DESTINATION_REJECTED: trusted publisher is missing")

python = "/usr/bin/python3"
if not os.path.isfile(python) or not os.access(python, os.X_OK):
    reject("PRIVATE_CI_DESTINATION_REJECTED: trusted Python runtime unavailable")

env = {
    "PATH": "/usr/local/bin:/usr/bin:/bin",
    "HOME": "/tmp",
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "GH_HOST": "github.com",
    "GH_PROMPT_DISABLED": "1",
    "GH_TOKEN": token,
}
try:
    os.execve(python, [python, "-I", "-E", str(publisher), *args], env)
except OSError:
    reject("PRIVATE_CI_DESTINATION_REJECTED: trusted publisher launch failed")
