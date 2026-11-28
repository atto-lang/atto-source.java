"""Adversarial offline regressions for read-only exact-head CI triage."""
import importlib.util
import unittest
from unittest import mock
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "triage", Path(__file__).resolve().parents[1] / "scripts" / "ci-run-triage.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
SHA = "a" * 40
RUN = {"id": 42, "status": "completed", "conclusion": "failure",
       "head_sha": SHA, "run_attempt": 2}


def step(name, conclusion="success", status="completed"):
    return {"name": name, "conclusion": conclusion, "status": status,
            "started_at": "2026-10-18T12:00:00Z", "completed_at": "2026-10-18T12:00:03Z"}


def job(conclusion="failure", steps=None, **kwargs):
    return {"id": 7, "status": "completed", "conclusion": conclusion,
            "steps": steps, **kwargs}


class ClassifierTests(unittest.TestCase):
    def test_unhashable_job_and_step_status_fail_closed(self):
        # Malformed API fields should produce a controlled diagnostic failure,
        # never an uncaught TypeError from set membership.
        for invalid in ([], {}, ["completed"], {"status": "completed"}, 1, True):
            with self.subTest(source="job", invalid=invalid):
                with self.assertRaisesRegex(m.TriageError, "invalid job status"):
                    m.classify(RUN, [job(steps=[], status=invalid)], SHA)
            with self.subTest(source="step", invalid=invalid):
                broken = step("Run tests", "failure")
                broken["status"] = invalid
                with self.assertRaisesRegex(m.TriageError, "invalid step status"):
                    m.classify(RUN, [job(steps=[broken])], SHA)

    def test_non_string_run_status_and_conclusion_fail_closed(self):
        for field in ("status", "conclusion"):
            for bad in ([], {}, 17, True):
                with self.subTest(field=field, bad=bad):
                    damaged = dict(RUN, **{field: bad})
                    with self.assertRaises(m.TriageError):
                        m.classify(damaged, [job(steps=[])], SHA)

    def test_gh_fetch_is_read_only_and_omits_api_error_text(self):
        reply = m.subprocess.CompletedProcess(
            ["gh"], returncode=0, stdout='{"status": "ok"}', stderr="")
        with mock.patch.object(m.subprocess, "run", return_value=reply) as runner:
            self.assertEqual(m.get_json("repos/org/repo/actions/runs/42"), {"status": "ok"})
            args, kwargs = runner.call_args
            self.assertEqual(args[0], ["gh", "api", "--hostname", "github.com",
                                       "--method", "GET",
                                       "repos/org/repo/actions/runs/42"])
            self.assertTrue(kwargs["capture_output"])
            self.assertFalse(kwargs["check"])

    def test_gh_fetch_does_not_echo_private_api_errors(self):
        failure = m.subprocess.CompletedProcess(
            ["gh"], returncode=1, stdout="", stderr="PRIVATE_TOKEN_VALUE")
        with mock.patch.object(m.subprocess, "run", return_value=failure):
            with self.assertRaisesRegex(m.TriageError, "GitHub API request failed") as err:
                m.get_json("repos/org/repo/actions/runs/42")
            self.assertNotIn("PRIVATE_TOKEN_VALUE", str(err.exception))

    def test_explicit_empty_vs_null_vs_missing(self):
        self.assertEqual(m.classify(RUN, [job(steps=[])], SHA),
                         "observed_no_step_failure")
        self.assertEqual(m.classify(RUN, [job(steps=None)], SHA),
                         "unobserved_failure")
        missing = job(steps=[])
        del missing["steps"]
        self.assertEqual(m.classify(RUN, [missing], SHA),
                         "unobserved_failure")

    def test_housekeeping_failure_is_not_compiler_test_failure(self):
        for name in ("Checkout", "Checkout repository", "Run actions/checkout@v4",
                     "Run actions/setup-java@v4", "Set up Java",
                     "Cache dependencies", "Post Run actions/checkout@v4",
                     "Upload artifact", "Download artifact"):
            with self.subTest(name=name):
                self.assertEqual(m.classify(RUN, [job(steps=[step(name, "failure")])], SHA),
                                 "setup_only_failure")

    def test_dependency_setup_never_claims_compiler_or_test_failure(self):
        # A step *name* may mention tests while only installing prerequisites.
        for name in (
            "Install test dependencies", "Restore build cache",
            "Download test artifacts", "Fetch test fixtures",
            "Prepare build workspace", "Bootstrap test tools",
            "Configure test runners", "Initialize test workspace",
            "Set up test harness", "Provision test runners",
            "Cache build dependencies", "Run install test dependencies",
        ):
            with self.subTest(name=name):
                self.assertEqual(
                    m.classify(RUN, [job(steps=[step(name, "failure")])], SHA),
                    "setup_only_failure",
                )
                success = dict(RUN, conclusion="success")
                self.assertEqual(
                    m.classify(success, [job("success", steps=[step(name)])], SHA),
                    "success_without_substantive_evidence",
                )

    def test_completed_build_and_test_failures(self):
        for name in ("Run unit tests", "Compile Oreslang", "Build backend",
                     "Run conformance tests"):
            with self.subTest(name=name):
                self.assertEqual(m.classify(RUN, [job(steps=[
                    step("Checkout"), step(name, "failure")])], SHA),
                                 "executed_build_test_failure")

    def test_started_but_not_completed_never_proves_failure(self):
        for status in ("queued", "in_progress", None):
            with self.subTest(status=status):
                self.assertEqual(m.classify(RUN, [job(steps=[
                    step("Run tests", "failure", status)])], SHA),
                                 "failure_without_completed_failing_step")
        self.assertEqual(m.classify(RUN, [job(steps=[
            {"name": "Run tests", "started_at": "now"}])], SHA),
                         "failure_without_completed_failing_step")

    def test_unknown_purpose_does_not_claim_test_failure(self):
        self.assertEqual(m.classify(RUN, [job(steps=[
            step("Do something", "failure")])], SHA),
                         "executed_step_failure_unclassified")
        self.assertEqual(m.classify(RUN, [job(steps=[
            step(None, "failure")])], SHA),
                         "failed_step_purpose_unknown")

    def test_terminal_conclusions_are_separate(self):
        for conclusion in ("cancelled", "skipped", "timed_out", "neutral",
                           "action_required", "stale"):
            with self.subTest(conclusion=conclusion):
                self.assertEqual(m.classify(dict(RUN, conclusion=conclusion),
                                            [job(steps=[])], SHA), conclusion)
        self.assertEqual(m.classify(dict(RUN, status="queued", conclusion=None),
                                    [], SHA), "pending")
        self.assertEqual(m.classify(RUN, [], SHA), "no_jobs_recorded")

    def test_cancelled_job_does_not_claim_failed_tests(self):
        self.assertNotEqual(m.classify(RUN, [job(conclusion="cancelled",
                                               steps=[step("Run tests", "failure")])], SHA),
                            "executed_build_test_failure")

    def test_success_requires_substantive_completion(self):
        success = dict(RUN, conclusion="success")
        self.assertEqual(m.classify(success, [job("success", [
            step("Checkout"), step("Run tests")])], SHA), "executed_success")
        self.assertEqual(m.classify(success, [job("success", [
            step("Checkout")])], SHA), "success_without_substantive_evidence")

    def test_mixed_evidence_keeps_per_job_classifications(self):
        out = m.assess(RUN, [job(steps=None), job(steps=[]),
                             job(steps=[step("Checkout", "failure")])], SHA)
        self.assertEqual(out["category"], "unobserved_failure")
        self.assertEqual([x["classification"] for x in out["jobs"]],
                         ["unobserved_failure", "observed_no_step_failure",
                          "setup_only_failure"])

    def test_completed_failure_not_erased_by_missing_metadata(self):
        out = m.assess(RUN, [job(steps=None), job(steps=[
            step("Run tests", "failure")])], SHA)
        self.assertEqual(out["category"], "executed_build_test_failure")
        self.assertEqual(out["jobs"][0]["classification"], "unobserved_failure")

    def test_exact_head_run_and_attempt_must_match(self):
        with self.assertRaisesRegex(m.TriageError, "head SHA"):
            m.classify(RUN, [job(steps=[])], "b" * 40)
        for candidate in (job(steps=[], head_sha="b" * 40),
                          job(steps=[], run_id=55),
                          job(steps=[], run_attempt=1)):
            with self.subTest(candidate=candidate), self.assertRaises(m.TriageError):
                m.assess(RUN, [candidate], SHA, run_id=42)
        with self.assertRaisesRegex(m.TriageError, "run ID"):
            m.assess(dict(RUN, id=43), [job(steps=[])], SHA, run_id=42)

    def test_pagination_fails_closed(self):
        def fetch(path):
            if path.endswith("/jobs?per_page=100"):
                return {"total_count": 101, "jobs": [job(steps=[])]}
            return RUN
        with self.assertRaisesRegex(m.TriageError, "pagination"):
            m.inspect("org/repo", 42, SHA, fetch=fetch)

    def test_bad_inputs_never_issue_requests(self):
        no_api = lambda path: self.fail("API request before validation")
        for repo in ("../repo", "org/repo?x=1", "org/repo/another", ""):
            with self.assertRaises(m.TriageError):
                m.inspect(repo, 42, SHA, fetch=no_api)
        for bad in (None, True, 0, "abc"):
            with self.assertRaises(m.TriageError):
                m.inspect("org/repo", bad, SHA, fetch=no_api)
        for bad in ("abc", "A" * 40):
            with self.assertRaises(m.TriageError):
                m.inspect("org/repo", 42, bad, fetch=no_api)
        for bad in ("steps", ["step"]):
            with self.assertRaises(m.TriageError):
                m.classify(RUN, [job(steps=bad)], SHA)

    def test_output_contains_no_untrusted_freeform_text(self):
        def fetch(path):
            if path.endswith("jobs?per_page=100"):
                return {"total_count": 1, "jobs": [
                    job(steps=[step("Run secret tests", "failure")],
                        name="private job name")]}
            return RUN
        report = m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(report["category"], "executed_build_test_failure")
        self.assertNotIn("secret", str(report).lower())
        self.assertNotIn("private", str(report).lower())


    def test_successful_job_with_failed_step_is_not_clean_execution(self):
        successful_run = dict(RUN, conclusion="success")
        observed = m.assess(successful_run, [job("success", [
            step("Run tests", "failure"), step("Complete job", "success")])], SHA)
        self.assertEqual(observed["category"], "success_with_reported_failing_step")
        uncertain = step("Run tests", "failure")
        del uncertain["completed_at"]
        outcome = m.assess(successful_run, [job("success", [
            uncertain, step("Run tests", "success")])], SHA)
        self.assertEqual(outcome["category"], "success_with_unverified_step_failure")

    def test_job_conclusion_must_not_be_overwritten_by_last_step(self):
        failing = job("failure", [step("Run tests", "failure"),
                                  step("Complete job", "success")])
        outcome = m.assess(RUN, [failing], SHA)
        self.assertEqual(outcome["category"], "executed_build_test_failure")
        self.assertEqual(outcome["jobs"][0]["completed_build_test_failures"], 1)
        failing = job("failure", [step("Checkout", "failure"),
                                  step("Complete job", "success")])
        self.assertEqual(m.classify(RUN, [failing], SHA), "setup_only_failure")

    def test_missing_timestamps_never_prove_executed_failure(self):
        for missing in ("started_at", "completed_at"):
            with self.subTest(missing=missing):
                sample = step("Run tests", "failure")
                del sample[missing]
                outcome = m.assess(RUN, [job(steps=[sample])], SHA)
                self.assertEqual(outcome["category"], "reported_failure_execution_unobserved")
                self.assertEqual(outcome["jobs"][0]["reported_failures_without_timing"], 1)
                self.assertEqual(outcome["jobs"][0]["completed_build_test_failures"], 0)

    def test_malformed_or_reversed_timestamps_are_unobserved(self):
        for started, completed in (
            ("banana", "2026-10-18T12:00:03Z"),
            ("2026-10-18T12:00:03Z", "2026-10-18T12:00:00Z"),
            ("2026-10-18T12:00:00", "2026-10-18T12:00:03"),
            ("2026-99-99T12:00:00Z", "2026-10-18T12:00:03Z"),
        ):
            with self.subTest(started=started, completed=completed):
                sample = step("Run tests", "failure")
                sample["started_at"] = started
                sample["completed_at"] = completed
                out = m.assess(RUN, [job(steps=[sample])], SHA)
                self.assertEqual(out["category"], "reported_failure_execution_unobserved")
                self.assertEqual(out["jobs"][0]["completed_build_test_failures"], 0)

    def test_valid_timezone_offsets_allow_execution_evidence(self):
        sample = step("Run tests", "failure")
        sample["started_at"] = "2026-10-18T08:00:00-04:00"
        sample["completed_at"] = "2026-10-18T12:00:03Z"
        self.assertEqual(m.classify(RUN, [job(steps=[sample])], SHA),
                         "executed_build_test_failure")

    def test_missing_timestamps_never_prove_executed_success(self):
        run = dict(RUN, conclusion="success")
        sample = step("Run tests")
        del sample["completed_at"]
        self.assertEqual(m.classify(run, [job("success", steps=[sample])], SHA),
                         "success_without_substantive_evidence")

    def test_run_attempt_changed_mid_read_rejected(self):
        calls = []
        def fetch(path):
            calls.append(path)
            if "jobs?" in path:
                return {"total_count": 1, "jobs": [job(steps=[])]}
            return dict(RUN, run_attempt=3) if len(calls) == 3 else RUN
        with self.assertRaisesRegex(m.TriageError, "run changed"):
            m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(len(calls), 3)

    def test_first_run_identity_checked_before_jobs(self):
        calls = []
        def fetch(path):
            calls.append(path)
            return dict(RUN, id=99)
        with self.assertRaisesRegex(m.TriageError, "identity"):
            m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(len(calls), 1)

    def test_invalid_step_status_is_not_silently_ignored(self):
        with self.assertRaisesRegex(m.TriageError, "step status"):
            m.classify(RUN, [job(steps=[step("Run tests", "failure", "weird")])], SHA)

    def test_read_only_get_requests_only(self):
        seen = []
        def fetch(path):
            seen.append(path)
            return ({"total_count": 1, "jobs": [job(steps=[])]}
                    if path.endswith("/jobs?per_page=100") else RUN)
        m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(seen, ["repos/org/repo/actions/runs/42",
                                "repos/org/repo/actions/runs/42/attempts/2/jobs?per_page=100",
                                "repos/org/repo/actions/runs/42"])



