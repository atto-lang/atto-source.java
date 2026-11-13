# Review-only private CI carrier proposal for Java PR #3020

**No source transfer is authorized by this document or its draft PR.**
The current V3 publisher **requires protected source `main`**; it has no
unprotected-source fallback. The source main branch currently reports
`protected=false`. An independent `@ORESoftware` approval is additionally
required for the exact non-draft, main-targeted source PR before any export. A user request to run CI does not substitute
for this repo's source-transfer governance gate.

## Immutable scope

- Source: `ores-truffle-oreslang/oreslang-source.java`, repository ID `1400792274`, PR **#3020**.
- Current audited source head (2026-11-12): `b1e6974bc6b739be4bffd2fc177a8d0b102cf780`.
- Current exact source root tree: `8f28e07b41404c5e5554c3324f37c05f1b9df3b9`.
- Source branch: `feat/bare-handle-state-checked-alias-20261112`, stacked on Java #2245.
- Proposed destination: **private** `beamscale/bmscl-compiler`, immutable repository ID `1383937120`.
- Funded-carrier proof: [Beamscale no-source canary run 43333973516](https://github.com/beamscale/bmscl-compiler/actions/runs/43333973516) **SUCCESS, 3 actual steps**, Ubuntu 24.04; workflow contains no source checkout and no secrets.
- Other two private carrier probes: `voxletra/voxletra-e2e` run 43333895825 **FAILED, steps=[]**; `zed-pkg-test/java-app` did not report a run for its no-source push.

## Approved mechanism, only after review and preflight

1. Reverify the exact source head/tree and `main`'s reviewed allowlist and reviewed carrier contract; reject changed source head or carrier repository ID.
2. Preflight destination privacy, access, GitHub Actions availability and dedicated, trusted workflow identity. Do not inject private source through issues, PR comments, paste websites or external services.
3. Mirror every **full source Git blob** through the first-party GitHub connector, including executable modes and nested tree structure; verify each destination blob SHA against source. No source-org `GITHUB_TOKEN` outside its repo; no PAT, cross-org checkout, Actions secrets or workflow artifact credentials.
4. Reconstruct the exact **source subtree** in a carrier payload commit with its reviewed carrier base and signed-off receipt. Require `git rev-parse HEAD:private-oreslang-source` to equal `8f28e07b41404c5e5554c3324f37c05f1b9df3b9`. The carrier root tree deliberately differs: it contains the carrier's unchanged reviewed base, source subtree, and `ci/oreslang-receipt.json`. Verify the reconstructed carrier root SHA from precisely those entries; no other carrier file may change.
5. Only after a separate independently reviewed isolated execution gate is ready, execute offline strict-checksum `mvn -o -C -B -ntp -Dsurefire.failIfNoSpecifiedTests=true -Dtest=ActorCriticalStateModeTest,ActorCriticalModeConformanceTest,ActorStateLoanTest,ActorSingleStateSchemaTest,ActorBareHandleStateCallTest,ActorSealedStateAtomicCommitTest test` with GraalVM/JDK25 and a trusted Maven dependency prefetch and unprivileged network-isolated guest process. Source code/plugins may not execute during networked dependency prefetch.
6. Require Surefire XML for all six named suites, total **54** tests, zero skips/failures/errors, plus genuine nonzero runner steps, exact source blob/root tree readback and negative security assertions. Later run full Maven verify and Native Image only after focused compiler-backed success.
7. Keep #3020 in draft and do not merge until its stacked parent #2245 has equally strong physical actor-state execution, AOT/security review, and independent reviewer approval. The WAL `|borrow mut s| -> bool` contract is not complete.

## Open blockers

- The current source org's focused workflow [run 43334803781](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43334803781) has failed at runner admission (`steps=null`), not at actual JUnit execution.
- [Private Beamscale review PR #68](https://github.com/beamscale/bmscl-compiler/pull/68) is open/draft and awaiting independent `ORESoftware` review. Its source-free [Actions run 43336911025](https://github.com/beamscale/bmscl-compiler/actions/runs/43336911025) completed successfully with **5 actual steps** and **22 security-negative cases**. This is control-plane evidence, **not** compiler test execution. No private source was exported.
- Beamscale `main` also reports `protected=false`. V3's fallback requires an independently reviewed **merged** carrier PR, matching reviewed main Git tree and exact security CODEOWNERS, and must pin that PR number and merged main SHA.
- Source `main` reports `protected=false`. V3's source branch-protection requirement blocks exports regardless of carrier availability. A repository administrator must enable and enforce protection; the GitHub connector does not expose an authorized way to change this setting.
- Source #3020 remains a **draft PR stacked on #2245**, while V3 requires a non-draft, independently approved, exact-head PR against `main` containing protected-main history. Parent #2245 is currently conflicted. These cannot be bypassed by re-labeling source-free tests.
- Current `private-ci-carrier-contracts.json` does not authorize this destination/plan; it must be reviewed and updated only after Beamscale #68 is independently reviewed and merged. Pending immutable draft identities: workflow blob `9a70a80c95a84f67a8b506292b76bf6ea808ce0e`, plan blob `19f7c5c0d4593a93a13efea0429d1b29ba46ea3a`, private carrier repository ID `1383937120`. Final contract must record the actual reviewed carrier `main` commit and PR #68 and be independently reviewed on the source side. Do not treat draft blobs as effective authorization.
- [Carrier source-free toolchain/isolation run 43337440522](https://github.com/beamscale/bmscl-compiler/actions/runs/43337440522) succeeded at latest Beamscale PR #68 head `dcddb72e12f4252a1100385a15b16c58396767c3`: 5 source-free contract steps, 6 GraalVM/JDK25 and bubblewrap sandbox job steps, 22 negative authorization tests, logged `JAVA3020_JDK25_GRAALVM_NATIVE_TOOLCHAIN_ADMITTED` and `JAVA3020_ISOLATED_NETWORK_DENIED_PASS`. These are environment/control-plane proofs **only**, not actual source tests. The carrier workflow deliberately fails any private-source execution without a separately approved offline sandbox command runner and Surefire gate.
- No compiler-backed success claimed for current exact head.
