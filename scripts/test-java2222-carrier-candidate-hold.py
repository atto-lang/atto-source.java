#!/usr/bin/env python3
import json
from pathlib import Path

PACKET = Path("docs/security/private-ci-java2222-honeypot-candidate-20261029.json")
ALLOWLIST = Path(".github/private-ci-approved-repositories.txt")
CONTRACTS = Path(".github/private-ci-carrier-contracts.json")
CARRIER = "honeypot-r-us-test/hnpt-lib-core"

EXPECTED_BLOBS = {
    "carrier_review_workflow_blob_sha": "805f8a1919afc8e5949bbabf3447ed1d7ced8d46",
    "carrier_execution_workflow_blob_sha": "db75eae33079bf4c66b2817d85d01fea66ab6c20",
    "carrier_codeowners_blob_sha": "0110a557c162204a399ebf1e628e50c9ed991439",
    "carrier_manifest_blob_sha": "d4801600385462c4a53d0e9989623457b69ccfea",
    "carrier_test_plan_blob_sha": "0894f7241eb0b510f335351b8ad33378946f9503",
    "carrier_candidate_verifier_blob_sha": "a26ca47b00280e58114315a9b1ffbc5e5b291e18",
    "carrier_source_verifier_blob_sha": "4eccdaf0235d7bb4d05461341bbb4ded09249fde",
    "carrier_runner_blob_sha": "a77ea1e03b36dd2d3a34b7904ab7f1196bed21bf",
    "carrier_adversarial_test_blob_sha": "ac6d6105cba6eb22726780876d1e830aacf78a59",
}

def fail(message):
    raise SystemExit("JAVA2222_CARRIER_CANDIDATE_DENIED: " + message)

def main():
    doc = json.loads(PACKET.read_text(encoding="utf-8"))
    expected_keys = {
        "schema","source_repository","source_pr","source_head_sha","source_main_sha",
        "source_main_protected","carrier_repository","carrier_repository_id",
        "carrier_review_pr","carrier_review_head_sha","carrier_main_protected",
        "carrier_execution_requires_protected_main",
        "carrier_execution_requires_direct_main_parent",
        "carrier_runner_local_scrub","carrier_provider_purge_proven",
        *EXPECTED_BLOBS.keys(),
        "carrier_current_head_source_free_run_id",
        "carrier_current_head_source_free_job_id",
        "carrier_current_head_source_free_result",
        "carrier_current_head_source_free_executed",
        "carrier_last_executed_head_sha",
        "carrier_last_executed_run_id",
        "carrier_last_executed_job_id",
        "carrier_last_executed_result",
        "runner_capacity_canary_repository",
        "runner_capacity_canary_repository_id",
        "runner_capacity_canary_quarantined",
        "runner_capacity_canary_run_id",
        "runner_capacity_canary_job_id",
        "runner_capacity_canary_result",
        "carrier_exact_head_approved","blocking_incidents","incidents_resolved",
        "reviewed_destination_allowlist_contains_carrier",
        "carrier_contract_contains_carrier","source_exact_head_approved",
        "transfer_authorized","status","purpose"
    }
    if set(doc) != expected_keys:
        fail("packet field set changed")
    if doc["schema"] != "ores-private-ci-carrier-candidate-review-v4":
        fail("schema")
    if doc["source_repository"] != "ores-truffle-oreslang/oreslang-source.java":
        fail("source repo")
    if doc["source_pr"] != 2222:
        fail("source PR")
    if doc["source_head_sha"] != "f41c6a109ee1899f1f6847e865428ac697da20e6":
        fail("source head")
    if doc["source_main_sha"] != "241bd67c39443cc2ef18df7e4ed456454af378c7":
        fail("source main")
    if doc["carrier_repository"] != CARRIER or doc["carrier_repository_id"] != 1343209023:
        fail("carrier identity")
    if doc["carrier_review_pr"] != 24:
        fail("carrier review PR")
    if doc["carrier_review_head_sha"] != "4e2e6496225e706f6a8455f09b04009eb8e5dc18":
        fail("carrier review head")
    for key, expected in EXPECTED_BLOBS.items():
        if doc.get(key) != expected:
            fail(key)
    if doc["carrier_current_head_source_free_run_id"] != 41008722433:
        fail("current-head run identity")
    if doc["carrier_current_head_source_free_job_id"] != 123909499411:
        fail("current-head job identity")
    if doc["carrier_current_head_source_free_result"] != "zero-step-failure":
        fail("current-head source-free classification")
    if doc["carrier_current_head_source_free_executed"] is not False:
        fail("current-head source-free execution must remain false")
    if doc["carrier_last_executed_head_sha"] != "9bf97e9238558662f0857c773777e622ca04ac39":
        fail("last executed carrier head")
    if doc["carrier_last_executed_run_id"] != 41004184603 or doc["carrier_last_executed_job_id"] != 123895209748:
        fail("last executed carrier receipt identity")
    if doc["carrier_last_executed_result"] != "success":
        fail("last executed carrier result")
    if doc["runner_capacity_canary_repository"] != "agent-pontifex-test/agent-pontifex-e2e-tests":
        fail("runner capacity canary repository")
    if doc["runner_capacity_canary_repository_id"] != 1370782550:
        fail("runner capacity canary repository id")
    if doc["runner_capacity_canary_quarantined"] is not True:
        fail("runner capacity canary must remain quarantined")
    if doc["runner_capacity_canary_run_id"] != 41009207044 or doc["runner_capacity_canary_job_id"] != 123911021605:
        fail("runner capacity canary identity")
    if doc["runner_capacity_canary_result"] != "success":
        fail("runner capacity canary result")
    if doc["carrier_execution_requires_protected_main"] is not True:
        fail("carrier execution must require protected main")
    if doc["carrier_execution_requires_direct_main_parent"] is not True:
        fail("carrier execution must require direct main parent")
    if doc["carrier_runner_local_scrub"] is not True:
        fail("carrier runner-local scrub must remain true")
    if doc["carrier_provider_purge_proven"] is not False:
        fail("provider purge must remain unproven")
    if doc["blocking_incidents"] != [22,77,127,171,172,198,200]:
        fail("blocking incident set")
    for key in (
        "source_main_protected",
        "carrier_main_protected",
        "carrier_exact_head_approved",
        "incidents_resolved",
        "reviewed_destination_allowlist_contains_carrier",
        "carrier_contract_contains_carrier",
        "source_exact_head_approved",
        "transfer_authorized",
    ):
        if doc[key] is not False:
            fail(key + " must remain false")
    if doc["status"] != "hold":
        fail("status must remain hold")

    approved = []
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line=line.strip()
        if not line or line.startswith("#"):
            continue
        approved.append(line.split()[0].casefold())
    if CARRIER.casefold() in approved:
        fail("candidate unexpectedly present in reviewed allowlist")

    contracts = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    carriers = contracts.get("carriers", {})
    if CARRIER in carriers:
        fail("candidate unexpectedly present in carrier contract")

    print("JAVA2222_CARRIER_CANDIDATE_HOLD_PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
