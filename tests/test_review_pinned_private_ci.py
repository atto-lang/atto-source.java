#!/usr/bin/env python3
import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/verify-review-pinned-private-ci.py"
SPEC = importlib.util.spec_from_file_location("review_pinned", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

PUBLISHER_HEAD = "1" * 40
DEST_HEAD = "2" * 40
SNAP_HEAD = "3" * 40
CARRIER_HEAD = "4" * 40
SOURCE_COMMIT = "5" * 40
SOURCE_TREE = "6" * 40

class ReviewPinnedPrivateCiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, value):
        p = self.root / name
        if isinstance(value, (dict, list)):
            p.write_text(json.dumps(value), encoding="utf-8")
        elif isinstance(value, bytes):
            p.write_bytes(value)
        else:
            p.write_text(value, encoding="utf-8")
        return str(p)

    def pr(self, repo, number, head):
        return {
            "number": number,
            "state": "open",
            "merged": False,
            "head": {"sha": head, "repo": {"full_name": repo}},
            "base": {"repo": {"full_name": repo}},
        }

    def reviews(self, head, reviewer="ORESoftware", state="APPROVED", rid=1):
        return [{"id": rid, "state": state, "commit_id": head,
                 "user": {"login": reviewer}}]

    def fixture(self):
        args = {
            "publisher_pr": self.write("publisher-pr.json",
                self.pr(MOD.SOURCE_REPO, 1123, PUBLISHER_HEAD)),
            "publisher_reviews": self.write("publisher-reviews.json",
                self.reviews(PUBLISHER_HEAD, rid=11)),
            "destination_pr": self.write("destination-pr.json",
                self.pr(MOD.SOURCE_REPO, 1092, DEST_HEAD)),
            "destination_reviews": self.write("destination-reviews.json",
                self.reviews(DEST_HEAD, rid=12)),
            "snapshot_pr": self.write("snapshot-pr.json",
                self.pr(MOD.SOURCE_REPO, 1173, SNAP_HEAD)),
            "snapshot_reviews": self.write("snapshot-reviews.json",
                self.reviews(SNAP_HEAD, rid=13)),
            "carrier_pr": self.write("carrier-pr.json",
                self.pr(MOD.CARRIER_REPO, 7, CARRIER_HEAD)),
            "carrier_reviews": self.write("carrier-reviews.json",
                self.reviews(CARRIER_HEAD, rid=14)),
            "incident_comments": self.write("incident.json", [{
                "id": 99,
                "body": "PRIVATE_CI_RESUME_APPROVED exact reviewed recovery gate",
                "user": {"login": "ORESoftware"},
            }]),
            "source_commit": self.write("source-commit.json", {
                "sha": SOURCE_COMMIT,
                "commit": {"tree": {"sha": SOURCE_TREE}},
            }),
            "destination_repo": self.write("destination.json", {
                "id": MOD.CARRIER_REPO_ID,
                "full_name": MOD.CARRIER_REPO,
                "private": True,
                "archived": False,
                "disabled": False,
            }),
            "carrier_workflow": self.write("workflow.yml",
                b"name: exact\non:\n  push:\n"),
            "snapshot_ledger": self.write("ledger.txt",
                f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}\n"),
        }
        return args

    def run_fixture(self, values):
        argv = []
        for key, value in values.items():
            argv += ["--" + key.replace("_", "-"), value]
        old = MOD.argparse.ArgumentParser.parse_args
        try:
            def parse_args(parser_self):
                return old(parser_self, argv)
            MOD.argparse.ArgumentParser.parse_args = parse_args
            out = io.StringIO()
            with redirect_stdout(out):
                MOD.main()
            return json.loads(out.getvalue())
        finally:
            MOD.argparse.ArgumentParser.parse_args = old

    def test_valid_exact_head_authority_emits_receipt(self):
        receipt = self.run_fixture(self.fixture())
        self.assertEqual(receipt["source_commit_sha"], SOURCE_COMMIT)
        self.assertEqual(receipt["source_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["carrier_base_sha"], CARRIER_HEAD)
        self.assertEqual(receipt["incident_resume_comment_id"], 99)
        self.assertEqual(receipt["publisher_review_id"], 11)
        self.assertEqual(receipt["carrier_review_id"], 14)

    def assertDenied(self, mutate):
        fx = self.fixture()
        mutate(fx)
        with self.assertRaises(MOD.Denied):
            self.run_fixture(fx)

    def test_stale_review_commit_denied(self):
        def mutate(fx):
            fx["publisher_reviews"] = self.write("publisher-reviews-stale.json",
                self.reviews("a" * 40))
        self.assertDenied(mutate)

    def test_wrong_reviewer_denied(self):
        def mutate(fx):
            fx["carrier_reviews"] = self.write("carrier-reviews-wrong.json",
                self.reviews(CARRIER_HEAD, reviewer="the1mills"))
        self.assertDenied(mutate)

    def test_duplicate_exact_approvals_denied(self):
        def mutate(fx):
            fx["snapshot_reviews"] = self.write("snapshot-reviews-dupe.json",
                self.reviews(SNAP_HEAD, rid=1) + self.reviews(SNAP_HEAD, rid=2))
        self.assertDenied(mutate)

    def test_nonapproved_review_denied(self):
        def mutate(fx):
            fx["destination_reviews"] = self.write("destination-reviews-changes.json",
                self.reviews(DEST_HEAD, state="CHANGES_REQUESTED"))
        self.assertDenied(mutate)

    def test_closed_security_pr_denied(self):
        def mutate(fx):
            p = self.pr(MOD.SOURCE_REPO, 1123, PUBLISHER_HEAD)
            p["state"] = "closed"
            fx["publisher_pr"] = self.write("publisher-pr-closed.json", p)
        self.assertDenied(mutate)

    def test_wrong_pr_repository_denied(self):
        def mutate(fx):
            fx["carrier_pr"] = self.write("carrier-pr-wrong.json",
                self.pr("other/repo", 7, CARRIER_HEAD))
        self.assertDenied(mutate)

    def test_resume_marker_must_be_independent(self):
        def mutate(fx):
            fx["incident_comments"] = self.write("incident-self.json", [{
                "id": 99, "body": MOD.RESUME_MARKER,
                "user": {"login": "the1mills"},
            }])
        self.assertDenied(mutate)

    def test_duplicate_resume_approvals_denied(self):
        def mutate(fx):
            fx["incident_comments"] = self.write("incident-dupe.json", [
                {"id": 99, "body": MOD.RESUME_MARKER, "user": {"login": "ORESoftware"}},
                {"id": 100, "body": MOD.RESUME_MARKER, "user": {"login": "ORESoftware"}},
            ])
        self.assertDenied(mutate)

    def test_source_tree_mismatch_denied(self):
        def mutate(fx):
            fx["source_commit"] = self.write("source-tree-wrong.json", {
                "sha": SOURCE_COMMIT, "commit": {"tree": {"sha": "7" * 40}},
            })
        self.assertDenied(mutate)

    def test_multiple_active_snapshot_rows_denied(self):
        def mutate(fx):
            fx["snapshot_ledger"] = self.write("ledger-multiple.txt",
                f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}\n"
                f"{'8'*40} {'9'*40} {MOD.CARRIER_REPO_ID}\n")
        self.assertDenied(mutate)

    def test_wrong_destination_id_denied(self):
        def mutate(fx):
            d = {"id": 1, "full_name": MOD.CARRIER_REPO, "private": True,
                 "archived": False, "disabled": False}
            fx["destination_repo"] = self.write("destination-wrong-id.json", d)
        self.assertDenied(mutate)

    def test_public_destination_denied(self):
        def mutate(fx):
            d = {"id": MOD.CARRIER_REPO_ID, "full_name": MOD.CARRIER_REPO,
                 "private": False, "archived": False, "disabled": False}
            fx["destination_repo"] = self.write("destination-public.json", d)
        self.assertDenied(mutate)

    def test_archived_destination_denied(self):
        def mutate(fx):
            d = {"id": MOD.CARRIER_REPO_ID, "full_name": MOD.CARRIER_REPO,
                 "private": True, "archived": True, "disabled": False}
            fx["destination_repo"] = self.write("destination-archived.json", d)
        self.assertDenied(mutate)

    def test_duplicate_json_key_denied(self):
        fx = self.fixture()
        fx["destination_repo"] = self.write(
            "destination-duplicate.json",
            '{"id":1357615104,"id":1357615104,"full_name":"sagitta-stack/sagitta-infra",'
            '"private":true,"archived":false,"disabled":false}'
        )
        with self.assertRaises(MOD.Denied):
            self.run_fixture(fx)

if __name__ == "__main__":
    unittest.main()
