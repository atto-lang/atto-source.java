#!/usr/bin/env python3
import json
from pathlib import Path

PACKET = Path("docs/security/private-ci-java2222-honeypot-candidate-20261029.json")
ALLOWLIST = Path(".github/private-ci-approved-repositories.txt")
CONTRACTS = Path(".github/private-ci-carrier-contracts.json")
CARRIER = "honeypot-r-us-test/hnpt-lib-core"

def fail(message):
    raise SystemExit("JAVA2222_CARRIER_CANDIDATE_DENIED: " + message)

def main():
    doc = json.loads(PACKET.read_text(encoding="utf-8"))
    expected_keys = {
        "schema","source_repository","source_pr","source_head_sha","source_main_sha",
        "source_main_protected","carrier_repository","carrier_repository_id",
        "carrier_review_pr","carrier_review_head_sha","carrier_workflow_blob_sha",
        "carrier_manifest_blob_sha","carrier_test_plan_blob_sha",
        "carrier_verifier_blob_sha","funded_run_id","funded_job_id","funded_result",
        "blocking_incidents","reviewed_destination_allowlist_contains_carrier",
        "carrier_contract_contains_carrier","source_exact_head_approved",
        "transfer_authorized","status","purpose"
    }
    if set(doc) != expected_keys:
        fail("packet field set changed")
    if doc["schema"] != "ores-private-ci-carrier-candidate-review-v1":
        fail("schema")
    if doc["source_repository"] != "ores-truffle-oreslang/oreslang-source.java":
        fail("source repo")
    if doc["source_pr"] != 2222:
        fail("source PR")
    if doc["carrier_repository"] != CARRIER or doc["carrier_repository_id"] != 1343209023:
        fail("carrier identity")
    if doc["carrier_review_pr"] != 24:
        fail("carrier review PR")
    if doc["carrier_review_head_sha"] != "8607b3f79284e2cea29bbfe756b0400b9d498d86":
        fail("carrier review head")
    if doc["funded_result"] != "success":
        fail("funded result")
    if doc["blocking_incidents"] != [22,77,127,171,172]:
        fail("blocking incident set")
    for key in (
        "source_main_protected",
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
