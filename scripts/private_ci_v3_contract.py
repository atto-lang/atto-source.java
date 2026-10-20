#!/usr/bin/env python3
"""Canonical schema-3 private-CI payload/provenance contract.

This module is deliberately transport-free. It validates and canonicalizes the
receipt/provenance bytes consumed by the hardened private carrier, and emits
audit-only compatibility trailers. It does not authorize a destination, inspect
reviews/incidents, or write GitHub objects/refs.
"""
from dataclasses import dataclass
import hashlib
import json
import re
from typing import Mapping

SCHEMA = 3
PROVENANCE_PATH = ".private-ci-provenance.json"
DESTINATION_PREFIX = "private-oreslang-source"

_SHA = re.compile(r"^[0-9a-f]{40}$")
_REPO = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$")
_LOGIN = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
_PERMISSION_RANK = {"none": 0, "read": 1, "triage": 2, "write": 3, "maintain": 4, "admin": 5}

RECEIPT_KEYS = frozenset({
    "schema",
    "source_repository",
    "source_repository_id",
    "source_commit_sha",
    "source_tree_sha",
    "carrier_repository",
    "carrier_repository_id",
    "carrier_base_sha",
    "carrier_workflow_blob_sha",
    "independent_reviewer_login",
    "independent_reviewer_id",
    "independent_reviewer_policy_blob_sha",
    "independent_reviewer_source_permission",
    "independent_reviewer_carrier_permission",
    "publisher_policy_pr",
    "publisher_policy_head_sha",
    "publisher_review_id",
    "destination_approval_pr",
    "destination_approval_head_sha",
    "destination_review_id",
    "snapshot_approval_pr",
    "snapshot_approval_head_sha",
    "snapshot_review_id",
    "carrier_policy_pr",
    "carrier_policy_head_sha",
    "carrier_review_id",
    "incident_resume_comment_id",
    "carrier_incident_resume_comment_id",
})

SHA_FIELDS = (
    "source_commit_sha",
    "source_tree_sha",
    "carrier_base_sha",
    "carrier_workflow_blob_sha",
    "independent_reviewer_policy_blob_sha",
    "publisher_policy_head_sha",
    "destination_approval_head_sha",
    "snapshot_approval_head_sha",
    "carrier_policy_head_sha",
)

POSITIVE_INT_FIELDS = (
    "source_repository_id",
    "carrier_repository_id",
    "independent_reviewer_id",
    "publisher_policy_pr",
    "publisher_review_id",
    "destination_approval_pr",
    "destination_review_id",
    "snapshot_approval_pr",
    "snapshot_review_id",
    "carrier_policy_pr",
    "carrier_review_id",
    "incident_resume_comment_id",
    "carrier_incident_resume_comment_id",
)


class ContractError(ValueError):
    pass


@dataclass(frozen=True)
class ReceiptBinding:
    source_repository: str
    source_repository_id: int
    carrier_repository: str
    carrier_repository_id: int
    publisher_policy_pr: int
    destination_approval_pr: int
    snapshot_approval_pr: int
    carrier_policy_pr: int


@dataclass(frozen=True)
class PayloadContract:
    provenance_path: str
    provenance_json: str
    provenance_blob_sha: str
    commit_message: str


def _require_repo(value, label):
    if type(value) is not str or not _REPO.fullmatch(value):
        raise ContractError("invalid repository field: " + label)
    return value


def _require_positive_int(value, label):
    if type(value) is not int or value <= 0:
        raise ContractError("invalid positive integer field: " + label)
    return value


def _require_sha(value, label):
    if type(value) is not str or not _SHA.fullmatch(value):
        raise ContractError("invalid SHA field: " + label)
    return value


def _require_permission(value, label):
    if type(value) is not str or value not in _PERMISSION_RANK:
        raise ContractError("invalid permission field: " + label)
    if _PERMISSION_RANK[value] < _PERMISSION_RANK["write"]:
        raise ContractError("reviewer permission below write: " + label)
    return value


