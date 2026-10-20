"""Design inventory checks only. NOT compiler or HTTP transport conformance.

Run: python3 -m unittest discover -s contracts/v1/http-response-fetch -p 'test_*.py'
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = ROOT / "docs" / "HTTP_RESPONSE_FETCH_API_V1.md"
CASES = Path(__file__).with_name("cases.json")


class FetchResponseDesignInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = SPEC.read_text(encoding="utf-8")
        cls.inventory = json.loads(CASES.read_text(encoding="utf-8"))

    def test_inventory_never_masquerades_as_backend_proof(self):
        inv = self.inventory
        self.assertEqual("DESIGN_ONLY_NOT_EXECUTABLE", inv["status"])
        self.assertIn("not executed", inv["evidence_note"])
        self.assertIn("NOT compiler-verified", self.doc)
        self.assertEqual(["java-graalvm", "llvm-native"], inv["source_backends"])
        for case in inv["cases"]:
            self.assertEqual(
                {"java-graalvm": "not_verified", "llvm-native": "not_verified"},
                case["backend_status"], case["id"])

    def test_unique_ordered_cases_and_required_negative_coverage(self):
        cases = self.inventory["cases"]
        ids = [c["id"] for c in cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(
            [f"HTTP-RSP-{n:03d}" for n in range(1, len(ids) + 1)], ids)
        self.assertGreaterEqual(len(cases), 35)
        categories = [c["expected"] for c in cases]
        self.assertGreaterEqual(categories.count("accept"), 12)
        self.assertGreaterEqual(categories.count("reject"), 14)
        self.assertGreaterEqual(categories.count("abort"), 2)
        self.assertEqual({"accept", "reject", "abort"}, set(categories))
        self.assertTrue(all(len(c["description"]) >= 20 for c in cases))

    def test_spec_covers_security_ownership_streaming_and_http_framing(self):
        for token in (
            "HttpResponse.empty(init)", "HttpResponse.json", "Content-Digest",
            "trailerNames", "Future<HttpHeaders>", "200–599", "204, 205",
            "304", "HEAD", "Set-Cookie", "Content-Length",
            "Transfer-Encoding", "BACKPRESSURE", "ABORTED",
            "HTTP/1.1", "HTTP/2", "HTTP/3", "not a claim",
        ):
            self.assertIn(token.casefold(), self.doc.casefold(), token)

    def test_standard_fetch_api_and_trailers_are_distinct(self):
        self.assertIn("WHATWG", self.doc)
        self.assertRegex(self.doc, r"\`ResponseInit\` does not have these properties")
        self.assertIn("Oreslang extensions", self.doc)
        self.assertNotIn("production-ready", self.doc.casefold())


if __name__ == "__main__":
    unittest.main()
