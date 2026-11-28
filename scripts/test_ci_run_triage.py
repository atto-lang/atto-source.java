#!/usr/bin/env python3
"""Source-free, offline regression matrix for read-only GitHub Actions triage.

Fixtures model recorded GitHub response shapes. They do NOT authenticate, invoke
GitHub, execute CI, prove a billing root cause, or certify native guest tests.
"""
import copy
import importlib.util
import io
import json
import subprocess
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SOURCE = Path(__file__).with_name("ci-run-triage.py")
spec = importlib.util.spec_from_file_location("ci_run_triage", SOURCE)
triage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(triage)


class RunnerAdmissionTriageContract(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.run = {
            "id": 17, "head_sha": self.sha, "run_attempt": 2,
            "status": "completed", "conclusion": "failure",
        }
        self.job = {
            "id": 101, "run_id": 17, "head_sha": self.sha,
            "run_attempt": 2, "status": "completed", "conclusion": "failure",
            "runner_id": 0, "runner_name": "", "steps": [],
        }

    @staticmethod
    def step(name="Run Maven test", conclusion="failure", **overrides):
        result = {
            "name": name, "status": "completed", "conclusion": conclusion,
            "started_at": "2026-11-01T04:00:00Z",
            "completed_at": "2026-11-01T04:00:02Z",
        }
        result.update(overrides)
        return result

    def classify(self):
        return triage._classify_job(self.job, self.run, run_id=17)

    def aggregate(self):
        return triage.assess(self.run, [self.job], self.sha, run_id=17)

    def admitted(self):
        self.job["runner_id"] = 1234
        self.job["runner_name"] = "GitHub Actions 1"

    def test_original_zero_runner_and_empty_steps_is_not_test_failure(self):
        self.assertEqual(self.classify()["classification"], "observed_no_step_failure")
        self.assertFalse(self.classify()["runner_assignment_observed"])
        self.assertEqual(self.aggregate()["category"], "observed_no_step_failure")

    def test_null_steps_still_not_executed(self):
        self.job["steps"] = None
        self.assertEqual(self.aggregate()["category"], "unobserved_failure")

    def test_missing_steps_field_still_not_executed(self):
        del self.job["steps"]
        self.assertEqual(self.classify()["classification"], "unobserved_failure")

    def test_null_runner_and_no_steps_not_executed(self):
        self.job["runner_id"] = None
        self.job["runner_name"] = None
        self.assertEqual(self.aggregate()["category"], "observed_no_step_failure")

    def test_positive_runner_without_steps_is_not_test_evidence(self):
        self.admitted()
        self.assertEqual(self.aggregate()["category"], "observed_no_step_failure")

    def test_successful_zero_step_job_is_not_execution_proof(self):
        self.admitted()
        self.run["conclusion"] = "success"
        self.job["conclusion"] = "success"
        self.assertEqual(self.aggregate()["category"], "success_without_substantive_evidence")

    def test_runnerless_timestamped_build_failure_cannot_be_called_compiler_failure(self):
        self.job["steps"] = [self.step()]
        self.assertEqual(self.aggregate()["category"], "reported_steps_without_runner_evidence")

    def test_runnerless_timestamped_success_cannot_be_promoted(self):
        self.run["conclusion"] = "success"
        self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        self.assertEqual(self.aggregate()["category"], "reported_steps_without_runner_evidence")

    def test_runnerless_housekeeping_cannot_be_called_execution(self):
        self.job["steps"] = [self.step("Set up job")]
        self.assertEqual(self.classify()["classification"], "reported_steps_without_runner_evidence")

    def test_positive_runner_id_with_blank_name_is_not_admission_proof(self):
        self.job["runner_id"] = 99
        self.job["steps"] = [self.step()]
        self.assertEqual(self.aggregate()["category"], "reported_steps_without_runner_evidence")

    def test_missing_runner_id_with_name_is_not_admission_proof(self):
        self.job["runner_id"] = None
        self.job["runner_name"] = "GitHub Actions 1"
        self.job["steps"] = [self.step()]
        self.assertEqual(self.aggregate()["category"], "reported_steps_without_runner_evidence")

    def test_real_runner_and_timed_build_failure_is_executed_failure(self):
        self.admitted()
        self.job["steps"] = [self.step()]
        result = self.aggregate()
        self.assertEqual(result["category"], "executed_build_test_failure")
        self.assertEqual(result["jobs"][0]["completed_build_test_failures"], 1)

    def test_real_runner_and_timed_success_is_only_observed_step_execution(self):
        self.admitted()
        self.run["conclusion"] = "success"
        self.job["conclusion"] = "success"
        self.job["steps"] = [self.step("Run unit tests", conclusion="success")]
        self.assertEqual(self.aggregate()["category"], "executed_success")
        self.assertTrue(self.classify()["runner_assignment_observed"])

    def test_real_runner_setup_failure_is_not_a_compiler_failure(self):
        self.admitted()
        self.job["steps"] = [self.step("Install compiler toolchain")]
        self.assertEqual(self.aggregate()["category"], "setup_only_failure")

    def test_real_runner_unclassified_failure_is_not_a_test_claim(self):
        self.admitted()
        self.job["steps"] = [self.step("Run custom phase")]
        self.assertEqual(self.aggregate()["category"], "executed_step_failure_unclassified")

    def test_reverse_timestamp_does_not_prove_execution(self):
        self.admitted()
        self.job["steps"] = [self.step(started_at="2026-11-01T04:00:03Z")]
        self.assertEqual(self.aggregate()["category"], "reported_failure_execution_unobserved")

    def test_naive_timestamp_does_not_prove_execution(self):
        self.admitted()
        self.job["steps"] = [self.step(started_at="2026-11-01T04:00:00")]
        self.assertEqual(self.aggregate()["category"], "reported_failure_execution_unobserved")

    def test_missing_timestamp_does_not_prove_execution(self):
        self.admitted()
        step = self.step()
        del step["completed_at"]
        self.job["steps"] = [step]
        self.assertEqual(self.aggregate()["category"], "reported_failure_execution_unobserved")

    def test_skipped_job_does_not_count_as_test(self):
        self.job["conclusion"] = "skipped"
        self.assertEqual(self.classify()["classification"], "skipped")

    def test_pending_job_does_not_count_as_test(self):
        self.job["status"] = "queued"
        self.job["conclusion"] = None
        self.assertEqual(self.classify()["classification"], "pending")

    def test_empty_job_inventory_is_not_success(self):
        self.run["conclusion"] = "success"
        self.assertEqual(triage.assess(self.run, [], self.sha)["category"], "no_jobs_recorded")

    def test_wrong_exact_head_rejected(self):
        with self.assertRaises(triage.TriageError):
            triage.assess(self.run, [self.job], "b" * 40)

    def test_job_head_mismatch_rejected(self):
        self.job["head_sha"] = "b" * 40
        with self.assertRaises(triage.TriageError):
            self.aggregate()

    def test_job_run_id_mismatch_rejected(self):
        self.job["run_id"] = 18
        with self.assertRaises(triage.TriageError):
            self.aggregate()

    def test_job_attempt_mismatch_rejected(self):
        self.job["run_attempt"] = 1
        with self.assertRaises(triage.TriageError):
            self.aggregate()

    def test_invalid_runner_ids_rejected(self):
        for value in (-1, False, True, 1.0, "123", [], {}):
            with self.subTest(value=repr(value)):
                self.job["runner_id"] = value
                with self.assertRaises(triage.TriageError):
                    self.classify()

    def test_invalid_runner_names_rejected(self):
        for value in (123, False, [], {}):
            with self.subTest(value=repr(value)):
                self.job["runner_name"] = value
                with self.assertRaises(triage.TriageError):
                    self.classify()

    def test_malformed_steps_rejected(self):
        for value in (0, "[]", {}, [None]):
            with self.subTest(value=repr(value)):
                self.job["steps"] = value
                with self.assertRaises(triage.TriageError):
                    self.classify()

    def test_unknown_job_status_rejected(self):
        self.job["status"] = "genuinely-not-a-github-status"
        with self.assertRaises(triage.TriageError):
            self.classify()

    def test_explicit_attempt_page_used_instead_of_latest_jobs(self):
        path = "repos/owner/repo/actions/runs/17/attempts/2/jobs?per_page=100"
        observed = []
        def fetch(query):
            observed.append(query)
            return {"total_count": 1, "jobs": [copy.deepcopy(self.job)]}
        jobs = triage._fetch_jobs_pages("owner/repo", 17, 2, fetch)
        self.assertEqual([job["id"] for job in jobs], [101])
        self.assertEqual(observed, [path])

    def test_paginated_101_jobs_complete(self):
        first = [dict(self.job, id=i) for i in range(1, 101)]
        last = [dict(self.job, id=101)]
        pages = [
            {"total_count": 101, "jobs": first},
            {"total_count": 101, "jobs": last},
        ]
        def fetch(query):
            return pages[1] if "page=2" in query else pages[0]
        result = triage._fetch_jobs_pages("owner/repo", 17, 2, fetch)
        self.assertEqual(len(result), 101)
        self.assertEqual(result[-1]["id"], 101)

    def test_truncated_pagination_fails_closed(self):
        with self.assertRaises(triage.TriageError):
            triage._fetch_jobs_pages("owner/repo", 17, 2,
                lambda _: {"total_count": 2, "jobs": [self.job]})

    def test_duplicate_job_id_fails_closed(self):
        with self.assertRaises(triage.TriageError):
            triage._fetch_jobs_pages("owner/repo", 17, 2,
                lambda _: {"total_count": 2, "jobs": [self.job, self.job]})

    def test_inconsistent_pagination_count_fails_closed(self):
        first = [dict(self.job, id=i) for i in range(1, 101)]
        def fetch(query):
            if "page=2" in query:
                return {"total_count": 102, "jobs": [dict(self.job, id=101)]}
            return {"total_count": 101, "jobs": first}
        with self.assertRaises(triage.TriageError):
            triage._fetch_jobs_pages("owner/repo", 17, 2, fetch)

    def test_live_run_attempt_change_fails_closed(self):
        repo = "owner/repo"
        run_path = f"repos/{repo}/actions/runs/17"
        pages = f"{run_path}/attempts/2/jobs?per_page=100"
        hits = 0
        def fetch(path):
            nonlocal hits
            if path == run_path:
                hits += 1
                result = copy.deepcopy(self.run)
                if hits == 2:
                    result["run_attempt"] = 3
                return result
            if path == pages:
                return {"total_count": 1, "jobs": [copy.deepcopy(self.job)]}
            raise AssertionError("unexpected query: " + path)
        with self.assertRaises(triage.TriageError):
            triage.inspect(repo, 17, self.sha, fetch)

    def test_live_pr_head_change_fails_closed(self):
        repo = "owner/repo"
        run_path = f"repos/{repo}/actions/runs/17"
        pr_path = f"repos/{repo}/pulls/9"
        pages = f"{run_path}/attempts/2/jobs?per_page=100"
        hits = 0
        def fetch(path):
            nonlocal hits
            if path == pr_path:
                hits += 1
                return {"number": 9, "head": {"sha": self.sha if hits == 1 else "b"*40}, "state": "open"}
            if path == run_path:
                return copy.deepcopy(self.run)
            if path == pages:
                return {"total_count": 1, "jobs": [copy.deepcopy(self.job)]}
            raise AssertionError("unexpected query: " + path)
        with self.assertRaises(triage.TriageError):
            triage.inspect(repo, 17, self.sha, fetch, pr_number=9)

    def test_non_pr_inspection_rejects_unbound_job_identity(self):
        repo = "owner/repo"
        run_path = f"repos/{repo}/actions/runs/17"
        page_path = f"{run_path}/attempts/2/jobs?per_page=100"
        mutations = {
            "missing_run_id": lambda job: job.pop("run_id"),
            "wrong_run_id": lambda job: job.update(run_id=18),
            "missing_head_sha": lambda job: job.pop("head_sha"),
            "wrong_head_sha": lambda job: job.update(head_sha="b" * 40),
            "wrong_attempt": lambda job: job.update(run_attempt=3),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                job = copy.deepcopy(self.job)
                mutate(job)
                def fetch(path):
                    if path == run_path:
                        return copy.deepcopy(self.run)
                    if path == page_path:
                        return {"total_count": 1, "jobs": [job]}
                    raise AssertionError("unexpected GET path: " + path)
                with self.assertRaises(triage.TriageError):
                    triage.inspect(repo, 17, self.sha, fetch)

    def test_non_pr_inspection_binds_valid_job_provenance(self):
        repo = "owner/repo"
        run_path = f"repos/{repo}/actions/runs/17"
        page_path = f"{run_path}/attempts/2/jobs?per_page=100"
        def fetch(path):
            if path == run_path:
                return copy.deepcopy(self.run)
            if path == page_path:
                return {"total_count": 1, "jobs": [copy.deepcopy(self.job)]}
            raise AssertionError("unexpected GET path: " + path)
        report = triage.inspect(repo, 17, self.sha, fetch)
        self.assertTrue(report["job_provenance_verified"])
        self.assertFalse(report["pr_head_verified"])
        self.assertEqual(report["category"], "observed_no_step_failure")

    def test_unknown_completed_step_result_cannot_hide_behind_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step(conclusion="success"),
            self.step(conclusion="success_typo"),
        ]
        with self.assertRaisesRegex(triage.TriageError, "unrecognized step conclusion"):
            self.aggregate()

    def test_completed_step_without_conclusion_cannot_hide_behind_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success"), self.step(conclusion=None)]
        with self.assertRaisesRegex(triage.TriageError, "completed step missing conclusion"):
            self.aggregate()

    def test_pending_step_with_success_conclusion_is_inconclusive(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step(conclusion="success"),
            self.step(status="in_progress", conclusion="success"),
        ]
        with self.assertRaisesRegex(triage.TriageError, "unfinished step has conclusion"):
            self.aggregate()

    def test_completed_job_with_pending_step_is_inconclusive(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step(conclusion="success"),
            self.step(status="in_progress", conclusion=None),
        ]
        with self.assertRaisesRegex(triage.TriageError, "completed job contains unfinished step"):
            self.aggregate()

    def test_missing_step_status_with_success_conclusion_is_inconclusive(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step(conclusion="success"),
            self.step(status=None, conclusion="success"),
        ]
        with self.assertRaisesRegex(triage.TriageError, "unfinished step has conclusion"):
            self.aggregate()

    def test_untimed_success_cannot_hide_behind_timed_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        untimed = self.step(conclusion="success")
        del untimed["completed_at"]
        self.job["steps"] = [self.step(conclusion="success"), untimed]
        with self.assertRaisesRegex(triage.TriageError, "lacks verified execution interval"):
            self.aggregate()

    def test_reverse_timed_success_cannot_hide_behind_valid_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step(conclusion="success"),
            self.step(conclusion="success", started_at="2026-11-01T04:00:03Z"),
        ]
        with self.assertRaisesRegex(triage.TriageError, "lacks verified execution interval"):
            self.aggregate()

    def test_successful_step_outside_job_interval_is_rejected(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job.update(started_at="2026-11-01T04:00:01Z",
                        completed_at="2026-11-01T04:00:03Z")
        self.job["steps"] = [self.step(conclusion="success")]
        with self.assertRaisesRegex(triage.TriageError, "outside job execution interval"):
            self.aggregate()

    def test_invalid_job_interval_cannot_certify_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job.update(started_at="2026-11-01T04:00:04Z",
                        completed_at="2026-11-01T04:00:03Z")
        self.job["steps"] = [self.step(conclusion="success")]
        with self.assertRaisesRegex(triage.TriageError, "invalid job execution interval"):
            self.aggregate()

    def test_valid_bounded_success_retains_execution_classification(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job.update(started_at="2026-11-01T03:59:59Z",
                        completed_at="2026-11-01T04:00:03Z")
        self.job["steps"] = [self.step(conclusion="success")]
        self.assertEqual(self.aggregate()["category"], "executed_success")

    def test_direct_assess_rejects_missing_job_head_sha_on_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        del self.job["head_sha"]
        with self.assertRaisesRegex(triage.TriageError, "job head SHA"):
            triage.assess(self.run, [self.job], self.sha)

    def test_direct_assess_rejects_missing_job_run_id_on_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        del self.job["run_id"]
        with self.assertRaisesRegex(triage.TriageError, "job run ID"):
            triage.assess(self.run, [self.job], self.sha)

    def test_direct_assess_rejects_invalid_job_ids_on_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        for invalid in (None, 0, -1, True, "101", 1.0):
            with self.subTest(invalid=repr(invalid)):
                self.job["id"] = invalid
                with self.assertRaisesRegex(triage.TriageError, "invalid job ID"):
                    triage.assess(self.run, [self.job], self.sha)

    def test_direct_assess_rejects_missing_run_id_on_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        del self.run["id"]
        with self.assertRaisesRegex(triage.TriageError, "job run ID"):
            triage.assess(self.run, [self.job], self.sha)

    def test_direct_assess_rejects_mismatched_job_run_without_run_id_arg(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        self.job["run_id"] = 18
        with self.assertRaisesRegex(triage.TriageError, "job run ID"):
            triage.assess(self.run, [self.job], self.sha)

    def test_direct_assess_accepts_bound_job_with_no_run_id_arg(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [self.step(conclusion="success")]
        self.assertEqual(triage.assess(self.run, [self.job], self.sha)["category"],
                         "executed_success")

    def test_duplicate_step_number_cannot_promote_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step("Set up job", conclusion="success", number=1),
            self.step("Run unit tests", conclusion="success", number=1),
        ]
        with self.assertRaises(triage.TriageError):
            self.aggregate()

    def test_reversed_step_numbers_cannot_promote_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step("Set up job", conclusion="success", number=3),
            self.step("Run unit tests", conclusion="success", number=2),
        ]
        with self.assertRaises(triage.TriageError):
            self.aggregate()

    def test_malformed_step_number_cannot_promote_success(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        for number in (None, True, 0, -1, "1", 1.5):
            with self.subTest(number=number):
                self.job["steps"] = [self.step(conclusion="success", number=number)]
                with self.assertRaises(triage.TriageError):
                    self.aggregate()

    def test_mixed_numbered_and_unnumbered_steps_rejected(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step("Set up job", conclusion="success", number=1),
            self.step("Run unit tests", conclusion="success"),
        ]
        with self.assertRaises(triage.TriageError):
            self.aggregate()

    def test_increasing_step_numbers_preserve_executed_step_classification(self):
        self.admitted()
        self.run["conclusion"] = self.job["conclusion"] = "success"
        self.job["steps"] = [
            self.step("Set up job", conclusion="success", number=1),
            self.step("Run unit tests", conclusion="success", number=2),
        ]
        self.assertEqual(self.aggregate()["category"], "executed_success")

    def test_gh_access_is_get_only(self):
        with patch.object(triage.subprocess, "run",
                          return_value=SimpleNamespace(returncode=0, stdout='{"ok": true}')) as called:
            self.assertEqual(triage.get_json("repos/a/b/actions/runs/17"), {"ok": True})
        args = called.call_args.args[0]
        self.assertIn("--method", args)
        self.assertEqual(args[args.index("--method") + 1], "GET")
        self.assertEqual(args[0:2], ["gh", "api"])

    def test_failed_api_request_is_inconclusive_not_execution_proof(self):
        with patch.object(triage.subprocess, "run",
                          return_value=SimpleNamespace(returncode=1, stdout="")):
            with self.assertRaises(triage.TriageError):
                triage.get_json("repos/a/b/actions/runs/17")

    def test_cli_inconclusive_returns_distinct_exit_two(self):
        with patch.object(triage, "inspect", side_effect=triage.TriageError("denied")):
            with redirect_stderr(io.StringIO()) as stderr:
                rc = triage.main(["owner/repo", "17", self.sha])
        self.assertEqual(rc, 2)
        self.assertIn("CI_TRIAGE_INCONCLUSIVE:", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
