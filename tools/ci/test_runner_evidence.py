import unittest
from runner_evidence import meaningful, qualified

class EvidenceTests(unittest.TestCase):
    def test_housekeeping_and_incomplete(self):
        for name in ("Set up job", "Complete job", "Post checkout"):
            self.assertFalse(meaningful({"name": name, "status": "completed", "conclusion": "success"}))
        self.assertFalse(meaningful({"name": "Compile", "status": "in_progress"}))
    def test_requires_runner_and_real_step(self):
        job = {"runner_id": 42, "runner_name": "GitHub Actions 1",
               "steps": [{"name": "Compile", "status": "completed", "conclusion": "failure"}]}
        self.assertTrue(qualified(job))
        for invalid in (0, None, -1, True):
            self.assertFalse(qualified({**job, "runner_id": invalid}))
        self.assertFalse(qualified({**job, "runner_name": " "}))
        self.assertFalse(qualified({**job, "steps": [{"name": "Set up job", "status": "completed", "conclusion": "success"}]}))
if __name__ == "__main__":
    unittest.main()
