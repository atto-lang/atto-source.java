#!/usr/bin/env python3
import importlib.util
import textwrap
import stat
import ast
import io
import json
import os
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
                self.pr(MOD.SOURCE_REPO, 1217, PUBLISHER_HEAD)),
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
            "carrier_incident_comments": self.write("carrier-incident.json", [self.resume_comment(cid=199)]),
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

    def test_valid_exact_head_authority_emits_schema3_receipt(self):
        receipt = self.run_fixture(self.fixture())
        self.assertEqual(receipt["schema"], 3)
        self.assertEqual(receipt["source_commit_sha"], SOURCE_COMMIT)
        self.assertEqual(receipt["source_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["carrier_base_sha"], CARRIER_HEAD)
        self.assertEqual(receipt["incident_resume_comment_id"], 99)
        self.assertEqual(receipt["carrier_incident_resume_comment_id"], 199)
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
                self.pr(MOD.SOURCE_REPO, 1217, PUBLISHER_HEAD, author_id=REVIEWER_ID))
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
            p = self.pr(MOD.SOURCE_REPO, 1217, PUBLISHER_HEAD)
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

    def test_legacy_publisher_pr_1198_denied(self):
        def mutate(fx):
            fx["publisher_pr"] = self.write("legacy-publisher-1198.json",
                self.pr(MOD.SOURCE_REPO, 1198, PUBLISHER_HEAD))
        self.assertDenied(mutate)

    def test_legacy_publisher_pr_1123_denied(self):
        def mutate(fx):
            fx["publisher_pr"] = self.write("legacy-publisher.json",
                self.pr(MOD.SOURCE_REPO, 1123, PUBLISHER_HEAD))
        self.assertDenied(mutate)

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


    def test_missing_carrier_incident_resume_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_incident_comments", self.write("carrier-incident-empty.json", [])))

    def test_carrier_incident_resume_wrong_head_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_incident_comments", self.write("carrier-incident-old-head.json", [
                self.resume_comment(cid=199, publisher="a" * 40)])))

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

    def test_canonical_destination_ledger_rejects_integer_aliases(self):
        for alias in ("+1357615104", "01357615104", "1_357_615_104",
                      "１３５７６１５１０４", "1357615104.0", "1357615104e0"):
            with self.subTest(alias=alias):
                path = self.write("destination-alias.txt",
                                  f"{MOD.CARRIER_REPO} {alias}\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_destination_entry(path)

    def test_canonical_snapshot_ledger_rejects_integer_aliases(self):
        for alias in ("+1357615104", "01357615104", "1_357_615_104",
                      "１３５７６１５１０４", "1357615104.0", "1357615104e0"):
            with self.subTest(alias=alias):
                path = self.write("snapshot-alias.txt",
                                  f"{SOURCE_COMMIT} {SOURCE_TREE} {alias}\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_snapshot_entry(path)

    def test_destination_ledger_does_not_normalize_whitespace(self):
        canonical = f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}"
        for row in (" " + canonical, canonical + " ", "\t" + canonical,
                    canonical.replace(" ", "\t"), canonical.replace(" ", "  "),
                    canonical + "\t", "   # comment"):
            with self.subTest(row=row):
                path = self.write("destination-whitespace.txt", row + "\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_destination_entry(path)

    def test_snapshot_ledger_does_not_normalize_whitespace(self):
        canonical = f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}"
        for row in (" " + canonical, canonical + " ", "\t" + canonical,
                    canonical.replace(" ", "\t"), canonical.replace(" ", "  "),
                    canonical + "\t", "   # comment"):
            with self.subTest(row=row):
                path = self.write("snapshot-whitespace.txt", row + "\n")
                with self.assertRaises(MOD.Denied):
                    MOD.parse_snapshot_entry(path)

    def test_blank_whitespace_line_cannot_hide_in_approved_ledger(self):
        for parser, row in (
                (MOD.parse_destination_entry, f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}"),
                (MOD.parse_snapshot_entry,
                 f"{SOURCE_COMMIT} {SOURCE_TREE} {MOD.CARRIER_REPO_ID}")):
            with self.subTest(parser=parser.__name__):
                path = self.write("ledger-whitespace-line.txt", row + "\n \n")
                with self.assertRaises(MOD.Denied):
                    parser(path)

    def test_repository_metadata_id_must_be_real_integer(self):
        for forged in (float(MOD.CARRIER_REPO_ID), str(MOD.CARRIER_REPO_ID)):
            with self.subTest(forged=forged):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "destination_repo", self.write("destination-forged-type.json", {
                        "id": forged, "full_name": MOD.CARRIER_REPO,
                        "private": True, "archived": False, "disabled": False
                    })))

    def test_nonfinite_json_numbers_fail_closed(self):
        for value in ("NaN", "Infinity", "-Infinity", "1e999", "-1e999"):
            with self.subTest(value=value):
                path = self.write("invalid-number.json", '{"extra":' + value + '}')
                with self.assertRaises(MOD.Denied):
                    MOD.load_json(path)

    def test_finite_standard_json_float_is_still_accepted(self):
        path = self.write("finite-number.json", '{"extra":1.25}')
        self.assertEqual(MOD.load_json(path), {"extra": 1.25})

    def test_reviewer_policy_float_schema_denied(self):
        def mutate(fx):
            policy = self.policy()
            policy["schema"] = 2.0
            fx["reviewer_policy"] = self.write("float-schema.json", policy)
        self.assertDenied(mutate)

    def test_security_pr_float_number_denied(self):
        def mutate(fx):
            pr = self.pr(MOD.SOURCE_REPO, 1217, PUBLISHER_HEAD)
            pr["number"] = 1217.0
            fx["publisher_pr"] = self.write("float-pr-number.json", pr)
        self.assertDenied(mutate)

    def test_live_permission_reviewer_id_type_denied(self):
        for invalid in (float(REVIEWER_ID), str(REVIEWER_ID)):
            with self.subTest(invalid=invalid):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "source_reviewer_permission",
                    self.write("permission-id-type.json",
                               self.permission("write", reviewer_id=invalid))))

    def test_exact_review_reviewer_id_type_denied(self):
        for invalid in (float(REVIEWER_ID), str(REVIEWER_ID)):
            with self.subTest(invalid=invalid):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "publisher_reviews", self.write("review-id-type.json",
                        self.reviews(PUBLISHER_HEAD, reviewer_id=invalid))))

    def test_incident_authorization_reviewer_id_type_denied(self):
        for invalid in (float(REVIEWER_ID), str(REVIEWER_ID)):
            with self.subTest(invalid=invalid):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "incident_comments", self.write("incident-id-type.json", [
                        self.resume_comment(reviewer_id=invalid)])))

    def test_review_timestamp_must_be_utc(self):
        for submitted in ("2026-10-17T20:00:00", "2026-10-17T21:00:00+01:00"):
            with self.subTest(submitted=submitted):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "publisher_reviews", self.write("review-time.json",
                        self.reviews(PUBLISHER_HEAD, submitted=submitted))))

    def test_incident_timestamp_must_be_utc(self):
        for created in ("2026-10-17T20:00:00", "2026-10-17T21:00:00+01:00"):
            with self.subTest(created=created):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "carrier_incident_comments", self.write("incident-time.json", [
                        self.resume_comment(cid=200, created=created)])))

    def test_json_reader_rejects_oversized_input(self):
        path = self.write("oversized-input.json", b" " * (MOD.MAX_JSON + 1))
        with self.assertRaises(MOD.Denied):
            MOD.load_json(path)

    def test_malformed_live_user_object_denied(self):
        for invalid in ([], "fake-user", 7):
            with self.subTest(invalid=invalid):
                value = self.permission("write")
                value["user"] = invalid
                self.assertDenied(lambda fx: fx.__setitem__(
                    "source_reviewer_permission", self.write("bad-user.json", value)))

    def test_oversized_approval_ledgers_denied_before_normalization(self):
        for field in ("destination_ledger", "snapshot_ledger"):
            with self.subTest(field=field):
                self.assertDenied(lambda fx: fx.__setitem__(
                    field, self.write("huge-ledger.txt",
                        b"#" + b"x" * MOD.MAX_LEDGER + b"\n")))

    def test_invalid_utf8_approval_ledgers_denied(self):
        for field in ("destination_ledger", "snapshot_ledger"):
            with self.subTest(field=field):
                self.assertDenied(lambda fx: fx.__setitem__(
                    field, self.write("invalid-ledger.txt", b"\xff")))

    def test_approval_ledger_exact_size_boundary(self):
        canonical = f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}\n"
        comment = "#" + ("x" * (MOD.MAX_LEDGER - len(canonical) - 2)) + "\n"
        path = self.write("boundary-ledger.txt", canonical + comment)
        self.assertEqual(len(Path(path).read_bytes()), MOD.MAX_LEDGER)
        self.assertEqual(MOD.parse_destination_entry(path), MOD.CARRIER_REPO_ID)

    def test_oversized_carrier_workflow_denied(self):
        self.assertDenied(lambda fx: fx.__setitem__(
            "carrier_workflow",
            self.write("huge-workflow.yml", b"#" + b"x" * MOD.MAX_CARRIER_WORKFLOW)))

    def test_oversized_reviewer_policy_denied_after_valid_json(self):
        body = json.dumps(self.policy())
        assert len(body) < MOD.MAX_REVIEWER_POLICY
        oversized = body + " " * (MOD.MAX_REVIEWER_POLICY + 1 - len(body))
        self.assertDenied(lambda fx: fx.__setitem__(
            "reviewer_policy", self.write("oversized-reviewer.json", oversized)))

    def test_malformed_source_commit_tree_shapes_denied(self):
        for commit in ([], None, {"sha": SOURCE_COMMIT, "commit": []},
                       {"sha": SOURCE_COMMIT, "commit": {"tree": []}},
                       {"sha": SOURCE_COMMIT, "commit": {"tree": None}},
                       {"sha": SOURCE_COMMIT, "commit": {"tree": SOURCE_TREE}}):
            with self.subTest(commit=commit):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "source_commit", self.write("bad-commit-tree.json", json.dumps(commit))))

    def test_malformed_destination_document_shapes_denied(self):
        for document in ([], None, "carrier", 0, False):
            with self.subTest(document=document):
                self.assertDenied(lambda fx: fx.__setitem__(
                    "destination_repo", self.write("bad-destination.json", json.dumps(document))))

    def test_bounded_bytes_rejects_invalid_empty_and_bom(self):
        for content in (b"", b"\xef\xbb\xbf{}", b"x" * (MOD.MAX_LEDGER + 1)):
            with self.subTest(length=len(content)):
                path = self.write("invalid-bytes.dat", content)
                with self.assertRaises(MOD.Denied):
                    MOD.bounded_bytes(path, MOD.MAX_LEDGER, "test")

    def test_policy_reader_rejects_symlinks_to_valid_files(self):
        original = self.write(
            "real-ledger.txt", f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}\n")
        linked = self.root / "linked-ledger.txt"
        linked.symlink_to(original)
        with self.assertRaises(MOD.Denied):
            MOD.parse_destination_entry(str(linked))
        original_json = self.write("real-json.json", {"valid": True})
        linked_json = self.root / "linked-json.json"
        linked_json.symlink_to(original_json)
        with self.assertRaises(MOD.Denied):
            MOD.load_json(str(linked_json))

    def test_policy_reader_rejects_fifo_without_blocking(self):
        pipe = self.root / "malicious-policy-fifo"
        os.mkfifo(pipe)
        with self.assertRaises(MOD.Denied):
            MOD.bounded_bytes(str(pipe), MOD.MAX_JSON, "FIFO")

    def test_policy_reader_rejects_hardlinked_files(self):
        original = self.write("original-ledger.txt",
                              f"{MOD.CARRIER_REPO} {MOD.CARRIER_REPO_ID}\n")
        linked = self.root / "hardlinked-ledger.txt"
        os.link(original, linked)
        with self.assertRaises(MOD.Denied):
            MOD.parse_destination_entry(str(linked))

    def test_security_pr_missing_or_forged_merge_state_denied(self):
        for forged in (None, "false", 0, [], {}):
            with self.subTest(forged=forged):
                def mutate(fx):
                    pr = self.pr(MOD.SOURCE_REPO, 1217, PUBLISHER_HEAD)
                    if forged is None:
                        del pr["merged"]
                    else:
                        pr["merged"] = forged
                    fx["publisher_pr"] = self.write("forged-merge-status.json", pr)
                self.assertDenied(mutate)

    def test_verifier_rejects_parent_directory_symlinks(self):
        (self.root / "real").mkdir()
        (self.root / "real" / "policy.json").write_text('{"valid":true}')
        (self.root / "alias").symlink_to("real", target_is_directory=True)
        with self.assertRaises(MOD.Denied):
            MOD.load_json(self.root / "alias" / "policy.json")

    def test_verifier_rejects_nested_parent_symlinks(self):
        (self.root / "real").mkdir()
        (self.root / "real" / "child").mkdir()
        (self.root / "real" / "child" / "policy.json").write_text('{"valid":true}')
        (self.root / "real" / "alias").symlink_to("child", target_is_directory=True)
        with self.assertRaises(MOD.Denied):
            MOD.load_json(self.root / "real" / "alias" / "policy.json")

    def test_verifier_rejects_embedded_nul_while_allowing_absolute_valid_file(self):
        valid = self.write("good.json", {"valid": True})
        self.assertEqual(MOD.load_json(valid), {"valid": True})
        invalid = self.write("nul.json", b'{"valid":true}\x00')
        with self.assertRaises(MOD.Denied):
            MOD.load_json(invalid)

    def test_verifier_directory_denials_do_not_leak_descriptors(self):
        proc = Path("/proc/self/fd")
        if not proc.is_dir():
            self.skipTest("Linux /proc/self/fd unavailable")
        candidate = self.root / "rejected-directory"
        candidate.mkdir()
        before = len(list(proc.iterdir()))
        for _ in range(64):
            with self.assertRaises(MOD.Denied):
                MOD.bounded_bytes(candidate, MOD.MAX_LEDGER, "ledger")
        self.assertEqual(len(list(proc.iterdir())), before)

    def test_verifier_bad_payload_denials_do_not_leak_descriptors(self):
        proc = Path("/proc/self/fd")
        if not proc.is_dir():
            self.skipTest("Linux /proc/self/fd unavailable")
        path = self.write("invalid-bytes.json", b"\xef\xbb\xbf{}")
        before = len(list(proc.iterdir()))
        for _ in range(64):
            with self.assertRaises(MOD.Denied):
                MOD.load_json(path)
        self.assertEqual(len(list(proc.iterdir())), before)

    def test_wrong_destination_id_denied(self):
        def mutate(fx):
            d = {"id": 1, "full_name": MOD.CARRIER_REPO, "private": True,
                 "archived": False, "disabled": False}
            fx["destination_repo"] = self.write("destination-wrong-id.json", d)
        self.assertDenied(mutate)

    def test_carrier_lifecycle_metadata_fails_closed(self):
        for key in ("archived", "disabled"):
            for forged in (None, "false", 0, [], {}):
                with self.subTest(key=key, forged=forged):
                    destination = {
                        "id": MOD.CARRIER_REPO_ID, "full_name": MOD.CARRIER_REPO,
                        "private": True, "archived": False, "disabled": False
                    }
                    if forged is None:
                        del destination[key]
                    else:
                        destination[key] = forged
                    self.assertDenied(lambda fx: fx.__setitem__(
                        "destination_repo",
                        self.write("carrier-lifecycle-malformed.json", destination)))

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

