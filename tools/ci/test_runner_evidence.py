import unittest
from unittest.mock import patch

import runner_evidence as ev

class EvidenceTests(unittest.TestCase):
    def test_housekeeping_and_incomplete_are_not_meaningful(self):
        for name in ("Set up job", "Complete job", "Post checkout", "post cache"):
            self.assertFalse(ev.meaningful({"name": name, "status": "completed", "conclusion": "success"}))
        self.assertFalse(ev.meaningful({"name": "Compile", "status": "in_progress", "conclusion": None}))

    def test_requires_positive_runner_name_and_real_step(self):
        job = {
            "status": "completed",
            "conclusion": "failure",
            "runner_id": 42,
            "runner_name": "GitHub Actions 1",
            "steps": [{"name": "Compile", "status": "completed", "conclusion": "failure"}],
        }
        self.assertTrue(ev.qualified(job))
        for invalid in (0, None, -1, True):
            self.assertFalse(ev.qualified({**job, "runner_id": invalid}))
        self.assertFalse(ev.qualified({**job, "runner_name": " "}))
        self.assertFalse(ev.qualified({
            **job,
            "steps": [{"name": "Set up job", "status": "completed", "conclusion": "success"}],
        }))
        self.assertFalse(ev.qualified({**job, "status": "in_progress", "conclusion": None}))

    def test_classification_is_conservative_and_non_parity(self):
        self.assertIn("compiler", ev.classify({
            "name": "compiler",
            "steps": [{"name": "Compile Ores fixture", "status": "completed", "conclusion": "success"}],
        }))
        self.assertEqual(["contract/static"], ev.classify({
            "name": "contract checks",
            "steps": [{"name": "Static check", "status": "completed", "conclusion": "success"}],
        }))

    def test_attempt_key_distinguishes_reruns(self):
        self.assertNotEqual(
            ev.attempt_key("o/r", 7, 1),
            ev.attempt_key("o/r", 7, 2),
        )

    @patch.object(ev, "BACKENDS", ("java",))
    @patch.object(ev, "pages")
    @patch.object(ev, "inspect_attempt")
    def test_collect_enumerates_historical_attempts_and_dedupes_reported(self, inspect, pages):
        pages.return_value = [{"id": 99, "run_attempt": 3, "status": "completed"}]
        inspect.side_effect = [
            {"attempt": 2},
            {"attempt": 3},
        ]
        already = {ev.attempt_key("ores-truffle-oreslang/oreslang-source.java", 99, 1)}
        reports, updated = ev.collect("token", already)
        self.assertEqual([2, 3], [c.args[2] for c in inspect.call_args_list])
        self.assertEqual([{"attempt": 2}, {"attempt": 3}], reports)
        self.assertIn(ev.attempt_key("ores-truffle-oreslang/oreslang-source.java", 99, 3), updated)

    @patch.object(ev, "BACKENDS", ("java",))
    @patch.object(ev, "pages")
    @patch.object(ev, "inspect_attempt")
    def test_nonqualifying_attempt_is_not_permanently_suppressed(self, inspect, pages):
        pages.return_value = [{"id": 100, "run_attempt": 1, "status": "completed"}]
        inspect.return_value = None
        reports, updated = ev.collect("token", set())
        self.assertEqual([], reports)
        self.assertEqual(set(), updated)

    def test_invalid_state_fails_closed(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            p.write_text('{"not":"a list"}')
            with self.assertRaises(ValueError):
                ev.load_state(p)

if __name__ == "__main__":
    unittest.main()
