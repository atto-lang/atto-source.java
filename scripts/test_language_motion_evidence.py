#!/usr/bin/env python3
"""Adversarial tests for source identity and fail-closed evidence promotion."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "scripts/check-language-motion-evidence.py"
SPEC = importlib.util.spec_from_file_location("language_motion_evidence", MODULE)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class LanguageMotionEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.record = json.loads(
            (ROOT / "contracts/conformance/language-motion-v1.json").read_text(encoding="utf-8"))

    def checked(self, record):
        return AUDIT.check_manifest(record, ROOT)

    def test_checked_in_evidence_has_consistent_pinned_fixtures(self):
        self.assertTrue(self.checked(self.record))

    def test_changed_blob_is_rejected_even_when_marked_pending(self):
        record = copy.deepcopy(self.record)
        record["examples"][0]["source_blob_sha1"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "pinned Git blob"):
            self.checked(record)

    def test_forged_verified_status_without_run_is_rejected(self):
        record = copy.deepcopy(self.record)
        record["examples"][0]["java_status"] = "verified"
        with self.assertRaisesRegex(ValueError, "immutable evidence"):
            self.checked(record)

    def test_zero_steps_never_count_as_verified_compilation(self):
        record = copy.deepcopy(self.record)
        fixture = record["examples"][0]
        fixture["java_status"] = "verified"
        fixture["java_evidence"] = {
            "commit_sha": "a" * 40,
            "fixture_blob_sha1": fixture["source_blob_sha1"],
            "run_url": "https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/1234",
            "executed_steps": 0,
        }
        with self.assertRaisesRegex(ValueError, "executed steps"):
            self.checked(record)

    def test_path_escape_and_unexpected_public_example_are_rejected(self):
        record = copy.deepcopy(self.record)
        record["examples"][0]["source_file"] = "examples/../.github/private-ci-approved-repositories.txt"
        with self.assertRaisesRegex(ValueError, "stay inside examples"):
            self.checked(record)
        record = copy.deepcopy(self.record)
        record["examples"][3]["public_property"] = "madeUpCode"
        with self.assertRaisesRegex(ValueError, "each be represented"):
            self.checked(record)

    def test_unsupported_status_is_rejected(self):
        record = copy.deepcopy(self.record)
        record["examples"][1]["java_status"] = "success_unchecked"
        with self.assertRaisesRegex(ValueError, "unknown status"):
            self.checked(record)


if __name__ == "__main__":
    unittest.main()