def validate_receipt(receipt: Mapping[str, object], binding: ReceiptBinding) -> dict:
    if type(receipt) is not dict or set(receipt) != RECEIPT_KEYS:
        raise ContractError("unexpected review-pinned receipt schema")
    if type(receipt.get("schema")) is not int or receipt["schema"] != SCHEMA:
        raise ContractError("unsupported private-CI receipt schema")

    _require_repo(binding.source_repository, "binding.source_repository")
    _require_positive_int(binding.source_repository_id, "binding.source_repository_id")
    _require_repo(binding.carrier_repository, "binding.carrier_repository")
    _require_positive_int(binding.carrier_repository_id, "binding.carrier_repository_id")
    for field in (
        "publisher_policy_pr",
        "destination_approval_pr",
        "snapshot_approval_pr",
        "carrier_policy_pr",
    ):
        _require_positive_int(getattr(binding, field), "binding." + field)

    fixed = {
        "source_repository": binding.source_repository,
        "source_repository_id": binding.source_repository_id,
        "carrier_repository": binding.carrier_repository,
        "carrier_repository_id": binding.carrier_repository_id,
        "publisher_policy_pr": binding.publisher_policy_pr,
        "destination_approval_pr": binding.destination_approval_pr,
        "snapshot_approval_pr": binding.snapshot_approval_pr,
        "carrier_policy_pr": binding.carrier_policy_pr,
    }
    for field, expected in fixed.items():
        actual = receipt.get(field)
        if type(actual) is not type(expected) or actual != expected:
            raise ContractError("receipt binding mismatch: " + field)

    for field in SHA_FIELDS:
        _require_sha(receipt.get(field), field)
    for field in POSITIVE_INT_FIELDS:
        _require_positive_int(receipt.get(field), field)

    reviewer_login = receipt.get("independent_reviewer_login")
    if type(reviewer_login) is not str or not _LOGIN.fullmatch(reviewer_login):
        raise ContractError("invalid independent reviewer login")

    _require_permission(
        receipt.get("independent_reviewer_source_permission"),
        "independent_reviewer_source_permission",
    )
    _require_permission(
        receipt.get("independent_reviewer_carrier_permission"),
        "independent_reviewer_carrier_permission",
    )

    if receipt["carrier_base_sha"] != receipt["carrier_policy_head_sha"]:
        raise ContractError("carrier base must equal reviewed carrier head")

    return dict(receipt)


def _strict_json_object(raw: bytes) -> dict:
    if not isinstance(raw, bytes) or not raw or len(raw) > 2 * 1024 * 1024:
        raise ContractError("invalid provenance bytes")
    if raw.startswith(b"\xef\xbb\xbf"):
        raise ContractError("provenance must not contain UTF-8 BOM")

    def unique_object(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                raise ContractError("duplicate provenance key: " + key)
            out[key] = value
        return out

    def reject_constant(_):
        raise ContractError("nonstandard provenance number")

    def strict_int(text):
        if len(text) > 64:
            raise ContractError("oversized provenance integer")
        return int(text)

    def strict_float(_):
        raise ContractError("floating-point provenance values are forbidden")

    try:
        doc = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
            parse_int=strict_int,
            parse_float=strict_float,
        )
    except (UnicodeError, ValueError, RecursionError) as exc:
        if isinstance(exc, ContractError):
            raise
        raise ContractError("invalid provenance JSON") from exc
    if type(doc) is not dict:
        raise ContractError("provenance must be a JSON object")
    return doc


def canonical_provenance_json(receipt: Mapping[str, object], binding: ReceiptBinding) -> str:
    checked = validate_receipt(receipt, binding)
    return json.dumps(
        checked,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ) + "\n"


def parse_canonical_provenance(raw: bytes, binding: ReceiptBinding) -> dict:
    doc = _strict_json_object(raw)
    checked = validate_receipt(doc, binding)
    canonical = canonical_provenance_json(checked, binding).encode("utf-8")
    if raw != canonical:
        raise ContractError("provenance JSON is not canonical")
    return checked


def git_blob_sha(raw: bytes) -> str:
    if not isinstance(raw, bytes):
        raise ContractError("Git blob input must be bytes")
    return hashlib.sha1(
        b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
    ).hexdigest()


def commit_trailers(receipt: Mapping[str, object], binding: ReceiptBinding) -> str:
    checked = validate_receipt(receipt, binding)
    provenance = canonical_provenance_json(checked, binding).encode("utf-8")
    provenance_sha = git_blob_sha(provenance)
    return (
        f"Oreslang-Private-CI-Schema: {SCHEMA}\n"
        f"Oreslang-Source-Repo: {checked['source_repository']}\n"
        f"Oreslang-Source-Repo-ID: {checked['source_repository_id']}\n"
        f"Oreslang-Source-Commit: {checked['source_commit_sha']}\n"
        f"Oreslang-Source-Tree: {checked['source_tree_sha']}\n"
        f"Oreslang-Destination-Prefix: {DESTINATION_PREFIX}\n"
        f"Oreslang-Carrier-Repo: {checked['carrier_repository']}\n"
        f"Oreslang-Carrier-Repo-ID: {checked['carrier_repository_id']}\n"
        f"Oreslang-Carrier-Base: {checked['carrier_base_sha']}\n"
        f"Oreslang-Provenance-Blob: {provenance_sha}\n"
    )


def build_payload_contract(receipt: Mapping[str, object], binding: ReceiptBinding) -> PayloadContract:
    provenance = canonical_provenance_json(receipt, binding)
    raw = provenance.encode("utf-8")
    checked = parse_canonical_provenance(raw, binding)
    trailers = commit_trailers(checked, binding)
    return PayloadContract(
        provenance_path=PROVENANCE_PATH,
        provenance_json=provenance,
        provenance_blob_sha=git_blob_sha(raw),
        commit_message=(
            "ci: exact approved private Oreslang snapshot "
            + checked["source_tree_sha"]
            + "\n\n"
            + trailers
        ),
    )
