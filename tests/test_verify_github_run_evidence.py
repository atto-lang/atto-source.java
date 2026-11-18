"""Source-free, offline regressions for the Java PR CI-evidence admission gate."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPT_DIR))
import verify_github_run_evidence as gate

SHA = "a" * 40
REPO = "ores-truffle-oreslang/oreslang-source.java"
REPO_ID = 1400792274
REQUIRED = {"test": {"Verify exact source revision", "Verify JIT/JVM profile"}}


def valid_evidence():
    return {
        "run": {
            "id": 100, "run_attempt": 2, "workflow_id": 7,
            "event": "pull_request", "head_sha": SHA,
            "status": "completed", "conclusion": "success",
            "repository": {"full_name": REPO, "id": REPO_ID},
        },
        "jobs": [{
            "run_id": 100, "name": "test", "status": "completed",
            "conclusion": "success", "runner_id": 42,
            "runner_name": "GitHub Actions 42",
            "steps": [
                {"name": "Verify exact source revision", "number": 1,
                 "status": "completed", "conclusion": "success"},
                {"name": "Verify JIT/JVM profile", "number": 2,
                 "status": "completed", "conclusion": "success"},
            ]
        }],
    }


class EvidenceGateTests(unittest.TestCase):
    def check(self, evidence, **kwargs):
        return gate.check(evidence, expected_repo=kwargs.get("repo", REPO),
                          expected_repo_id=kwargs.get("repo_id", REPO_ID),
                          expected_sha=kwargs.get("sha", SHA),
                          required=kwargs.get("required", REQUIRED))

    def rejects(self, modified):
        with self.assertRaises(gate.EvidenceError):
            self.check(modified)

    def test_exact_head_stepful_success(self):
        self.assertTrue(self.check(valid_evidence()))

    def test_missing_and_ambiguous_jobs(self):
        evidence = valid_evidence()
        evidence["jobs"] = []
        self.rejects(evidence)
        evidence = valid_evidence()
        evidence["jobs"].append(copy.deepcopy(evidence["jobs"][0]))
        self.rejects(evidence)

    def test_zero_steps_is_not_success(self):
        evidence = valid_evidence()
        evidence["jobs"][0]["steps"] = []
        self.rejects(evidence)

    def test_zero_runner_is_not_success(self):
        for runner_id in (0, None, True, -1):
            with self.subTest(runner_id=runner_id):
                evidence = valid_evidence()
                evidence["jobs"][0]["runner_id"] = runner_id
                self.rejects(evidence)

    def test_mismatched_exact_head(self):
        evidence = valid_evidence()
        evidence["run"]["head_sha"] = "b" * 40
        self.rejects(evidence)

    def test_wrong_repo_identity_or_id(self):
        for key, val in (("full_name", "attacker/imitator"), ("id", 456)):
            evidence = valid_evidence()
            evidence["run"]["repository"][key] = val
            self.rejects(evidence)

    def test_no_implicit_success_or_skip(self):
        for field, val in (("status", "in_progress"), ("conclusion", "neutral"),
                           ("conclusion", "failure"), ("conclusion", None)):
            evidence = valid_evidence()
            evidence["run"][field] = val
            self.rejects(evidence)

    def test_wrong_job_run_and_job_failure(self):
        for field, val in (("run_id", 101), ("status", "queued"),
                           ("conclusion", "skipped"), ("conclusion", "failure")):
            evidence = valid_evidence()
            evidence["jobs"][0][field] = val
            self.rejects(evidence)

    def test_missing_test_step(self):
        evidence = valid_evidence()
        evidence["jobs"][0]["steps"].pop()
        self.rejects(evidence)

    def test_skipped_or_failed_step(self):
        for val in ("skipped", "failure", "neutral", None):
            evidence = valid_evidence()
            evidence["jobs"][0]["steps"][1]["conclusion"] = val
            self.rejects(evidence)

    def test_duplicate_step_names(self):
        evidence = valid_evidence()
        evidence["jobs"][0]["steps"][1]["name"] = "Verify exact source revision"
        self.rejects(evidence)

    def test_empty_requirement_fails_closed(self):
        with self.assertRaises(gate.EvidenceError):
            self.check(valid_evidence(), required={})

    def test_fake_test_and_missing_sha_fails_closed(self):
        for v in ("", None, True, "a"*39, "A"*40):
            with self.subTest(sha=v), self.assertRaises(gate.EvidenceError):
                self.check(valid_evidence(), sha=v)

    def test_trigger_must_be_pull_request(self):
        for event in ("push", "workflow_dispatch", "pull_request_target"):
            evidence = valid_evidence()
            evidence["run"]["event"] = event
            self.rejects(evidence)

    def test_cli_bounded_duplicate_json_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "evidence.json"
            cmd = [sys.executable, str(SCRIPT_DIR / "verify_github_run_evidence.py"),
                   "--evidence", str(path), "--repo", REPO,
                   "--repo-id", str(REPO_ID), "--sha", SHA,
                   "--require", "test=Verify exact source revision",
                   "--require", "test=Verify JIT/JVM profile"]
            path.write_text(json.dumps(valid_evidence()), encoding="utf-8")
            self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
            path.write_text('{"run": {}, "run": {}, "jobs": []}', encoding="utf-8")
            self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 1)
            path.write_bytes(b"x" * (gate.MAX_EVIDENCE_BYTES + 1))
            self.assertEqual(subprocess.run(cmd, capture_output=True).returncode, 1)

    def test_duplicate_requirement_fails_closed(self):
        with self.assertRaises(gate.EvidenceError):
            gate.parse_requirements(["test=verify", "test=verify"])

    def test_unrelated_job_is_not_substitute_for_required_job(self):
        evidence = valid_evidence()
        evidence["jobs"][0]["name"] = "test-copy"
        self.rejects(evidence)


if __name__ == "__main__":
    unittest.main()