class PaginationHardeningTests(unittest.TestCase):
    def test_all_101_jobs_collected(self):
        calls = []
        def fetch(path):
            calls.append(path)
            if "jobs?" not in path:
                return RUN
            offset = 100 if "&page=2" in path else 0
            length = 1 if offset else 100
            return {"total_count": 101, "jobs": [
                {"id": offset + i + 1, "status": "completed",
                 "conclusion": "failure", "steps": []}
                for i in range(length)]}
        report = m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(report["job_count"], 101)
        self.assertEqual(report["category"], "observed_no_step_failure")
        self.assertEqual(len(calls), 4)

    def test_short_nonfinal_page_is_rejected(self):
        def fetch(path):
            if "jobs?" not in path:
                return RUN
            return {"total_count": 101,
                    "jobs": [{"id": i + 1, "steps": []} for i in range(50)]}
        with self.assertRaisesRegex(m.TriageError, "pagination"):
            m.inspect("org/repo", 42, SHA, fetch=fetch)

    def test_racing_totals_and_duplicates_fail_closed(self):
        def racing(path):
            if "jobs?" not in path:
                return RUN
            if "&page=2" in path:
                return {"total_count": 102, "jobs": [{"id": 1, "steps": []}]}
            return {"total_count": 101, "jobs": [
                {"id": i + 1, "steps": []} for i in range(100)]}
        with self.assertRaisesRegex(m.TriageError, "pagination"):
            m.inspect("org/repo", 42, SHA, fetch=racing)
        def duplicate(path):
            if "jobs?" not in path:
                return RUN
            if "&page=2" in path:
                return {"total_count": 101, "jobs": [{"id": 1, "steps": []}]}
            return {"total_count": 101, "jobs": [
                {"id": i + 1, "steps": []} for i in range(100)]}
        with self.assertRaisesRegex(m.TriageError, "pagination"):
            m.inspect("org/repo", 42, SHA, fetch=duplicate)



