# Source-owner decision request: Atto actor stack PR #2836 private CI

**Status: REQUEST ONLY — no approval, allowlist mutation, mirror, token, or source transfer.**

The actor-receiver stack is open as an immutable main-targeted integration
review candidate [source PR #2836](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2836).
It is deliberately a **draft**, not yet authorized for the v3 publisher.

## Exact candidate evidence (recheck all immediately before any approval)

| Property | Candidate value |
| --- | --- |
| Source repository | `ores-truffle-oreslang/oreslang-source.java` |
| Source actor stack | #2756 → #2776 → #2786 → #2787 → #2788 → #2789 → #2819 → #2828 |
| Main-base review source PR | #2836 (draft; separate from stacked PR #2828) |
| Source commit | `71e41a7b21398de8f499b518ca4407a3124e86ba` |
| Source root tree | `1f002cb698f3f4f2ebd0d1b5e876593d7a139319` |
| Source `main` at preparation | `6d8eb70048eafc9a84c5aebe9c1bb06104f16e76` |
| Candidate destination | `opto-sync-test/go-java-validation` (private, repo ID `1323598177`) |
| Reviewed-candidate carrier PR | [#38](https://github.com/opto-sync-test/go-java-validation/pull/38) (draft) |
| Candidate carrier base `main` | `db642bca2ed19ffa4262e85f95d8f296020b978c` (NOT a final reviewed merge SHA) |
| Carrier workflow | `.github/workflows/oreslang-exact-tree-carrier.yml` |
| Candidate workflow blob | `8cf041f09d64c2ce6af8e2405a15d6493c382e06` |
| Candidate trusted plan | `ci/test-plans/java-pr2836-actor-stack.json` |
| Candidate plan blob | `7d6ca670536725e2f77717c63ea24227d51d29ac` |
| Pinned source POM blob | `2e566927d4b8e07884ce53b0c463d440201fe54c` |

## Critical source and carrier security gates

1. **Source `main` MUST be effectively protected.** Its GitHub branch
   metadata currently reads `protected=false`. Source private-repository
   rulesets returned a plan-upgrade error. The v3
   `authorized_main_sha` function explicitly rejects unprotected source
   `main` before any export, regardless of user or connector permissions.
2. Independently review and approve carrier PR #38 as `ORESoftware`. The
   carrier's `main` was also unprotected. The v3 publisher accepts an
   unprotected carrier only with an independently approved, merged carrier
   PR attestation where the reviewed exact head **tree equals final main
   tree** and `review_pr` identifies the attested PR. Direct merge without
   exact review **cannot** replace this requirement.
3. After carrier PR #38 is independently reviewed and merged, resolve its
   **final main commit + tree**. Do not use the proposal's old
   `db642...` carrier base SHA as though it included the new plan.
4. Stage a **separate independently reviewed** source governance PR against
   newly protected `main`:
   - Add `opto-sync-test/go-java-validation 1323598177` to
     `.github/private-ci-approved-repositories.txt` only if the actual
     protected-main policy and its policy-regression tests accept it.
   - Add the exact candidate carrier to
     `.github/private-ci-carrier-contracts.json` with:
     `repository_id=1323598177`,
     `base_sha=<final independently reviewed carrier main SHA>`,
     `workflow_path=.github/workflows/oreslang-exact-tree-carrier.yml`,
     `workflow_blob_sha=8cf041f09d64c2ce6af8e2405a15d6493c382e06`,
     `review_pr=38`, and only
     `test_plans["ci/test-plans/java-pr2836-actor-stack.json"]` with
     `blob_sha=7d6ca670536725e2f77717c63ea24227d51d29ac`
     and `allowed_prs=[2836]`.
   - Require an actual `ORESoftware` approval of the exact protected
     governance change; never mistake a requested review for APPROVED.
5. A governance merge advances source `main`: PR #2836 must then be
   rebased on that exact protected source main (and any code conflict
   resolved) and **reviewed again at its new immutable head**. The current
   source commit and tree will no longer be eligible after that merge.
   Keep the approved POM/binder/test blob pins synchronized if code changes.
6. Source PR #2836 must become **non-draft**, target protected `main`,
   have `behind_by=0`, and obtain fresh independent `ORESoftware`
   APPROVED review matching its precise `head.sha`.
7. The v3 test plan requires `mvn -o -C` and an exact `pom.xml` blob
   pin. Those are present in the candidate. **Offline Maven artifact
   prefetch/cache is not yet proven**, so security owners must inspect the
   source-free, review-controlled artifact acquisition strategy and forbid
   unconstrained network or untrusted Maven execution. The test plan must
   fail closed if any requested artifact cannot be obtained safely.
8. After **all** gates pass, run the checked one-use v3 publisher against
   exactly the approved commit/tree and carrier. Never grant source export
   merely because GitHub Actions minutes exist in another organization.

## Test evidence so far

- [BeamScale source-free JDK 25 smoke #42950858579](https://github.com/beamscale/bmscl-compiler/actions/runs/42950858579):
  **PASS**, seven executed steps; does **not** contain Atto source.
- [Carrier PR #38 source-free verifier run #42953161382](https://github.com/opto-sync-test/go-java-validation/actions/runs/42953161382):
  **PASS** on source-free verifier regressions; private-source job skipped.
  Revalidate against the current PR head before considering this final.
- [Source-local actor exact-head #42951101553](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/42951101553):
  **FAILURE before runner admission**, zero executed steps; not a failed
  compiler test verdict.
- The real Java actor binder, parser, typechecker, ownership and guest
  tests have **NOT EXECUTED** on the proposed integrated source tree.
- LLVM, .NET, Wasm and Forge actor receiver parity is **NOT VERIFIED**.

## Approval decision / manual actions

The GitHub connector in this session has read access to branch metadata,
but no supported branch-protection mutation. An authorized repository
administrator must enable and verify actual source-main enforcement
(with appropriate GitHub plan/organization changes if required). Independent
security-owner review must then confirm the carrier trust boundary and
source export scope. No source mirror may occur before those changes.
