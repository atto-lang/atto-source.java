# Private-CI schema-3 payload contract

This document defines the format bridge between the current Java source tree and the hardened review-pinned private-CI carrier. It is intentionally format-only: it does not authorize a destination, enable source export, relax source branch protection, or create carrier refs.

## Canonical payload

The carrier payload contains the exact private source subtree at `private-oreslang-source/` and one root provenance file at `.private-ci-provenance.json`. The provenance file is UTF-8 JSON with the exact schema-3 key set implemented by `scripts/private_ci_v3_contract.py`, keys sorted lexicographically, compact separators, no BOM, no duplicate keys, no floating-point identity values, and exactly one trailing LF.

The source commit message may additionally carry audit-only trailers. These include the existing `Oreslang-Source-Repo`, `Oreslang-Source-Commit`, `Oreslang-Source-Tree`, and `Oreslang-Destination-Prefix` compatibility fields plus schema, repository-ID, carrier-base, and provenance-blob bindings. Trailers are self-asserted audit metadata; they are not authorization or a cryptographic source-organization signature.

## Authorization boundary

`private_ci_v3_contract.py` validates shape and canonical bytes only. A production publisher must separately prove all live authorization represented by the receipt: repository identity/privacy, protected source policy, exact reviewed PR heads and approvals, reviewer identity and permissions, carrier workflow identity, immutable source commit/tree, snapshot approval, incident-resume authority, and per-write revocation checks. The hardened carrier must independently verify the same authority before source checkout.

Current source `main` remains fail-closed for private export while its required protected-origin gate is unsatisfied. Adding this contract does not change that behavior and does not activate any destination allowlist.

## Verification

Run the independent source-free contract regression:

    python3 -m unittest tests/test_private_ci_v3_contract.py

The test suite checks the exact schema-3 field set, canonical JSON round-trip, duplicate-key and noncanonical rejection, strict integer identity fields, binding drift, carrier-base/review-head equality, write-or-stronger reviewer permissions, malformed/missing/extra fields, compatibility trailers, and provenance digest changes when authority identities change.