class WorkflowSecureInputOpenTests(unittest.TestCase):
    """Exercise the exact embedded workflow helper without requiring Actions.

    The source-org Actions runner currently fails before step execution.
    Extract the reviewed Python AST from the workflow itself, not a second
    hand-maintained implementation that could drift from production.
    """
    @classmethod
    def setUpClass(cls):
        workflow = (Path(__file__).resolve().parents[1] /
                    ".github/workflows/private-ci-policy.yml").read_text(
                        encoding="utf-8")
        marker = "          python3 -I - <<'PY'\n"
        if workflow.count(marker) != 1:
            raise AssertionError("private CI policy embedded Python missing")
        embedded = workflow.split(marker, 1)[1].split("\n          PY", 1)[0]
        tree = ast.parse(textwrap.dedent(embedded))
        matches = [node for node in tree.body
                   if isinstance(node, ast.FunctionDef) and node.name == "read_regular"]
        if len(matches) != 1:
            raise AssertionError("missing exactly one audited read_regular")
        scope = {"os": os, "stat": stat, "Path": Path}
        exec(compile(ast.Module(body=matches, type_ignores=[]),
                     "<reviewed-workflow-read-regular>", "exec"), scope)
        cls.read_regular = staticmethod(scope["read_regular"])

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.saved_cwd = os.getcwd()
        os.chdir(self.tmp.name)

    def tearDown(self):
        os.chdir(self.saved_cwd)
        self.tmp.cleanup()

    def test_regular_nested_policy_file_allowed(self):
        Path(".github").mkdir()
        Path(".github/policy.json").write_text('{"enabled":false}')
        self.assertEqual(self.read_regular(Path(".github/policy.json")),
                         '{"enabled":false}')

    def test_leaf_symlink_refused(self):
        Path("actual").write_text("policy")
        Path("alias").symlink_to("actual")
        with self.assertRaises(SystemExit):
            self.read_regular("alias")

    def test_parent_directory_symlink_refused(self):
        Path("real").mkdir()
        Path("real/policy.txt").write_text("policy")
        Path("alias").symlink_to("real", target_is_directory=True)
        with self.assertRaises(SystemExit):
            self.read_regular("alias/policy.txt")

    def test_nested_parent_directory_symlink_refused(self):
        Path(".github").mkdir()
        Path(".github/real").mkdir()
        Path(".github/real/policy.txt").write_text("policy")
        Path(".github/alias").symlink_to("real", target_is_directory=True)
        with self.assertRaises(SystemExit):
            self.read_regular(".github/alias/policy.txt")

    def test_directories_and_fifos_cannot_be_read_as_policy(self):
        Path("dir").mkdir()
        with self.assertRaises(SystemExit):
            self.read_regular("dir")
        if hasattr(os, "mkfifo"):
            os.mkfifo("pipe")
            with self.assertRaises(SystemExit):
                self.read_regular("pipe")

    def test_repeated_directory_rejections_do_not_leak_descriptors(self):
        proc = Path("/proc/self/fd")
        if not proc.is_dir():
            self.skipTest("Linux /proc/self/fd unavailable")
        Path("dir").mkdir()
        before = len(list(proc.iterdir()))
        for _ in range(64):
            with self.assertRaises(SystemExit):
                self.read_regular("dir")
        self.assertEqual(len(list(proc.iterdir())), before)

    def test_bad_payload_rejections_do_not_leak_descriptors(self):
        proc = Path("/proc/self/fd")
        if not proc.is_dir():
            self.skipTest("Linux /proc/self/fd unavailable")
        Path("payload").write_bytes(b"\xef\xbb\xbf{}")
        before = len(list(proc.iterdir()))
        for _ in range(64):
            with self.assertRaises(SystemExit):
                self.read_regular("payload")
        self.assertEqual(len(list(proc.iterdir())), before)

    def test_empty_oversized_or_invalid_encoding_rejected(self):
        for name, data in (("empty", b""),
                           ("huge", b"x" * 33),
                           ("bom", b"\xef\xbb\xbf{}"),
                           ("nul", b"a\x00b"),
                           ("badutf8", b"\xff")):
            Path(name).write_bytes(data)
            with self.subTest(name=name), self.assertRaises(SystemExit):
                self.read_regular(name, max_bytes=32)

    def test_dot_dot_and_absolute_paths_refused(self):
        Path("ok").write_text("policy")
        for candidate in ("../ok", str(Path("ok").resolve()), "no/../../ok"):
            with self.subTest(path=candidate), self.assertRaises(SystemExit):
                self.read_regular(candidate)

    def test_missing_file_and_dangling_symlink_refused(self):
        Path("missing-link").symlink_to("does-not-exist")
        for candidate in ("missing", "missing-link"):
            with self.subTest(path=candidate), self.assertRaises(SystemExit):
                self.read_regular(candidate)


if __name__ == "__main__":
    unittest.main()
