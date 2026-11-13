# Review-only private CI carrier proposal for Java PR #3020

**No source transfer is authorized by this document or its draft PR.**
Require an independent `@ORESoftware` approval and enforcement by protected
source `main` or an exact V3 review attestation before any source blob is
published to the destination. A user request to run CI does not substitute
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
4. Reconstruct root source tree in a temporary branch; require destination `git rev-parse HEAD^{tree}` to match exactly `8f28e07b41404c5e5554c3324f37c05f1b9df3b9` or fail closed. No extra synthetic test/workflow files in that exact tree. A separately reviewed destination workflow on its protected default branch may select the mirrored head by immutable SHA.
5. Execute `mvn -B -ntp -Dtest=ActorBareHandleStateCallTest,ActorCriticalStateModeTest,ActorCriticalModeConformanceTest,ActorStateLoanTest,ActorSingleStateSchemaTest,ActorSealedStateAtomicCommitTest test` with a pinned GraalVM JDK25 toolchain.
6. Require Surefire XML for all six named suites, total **54** tests, zero skips/failures/errors, plus genuine nonzero runner steps, exact source blob/root tree readback and negative security assertions. Later run full Maven verify and Native Image only after focused compiler-backed success.
7. Keep #3020 in draft and do not merge until its stacked parent #2245 has equally strong physical actor-state execution, AOT/security review, and independent reviewer approval. The WAL `|borrow mut s| -> bool` contract is not complete.

## Open blockers

- The current source org's focused workflow [run 43334803781](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43334803781) has failed at runner admission (`steps=null`), not at actual JUnit execution.
- No current independently authorized Beamscale export binding for #3020.
- Current `private-ci-carrier-contracts.json` does not authorize this destination/plan; it must be reviewed and updated with exact approved workflow and test-plan blob IDs in the same authorization wave, not silently bypassed.
- No compiler-backed success claimed for current exact head.
