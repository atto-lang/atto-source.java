# AttoLang durable M0 private carrier authorization request — 2026-11-03

**Status: REVIEW REQUEST ONLY — EXPORT NOT AUTHORIZED.** This document is not a destination allowlist, not a V3 carrier contract, and not an approval. It is intended for independent review under [security issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962) and [compiler issue #2475](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2475).

## Scope, identities and exact source candidate

| Property | Proposed value | Verified source |
| --- | --- | --- |
| Source private repository | `ores-truffle-oreslang/oreslang-source.java` | Live GitHub connector metadata |
| Source immutable numeric ID | `1400792274` | Live GitHub connector metadata |
| Candidate carrier private repository | `scintilla-run/scintilla-e2e` | Live GitHub connector metadata |
| Candidate carrier immutable numeric ID | `1346629495` | Live GitHub connector metadata |
| Source PR | [#2480](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2480), stacked on draft #2474 | Latest PR metadata |
| Carrier source-free proof | [run #41588920031](https://github.com/scintilla-run/scintilla-e2e/actions/runs/41588920031), six executed steps, success | Source-free runner evidence only |

The exact source commit/tree **must be refreshed immediately before any authorization**. A newer branch push invalidates any previous head or tree pin.

## Required independent gates before the first private source-bearing API write

1. **Effective source-main protection:** GitHub reported `main.protected=false`. V3 has no review-only fallback for the source origin. Code owners cannot waive this requirement through a PR comment. The source repository plan/administrator must enable the effective required PR approval, stale-dismissal, strict required checks, admin enforcement, no bypass, no force pushes and no deletion; then obtain authenticated live proof.
2. **V3-eligible source PR:** #2480 currently targets another feature branch, **not `main`**, while the V3 publisher demands an open exact-head source PR against `main`. Independently reconcile/merge the prerequisite #2474 through reviewed governance and then rebase/retarget #2480 appropriately; do **not** retarget blindly or merge without evidence.
3. **Independent exact-head review:** Security owner `ORESoftware` (immutable reviewer ID `11139560`) must approve the exact current source PR head. A review of a preceding commit or solely the transfer plan is insufficient.
4. **Approved numeric destination:** A separate protected-main governance PR must approve the exact destination `scintilla-run/scintilla-e2e 1346629495` in `.github/private-ci-approved-repositories.txt`, with independent review. There is currently no approval row.
5. **Reviewed carrier contract:** Pin exact carrier `main` commit, complete reviewed `main` root Git-tree SHA, carrier workflow path/blob SHA, signed/approved `.github/CODEOWNERS`, exact test-plan path/blob SHA, and an `allowed_prs` set including the properly retargeted source PR. Source policy `.github/private-ci-carrier-contracts.json` currently has **no** `scintilla-run/scintilla-e2e` contract.
6. **Carrier provenance:** Carrier `main.protected=false`. V3 permits its separate exact-tree, reviewed-PR fallback only with independent approved merged PR and matching complete main root tree; a successful source-free workflow or branch-only workflow is not sufficient.
7. **Least privilege and isolation:** No untrusted fork/PR triggers, no arbitrary executable PR-supplied carrier workflows, no source token or runtime secret leakage, dependency preparation before source execution, constrained offline Maven execution, no access to unrelated carrier Git blobs and a source-free cleanup job separated from source execution.
8. **Approved publisher only:** Run the reviewed V3 publisher from protected source `main`, rather than a new connector-based ad hoc blob/tree copier or an arbitrary branch checkout. Prove private/private numeric IDs, unchanged approvals and source tree before **each** write. Audit source-bearing dangling-object retention and cleanup; deleting refs is not erasure.

## Proposed test acceptance — only after gates 1–8

- Bound exact source HEAD/root tree; verify every nested Git blob, mode and complete source subtree SHA after the transfer.
- Check source compiler-only M0: 35 authored JUnit tests across the four named suites; no skip/failure/error; canonical manifest SHA matches emitted UTF-8 bytes.
- Run full Maven `verify` in a separate dependent job; publish source SHA/tree, runner steps, suite counts and signed or hashed receipts. Host compiler tests do **not** prove actual durable replay/guest execution.
- Keep #2474 and #2480 draft until code and security review, source-backed executed tests and full Maven results exist. No durable workflow dispatcher/runtime is enabled by this candidate.

## Current disposition

**HOLD.** Neither the source-main protection gate, the carrier-main protection/review attestation, source PR base, the exact approved destination, nor an independent PR review is currently satisfied. No private source was mirrored to the carrier under this request.
