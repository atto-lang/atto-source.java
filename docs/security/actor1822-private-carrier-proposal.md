# REVIEW-ONLY candidate: GraalVM actor native-memory PR #1822

This document is **not an approved destination** and does not change the
publisher allowlist, transfer tooling, branch rules, repository secrets,
GitHub permissions, or workflow dispatch policy. Source export MUST remain
disabled until the protected policy and reviewer gates below are complete.

## Proposed identity

| Evidence | Immutable value |
| --- | --- |
| Source repository | `ores-truffle-oreslang/oreslang-source.java` |
| Source numeric repository ID | `1400792274` (recheck from authenticated API) |
| Source PR | #1822, draft, `feat/actor-native-state-layouts-20261021` |
| Candidate destination | `sonus-auris-test/clients-api-contract` |
| Candidate numeric repository ID | `1323599063` (recheck from authenticated API) |
| Candidate visibility | Private (recheck immediately before each write) |
| Capacity canary commit | `16aefb6cf354b23be143a79cf2d0bd9d7cb702a2` |
| Capacity canary workflow blob | `7ab2cd755bf0dfbcc12a68b895d098b81769edd5` |
| Capacity-only run | https://github.com/sonus-auris-test/clients-api-contract/actions/runs/39641281915 — success |

The canary executed an independent source-free Linux test. It is **not** a
private-code approval, a funded-minutes guarantee, a proof of the proposed
GraalVM workflow's integrity, or a compilation of Oreslang.

## Mandatory conditions before even attempting an exact-tree mirror

1. Resolve source issue #962 and verify the real publisher entry point
   authenticates the **private source repository ID, checked-out protected
   policy provenance, exact immutable PR head/tree and destination numeric
   ID** before reading or writing private source.
2. Obtain genuinely independent security/code-owner review on a protected
   branch for a narrow, PR-specific approval. Do not self-approve by merging
   this proposal, editing a policy file on an untrusted branch, or marking
   this draft ready. Confirm branch/ruleset enforcement (#904).
3. Prepare and independently review a **new** constrained private carrier
   workflow under the destination with a pinned workflow blob and test-plan
   identity. The current source-free canary cannot checkout or test source.
   Disallow fork inputs, token persistence, artifact/source publication,
   third-party exfiltration and uncontrolled PR-triggered execution.
4. Transfer only the reviewed exact private Git tree, to a private
   destination, using the authenticated publisher and a generation-specific
   throwaway branch; prove subtree Git tree identity at destination and pin
   the destination commit SHA. Do not use cross-org `GITHUB_TOKEN`
   checkout or unreviewed mirror scripts.
5. Run exact-head pinned GraalVM/JDK25 Maven tests:
   `ActorLinearMemoryTest`, `ActorNativeArenaTest`,
   `ActorNativeObjectAllocationTest`, `ActorShardColumnsTest`,
   `ActorShardColumnCopyTest`, `ActorShardSnapshotLeaseTest`,
   `ActorShardStopRaceTest`, `ActorAsyncFinalizationBarrierTest`,
   `ActorPhysicalTurnGateTest`, `SourceActorSpawnExecutionTest`,
   `ClassConstructorHardeningTest`, and
   `ActorDeclarationLexicalBoundaryTest`, plus source/Native Image
   compilation where supported. Capture actual executed steps, logs, exact
   source/destination SHAs, and failure diagnostics. Any new change to #1822
   invalidates older test passes.
6. Cleanup and independently verify removal of test branches, copies and
   transient artifacts without deleting project-owned files or references.

## Current disposition

**HOLD**: #962 remains open; the active source
`.github/private-ci-approved-repositories.txt` contains no approved
destination. This review-only proposal never grants authority to mirror the
private source, even though a separate Actions capacity smoke succeeded.
Source-org hosted jobs have failed before test execution, and #1822 remains a
draft with zero new exact-head GraalVM test passes.
