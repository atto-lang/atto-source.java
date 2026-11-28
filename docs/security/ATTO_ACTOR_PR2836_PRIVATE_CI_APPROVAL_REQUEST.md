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
| Candidate workflow blob | `8f252830f6d62647c91e4a00182480808f5ac7a2` |
| Candidate carrier CODEOWNERS blob | `0110a557c162204a399ebf1e628e50c9ed991439` |
| Current carrier PR #38 head (not reviewed/merged) | `b397077b56b66d1a2f345ef2a8a0cc110818a85d` |
| Current carrier PR #38 tree (not reviewed/merged) | `fc3524c1a76099de481222265716ed22453a9296` |
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
     `workflow_blob_sha=8f252830f6d62647c91e4a00182480808f5ac7a2`,
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

## Source-free carrier regression checkpoint — 2026-11-11

[Carrier run #42957248844](https://github.com/opto-sync-test/go-java-validation/actions/runs/42957248844) succeeded at draft PR #38 head `b397077b56b66d1a2f345ef2a8a0cc110818a85d`: **36 source-free tests passed** (6 exact-tree verifier tests, 6 trusted-plan runner tests, 11 report-verifier tests and 13 PR2836 plan-integrity tests), with nine recorded `verifier-tests` steps. The `verify-carrier` source-bearing job was skipped. The workflow checked exact CODEOWNERS bytes; no private source was copied.

This is an auditable **candidate**, not approved carrier main. Exact SHA/tree, carrier workflow blob, plan blob, and CODEOWNERS blob must be re-fetched after independent security review and merge. Source `main` protection and authorized private-source execution remain blocked. Any plan/code change invalidates older review/receipt expectations.

## Synthetic offline Maven proof — 2026-11-11

[Carrier PR #38 run #42958149378](https://github.com/opto-sync-test/go-java-validation/actions/runs/42958149378): **SUCCESS**. The `verifier-tests` job ran 36 source-free tests (6 provenance + 6 trusted-plan + 11 Surefire XML + 13 immutable plan scope). The additional `source-free-maven-offline` job installed pinned Java 25, resolved dependencies for a **carrier-owned synthetic Maven/JUnit project only**, ran a network-enabled synthetic test to populate dynamically loaded Surefire provider dependencies, deleted the synthetic `target` output, then recompiled and executed one actual JUnit test with Maven `-o -C` and nonempty successful Surefire evidence. Job `verify-carrier` was skipped; **no private source transferred**.

**Scope of evidence:** Maven `dependency:go-offline` alone was insufficient: the first attempt missed `surefire-junit-platform:3.5.2`, and an explicit provider download missed `junit-platform-commons:1.9.3`. The final source-free proof warms the synthetic provider cache before offline rerun, but does **not** prove that all required AttoLang POM dependencies are cached, that process network egress is disabled, or that untrusted source may run safely. A separate reviewed, carrier-owned prefetch manifest and isolation gate are still needed before private compiler execution.

Current **candidate** carrier PR head `b397077b56b66d1a2f345ef2a8a0cc110818a85d`; candidate root tree `fc3524c1a76099de481222265716ed22453a9296`; workflow blob `8f252830f6d62647c91e4a00182480808f5ac7a2`; test-plan blob remains `7d6ca670536725e2f77717c63ea24227d51d29ac`. These identifiers require independent security review/merge attestation and must not be treated as a protected carrier base.

## Source-free Docker isolation and immutable Maven cache checkpoint

[Carrier run #42967009809](https://github.com/opto-sync-test/go-java-validation/actions/runs/42967009809) — **SUCCESS** on current PR #38 head `b397077b56b66d1a2f345ef2a8a0cc110818a85d`: 43 source-free tests passed (6 provenance + 6 plan runner + 11 Surefire verifier + 13 test-plan scope + 7 actor execution guard), and one synthetic Java 25 JUnit test compiled and executed in Docker's network-disabled container. Source-bearing `verify-carrier` job was **SKIPPED**; no Atto private source copied or executed.

The source-free execution proof used OCI image `maven@sha256:d4bb5796a17ab2422f609b764ba16ddcbd2e6ba5b639168eb9e99e6de8f1f431` with `--network none`, read-only source/root, no-new-privileges, dropped capabilities, bounded resources and explicit network TCP denial. Maven needs internal lock files even with `-o`; therefore the sandbox writes to a disposable cache copy, while preserving an untouched prefetched seed and comparing JAR/POM SHA-256 digests before/after. Receipts: `synthetic_container_tcp=BLOCKED`, `synthetic_dependency_sha256_integrity=PASS`, `synthetic_container_offline_jdk25_junit=PASS`.

The proposed carrier now has a **fail-closed `PR #2836` host-execution guard**, with seven source-free regression tests. Until a separately independently reviewed source-bearing isolated executor and complete approved cache are available, even approval of the existing carrier PR will not permit host-side Atto Maven execution. This is intentional. Source `main` remains unprotected, source-side allowlist/contract authorization and independent exact-head review are not present, and the full AttoLang dependency graph and real compiler test execution are **NOT VERIFIED**.

Re-verify latest candidate carrier root tree `fc3524c1a76099de481222265716ed22453a9296`, workflow blob `8f252830f6d62647c91e4a00182480808f5ac7a2`, plan blob `7d6ca670536725e2f77717c63ea24227d51d29ac`, CODEOWNERS blob `0110a557c162204a399ebf1e628e50c9ed991439`, isolated synthetic runner script blob `30bd01f2f006b0e544080d735010316d461d4d34`, and host-side execution guard blob `4409bb552a780f9b43faf378411ec774bbb07018` after every change. These are review candidates only, not protected-main approved identities.
