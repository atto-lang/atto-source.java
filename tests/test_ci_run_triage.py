"""Offline regressions for read-only exact-head CI triage."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "triage", Path(__file__).resolve().parents[1] / "scripts" / "ci-run-triage.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
SHA = "a" * 40
RUN = {"status": "completed", "conclusion": "failure", "head_sha": SHA}


class TriageTests(unittest.TestCase):
    def test_zero_steps_does_not_claim_billing(self):
        self.assertEqual(m.classify(RUN, [{"conclusion": "failure", "steps": []}], SHA),
                         "failed_before_steps_cause_unverified")

    def test_started_steps_classifies_failed_code(self):
        jobs = [{"conclusion": "failure", "steps": [{"started_at": "2026-10-18T12:00:00Z"}]}]
        self.assertEqual(m.classify(RUN, jobs, SHA), "executed_non_success")

    def test_skipped_steps_are_not_executed(self):
        self.assertEqual(m.classify(RUN, [{"steps": [{"started_at": None}]}], SHA),
                         "failed_before_steps_cause_unverified")

    def test_success_after_execution(self):
        run = dict(RUN, conclusion="success")
        jobs = [{"conclusion": "success", "steps": [{"started_at": "2026-10-18T00:00:00Z"}]}]
        self.assertEqual(m.classify(run, jobs, SHA), "executed_success")

    def test_no_jobs_and_pending(self):
        self.assertEqual(m.classify(RUN, [], SHA), "no_jobs_recorded")
        self.assertEqual(m.classify(dict(RUN, status="queued"), [], SHA), "not_terminal")

    def test_missing_steps_is_not_false_evidence(self):
        self.assertEqual(m.classify(RUN, [{"conclusion": "failure"}], SHA),
                         "steps_metadata_unavailable")
        self.assertEqual(m.classify(RUN, [{"conclusion": "failure", "steps": None}], SHA),
                         "steps_metadata_unavailable")

    def test_wrong_source_and_incomplete_pagination_fail(self):
        with self.assertRaisesRegex(m.TriageError, "head SHA"):
            m.classify(RUN, [{"steps": []}], "b" * 40)
        seen = []
        def fetch(path):
            seen.append(path)
            if path.endswith("/jobs?per_page=100"):
                return {"total_count": 101, "jobs": [{"steps": []}]}
            return RUN
        with self.assertRaisesRegex(m.TriageError, "pagination"):
            m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(len(seen), 2)

    def test_real_unstarted_job_shape_and_invalid_steps(self):
        def fetch(path):
            if path.endswith("/jobs?per_page=100"):
                return {"total_count": 1, "jobs": [{"conclusion": "failure", "id": 123}]}
            return RUN
        report = m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(report["category"], "steps_metadata_unavailable")
        self.assertIsNone(report["executed_steps"])
        with self.assertRaisesRegex(m.TriageError, "step metadata"):
            m.classify(RUN, [{"steps": ["unexpected string"]}], SHA)
        with self.assertRaisesRegex(m.TriageError, "steps collection"):
            m.classify(RUN, [{"steps": "not a list"}], SHA)

    def test_invalid_job_total_count_fails_closed(self):
        for total in (None, -1, "1", True, 0, 2):
            def fetch(path):
                if path.endswith("/jobs?per_page=100"):
                    return {"total_count": total, "jobs": [{"steps": []}]}
                return RUN
            with self.subTest(total=total), self.assertRaisesRegex(m.TriageError, "pagination"):
                m.inspect("org/repo", 42, SHA, fetch=fetch)

    def test_rejects_input_before_api_and_does_not_write(self):
        for name in ("../repo", "a/../repo", "org/repo?cmd=x", "a/repo/other"):
            with self.assertRaises(m.TriageError):
                m.inspect(name, 42, SHA, fetch=lambda path: self.fail("network before validation"))
        with self.assertRaises(m.TriageError):
            m.inspect("org/repo", 42, "bad", fetch=lambda path: self.fail("network before validation"))

    def test_successful_inspection_strips_freeform_job_data(self):
        def fetch(path):
            if path.endswith("jobs?per_page=100"):
                return {"jobs": [{"conclusion": "failure", "name": "secret data", "steps": []}],
                        "total_count": 1}
            return RUN
        report = m.inspect("org/repo", 42, SHA, fetch=fetch)
        self.assertEqual(report["category"], "failed_before_steps_cause_unverified")
        self.assertNotIn("secret", str(report))


if __name__ == "__main__":
    unittest.main()