class VerifiedPRModeTests(unittest.TestCase):
    PR = {"number": 17, "state": "open", "head": {"sha": SHA}}

    @staticmethod
    def _job(**overrides):
        j = job(steps=[step("Run unit tests", "failure")],
                run_id=42, run_attempt=2, head_sha=SHA)
        j.update(overrides)
        return j

    def _fetch(self, *, pr=None, run=None, candidate=None):
        calls = []
        def fetch(path):
            calls.append(path)
            if path.endswith("pulls/17"):
                return pr or self.PR
            if "jobs?" in path:
                return {"total_count": 1, "jobs": [candidate or self._job()]}
            return run or RUN
        return fetch, calls

    def test_full_exact_pr_head_job_and_attempt_verification(self):
        fetch, calls = self._fetch()
        out = m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)
        self.assertEqual(out["category"], "executed_build_test_failure")
        self.assertTrue(out["pr_head_verified"])
        self.assertTrue(out["job_provenance_verified"])
        self.assertEqual(out["run_attempt"], 2)
        self.assertEqual(calls, ["repos/org/repo/pulls/17", "repos/org/repo/actions/runs/42",
                                 "repos/org/repo/actions/runs/42/attempts/2/jobs?per_page=100",
                                 "repos/org/repo/actions/runs/42", "repos/org/repo/pulls/17"])

    def test_scoped_attempt_url_supplies_attempt_provenance(self):
        # GitHub's documented job payload has run_id and head_sha,
        # but does not guarantee a job-level run_attempt property.
        job_without_attempt = self._job()
        del job_without_attempt["run_attempt"]
        fetch, calls = self._fetch(candidate=job_without_attempt)
        out = m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)
        self.assertEqual(out["category"], "executed_build_test_failure")
        self.assertTrue(out["job_provenance_verified"])
        self.assertEqual(out["run_attempt"], 2)
        self.assertIn("repos/org/repo/actions/runs/42/attempts/2/jobs?per_page=100", calls)
        self.assertNotIn("repos/org/repo/actions/runs/42/jobs?per_page=100", calls)

    def test_rerun_attempt_selects_distinct_job_endpoint(self):
        latest = dict(RUN, run_attempt=3)
        job_attempt_3 = self._job(run_attempt=3)
        fetch, calls = self._fetch(run=latest, candidate=job_attempt_3)
        result = m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)
        self.assertEqual(result["run_attempt"], 3)
        self.assertIn("repos/org/repo/actions/runs/42/attempts/3/jobs?per_page=100", calls)
        self.assertNotIn("repos/org/repo/actions/runs/42/attempts/2/jobs?per_page=100", calls)

    def test_no_jobs_does_not_claim_job_provenance(self):
        calls = []
        def fetch(path):
            calls.append(path)
            if path.endswith("pulls/17"):
                return self.PR
            if "jobs?" in path:
                return {"total_count": 0, "jobs": []}
            return RUN
        out = m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)
        self.assertEqual(out["category"], "no_jobs_recorded")
        self.assertTrue(out["pr_head_verified"])
        self.assertFalse(out["job_provenance_verified"])

    def test_stale_pr_head_rejected_before_run_reads(self):
        fetch, calls = self._fetch(pr={"number": 17, "state": "open", "head": {"sha": "b" * 40}})
        with self.assertRaisesRegex(m.TriageError, "PR current head SHA"):
            m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)
        self.assertEqual(calls, ["repos/org/repo/pulls/17"])

    def test_head_changes_during_inspection(self):
        fetch, calls = self._fetch()
        def racing(path):
            if path.endswith("pulls/17") and sum(p.endswith("pulls/17") for p in calls) == 1:
                calls.append(path)
                return {"number": 17, "state": "open", "head": {"sha": "b" * 40}}
            return fetch(path)
        with self.assertRaisesRegex(m.TriageError, "PR current head SHA"):
            m.inspect("org/repo", 42, SHA, fetch=racing, pr_number=17)

    def test_missing_or_mismatched_attempt_and_sha_provenance(self):
        for changed in ({"run_attempt": 3},
                        {"head_sha": None}, {"head_sha": "b" * 40},
                        {"run_id": None}, {"run_id": 43}):
            with self.subTest(changed=changed):
                fetch, _ = self._fetch(candidate=self._job(**changed))
                with self.assertRaisesRegex(m.TriageError, "provenance"):
                    m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)

    def test_run_attempt_missing_rejected_without_pr_too(self):
        for bad in (None, 0, True, "1"):
            with self.subTest(attempt=bad):
                fetch, _ = self._fetch(run={**RUN, "run_attempt": bad})
                with self.assertRaisesRegex(m.TriageError, "run attempt"):
                    m.inspect("org/repo", 42, SHA, fetch=fetch)

    def test_closed_or_unverifiable_pr_denied(self):
        for pr in ({"number": 17, "state": "closed", "head": {"sha": SHA}},
                   {"number": 17, "state": "open", "head": {}},
                   {"number": 18, "state": "open", "head": {"sha": SHA}}):
            with self.subTest(pr=pr):
                fetch, _ = self._fetch(pr=pr)
                with self.assertRaises(m.TriageError):
                    m.inspect("org/repo", 42, SHA, fetch=fetch, pr_number=17)

    def test_invalid_pr_number_rejected_before_api(self):
        for bad in (0, -1, True, "17"):
            with self.subTest(number=bad):
                with self.assertRaises(m.TriageError):
                    m.inspect("org/repo", 42, SHA, pr_number=bad,
                              fetch=lambda _: self.fail("unexpected API request"))


if __name__ == "__main__":
    unittest.main()
