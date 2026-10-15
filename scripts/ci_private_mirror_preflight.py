#!/usr/bin/env python3
"""Fail-closed GitHub visibility and identity admission before copying private code.

This is a necessary preflight, not a substitute for enforcing private repository
visibility throughout a mirror upload or GitHub-side deletion of old objects.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request


MAX_RESPONSE_BYTES = 131072
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z", re.ASCII)


class MirrorPreflightError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise MirrorPreflightError("repository metadata redirect refused")


def validate_repository_name(name):
    if not isinstance(name, str) or not REPOSITORY.fullmatch(name):
        raise MirrorPreflightError("invalid GitHub owner/repository")
    owner, repository = name.split("/", 1)
    if owner in (".", "..") or repository in (".", ".."):
        raise MirrorPreflightError("invalid GitHub owner/repository")
    return owner, repository


def validate_metadata(actual, expected_name, expected_id):
    if not isinstance(actual, dict):
        raise MirrorPreflightError("repository metadata is not an object")
    if actual.get("private") is not True or actual.get("visibility") != "private":
        raise MirrorPreflightError("repository is not explicitly private")
    if str(actual.get("full_name", "")).lower() != expected_name.lower():
        raise MirrorPreflightError("repository identity mismatch")
    if type(actual.get("id")) is not int or actual["id"] != expected_id:
        raise MirrorPreflightError("repository numeric identity mismatch")
    # Unknown state must fail closed as well as explicitly archived/disabled.
    if actual.get("archived") is not False or actual.get("disabled") is not False:
        raise MirrorPreflightError("repository active status is missing or inactive")


def fetch_metadata(name, token, opener=None):
    owner, repository = validate_repository_name(name)
    if not token or not isinstance(token, str) or token.isspace():
        raise MirrorPreflightError("authenticated repository visibility required")
    req = urllib.request.Request(
        f"https://api.github.com/repos/{owner}/{repository}",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "oreslang-private-mirror-preflight",
        },
        method="GET",
    )
    handler = opener or urllib.request.build_opener(NoRedirect()).open
    try:
        with handler(req, timeout=8) as response:
            if response.status != 200:
                raise MirrorPreflightError("repository metadata could not be verified")
            if response.geturl() != req.full_url:
                raise MirrorPreflightError("repository metadata URL changed")
            data = response.read(MAX_RESPONSE_BYTES + 1)
            if len(data) > MAX_RESPONSE_BYTES:
                raise MirrorPreflightError("repository metadata response too large")
    except (urllib.error.URLError, TimeoutError, OSError):
        # Never include raw HTTP exception text: it can contain request data.
        raise MirrorPreflightError("repository metadata unavailable") from None
    try:
        return json.loads(data)
    except (UnicodeError, json.JSONDecodeError):
        raise MirrorPreflightError("invalid repository metadata") from None


def verify(source, destination, source_id, destination_id, source_token, destination_token, *, fetch=fetch_metadata):
    validate_repository_name(source)
    validate_repository_name(destination)
    if source.lower() == destination.lower():
        raise MirrorPreflightError("source and destination must be distinct repositories")
    # bool is an int subclass in Python; never admit it as a pinned GitHub ID.
    if (type(source_id) is not int or type(destination_id) is not int
            or source_id <= 0 or destination_id <= 0
            or source_id == destination_id):
        raise MirrorPreflightError("expected numeric repository identities must be distinct and positive")
    for name, expected_id, token in (
        (source, source_id, source_token),
        (destination, destination_id, destination_token),
    ):
        metadata = fetch(name, token)
        validate_metadata(metadata, name, expected_id)
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="owner/name of private source repo")
    parser.add_argument("--destination", required=True, help="owner/name of private mirror repo")
    parser.add_argument("--source-id", required=True, type=int, help="pinned numeric GitHub repository ID")
    parser.add_argument("--destination-id", required=True, type=int, help="pinned numeric GitHub repository ID")
    args = parser.parse_args(argv)
    try:
        verify(args.source, args.destination, args.source_id, args.destination_id,
               os.environ.get("ORES_SOURCE_READ_TOKEN"), os.environ.get("ORES_DEST_READ_TOKEN"))
    except MirrorPreflightError as error:
        print(f"DENIED: {error}", file=sys.stderr)
        return 1
    print("PRIVATE_MIRROR_ADMISSION_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
