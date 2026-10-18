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
REVIEWER_LOGIN = "external-security-reviewer"
REVIEWER_ID = 9000001
OPERATOR_IDS = [11139560, 2148448]

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

    def policy(self, reviewer_id=REVIEWER_ID, login=REVIEWER_LOGIN, enabled=True,
               minimum="write", blocked=None):
        return {
            "schema": 2,
            "enabled": enabled,
            "minimumPermission": minimum,
            "disallowedReviewerIds": list(OPERATOR_IDS if blocked is None else blocked),
            "reviewer": {"login": login, "id": reviewer_id} if enabled else None,
        }

    def permission(self, permission="write", login=REVIEWER_LOGIN,
                   reviewer_id=REVIEWER_ID, user_type="User"):
        return {
            "permission": permission,
            "user": {"login": login, "id": reviewer_id, "type": user_type},
        }

    def pr(self, repo, number, head, author_id=None):
        if author_id is None:
            author_id = 8000000 + number
        return {
            "number": number,
            "state": "open",
            "merged": False,
            "draft": False,
            "user": {"login": "security-author", "id": author_id, "type": "User"},
            "head": {"sha": head, "repo": {"full_name": repo}},
            "base": {"ref": "main", "repo": {"full_name": repo}},
        }

    def reviews(self, head, reviewer=REVIEWER_LOGIN, reviewer_id=REVIEWER_ID,
                state="APPROVED", rid=1, submitted="2026-10-17T20:00:00Z",
                user_type="User"):
        return [{
            "id": rid,
            "state": state,
            "commit_id": head,
            "submitted_at": submitted,
            "user": {"login": reviewer, "id": reviewer_id, "type": user_type},
        }]

    def resume_comment(self, cid=99, login=REVIEWER_LOGIN, reviewer_id=REVIEWER_ID,
                       publisher=PUBLISHER_HEAD, user_type="User",
                       marker=None, created="2026-10-17T20:00:00Z"):
        return {
            "id": cid,
            "created_at": created,
            "body": (
                (MOD.RESUME_MARKER if marker is None else marker)
                + " publisher=" + publisher
                + " destination=" + DEST_HEAD
                + " snapshot=" + SNAP_HEAD
                + " carrier=" + CARRIER_HEAD
                + " source_commit=" + SOURCE_COMMIT
                + " source_tree=" + SOURCE_TREE
                + " carrier_repo_id=" + str(MOD.CARRIER_REPO_ID)
            ),
            "user": {"login": login, "id": reviewer_id, "type": user_type},
        }

    def fixture(self):
        return {
            "reviewer_policy": self.write("reviewer-policy.json", self.policy()),
            "source_reviewer_permission": self.write(
                "source-reviewer-permission.json", self.permission("write")),
            "carrier_reviewer_permission": self.write(
                "carrier-reviewer-permission.json", self.permission("maintain")),
            "publisher_pr": self.write("publisher-pr.json",
                self.pr(MOD.SOURCE_REPO, 1123, PUBLISHER_HEAD)),
            "publisher_reviews": self.write("publisher-reviews.json",
                self.reviews(PUBLISHER_HEAD, rid=11)),
            "destination_pr": self.write("destination-pr.json",
                self.pr(MOD.SOURCE_REPO, 1092, DEST_HEAD)),
            "destination_reviews": self.write("destination-reviews.json",
                self.reviews(DEST_HEAD, rid=12)),
            "snapshot_pr": self.write("snapshot-pr.json",
                self.pr(MOD.SOURCE_REPO, 1189, SNAP_HEAD)),
            "snapshot_reviews": self.write("snapshot-reviews.json",
                self.reviews(SNAP_HEAD, rid=13)),
            "carrier_pr": self.write("carrier-pr.json",
                self.pr(MOD.CARRIER_REPO, 7, CARRIER_HEAD)),
            "carrier_reviews": self.write("carrier-reviews.json",
                self.reviews(CARRIER_HEAD, rid=14)),
            "incident_comments": self.write("incident.json", [self.resume_comment()]),
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
            "destination_ledger": self.write("destination-ledger.txt",
                f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}\n"),
            "carrier_workflow": self.write("workflow.yml",
                b"name: exact\non:\n  push:\n"),
            "snapshot_ledger": self.write("ledger.txt",
                f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}\n"),
        }

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

    def assertDenied(self, mutate):
        fx = self.fixture()
        mutate(fx)
        with self.assertRaises(MOD.Denied):
            self.run_fixture(fx)

    def test_permission_fixture_matches_live_permission_contract(self):
        value = self.permission("maintain")
        self.assertEqual(value, {
            "permission": "maintain",
            "user": {
                "login": REVIEWER_LOGIN,
                "id": REVIEWER_ID,
                "type": "User",
            },
        })
        self.assertEqual(
            MOD.live_permission(value, (REVIEWER_LOGIN, REVIEWER_ID, "write"), "write", "fixture"),
            "maintain",
        )

    def test_valid_exact_head_authority_emits_schema2_receipt(self):
        receipt = self.run_fixture(self.fixture())
        self.assertEqual(receipt["schema"], 2)
        self.assertEqual(receipt["source_commit_sha"], SOURCE_COMMIT)
        self.assertEqual(receipt["source_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["carrier_base_sha"], CARRIER_HEAD)
        self.assertEqual(receipt["incident_resume_comment_id"], 99)
        self.assertEqual(receipt["publisher_review_id"], 11)
        self.assertEqual(receipt["carrier_review_id"], 14)
        self.assertEqual(receipt["independent_reviewer_login"], REVIEWER_LOGIN)
        self.assertEqual(receipt["independent_reviewer_id"], REVIEWER_ID)
        self.assertRegex(receipt["independent_reviewer_policy_blob_sha"], r"^[0-9a-f]{40}$")
        self.assertEqual(receipt["independent_reviewer_source_permission"], "write")
        self.assertEqual(receipt["independent_reviewer_carrier_permission"], "maintain")

    def test_disabled_reviewer_policy_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("disabled.json", self.policy(enabled=False))))

    def test_schema1_reviewer_policy_denied(self):
        def mutate(fx):
            p = self.policy()
            p["schema"] = 1
            fx["reviewer_policy"] = self.write("schema1.json", p)
        self.assertDenied(mutate)

    def test_invalid_minimum_permission_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("bad-min.json", self.policy(minimum="read"))))

    def test_duplicate_blocked_ids_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("dupe-block.json",
                self.policy(blocked=[11139560, 11139560]))))

    def test_missing_operator_id_from_denylist_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("missing-operator.json",
                self.policy(blocked=[11139560]))))

    def test_extra_reviewer_id_in_denylist_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("extra-block.json",
                self.policy(blocked=[11139560, 2148448, 9000002]))))

    def test_operator_account_oresoftware_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("operator1.json",
                self.policy(reviewer_id=11139560, login="ORESoftware"))))

    def test_operator_account_the1mills_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("operator2.json",
                self.policy(reviewer_id=2148448, login="the1mills"))))

    def test_reviewer_cannot_be_pr_author(self):
        def mutate(fx):
            fx["publisher_pr"] = self.write("self-author.json",
                self.pr(MOD.SOURCE_REPO, 1123, PUBLISHER_HEAD, author_id=REVIEWER_ID))
        self.assertDenied(mutate)

    def test_source_live_permission_read_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "source_reviewer_permission", self.write("source-read.json",
                self.permission("read"))))

    def test_carrier_live_permission_triage_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_reviewer_permission", self.write("carrier-triage.json",
                self.permission("triage"))))

    def test_live_permission_none_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "source_reviewer_permission", self.write("source-none.json",
                self.permission("none"))))

    def test_live_permission_wrong_id_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "source_reviewer_permission", self.write("source-wrong-id.json",
                self.permission("admin", reviewer_id=1))))

    def test_live_permission_renamed_login_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_reviewer_permission", self.write("carrier-renamed.json",
                self.permission("admin", login="renamed-reviewer"))))

    def test_live_permission_bot_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_reviewer_permission", self.write("carrier-bot.json",
                self.permission("admin", user_type="Bot"))))

    def test_stale_review_commit_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "publisher_reviews", self.write("stale.json", self.reviews("a" * 40))))

    def test_reviewer_id_mismatch_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_reviews", self.write("wrong-id.json",
                self.reviews(CARRIER_HEAD, reviewer_id=1))))

    def test_wrong_reviewer_login_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_reviews", self.write("wrong-login.json",
                self.reviews(CARRIER_HEAD, reviewer="wrong-reviewer"))))

    def test_review_bot_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_reviews", self.write("bot-review.json",
                self.reviews(CARRIER_HEAD, user_type="Bot"))))

    def test_nonapproved_review_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "destination_reviews", self.write("changes.json",
                self.reviews(DEST_HEAD, state="CHANGES_REQUESTED"))))

    def test_later_changes_requested_revokes_exact_head_approval(self):
        def mutate(fx):
            fx["publisher_reviews"] = self.write("revoked.json",
                self.reviews(PUBLISHER_HEAD, state="APPROVED", rid=10,
                             submitted="2026-10-17T20:00:00Z")
                + self.reviews(PUBLISHER_HEAD, state="CHANGES_REQUESTED", rid=11,
                               submitted="2026-10-17T20:01:00Z"))
        self.assertDenied(mutate)

    def test_newer_approval_restores_exact_head_authority(self):
        fx = self.fixture()
        fx["publisher_reviews"] = self.write("restored.json",
            self.reviews(PUBLISHER_HEAD, state="CHANGES_REQUESTED", rid=10,
                         submitted="2026-10-17T20:00:00Z")
            + self.reviews(PUBLISHER_HEAD, state="APPROVED", rid=11,
                           submitted="2026-10-17T20:01:00Z"))
        receipt = self.run_fixture(fx)
        self.assertEqual(receipt["publisher_review_id"], 11)

    def test_equal_timestamp_uses_higher_review_id(self):
        fx = self.fixture()
        fx["publisher_reviews"] = self.write("same-time.json",
            self.reviews(PUBLISHER_HEAD, state="CHANGES_REQUESTED", rid=10,
                         submitted="2026-10-17T20:00:00Z")
            + self.reviews(PUBLISHER_HEAD, state="APPROVED", rid=11,
                           submitted="2026-10-17T20:00:00Z"))
        receipt = self.run_fixture(fx)
        self.assertEqual(receipt["publisher_review_id"], 11)

    def test_missing_submitted_at_denied(self):
        def mutate(fx):
            r = self.reviews(PUBLISHER_HEAD)
            del r[0]["submitted_at"]
            fx["publisher_reviews"] = self.write("no-time.json", r)
        self.assertDenied(mutate)

    def test_closed_security_pr_denied(self):
        def mutate(fx):
            p = self.pr(MOD.SOURCE_REPO, 1123, PUBLISHER_HEAD)
            p["state"] = "closed"
            fx["publisher_pr"] = self.write("closed.json", p)
        self.assertDenied(mutate)

    def test_non_main_security_pr_denied(self):
        def mutate(fx):
            p = self.pr(MOD.SOURCE_REPO, 1092, DEST_HEAD)
            p["base"]["ref"] = "release"
            fx["destination_pr"] = self.write("wrong-base.json", p)
        self.assertDenied(mutate)

    def test_draft_security_pr_denied(self):
        def mutate(fx):
            p = self.pr(MOD.SOURCE_REPO, 1092, DEST_HEAD)
            p["draft"] = True
            fx["destination_pr"] = self.write("draft.json", p)
        self.assertDenied(mutate)

    def test_wrong_pr_repository_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_pr", self.write("wrong-repo.json",
                self.pr("other/repo", 7, CARRIER_HEAD))))

    def test_resume_marker_wrong_reviewer_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "incident_comments", self.write("incident-wrong.json", [
                self.resume_comment(login="wrong-reviewer", reviewer_id=42)])))

    def test_resume_marker_wrong_id_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "incident_comments", self.write("incident-wrong-id.json", [
                self.resume_comment(reviewer_id=42)])))

    def test_duplicate_resume_approvals_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "incident_comments", self.write("incident-dupe.json", [
                self.resume_comment(cid=99, created="2026-10-17T20:00:00Z"),
                self.resume_comment(cid=100, created="2026-10-17T20:01:00Z")])))

    def test_resume_revocation_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "incident_comments", self.write("incident-revoked.json", [
                self.resume_comment(cid=99, created="2026-10-17T20:00:00Z"),
                self.resume_comment(cid=100, marker=MOD.RESUME_REVOKE_MARKER,
                                    created="2026-10-17T20:01:00Z")])))

    def test_resume_reapproval_after_revocation_allowed(self):
        fx = self.fixture()
        fx["incident_comments"] = self.write("incident-reapproved.json", [
            self.resume_comment(cid=99, created="2026-10-17T20:00:00Z"),
            self.resume_comment(cid=100, marker=MOD.RESUME_REVOKE_MARKER,
                                created="2026-10-17T20:01:00Z"),
            self.resume_comment(cid=101, created="2026-10-17T20:02:00Z"),
        ])
        receipt = self.run_fixture(fx)
        self.assertEqual(receipt["incident_resume_comment_id"], 101)

    def test_resume_comment_missing_created_at_denied(self):
        def mutate(fx):
            value = self.resume_comment()
            del value["created_at"]
            fx["incident_comments"] = self.write("incident-no-created.json", [value])
        self.assertDenied(mutate)

    def test_unbound_resume_marker_denied(self):
        def mutate(fx):
            c = self.resume_comment()
            c["body"] = MOD.RESUME_MARKER + " generic"
            fx["incident_comments"] = self.write("incident-unbound.json", [c])
        self.assertDenied(mutate)

    def test_resume_bound_to_old_head_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "incident_comments", self.write("incident-old-head.json", [
                self.resume_comment(publisher="a" * 40)])))

    def test_source_tree_mismatch_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "source_commit", self.write("source-tree-wrong.json", {
                "sha": SOURCE_COMMIT, "commit": {"tree": {"sha": "7" * 40}},
            })))

    def test_multiple_active_snapshot_rows_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "snapshot_ledger", self.write("ledger-multiple.txt",
                f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}\n"
                f"{'8'*40} {'9'*40} {MOD.CARRIER_REPO_ID}\n")))

    def test_wrong_destination_ledger_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "destination_ledger", self.write("destination-wrong.txt",
                "other/repo 1357615104\n")))

    def test_multiple_destination_entries_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "destination_ledger", self.write("destination-multiple.txt",
                f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}\n"
                f"other/repo {MOD.CARRIER_REPO_ID}\n")))

    def test_destination_ledger_rejects_noncanonical_numeric_aliases(self):
        # Python int() accepts all of these representations of the same ID.
        for alias in ("+1357615104", "01357615104", "1_357_615_104",
                      "１３５７６１５１０４"):
            with self.subTest(alias=alias):
                path = self.write("noncanonical-destination.txt",
                                  f"{MOD.CARRIER_REPO} {alias}\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_destination_entry(path)

    def test_snapshot_ledger_rejects_noncanonical_numeric_aliases(self):
        for alias in ("+1357615104", "01357615104", "1_357_615_104",
                      "１３５７６１５１０４"):
            with self.subTest(alias=alias):
                path = self.write("noncanonical-snapshot.txt",
                                  f"{SOURCE_COMMIT} {SOURCE_TREE} {alias}\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_snapshot_entry(path)

    def test_destination_ledger_rejects_whitespace_normalization(self):
        canonical = f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}"
        for row in (" " + canonical, canonical + " ", "\t" + canonical,
                    canonical.replace(" ", "\t"),
                    canonical.replace(" ", "  ")):
            with self.subTest(row=row):
                path = self.write("whitespace-destination.txt", row + "\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_destination_entry(path)

    def test_snapshot_ledger_rejects_whitespace_normalization(self):
        canonical = f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}"
        for row in (" " + canonical, canonical + " ", "\t" + canonical,
                    canonical.replace(" ", "\t"),
                    canonical.replace(" ", "  ")):
            with self.subTest(row=row):
                path = self.write("whitespace-snapshot.txt", row + "\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_snapshot_entry(path)

    def test_ledger_rejects_unicode_line_separators_and_ascii_controls(self):
        destination = f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}"
        snapshot = f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}"
        for sep in ("\u0085", "\u2028", "\u2029", "\r",
                    "\v", "\f", "\x00", "\t"):
            # Hidden separators must not create new accepted rows/comments.
            with self.subTest(separator=repr(sep)):
                p = self.write("ledger-destination.txt", destination + sep + "#extra\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_destination_entry(p)
                p = self.write("ledger-snapshot.txt", snapshot + sep + "#extra\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_snapshot_entry(p)

    def test_ledger_rejects_indented_comments_and_space_only_lines(self):
        # Workflow and reviewer verifier must interpret these exact same rows.
        destination = f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}"
        snapshot = f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}"
        for trailing in (" \n", " #disguised-comment\n"):
            with self.subTest(trailing=repr(trailing)):
                path = self.write("noncanonical-destination.txt",
                                  destination + "\n" + trailing)
                with self.assertRaises(MOD.Denied):
                    MOD.parse_destination_entry(path)
                path = self.write("noncanonical-snapshot.txt",
                                  snapshot + "\n" + trailing)
                with self.assertRaises(MOD.Denied):
                    MOD.parse_snapshot_entry(path)

    def test_ledger_rejects_oversized_input_without_normalization(self):
        oversized = b"#" + b"x" * 65536 + b"\n"
        for parser in (MOD.parse_destination_entry, MOD.parse_snapshot_entry):
            with self.subTest(parser=parser.__name__):
                p = self.write("ledger-oversized.txt", oversized)
                with self.assertRaises(MOD.Denied):
                    parser(p)

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
