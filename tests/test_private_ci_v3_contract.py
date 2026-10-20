import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "private_ci_v3_contract",
    ROOT / "scripts" / "private_ci_v3_contract.py",
)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load private-CI v3 contract")
v3 = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = v3
SPEC.loader.exec_module(v3)

A = "a" * 40
B = "b" * 40
C = "c" * 40
D = "d" * 40
E = "e" * 40
F = "f" * 40

BINDING = v3.ReceiptBinding(
    source_repository="ores-truffle-oreslang/oreslang-source.java",
    source_repository_id=1400792274,
    carrier_repository="trusted-ci/private-carrier",
    carrier_repository_id=123456789,
    publisher_policy_pr=1217,
    destination_approval_pr=1092,
    snapshot_approval_pr=1189,
    carrier_policy_pr=7,
)


def receipt():
    return {
        "schema": 3,
        "source_repository": BINDING.source_repository,
        "source_repository_id": BINDING.source_repository_id,
        "source_commit_sha": A,
        "source_tree_sha": B,
        "carrier_repository": BINDING.carrier_repository,
        "carrier_repository_id": BINDING.carrier_repository_id,
        "carrier_base_sha": C,
        "carrier_workflow_blob_sha": D,
        "independent_reviewer_login": "security-reviewer",
        "independent_reviewer_id": 987654,
        "independent_reviewer_policy_blob_sha": E,
        "independent_reviewer_source_permission": "write",
        "independent_reviewer_carrier_permission": "maintain",
        "publisher_policy_pr": BINDING.publisher_policy_pr,
        "publisher_policy_head_sha": F,
        "publisher_review_id": 1001,
        "destination_approval_pr": BINDING.destination_approval_pr,
        "destination_approval_head_sha": A,
        "destination_review_id": 1002,
        "snapshot_approval_pr": BINDING.snapshot_approval_pr,
        "snapshot_approval_head_sha": B,
        "snapshot_review_id": 1003,
        "carrier_policy_pr": BINDING.carrier_policy_pr,
        "carrier_policy_head_sha": C,
        "carrier_review_id": 1004,
        "incident_resume_comment_id": 1005,
        "carrier_incident_resume_comment_id": 1006,
    }


class PrivateCiV3ContractTest(unittest.TestCase):
    def test_exact_schema_matches_hardened_carrier_contract(self):
        self.assertEqual(set(receipt()), set(v3.RECEIPT_KEYS))
        self.assertEqual(3, v3.validate_receipt(receipt(), BINDING)["schema"])

    def test_canonical_json_round_trips_byte_for_byte(self):
        raw = v3.canonical_provenance_json(receipt(), BINDING).encode("utf-8")
        self.assertEqual(receipt(), v3.parse_canonical_provenance(raw, BINDING))
        self.assertTrue(raw.endswith(b"\n"))
        self.assertNotIn(b" ", raw)

    def test_duplicate_and_noncanonical_json_fail_closed(self):
        canonical = v3.canonical_provenance_json(receipt(), BINDING)
        self.assertRaises(
            v3.ContractError,
            v3.parse_canonical_provenance,
            canonical.replace('"schema":3', '"schema":3,"schema":3', 1).encode(),
            BINDING,
        )
        pretty = json.dumps(receipt(), indent=2, sort_keys=True).encode() + b"\n"
        self.assertRaises(v3.ContractError, v3.parse_canonical_provenance, pretty, BINDING)

    def test_float_boolean_and_zero_identity_fields_are_rejected(self):
        for value in (3.0, True, 0):
            with self.subTest(value=value):
                candidate = receipt()
                candidate["schema"] = value
                self.assertRaises(v3.ContractError, v3.validate_receipt, candidate, BINDING)
        for field in ("source_repository_id", "independent_reviewer_id", "publisher_review_id"):
            candidate = receipt()
            candidate[field] = True
            with self.subTest(field=field):
                self.assertRaises(v3.ContractError, v3.validate_receipt, candidate, BINDING)

    def test_binding_drift_is_rejected(self):
        for field, value in (
            ("source_repository", "fork/source"),
            ("source_repository_id", 9),
            ("carrier_repository", "evil/carrier"),
            ("carrier_repository_id", 10),
            ("publisher_policy_pr", 1),
            ("destination_approval_pr", 2),
            ("snapshot_approval_pr", 3),
            ("carrier_policy_pr", 4),
        ):
            candidate = receipt()
            candidate[field] = value
            with self.subTest(field=field):
                self.assertRaises(v3.ContractError, v3.validate_receipt, candidate, BINDING)

    def test_carrier_base_must_equal_reviewed_carrier_head(self):
        candidate = receipt()
        candidate["carrier_base_sha"] = D
        self.assertRaises(v3.ContractError, v3.validate_receipt, candidate, BINDING)

    def test_reviewer_permissions_are_write_or_stronger(self):
        for field in (
            "independent_reviewer_source_permission",
            "independent_reviewer_carrier_permission",
        ):
            for permission in ("none", "read", "triage"):
                candidate = receipt()
                candidate[field] = permission
                with self.subTest(field=field, permission=permission):
                    self.assertRaises(v3.ContractError, v3.validate_receipt, candidate, BINDING)

    def test_missing_extra_and_malformed_fields_are_rejected(self):
        missing = receipt()
        missing.pop("incident_resume_comment_id")
        self.assertRaises(v3.ContractError, v3.validate_receipt, missing, BINDING)
        extra = receipt()
        extra["legacy"] = True
        self.assertRaises(v3.ContractError, v3.validate_receipt, extra, BINDING)
        malformed = receipt()
        malformed["source_commit_sha"] = "A" * 40
        self.assertRaises(v3.ContractError, v3.validate_receipt, malformed, BINDING)

    def test_payload_contract_binds_manifest_and_compatibility_trailers(self):
        payload = v3.build_payload_contract(receipt(), BINDING)
        self.assertEqual(".private-ci-provenance.json", payload.provenance_path)
        self.assertEqual(v3.git_blob_sha(payload.provenance_json.encode()), payload.provenance_blob_sha)
        self.assertIn("Oreslang-Private-CI-Schema: 3\n", payload.commit_message)
        self.assertIn("Oreslang-Source-Repo: ores-truffle-oreslang/oreslang-source.java\n", payload.commit_message)
        self.assertIn("Oreslang-Source-Commit: " + A + "\n", payload.commit_message)
        self.assertIn("Oreslang-Source-Tree: " + B + "\n", payload.commit_message)
        self.assertIn("Oreslang-Destination-Prefix: private-oreslang-source\n", payload.commit_message)
        self.assertIn("Oreslang-Provenance-Blob: " + payload.provenance_blob_sha + "\n", payload.commit_message)

    def test_authority_mutations_change_provenance_blob(self):
        original = v3.build_payload_contract(receipt(), BINDING).provenance_blob_sha
        for field, value in (
            ("publisher_review_id", 2001),
            ("destination_review_id", 2002),
            ("snapshot_review_id", 2003),
            ("carrier_review_id", 2004),
            ("incident_resume_comment_id", 2005),
            ("carrier_incident_resume_comment_id", 2006),
        ):
            candidate = receipt()
            candidate[field] = value
            with self.subTest(field=field):
                self.assertNotEqual(
                    original,
                    v3.build_payload_contract(candidate, BINDING).provenance_blob_sha,
                )


if __name__ == "__main__":
    unittest.main()
