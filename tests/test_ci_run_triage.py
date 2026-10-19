"""Adversarial offline regressions for read-only exact-head CI triage."""
import importlib.util
import unittest
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
            "started_at": "2026-10-18T12:00:00Z"}


def job(conclusion="failure", steps=None, **kwargs):
    return {"id": 7, "status": "completed", "conclusion": conclusion,
            "steps": steps, **kwargs}


class ClassifierTests(unittest.TestCase):
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

    def test_read_only_get_requests_only(self):
        seen = []
        def fetch(path):
            seen.append(path)
            return ({"total_count": 1, "jobs": [job(steps=[])]}
                    if path.endswith("/jobs?per_page=100") else RUN)
        m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(seen, ["repos/org/repo/actions/runs/42",
                                "repos/org/repo/actions/runs/42/jobs?per_page=100"])


if __name__ == "__main__":
    unittest.main()
