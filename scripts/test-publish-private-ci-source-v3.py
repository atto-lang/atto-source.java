#!/usr/bin/env python3
"""Source-free unit tests for the protected v3 private-CI dispatcher."""
import base64
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("publish-private-ci-source-v3.py")
SPEC = importlib.util.spec_from_file_location("private_ci_v3", SCRIPT)
v3 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v3)

SOURCE_TREE = "a" * 40
ROOT_TREE = "b" * 40
COMMIT = "c" * 40
BASE = "d" * 40
WORKFLOW = "e" * 40
PLAN_BLOB = "f" * 40
PROTECTED = "1" * 40
NONCE = "0" * 32
POLICY = b"p" * 32
PLAN = (
    json.dumps(
        {
            "schema": "ores-private-ci-test-plan-v1",
            "source_repo": v3.legacy.SOURCE_REPO,
            "allowed_prs": [1482],
            "commands": [
                {
                    "argv": ["python3", "-c", "print('ok')"],
                    "cwd": ".",
                    "timeout_seconds": 30,
                }
            ],
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    + "\n"
).encode()


def contract():
    return {
        "repository_id": 1323598177,
        "base_sha": BASE,
        "workflow_path": ".github/workflows/oreslang-exact-tree-carrier.yml",
        "workflow_blob_sha": WORKFLOW,
        "test_plan_path": "ci/test-plans/java-pr1482.json",
        "test_plan_blob_sha": PLAN_BLOB,
    }


class PrivateCiV3Test(unittest.TestCase):
    def test_carrier_main_requires_protected_reviews_ci_and_codeowners(self):
        rules = (
            b"* @ORESoftware\n"
            b"/.github/ @ORESoftware\n"
            b"/.github/CODEOWNERS @ORESoftware\n"
        )
        root = "repos/trusted/ci"
        branch = {"name": "main", "protected": True,
                  "commit": {"sha": BASE}}
        protection = {
            "required_pull_request_reviews": {
                "require_code_owner_reviews": True,
                "dismiss_stale_reviews": True,
                "required_approving_review_count": 1,
                "bypass_pull_request_allowances": {
                    "users": [], "teams": [], "apps": [],
                },
            },
            "required_status_checks": {
                "strict": True, "contexts": ["carrier-contracts"], "checks": [],
            },
            "enforce_admins": {"enabled": True},
            "allow_force_pushes": {"enabled": False},
            "allow_deletions": {"enabled": False},
        }
        codeowners = {
            "path": ".github/CODEOWNERS", "type": "file",
            "encoding": "base64",
            "content": base64.b64encode(rules).decode("ascii"),
            "sha": v3.git_blob_sha(rules),
        }
        def api(route):
            return {
                root + "/branches/main": branch,
                root + "/branches/main/protection": protection,
                root + "/contents/.github/CODEOWNERS?ref=" + BASE: codeowners,
            }[route]
        with patch.object(v3.legacy, "gh_api", side_effect=api) as gh:
            v3.require_destination_main_governance("trusted/ci", BASE)
        self.assertEqual(gh.call_count, 3)

        bad_cases = (
            (dict(branch, protected=False), protection, codeowners),
            (branch, dict(protection, enforce_admins={"enabled": False}), codeowners),
            (branch, dict(protection, required_status_checks={
                "strict": False, "contexts": ["carrier-contracts"], "checks": [],
            }), codeowners),
            (branch, dict(protection, required_pull_request_reviews={
                **protection["required_pull_request_reviews"],
                "bypass_pull_request_allowances": {
                    "users": [{"login": "bypass"}], "teams": [], "apps": [],
                },
            }), codeowners),
            (branch, dict(protection, allow_force_pushes={"enabled": True}), codeowners),
            (branch, protection, dict(codeowners, sha="0" * 40)),
        )
        for changed_branch, changed_protection, changed_owners in bad_cases:
            with self.subTest(change=(changed_branch, changed_protection, changed_owners)):
                def changed(route):
                    return {
                        root + "/branches/main": changed_branch,
                        root + "/branches/main/protection": changed_protection,
                        root + "/contents/.github/CODEOWNERS?ref=" + BASE: changed_owners,
                    }[route]
                with patch.object(v3.legacy, "gh_api", side_effect=changed):
                    with self.assertRaises(v3.Denied):
                        v3.require_destination_main_governance("trusted/ci", BASE)

    def test_direct_carrier_directory_proof_detects_extra_empty_subtrees(self):
        base_root = "1" * 40
        new_root = "2" * 40
        old_ci = "3" * 40
        new_ci = "4" * 40
        receipt = "5" * 40
        license_sha = "6" * 40
        plan_sha = "7" * 40
        def entry(name, mode, kind, sha):
            return {"path": name, "mode": mode, "type": kind, "sha": sha}
        original = [
            entry("ci", "040000", "tree", old_ci),
            entry("LICENSE", "100644", "blob", license_sha),
        ]
        published = [
            entry("ci", "040000", "tree", new_ci),
            entry("LICENSE", "100644", "blob", license_sha),
            entry(v3.legacy.PREFIX, "040000", "tree", SOURCE_TREE),
        ]
        ci_before = [entry("test-plan.json", "100644", "blob", plan_sha)]
        ci_after = ci_before + [
            entry("oreslang-receipt.json", "100644", "blob", receipt)
        ]
        listings = {
            base_root: original, new_root: published,
            old_ci: ci_before, new_ci: ci_after,
        }
        def api(route):
            sha = route.rsplit("/", 1)[1]
            return {"sha": sha, "truncated": False, "tree": listings[sha]}
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            v3.verify_carrier_directories(
                "trusted/ci", base_root, SOURCE_TREE, receipt, new_root
            )
            listings[new_root] = published + [
                entry("injected-empty-tree", "040000", "tree", "8" * 40)
            ]
            with self.assertRaisesRegex(v3.Denied, "root directory differs"):
                v3.verify_carrier_directories(
                    "trusted/ci", base_root, SOURCE_TREE, receipt, new_root
                )
            listings[new_root] = published
            listings[new_ci] = ci_after + [
                entry("unexpected.txt", "100644", "blob", "8" * 40)
            ]
            with self.assertRaisesRegex(v3.Denied, "receipt directory changed"):
                v3.verify_carrier_directories(
                    "trusted/ci", base_root, SOURCE_TREE, receipt, new_root
                )
            listings[new_ci] = ci_after
            listings[new_root] = [
                dict(item, sha="9" * 40)
                if item["path"] == v3.legacy.PREFIX else item
                for item in published
            ]
            with self.assertRaisesRegex(v3.Denied, "root directory differs"):
                v3.verify_carrier_directories(
                    "trusted/ci", base_root, SOURCE_TREE, receipt, new_root
                )

    def test_direct_carrier_tree_rejects_unsafe_entries_and_missing_metadata(self):
        tree = "a" * 40
        def entry(path):
            return {"path": path, "mode": "100644",
                    "type": "blob", "sha": "b" * 40}
        for entries in (
            [entry("safe"), entry("safe")],
            [entry("bad/subpath")],
            [entry("nul" + chr(0) + "path")],
            [dict(entry("safe"), type=["blob"])],
        ):
            with self.subTest(entries=entries), patch.object(
                v3.legacy, "gh_api",
                return_value={"sha": tree, "truncated": False, "tree": entries},
            ):
                with self.assertRaises(v3.Denied):
                    v3.direct_tree_entries("trusted/ci", tree)
        with patch.object(v3.legacy, "gh_api", return_value=None):
            with self.assertRaises(v3.Denied):
                v3.direct_tree_entries("trusted/ci", tree)

    def test_carrier_stored_tree_and_commit_attestation(self):
        before = {"LICENSE": ("100644", "blob", "7" * 40)}
        after = dict(before)
        after[v3.legacy.PREFIX + "/x.ores"] = (
            "100644", "blob", "8" * 40
        )
        after["ci/oreslang-receipt.json"] = (
            "100644", "blob", "9" * 40
        )
        elements = [{
            "path": "x.ores", "mode": "100644",
            "type": "blob", "sha": "8" * 40,
        }]
        def api(route):
            if route.endswith("/git/commits/" + COMMIT):
                return {
                    "sha": COMMIT, "tree": {"sha": ROOT_TREE},
                    "parents": [{"sha": BASE}],
                }
            raise AssertionError("unexpected API " + route)
        with (
            patch.object(v3, "verified_recursive_leaves",
                         side_effect=[before, after]),
            patch.object(v3.legacy, "gh_api", side_effect=api),
        ):
            v3.verify_published_root_and_commit(
                "trusted/ci", "1" * 40, BASE, SOURCE_TREE,
                elements, "9" * 40, ROOT_TREE, COMMIT,
            )
        corrupted = dict(after)
        corrupted["ci/oreslang-receipt.json"] = (
            "100644", "blob", "0" * 40
        )
        with (
            patch.object(v3, "verified_recursive_leaves",
                         side_effect=[before, corrupted]),
            patch.object(v3.legacy, "gh_api", side_effect=api) as gh,
        ):
            with self.assertRaisesRegex(v3.Denied, "root tree differs"):
                v3.verify_published_root_and_commit(
                    "trusted/ci", "1" * 40, BASE, SOURCE_TREE,
                    elements, "9" * 40, ROOT_TREE, COMMIT,
                )
        gh.assert_not_called()
        with (
            patch.object(v3, "verified_recursive_leaves",
                         side_effect=[before, after]),
            patch.object(
                v3.legacy, "gh_api",
                return_value={
                    "sha": COMMIT, "tree": {"sha": ROOT_TREE},
                    "parents": [{"sha": "0" * 40}],
                },
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "parent differs"):
                v3.verify_published_root_and_commit(
                    "trusted/ci", "1" * 40, BASE, SOURCE_TREE,
                    elements, "9" * 40, ROOT_TREE, COMMIT,
                )

    def test_route_paths_fail_closed_on_encoded_and_ambiguous_names(self):
        invalid = [
            "ci/test-plans//bad.json",
            "ci/test-plans/./bad.json",
            "ci/test-plans/%2e%2e/bad.json",
            "ci/test-plans/x.json?ref=other.json",
            "ci/test-plans/x.json#other.json",
            "ci/test-plans/../bad.json",
        ]
        for value in invalid:
            with self.subTest(path=value), self.assertRaises(v3.Denied):
                v3.safe_policy_path(value, "ci/test-plans/", ".json")
        self.assertEqual(
            v3.safe_policy_path("ci/test-plans/one.json", "ci/test-plans/", ".json"),
            "ci/test-plans/one.json",
        )

    def test_v3_rejects_nonfinite_contract_and_plan_json(self):
        for value in (b'{"v":NaN}', b'{"v":1e999}', b'{"v":1,"v":2}'):
            with self.subTest(value=value), self.assertRaises(v3.Denied):
                v3.strict_json_loads(value.decode("ascii"), "untrusted v3 JSON")
        plan = b'{"schema":"ores-private-ci-test-plan-v1","allowed_prs":[1482],"x":1e999}'
        with self.assertRaises(v3.Denied):
            v3.validate_test_plan(plan, 1482)

    def test_review_list_uses_trusted_cli_minimal_env_and_rejects_ambiguity(self):
        result = type("Result", (), {
            "returncode": 0, "stdout": '[{"state":"APPROVED"}]', "stderr": "",
        })()
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "synthetic"},
            ),
            patch.object(v3.subprocess, "run", return_value=result) as run,
        ):
            self.assertEqual(v3.gh_api_list("repos/trusted/ci/pulls/1/reviews"),
                             [{"state": "APPROVED"}])
        self.assertEqual(run.call_args.args[0][0], "/usr/bin/gh")
        self.assertEqual(run.call_args.kwargs["env"], {"GH_TOKEN": "synthetic"})
        invalid = type("Result", (), {
            "returncode": 0, "stdout": '[{"state":NaN}]', "stderr": "",
        })()
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "synthetic"},
            ),
            patch.object(v3.subprocess, "run", return_value=invalid),
        ):
            with self.assertRaises(v3.Denied):
                v3.gh_api_list("repos/trusted/ci/pulls/1/reviews")

    def test_ref_api_requires_sealed_process_and_bounded_text(self):
        response = type("Result", (), {
            "returncode": 0, "stdout": "{}", "stderr": "",
        })()
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "synthetic"},
            ),
            patch.object(v3.subprocess, "run", return_value=response) as run,
        ):
            self.assertIs(
                v3._gh_ref_request("trusted/ci", "ci/authorized-" + COMMIT + "-" + NONCE, "GET"),
                response,
            )
        self.assertEqual(run.call_args.args[0][0], "/usr/bin/gh")
        self.assertEqual(run.call_args.kwargs["env"], {"GH_TOKEN": "synthetic"})
        invalid = type("Result", (), {
            "returncode": 0, "stdout": "012345", "stderr": "",
        })()
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(v3.legacy, "github_api_environment", return_value={}),
            patch.object(v3.legacy, "MAX_GH_API_RESPONSE_CHARS", 4),
            patch.object(v3.subprocess, "run", return_value=invalid),
        ):
            with self.assertRaisesRegex(v3.Denied, "response exceeds size limit"):
                v3._gh_ref_request("trusted/ci", "ci/authorized-" + COMMIT + "-" + NONCE, "GET")

    def test_source_pr_requires_latest_independent_exact_head_review(self):
        approved = {
            "id": 101,
            "user": {"id": v3.REVIEW_AUTHORITY_USER_ID,
                     "login": v3.REVIEW_AUTHORITY_LOGIN},
            "submitted_at": "2026-10-20T19:00:00Z",
            "state": "APPROVED",
            "commit_id": "8" * 40,
        }
        author = {"id": 424242, "login": "PR-author"}
        with patch.object(v3, "gh_api_list", return_value=[approved]) as reviews:
            v3.require_source_pr_independent_review(1482, "8" * 40, author)
        reviews.assert_called_once_with(
            "repos/" + v3.legacy.SOURCE_REPO + "/pulls/1482/reviews?per_page=100"
        )
        for incorrect in (
            dict(approved, commit_id="9" * 40),
            dict(approved, state="CHANGES_REQUESTED"),
            dict(approved, user={"id": 1234, "login": "ORESoftware"}),
            dict(approved, user={"id": v3.REVIEW_AUTHORITY_USER_ID,
                                 "login": "forged-name"}),
        ):
            with self.subTest(incorrect=incorrect), patch.object(
                v3, "gh_api_list", return_value=[incorrect]
            ):
                with self.assertRaises(v3.Denied):
                    v3.require_source_pr_independent_review(1482, "8" * 40, author)

    def test_source_pr_latest_review_revocation_and_pagination_fail_closed(self):
        earlier = {
            "id": 10, "user": {"id": v3.REVIEW_AUTHORITY_USER_ID,
                              "login": v3.REVIEW_AUTHORITY_LOGIN},
            "submitted_at": "2026-10-20T19:00:00Z",
            "state": "APPROVED", "commit_id": "8" * 40,
        }
        revoked = dict(earlier, id=11, submitted_at="2026-10-20T19:01:00Z",
                       state="DISMISSED")
        author = {"id": 424242}
        with patch.object(v3, "gh_api_list", return_value=[earlier, revoked]):
            with self.assertRaisesRegex(v3.Denied, "stale or revoked"):
                v3.require_source_pr_independent_review(1482, "8" * 40, author)
        with patch.object(v3, "gh_api_list", return_value=[earlier] * 100):
            with self.assertRaisesRegex(v3.Denied, "incomplete"):
                v3.require_source_pr_independent_review(1482, "8" * 40, author)
        with patch.object(v3, "gh_api_list") as reviews:
            with self.assertRaisesRegex(v3.Denied, "self-authorize"):
                v3.require_source_pr_independent_review(
                    1482, "8" * 40, {"id": v3.REVIEW_AUTHORITY_USER_ID}
                )
            reviews.assert_not_called()

    def test_export_denied_without_executing_checkout_preflight(self):
        # Neither a policy denial nor an attempt to use a different checkout
        # may invoke executable checkout code as an admission check.
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            with (
                patch.object(
                    v3.legacy, "require_preflight",
                    side_effect=AssertionError("unattested checkout shell executed"),
                ) as shell,
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    side_effect=v3.Denied("destination not independently approved"),
                ) as policy,
                patch.object(
                    v3, "authorized_main_sha",
                    side_effect=AssertionError("authority requested after local denial"),
                ) as authority,
                patch.object(
                    v3.legacy, "collect_source",
                    side_effect=AssertionError("private source read before admission"),
                ) as source,
                patch.object(
                    v3.legacy, "gh_api",
                    side_effect=AssertionError("GitHub API called after local denial"),
                ) as api,
            ):
                with self.assertRaisesRegex(v3.Denied, "not independently approved"):
                    v3.publish(
                        "trusted/ci",
                        root / "nonexistent-source",
                        "4" * 40,
                        1482,
                        "ci/test-plans/java-pr1482.json",
                        root,
                    )
            policy.assert_called_once_with(root, "trusted/ci")
            shell.assert_not_called()
            authority.assert_not_called()
            source.assert_not_called()
            api.assert_not_called()

    def test_review_authority_denial_stops_before_source_or_write(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            with (
                patch.object(
                    v3.legacy, "require_preflight",
                    side_effect=AssertionError("unattested shell executed"),
                ) as shell,
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, 1323598177),
                ) as policy,
                patch.object(
                    v3, "authorized_main_sha",
                    side_effect=v3.Denied("exact independent review absent"),
                ) as authority,
                patch.object(
                    v3.legacy, "collect_source",
                    side_effect=AssertionError("private source read"),
                ) as source,
                patch.object(
                    v3.legacy, "gh_api",
                    side_effect=AssertionError("unapproved GitHub write"),
                ) as api,
            ):
                with self.assertRaisesRegex(v3.Denied, "independent review absent"):
                    v3.publish(
                        "trusted/ci",
                        root / "nonexistent-source",
                        "4" * 40,
                        1482,
                        "ci/test-plans/java-pr1482.json",
                        root,
                    )
            policy.assert_called_once_with(root, "trusted/ci")
            authority.assert_called_once_with("trusted/ci", POLICY, 1323598177)
            shell.assert_not_called()
            source.assert_not_called()
            api.assert_not_called()

    def review_governance_fixture(self, policy=b"trusted/ci 246810\n"):
        codeowners = (
            "\n".join(
                path + " " + v3.legacy.SECURITY_CODEOWNER
                for path in v3.legacy.SECURITY_CODEOWNER_PATHS
            )
            + "\n"
        ).encode("utf-8")
        blobs = {}
        for path in v3.REVIEWED_GOVERNANCE_PATHS:
            if path == v3.legacy.POLICY_PATH:
                raw = policy
            elif path == v3.legacy.CODEOWNERS_PATH:
                raw = codeowners
            else:
                raw = (path + "\n").encode("utf-8")
            blobs[path] = (raw, v3.git_blob_sha(raw))
        tree = {
            path: ("100644", "blob", blob)
            for path, (_raw, blob) in blobs.items()
        }
        return blobs, tree

    def review_authority_api(self, main_sha="2"*40, head_sha="3"*40,
                             author_id=424242, protected=False):
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                }
            if route == (
                    "repos/" + v3.legacy.SOURCE_REPO
                    + "/branches/" + v3.legacy.SOURCE_PROTECTED_BRANCH):
                return {
                    "name": v3.legacy.SOURCE_PROTECTED_BRANCH,
                    "protected": protected,
                    "commit": {"sha": main_sha},
                }
            if route == (
                    "repos/" + v3.legacy.SOURCE_REPO
                    + "/pulls/" + str(v3.REVIEW_AUTHORITY_PR)):
                return {
                    "number": v3.REVIEW_AUTHORITY_PR,
                    "state": "closed",
                    "merged_at": "2026-10-20T18:00:00Z",
                    "user": {"id": author_id, "login": "authority-author"},
                    "head": {
                        "sha": head_sha,
                        "repo": {
                            "full_name": v3.legacy.SOURCE_REPO,
                            "id": v3.SOURCE_REPOSITORY_ID,
                        },
                    },
                    "base": {
                        "ref": v3.legacy.SOURCE_PROTECTED_BRANCH,
                        "repo": {
                            "full_name": v3.legacy.SOURCE_REPO,
                            "id": v3.SOURCE_REPOSITORY_ID,
                        },
                    },
                }
            raise AssertionError((method, route))
        return api

    def approved_authority_review(self, head_sha="3"*40, review_id=7,
                                  state="APPROVED",
                                  submitted="2026-10-20T17:59:00Z"):
        return {
            "id": review_id,
            "state": state,
            "commit_id": head_sha,
            "submitted_at": submitted,
            "user": {
                "id": v3.REVIEW_AUTHORITY_USER_ID,
                "login": v3.REVIEW_AUTHORITY_LOGIN,
            },
        }

    def test_unprotected_main_accepts_exact_independent_review_attestation(self):
        policy = b"trusted/ci 246810\n"
        blobs, tree = self.review_governance_fixture(policy)
        api = self.review_authority_api()
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(
                v3, "gh_api_list",
                return_value=[self.approved_authority_review()],
            ),
            patch.object(v3, "governance_tree_entries", return_value=tree),
            patch.object(
                v3, "remote_file_at_source_main",
                side_effect=lambda path, _ref, _limit: blobs[path],
            ),
        ):
            self.assertEqual(
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                ),
                "2" * 40,
            )

    def test_review_attestation_rejects_author_as_security_reviewer(self):
        policy = b"trusted/ci 246810\n"
        api = self.review_authority_api(
            author_id=v3.REVIEW_AUTHORITY_USER_ID
        )
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "independent merged"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                )

    def test_review_attestation_rejects_latest_revocation(self):
        policy = b"trusted/ci 246810\n"
        api = self.review_authority_api()
        reviews = [
            self.approved_authority_review(review_id=7),
            self.approved_authority_review(
                review_id=8, state="CHANGES_REQUESTED",
                submitted="2026-10-20T17:59:30Z",
            ),
        ]
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(v3, "gh_api_list", return_value=reviews),
        ):
            with self.assertRaisesRegex(v3.Denied, "stale, late, or not approved"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                )

    def test_review_attestation_rejects_destination_repository_id_mismatch(self):
        policy = b"trusted/ci 246810\n"
        blobs, tree = self.review_governance_fixture(policy)
        api = self.review_authority_api()
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(
                v3, "gh_api_list",
                return_value=[self.approved_authority_review()],
            ),
            patch.object(v3, "governance_tree_entries", return_value=tree),
            patch.object(
                v3, "remote_file_at_source_main",
                side_effect=lambda path, _ref, _limit: blobs[path],
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "repository ID"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246811
                )

    def test_review_attestation_rejects_governance_git_entry_drift(self):
        policy = b"trusted/ci 246810\n"
        blobs, reviewed = self.review_governance_fixture(policy)
        current = dict(reviewed)
        target = "scripts/publish-private-ci-source-v3.py"
        current[target] = ("100755", "blob", reviewed[target][2])
        api = self.review_authority_api()
        with (
            patch.object(v3.legacy, "gh_api", side_effect=api),
            patch.object(
                v3, "gh_api_list",
                return_value=[self.approved_authority_review()],
            ),
            patch.object(
                v3, "governance_tree_entries",
                side_effect=[reviewed, current],
            ),
            patch.object(
                v3, "remote_file_at_source_main",
                side_effect=lambda path, _ref, _limit: blobs[path],
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "Git entry drifted"):
                v3.review_attested_main_sha(
                    "trusted/ci", hashlib.sha256(policy).digest(), 246810
                )

    def test_authorized_main_prefers_classic_protection_when_available(self):
        branch = {
            "name": v3.legacy.SOURCE_PROTECTED_BRANCH,
            "protected": True,
            "commit": {"sha": PROTECTED},
        }
        with (
            patch.object(v3.legacy, "gh_api", return_value=branch),
            patch.object(
                v3.legacy, "verify_protected_origin",
                return_value=PROTECTED,
            ) as protected,
            patch.object(v3, "review_attested_main_sha") as fallback,
        ):
            self.assertEqual(
                v3.authorized_main_sha("trusted/ci", POLICY, 246810),
                PROTECTED,
            )
            protected.assert_called_once_with("trusted/ci", POLICY, 246810)
            fallback.assert_not_called()

    def test_maven_plan_requires_offline_mode_and_exact_pom_blob(self):
        base = {
            "schema": "ores-private-ci-test-plan-v1",
            "source_repo": v3.legacy.SOURCE_REPO,
            "allowed_prs": [1482],
            "commands": [{
                "argv": ["mvn", "-B", "test"],
                "cwd": ".",
                "timeout_seconds": 300,
            }],
        }
        raw = (json.dumps(base) + "\n").encode()
        with self.assertRaisesRegex(v3.Denied, "offline"):
            v3.validate_test_plan(raw, 1482)

        base["commands"][0]["argv"] = ["mvn", "-o", "-B", "test"]
        raw = (json.dumps(base) + "\n").encode()
        with self.assertRaisesRegex(v3.Denied, "bind exact pom.xml"):
            v3.validate_test_plan(raw, 1482)

        pom = b"<project/>\n"
        base["required_source_blobs"] = {"pom.xml": v3.git_blob_sha(pom)}
        raw = (json.dumps(base) + "\n").encode()
        meta = v3.validate_test_plan(raw, 1482)
        self.assertTrue(meta["uses_maven"])
        self.assertEqual(
            meta["required_source_blobs"]["pom.xml"],
            v3.git_blob_sha(pom),
        )
        v3.verify_required_source_blobs(
            [("pom.xml", pom, "100644")],
            meta["required_source_blobs"],
        )

    def test_required_source_blob_mismatch_fails_before_upload(self):
        expected = v3.git_blob_sha(b"reviewed pom\n")
        with self.assertRaisesRegex(v3.Denied, "required blob: pom.xml"):
            v3.verify_required_source_blobs(
                [("pom.xml", b"changed pom\n", "100644")],
                {"pom.xml": expected},
            )

    def test_test_plan_rejects_unreviewed_extension_fields(self):
        doc = json.loads(PLAN)
        doc["network"] = "allow"
        raw = (json.dumps(doc) + "\n").encode()
        with self.assertRaisesRegex(v3.Denied, "unexpected fields"):
            v3.validate_test_plan(raw, 1482)

    def test_remote_protected_file_rejects_blob_sha_mismatch(self):
        raw = b"reviewed-bytes\n"
        encoded = base64.b64encode(raw).decode("ascii")

        with patch.object(
            v3.legacy,
            "gh_api",
            return_value={
                "path": "scripts/publish-private-ci-source-v3.py",
                "type": "file",
                "encoding": "base64",
                "content": encoded,
                "sha": "0" * 40,
            },
        ):
            with self.assertRaisesRegex(v3.Denied, "blob digest mismatch"):
                v3.remote_file_at_source_main(
                    "scripts/publish-private-ci-source-v3.py",
                    PROTECTED,
                    4096,
                )

    def test_local_control_plane_read_requires_descriptor_safety_flags(self):
        with (
            patch.object(v3.legacy, "safe_descriptor_flags_available", return_value=False),
            patch.object(v3.os, "open") as opened,
        ):
            with self.assertRaisesRegex(v3.Denied, "cannot safely read"):
                v3.read_local_protected_file(
                    Path("."), "scripts/publish-private-ci-source-v3.py", 4096
                )
            opened.assert_not_called()

    def test_control_plane_rejects_local_runtime_divergence(self):
        with (
            patch.object(v3, "require_control_plane_location"),
            patch.object(
                v3,
                "read_local_protected_file",
                return_value=b"modified-local-code\n",
            ),
            patch.object(
                v3,
                "remote_file_at_source_main",
                return_value=(b"reviewed-protected-code\n", "1" * 40),
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "control plane diverges"):
                v3.checked_control_plane(Path("."), PROTECTED)

    def test_control_plane_requires_executing_modules_under_trusted_root(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            scripts = root / "scripts"
            scripts.mkdir()
            v3_path = scripts / "publish-private-ci-source-v3.py"
            legacy_path = scripts / "publish-private-ci-source.py"
            v3_path.write_text("# reviewed v3\n")
            legacy_path.write_text("# reviewed legacy\n")

            with (
                patch.object(v3, "__file__", str(v3_path)),
                patch.object(v3, "LEGACY_PATH", legacy_path),
            ):
                v3.require_control_plane_location(root)

            outside = root / "modified-copy.py"
            outside.write_text("# modified\n")
            with (
                patch.object(v3, "__file__", str(outside)),
                patch.object(v3, "LEGACY_PATH", legacy_path),
            ):
                with self.assertRaisesRegex(v3.Denied, "outside the trusted control plane"):
                    v3.require_control_plane_location(root)

    def test_parse_contract_requires_exact_pr_and_pinned_fields(self):
        raw = json.dumps(
            {
                "schema": 1,
                "carriers": {
                    "trusted/ci": {
                        "repository_id": 42,
                        "base_sha": BASE,
                        "workflow_path": ".github/workflows/oreslang-exact-tree-carrier.yml",
                        "workflow_blob_sha": WORKFLOW,
                        "test_plans": {
                            "ci/test-plans/java-pr1482.json": {
                                "blob_sha": PLAN_BLOB,
                                "allowed_prs": [1482],
                            }
                        },
                    }
                },
            }
        ).encode()
        got = v3.parse_contract(
            raw, "trusted/ci", 1482, "ci/test-plans/java-pr1482.json"
        )
        self.assertEqual(got["repository_id"], 42)
        self.assertEqual(got["base_sha"], BASE)
        self.assertEqual(got["workflow_blob_sha"], WORKFLOW)
        self.assertEqual(got["test_plan_blob_sha"], PLAN_BLOB)
        with self.assertRaisesRegex(v3.Denied, "not authorized"):
            v3.parse_contract(
                raw, "trusted/ci", 1483, "ci/test-plans/java-pr1482.json"
            )

    def test_contract_rejects_duplicate_json_keys(self):
        raw = (
            '{"schema":1,"schema":1,"carriers":{"trusted/ci":{'
            '"repository_id":42,"base_sha":"' + BASE + '",'
            '"workflow_path":".github/workflows/x.yml",'
            '"workflow_blob_sha":"' + WORKFLOW + '",'
            '"test_plans":{}}}}'
        ).encode()
        with self.assertRaisesRegex(v3.Denied, "duplicate JSON key"):
            v3.parse_contract(raw, "trusted/ci", 1, "ci/test-plans/x.json")

    def test_checked_destination_rejects_source_bearing_reviewed_base(self):
        carrier = contract()
        def api(route, method="GET", payload=None):
            if route == "repos/trusted/ci/git/ref/heads/main":
                return {"object": {"sha": BASE}}
            if route == "repos/trusted/ci/git/commits/" + BASE:
                return {"sha": BASE, "tree": {"sha": ROOT_TREE}}
            if route == "repos/trusted/ci/git/trees/" + ROOT_TREE + "?recursive=1":
                return {
                    "sha": ROOT_TREE,
                    "truncated": False,
                    "tree": [
                        {
                            "path": v3.legacy.PREFIX + "/old.ores",
                            "type": "blob",
                            "mode": "100644",
                            "sha": "2" * 40,
                        }
                    ],
                }
            raise AssertionError(route)
        with patch.object(
            v3.legacy,
            "checked_repo",
            return_value={
                "id": carrier["repository_id"],
                "private": True,
                "visibility": "private",
                "archived": False,
                "disabled": False,
                "fork": False,
                "default_branch": "main",
            },
        ), patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "not source-free"):
                v3.checked_destination("trusted/ci", carrier)

    def test_verify_source_pr_requires_exact_open_head(self):
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                    "archived": False,
                    "disabled": False,
                    "fork": False,
                }
            if route == "repos/" + v3.legacy.SOURCE_REPO + "/pulls/1482":
                return {
                    "number": 1482,
                    "state": "open",
                    "head": {
                        "sha": "9" * 40,
                        "repo": {"full_name": v3.legacy.SOURCE_REPO},
                    },
                    "base": {"ref": "main"},
                }
            raise AssertionError(route)
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "exact open PR head"):
                v3.verify_source_pr(1482, "8" * 40)

    def test_verify_source_pr_rejects_source_repository_id_replacement(self):
        def api(route, method="GET", payload=None):
            if route == "repos/" + v3.legacy.SOURCE_REPO:
                return {
                    "id": v3.SOURCE_REPOSITORY_ID + 1,
                    "full_name": v3.legacy.SOURCE_REPO,
                    "private": True,
                    "visibility": "private",
                    "archived": False,
                    "disabled": False,
                    "fork": False,
                }
            raise AssertionError(route)
        with patch.object(v3.legacy, "gh_api", side_effect=api):
            with self.assertRaisesRegex(v3.Denied, "identity/state"):
                v3.verify_source_pr(1482, "8" * 40)

    def test_authorize_write_rechecks_live_source_and_carrier_governance(self):
        carrier = contract()
        with (
            patch.object(
                v3.legacy,
                "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"]),
            ),
            patch.object(v3, "require_source_authority_epoch") as source_epoch,
            patch.object(
                v3, "require_destination_main_governance",
                side_effect=v3.Denied("carrier protection weakened"),
            ) as carrier_epoch,
        ):
            with self.assertRaisesRegex(v3.Denied, "protection weakened"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier, PROTECTED)
        source_epoch.assert_called_once_with(PROTECTED)
        carrier_epoch.assert_called_once_with("trusted/ci", BASE)

    def test_authorize_write_rejects_reviewed_repository_id_change(self):
        carrier = contract()
        with (
            patch.object(
                v3.legacy,
                "checked_policy_approval",
                return_value=(POLICY, carrier["repository_id"] + 1),
            ),
            patch.object(v3, "require_source_authority_epoch") as source_epoch,
            patch.object(v3, "require_destination_main_governance") as carrier_epoch,
        ):
            with self.assertRaisesRegex(v3.Denied, "repository identity changed"):
                v3.authorize_write(
                    Path("."), "trusted/ci", POLICY, carrier, PROTECTED)
            source_epoch.assert_not_called()
            carrier_epoch.assert_not_called()

    def test_source_authority_epoch_rejects_main_advance(self):
        branch = {
            "name": v3.legacy.SOURCE_PROTECTED_BRANCH,
            "commit": {"sha": "9" * 40},
        }
        with patch.object(v3.legacy, "gh_api", return_value=branch):
            with self.assertRaisesRegex(v3.Denied, "advanced during transfer"):
                v3.require_source_authority_epoch(PROTECTED)

    def test_publish_rejects_contract_repository_id_not_in_reviewed_allowlist(self):
        carrier = contract()
        mismatched = dict(carrier, repository_id=carrier["repository_id"] + 1)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(
                    v3, "authorized_main_sha",
                    return_value=PROTECTED,
                ),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=mismatched),
                patch.object(v3, "verify_source_pr") as verify_source_pr,
            ):
                with self.assertRaisesRegex(
                    v3.Denied, "contract repository ID"
                ):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                    )
                verify_source_pr.assert_not_called()

    def test_publish_binds_receipt_branch_and_authorization_ref(self):
        carrier = contract()
        writes = []
        blob_payloads = []
        commit_payload = {}

        def api(route, method="GET", payload=None):
            writes.append((method, route, payload))
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                blob_payloads.append(data)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                if payload.get("base_tree") is None:
                    return {"sha": SOURCE_TREE}
                return {"sha": ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                commit_payload.update(payload)
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(
                    v3, "authorized_main_sha", return_value=PROTECTED
                ) as verify_origin,
                patch.object(v3, "checked_control_plane") as control_plane,
                patch.object(v3, "checked_contract", return_value=carrier) as checked_contract,
                patch.object(
                    v3,
                    "verify_source_pr",
                    return_value=v3.SOURCE_REPOSITORY_ID,
                ) as verify_source_pr,
                patch.object(
                    v3,
                    "checked_destination",
                    return_value=(
                        {
                            "id": carrier["repository_id"],
                            "private": True,
                            "visibility": "private",
                        },
                        "3" * 40,
                        PLAN,
                    ),
                ) as checked_destination,
                patch.object(
                    v3.legacy,
                    "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(
                    v3.legacy,
                    "verified_exact_source_tree",
                    return_value=SOURCE_TREE,
                ),
                patch.object(v3, "secrets") as entropy,
                patch.object(v3, "authorize_write") as authorize_write,
                patch.object(v3, "verify_carrier_directories") as directory_proof,
                patch.object(v3, "verify_published_root_and_commit") as commit_proof,
                patch.object(
                    v3.legacy, "checked_repo",
                    return_value={"id": carrier["repository_id"]},
                ),
                patch.object(v3, "require_handoff_refs_absent") as absent_guard,
                patch.object(v3, "require_exact_ref") as require_exact_ref,
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                entropy.token_hex.return_value = "0" * 32
                result = v3.publish(
                    "trusted/ci",
                    source,
                    "4" * 40,
                    1482,
                    "ci/test-plans/java-pr1482.json",
                    root,
                    now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                )

        self.assertEqual(verify_origin.call_count, 3)
        self.assertEqual(control_plane.call_count, 3)
        self.assertEqual(checked_contract.call_count, 3)
        self.assertEqual(checked_destination.call_count, 3)
        self.assertEqual(verify_source_pr.call_count, 3)
        self.assertEqual(authorize_write.call_count, 7)
        directory_proof.assert_called_once()
        commit_proof.assert_called_once()
        absent_guard.assert_called_once_with(
            "trusted/ci", "ci/oreslang-" + SOURCE_TREE + "-" + NONCE,
            "ci/authorized-" + COMMIT + "-" + NONCE, carrier["repository_id"]
        )

        self.assertEqual(result["branch"], "ci/oreslang-" + SOURCE_TREE + "-" + NONCE)
        self.assertEqual(
            result["authorization_ref"], "ci/authorized-" + COMMIT + "-" + NONCE
        )
        receipt_bytes = next(
            item for item in blob_payloads if b'"schema":"ores-private-ci-receipt-v3"' in item
        )
        receipt = json.loads(receipt_bytes)
        self.assertEqual(receipt["expires_at"], "2026-10-21T00:00:00Z")
        self.assertEqual(receipt["source"]["pr"], 1482)
        self.assertEqual(
            receipt["source"]["repo_id"], v3.SOURCE_REPOSITORY_ID)
        self.assertEqual(receipt["source"]["head_sha"], "4" * 40)
        self.assertEqual(receipt["source"]["root_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["mirror"]["root_tree_sha"], SOURCE_TREE)
        self.assertEqual(receipt["handoff"]["nonce"], NONCE)
        self.assertEqual(result["handoff_nonce"], NONCE)
        self.assertEqual(receipt["destination"]["repo_id"], 1323598177)
        self.assertEqual(receipt["test_plan"]["path"], carrier["test_plan_path"])
        self.assertEqual(
            receipt["test_plan"]["sha256"], hashlib.sha256(PLAN).hexdigest()
        )
        digest = v3.canonical_digest(receipt)
        self.assertEqual(result["receipt_sha256"], digest)
        self.assertEqual(
            commit_payload["message"],
            (
                "ci: exact private Oreslang tree "
                + "4" * 40
                + " tree "
                + SOURCE_TREE
                + "\ncarrier-base "
                + BASE
                + " tree "
                + "3" * 40
                + "\nconnector-authorized source-repository "
                + v3.legacy.SOURCE_REPO
                + " id "
                + str(v3.SOURCE_REPOSITORY_ID)
                + "\nhandoff-nonce "
                + "0" * 32
            ),
        )
        refs = [
            payload["ref"]
            for method, route, payload in writes
            if method == "POST" and route.endswith("/git/refs")
        ]
        self.assertEqual(
            refs,
            [
                "refs/heads/ci/oreslang-" + SOURCE_TREE + "-" + NONCE,
                "refs/heads/ci/authorized-" + COMMIT + "-" + NONCE,
            ],
        )
        self.assertEqual(verify_source_pr.call_count, 3)
        self.assertEqual(
            [call.args for call in require_exact_ref.call_args_list],
            [
                (
                    "trusted/ci", "ci/oreslang-" + SOURCE_TREE + "-" + NONCE, COMMIT,
                    carrier["repository_id"],
                ),
                (
                    "trusted/ci", "ci/oreslang-" + SOURCE_TREE + "-" + NONCE, COMMIT,
                    carrier["repository_id"],
                ),
                (
                    "trusted/ci", "ci/authorized-" + COMMIT + "-" + NONCE, COMMIT,
                    carrier["repository_id"],
                ),
            ],
        )

    def test_publish_leaves_nonce_payload_inert_if_protection_changes_before_authorization(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(
                    v3,
                    "authorized_main_sha",
                    side_effect=[PROTECTED, PROTECTED, v3.Denied("protection revoked")],
                ),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_carrier_directories"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(
                    v3.legacy, "checked_repo",
                    return_value={"id": carrier["repository_id"]},
                ),
                patch.object(v3, "require_handoff_refs_absent") as absent_guard,
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                with self.assertRaisesRegex(v3.Denied, "protection revoked"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )


    def test_publish_leaves_nonce_payload_inert_if_pr_head_moves_before_authorization(self):
        carrier = contract()
        writes = []

        def api(route, method="GET", payload=None):
            writes.append((method, route, payload))
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                return {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            verify_calls = [v3.SOURCE_REPOSITORY_ID, v3.SOURCE_REPOSITORY_ID, v3.Denied("PR head moved")]
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", side_effect=verify_calls),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_carrier_directories"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(
                    v3.legacy, "checked_repo",
                    return_value={"id": carrier["repository_id"]},
                ),
                patch.object(v3, "require_handoff_refs_absent") as absent_guard,
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                with self.assertRaisesRegex(v3.Denied, "PR head moved"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )

        ref_posts = [
            payload["ref"]
            for method, route, payload in writes
            if method == "POST" and route.endswith("/git/refs")
        ]
        self.assertEqual(len(ref_posts), 1)
        self.assertRegex(
            ref_posts[0],
            r"^refs/heads/ci/oreslang-" + SOURCE_TREE + r"-[0-9a-f]{32}$",
        )

    def test_publish_does_not_destructively_rollback_if_authorization_creation_fails(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                if payload["ref"].startswith("refs/heads/ci/authorized-"):
                    raise v3.Denied("authorization creation failed")
                return {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_carrier_directories"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(
                    v3.legacy, "checked_repo",
                    return_value={"id": carrier["repository_id"]},
                ),
                patch.object(v3, "require_handoff_refs_absent") as absent_guard,
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                with self.assertRaisesRegex(v3.Denied, "authorization creation failed"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )


    def test_publish_does_not_delete_refs_on_keyboard_interrupt(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                if payload["ref"].startswith("refs/heads/ci/authorized-"):
                    raise KeyboardInterrupt("synthetic interrupted write")
                return {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_carrier_directories"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(
                    v3.legacy, "checked_repo",
                    return_value={"id": carrier["repository_id"]},
                ),
                patch.object(v3, "require_handoff_refs_absent") as absent_guard,
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                with self.assertRaisesRegex(KeyboardInterrupt, "synthetic interrupted write"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )


    def test_publish_does_not_delete_refs_on_authorization_confirmation_mismatch(self):
        carrier = contract()

        def api(route, method="GET", payload=None):
            if method == "POST" and route.endswith("/git/blobs"):
                data = base64.b64decode(payload["content"], validate=True)
                return {"sha": v3.git_blob_sha(data)}
            if method == "POST" and route.endswith("/git/trees"):
                return {"sha": SOURCE_TREE if payload.get("base_tree") is None else ROOT_TREE}
            if method == "POST" and route.endswith("/git/commits"):
                return {"sha": COMMIT}
            if method == "POST" and route.endswith("/git/refs"):
                if payload["ref"].startswith("refs/heads/ci/authorized-"):
                    return {"ref": payload["ref"], "object": {"type": "commit", "sha": "9" * 40}}
                return {"ref": payload["ref"], "object": {"type": "commit", "sha": payload["sha"]}}
            raise AssertionError((method, route))

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = root / "snapshot"
            source.mkdir()
            (source / "hello.ores").write_text("synthetic\n")
            with (
                patch.object(v3.legacy, "require_preflight"),
                patch.object(v3.legacy, "checked_policy_fingerprint", return_value=POLICY),
                patch.object(
                    v3.legacy, "checked_policy_approval",
                    return_value=(POLICY, carrier["repository_id"]),
                ),
                patch.object(v3, "authorized_main_sha", return_value=PROTECTED),
                patch.object(v3, "checked_control_plane"),
                patch.object(v3, "checked_contract", return_value=carrier),
                patch.object(v3, "verify_source_pr", return_value=v3.SOURCE_REPOSITORY_ID),
                patch.object(
                    v3, "checked_destination",
                    return_value=(
                        {"id": carrier["repository_id"], "private": True, "visibility": "private"},
                        "3" * 40,
                        PLAN,
                    ),
                ),
                patch.object(
                    v3.legacy, "collect_source",
                    return_value=[("hello.ores", b"synthetic\n", "100644")],
                ),
                patch.object(v3.legacy, "verified_exact_source_tree", return_value=SOURCE_TREE),
                patch.object(v3, "authorize_write"),
                patch.object(v3, "verify_carrier_directories"),
                patch.object(v3, "verify_published_root_and_commit"),
                patch.object(
                    v3.legacy, "checked_repo",
                    return_value={"id": carrier["repository_id"]},
                ),
                patch.object(v3, "require_handoff_refs_absent") as absent_guard,
                patch.object(v3, "require_exact_ref"),
                patch.object(v3.legacy, "gh_api", side_effect=api),
            ):
                with self.assertRaisesRegex(v3.Denied, "dispatcher authorization ref"):
                    v3.publish(
                        "trusted/ci", source, "4" * 40, 1482,
                        "ci/test-plans/java-pr1482.json", root,
                        now=dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc),
                    )


    def test_publisher_failure_path_never_calls_destructive_ref_delete(self):
        source = v3.publish.__code__.co_names
        self.assertNotIn("delete_exact_ref", source)
        self.assertFalse(hasattr(v3, "delete_exact_ref"))

    def test_ref_request_accepts_exact_authorization_ref_and_uses_get_route(self):
        response = type(
            "Result", (),
            {"returncode": 0, "stderr": "", "stdout": "{}"},
        )()
        ref_name = "ci/authorized-" + COMMIT + "-" + NONCE
        with (
            patch.object(v3.legacy, "trusted_github_cli", return_value="/usr/bin/gh"),
            patch.object(
                v3.legacy, "github_api_environment",
                return_value={"GH_TOKEN": "synthetic"},
            ),
            patch.object(v3.subprocess, "run", return_value=response) as run,
        ):
            self.assertIs(
                v3._gh_ref_request("trusted/ci", ref_name, "GET"),
                response,
            )
        argv = run.call_args.args[0]
        self.assertIn(
            "repos/trusted/ci/git/ref/heads/ci%2Fauthorized-" + COMMIT,
            argv,
        )

    def test_ref_request_rejects_delete_method_without_subprocess(self):
        ref_name = "ci/authorized-" + COMMIT + "-" + NONCE
        with patch.object(v3.subprocess, "run") as run:
            with self.assertRaisesRegex(v3.Denied, "lookup method"):
                v3._gh_ref_request("trusted/ci", ref_name, "DELETE")
        run.assert_not_called()

    def test_ref_request_rejects_unrelated_ci_namespace(self):
        with self.assertRaisesRegex(v3.Denied, "target is invalid"):
            v3._gh_ref_request("trusted/ci", "ci/not-an-oreslang-handoff", "GET")

    def test_ref_absence_requires_exact_leading_gh_status(self):
        variants = (
            ("gh: Not Found (HTTP 404)", True),
            ("HTTP 404: Not Found", False),
            ("gh: Unauthorized (HTTP 401)\nHTTP 404", False),
            ("gh: Unauthorized (HTTP 401) and (HTTP 404)", False),
            ("gh: Forbidden (HTTP 403)", False),
            ("gh: Not Found (HTTP 404)\rspoof", False),
            ("gh: Not Found (HTTP 404) extra", False),
        )
        for diagnostic, accepted in variants:
            with self.subTest(diagnostic=diagnostic):
                response = type("Result", (), {
                    "returncode": 1, "stderr": diagnostic, "stdout": "",
                })()
                self.assertEqual(
                    v3.is_github_ref_absent_response(response), accepted
                )
        success = type("Result", (), {
            "returncode": 0, "stderr": "gh: Not Found (HTTP 404)", "stdout": "",
        })()
        self.assertFalse(v3.is_github_ref_absent_response(success))

    def test_live_ref_404_requires_carrier_identity_to_remain_visible(self):
        response = type(
            "Result", (),
            {"returncode": 1, "stderr": "gh: Not Found (HTTP 404)", "stdout": ""},
        )()
        with (
            patch.object(v3, "_gh_ref_request", return_value=response),
            patch.object(
                v3.legacy,
                "checked_repo",
                side_effect=v3.Denied("carrier no longer visible"),
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "no longer visible"):
                v3.live_ref_sha(
                    "trusted/ci", "ci/authorized-" + COMMIT + "-" + NONCE, 1323598177
                )

    def test_live_ref_rejects_carrier_repository_id_replacement(self):
        response = type(
            "Result", (),
            {"returncode": 0, "stderr": "", "stdout": "{}"},
        )()
        with (
            patch.object(v3, "_gh_ref_request", return_value=response),
            patch.object(
                v3.legacy,
                "checked_repo",
                return_value={
                    "id": 999,
                    "full_name": "trusted/ci",
                    "private": True,
                    "visibility": "private",
                },
            ),
        ):
            with self.assertRaisesRegex(v3.Denied, "repository identity changed"):
                v3.live_ref_sha(
                    "trusted/ci", "ci/authorized-" + COMMIT + "-" + NONCE, 1323598177
                )

    def test_live_ref_404_is_absent_only_after_private_carrier_recheck(self):
        response = type(
            "Result", (),
            {"returncode": 1, "stderr": "gh: Not Found (HTTP 404)", "stdout": ""},
        )()
        with (
            patch.object(v3, "_gh_ref_request", return_value=response),
            patch.object(
                v3.legacy,
                "checked_repo",
                return_value={
                    "id": 1323598177,
                    "full_name": "trusted/ci",
                    "private": True,
                    "visibility": "private",
                },
            ) as checked_repo,
        ):
            self.assertIsNone(
                v3.live_ref_sha(
                    "trusted/ci", "ci/authorized-" + COMMIT + "-" + NONCE, 1323598177
                )
            )
        checked_repo.assert_called_once_with("trusted/ci", 1323598177)

    def test_existing_handoff_refs_are_never_adopted_or_deleted(self):
        branches = ("ci/oreslang-" + SOURCE_TREE + "-" + NONCE, "ci/authorized-" + COMMIT + "-" + NONCE)
        for result_sequence in ((COMMIT,), (None, COMMIT)):
            with (
                patch.object(v3, "live_ref_sha", side_effect=result_sequence) as read,
                patch.object(v3, "create_handoff_ref") as create,
            ):
                with self.assertRaisesRegex(v3.Denied, "already exists"):
                    v3.require_handoff_refs_absent(
                        "trusted/ci", branches[0], branches[1], 1323598177
                    )
                create.assert_not_called()
                self.assertEqual(read.call_count, len(result_sequence))
        with patch.object(v3, "live_ref_sha", side_effect=[None, None]) as read:
            v3.require_handoff_refs_absent(
                "trusted/ci", branches[0], branches[1], 1323598177
            )
        self.assertEqual(
            [call.args[1] for call in read.call_args_list], list(branches)
        )

    def test_handoff_entropy_is_128_bit_and_fails_closed(self):
        with patch.object(v3.secrets, "token_hex", return_value="a" * 32) as entropy:
            self.assertEqual(v3.fresh_handoff_nonce(), "a" * 32)
        entropy.assert_called_once_with(16)
        for unsafe in (None, "", "g" * 32, "a" * 31, "A" * 32):
            with self.subTest(nonce=unsafe), patch.object(
                v3.secrets, "token_hex", return_value=unsafe
            ):
                with self.assertRaisesRegex(v3.Denied, "entropy invalid"):
                    v3.fresh_handoff_nonce()
        with patch.object(v3.secrets, "token_hex", side_effect=OSError("no entropy")):
            with self.assertRaisesRegex(v3.Denied, "entropy unavailable"):
                v3.fresh_handoff_nonce()

    def test_create_handoff_ref_requires_live_numeric_identity(self):
        ref = "ci/oreslang-" + SOURCE_TREE + "-" + NONCE
        with (
            patch.object(
                v3.legacy, "checked_repo",
                side_effect=v3.Denied("replaced repository"),
            ),
            patch.object(v3.legacy, "gh_api") as api,
        ):
            with self.assertRaisesRegex(v3.Denied, "replaced repository"):
                v3.create_handoff_ref(
                    "trusted/ci", ref, COMMIT, 1323598177
                )
        api.assert_not_called()

    def test_create_handoff_ref_requires_exact_post_response(self):
        ref = "ci/oreslang-" + SOURCE_TREE + "-" + NONCE
        exact = {"ref": "refs/heads/" + ref, "object": {"type": "commit", "sha": COMMIT}}
        poisoned = (
            {},
            {"ref": "refs/heads/" + ref, "object": {"sha": COMMIT}},
            {"ref": "refs/heads/" + ref, "object": {"type": "tag", "sha": COMMIT}},
            {"ref": "refs/heads/" + ref, "object": {"type": "commit", "sha": "9" * 40}},
            {"ref": "refs/heads/ci/oreslang-other",
             "object": {"sha": COMMIT}},
            {"ref": "refs/heads/" + ref},
        )
        with patch.object(
            v3.legacy, "checked_repo", return_value={"id": 1323598177}
        ):
            with patch.object(v3.legacy, "gh_api", return_value=exact) as api:
                self.assertEqual(v3.create_handoff_ref(
                    "trusted/ci", ref, COMMIT, 1323598177
                ), exact)
            self.assertEqual(api.call_args.kwargs["method"], "POST")
            self.assertEqual(api.call_args.kwargs["payload"], {
                "ref": "refs/heads/" + ref, "sha": COMMIT,
            })
            for response in poisoned:
                with self.subTest(response=response), patch.object(
                    v3.legacy, "gh_api", return_value=response
                ):
                    with self.assertRaisesRegex(v3.Denied, "did not confirm"):
                        v3.create_handoff_ref(
                            "trusted/ci", ref, COMMIT, 1323598177
                        )
            with patch.object(
                v3.legacy, "gh_api", side_effect=v3.Denied("remote reply lost")
            ):
                with self.assertRaisesRegex(v3.Denied, "remote reply lost"):
                    v3.create_handoff_ref(
                        "trusted/ci", ref, COMMIT, 1323598177
                    )

    def test_manifest_matches_carrier_row_shape(self):
        rows, digest = v3.manifest(
            [
                ("b", b"two", "100755"),
                ("a", b"one", "100644"),
            ]
        )
        self.assertEqual([row[0] for row in rows], ["a", "b"])
        self.assertEqual(
            digest, hashlib.sha256(v3.canonical_bytes(rows)).hexdigest()
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)

