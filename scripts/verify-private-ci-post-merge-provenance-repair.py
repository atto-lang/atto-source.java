#!/usr/bin/env python3
"""Verify the review-only private-CI governance snapshot without authorizing export."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys

SCHEMA = "ores-private-ci-provenance-snapshot-v1"
SOURCE_REPOSITORY = "ores-truffle-oreslang/oreslang-source.java"
SOURCE_REPOSITORY_ID = 1400792274
SOURCE_MAIN_SHA = "9f4f4a3b75dd4b124e8509b65a88a98213c11ab5"
MANIFEST = "docs/security/private-ci-post-merge-provenance-repair-20261027.json"
LEDGER = ".github/private-ci-approved-repositories.txt"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_JSON_DEPTH = 64
EXPECTED = {
    ".github/CODEOWNERS": "078909add1bb1d37eefc22c11dfa8e7ceb76aace",
    ".github/workflows/private-ci-mirror-preflight.yml": "288da01fdc0414cf9fbd3f47be459e7569927771",
    "scripts/test-private-ci-codeowners-contract.sh": "18e531ba5a85f8276800ece0914bfa554c319bf3",
    "scripts/publish-private-ci-source.py": "532451d1c131ed6bb90558f9491c089ed1801ce1",
    "scripts/publish-private-ci-source-v3.py": "b813da3cff66277bce13ea9e231ef210cea1c4e0",
    "scripts/check-private-ci-destination.sh": "ea000956404520ee2df938716b5032bb52368db1",
    LEDGER: "aea82279b2a1b8e2e7a8d20fd68f975046b2c4ce",
}


class Rejected(RuntimeError):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected("duplicate JSON key")
        result[key] = value
    return result


def reject_nonfinite_json(value: str):
    raise Rejected("invalid provenance snapshot JSON")


def require_json_depth(raw: bytes) -> None:
    """Reject overly deep JSON before the runtime parser allocates nested objects."""
    depth = 0
    quoted = False
    escaped = False
    for byte in raw:
        if quoted:
            if escaped:
                escaped = False
            elif byte == 92:
                escaped = True
            elif byte == 34:
                quoted = False
        elif byte == 34:
            quoted = True
        elif byte in (91, 123):
            depth += 1
            if depth > MAX_JSON_DEPTH:
                raise Rejected("invalid provenance snapshot JSON")
        elif byte in (93, 125):
            depth -= 1
            if depth < 0:
                raise Rejected("invalid provenance snapshot JSON")


def git_blob_sha(data: bytes) -> str:
    header = b"blob " + str(len(data)).encode("ascii") + b"\0"
    return hashlib.sha1(header + data).hexdigest()


def safe_relative_path(value: str) -> str:
    if not isinstance(value, str) or not value or value.startswith("/") or "\\" in value:
        raise Rejected("unsafe manifest path")
    parts = PurePosixPath(value).parts
    if any(part in ("", ".", "..") or part.casefold() == ".git" for part in parts):
        raise Rejected("unsafe manifest path")
    return value


def read_regular(root: Path, relative: str) -> bytes:
    relative = safe_relative_path(relative)
    candidate = root.joinpath(*PurePosixPath(relative).parts)
    try:
        metadata = os.lstat(candidate)
    except OSError as exc:
        raise Rejected("snapshot file missing: " + relative) from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise Rejected("snapshot file is not a regular non-symlink file: " + relative)
    resolved = candidate.resolve(strict=True)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise Rejected("snapshot file escapes repository root: " + relative) from exc
    return resolved.read_bytes()


def load_manifest(root: Path):
    raw = read_regular(root, MANIFEST)
    if not raw or len(raw) > MAX_MANIFEST_BYTES or raw.startswith(bytes.fromhex("efbbbf")):
        raise Rejected("invalid provenance snapshot JSON")
    require_json_depth(raw)
    try:
        doc = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=reject_nonfinite_json,
        )
    except (UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise Rejected("invalid provenance snapshot JSON") from exc
    required = {
        "schema", "source_repository", "source_repository_id", "source_main_sha",
        "authorizes_source_transfer", "required_active_destinations", "controls",
    }
    if not isinstance(doc, dict) or set(doc) != required:
        raise Rejected("provenance snapshot fields are not exact")
    if doc["schema"] != SCHEMA or doc["source_repository"] != SOURCE_REPOSITORY:
        raise Rejected("provenance snapshot identity mismatch")
    if type(doc["source_repository_id"]) is not int or doc["source_repository_id"] != SOURCE_REPOSITORY_ID:
        raise Rejected("source repository ID mismatch")
    if (not isinstance(doc["source_main_sha"], str) or
            doc["source_main_sha"] != SOURCE_MAIN_SHA or
            not HEX40.fullmatch(doc["source_main_sha"])):
        raise Rejected("source main SHA mismatch")
    if doc["authorizes_source_transfer"] is not False:
        raise Rejected("review-only snapshot must never authorize source transfer")
    if (type(doc["required_active_destinations"]) is not int or
            doc["required_active_destinations"] != 0):
        raise Rejected("review-only snapshot requires an empty active destination ledger")
    controls = doc["controls"]
    if not isinstance(controls, list) or len(controls) != len(EXPECTED):
        raise Rejected("control manifest length mismatch")
    observed = {}
    for row in controls:
        if not isinstance(row, dict) or set(row) != {"path", "blob_sha"}:
            raise Rejected("control manifest row is malformed")
        path = safe_relative_path(row["path"])
        sha = row["blob_sha"]
        if path in observed or not isinstance(sha, str) or not HEX40.fullmatch(sha):
            raise Rejected("control manifest identity is invalid")
        observed[path] = sha
    if observed != EXPECTED:
        raise Rejected("control manifest does not match reviewed exact blob set")
    return doc


def verify(root: Path) -> None:
    root = root.resolve(strict=True)
    load_manifest(root)
    for path, expected_sha in EXPECTED.items():
        actual = git_blob_sha(read_regular(root, path))
        if actual != expected_sha:
            raise Rejected(f"blob mismatch for {path}: {actual}")
    try:
        ledger = read_regular(root, LEDGER).decode("utf-8")
    except UnicodeError as exc:
        raise Rejected("approved-destination ledger is not UTF-8") from exc
    active = [
        line.strip() for line in ledger.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if active:
        raise Rejected("active private-CI destination rows are present")
    print("PRIVATE_CI_PROVENANCE_SNAPSHOT_PASS")
    print("PRIVATE_CI_SOURCE_TRANSFER_AUTHORIZED=false")
    print("PRIVATE_CI_ACTIVE_DESTINATIONS=0")


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) == 2 else Path(".")
    try:
        verify(root)
    except Rejected as exc:
        print("PRIVATE_CI_PROVENANCE_SNAPSHOT_REJECTED: " + str(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
