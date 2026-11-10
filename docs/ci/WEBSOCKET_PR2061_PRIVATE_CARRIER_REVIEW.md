# Review request: private exact-tree WebSocket CI for Java PR #2061

**Status: PROPOSAL ONLY — no destination authorization, no source transfer.**
This file is a review artifact, not a V3 admission receipt, allowlist entry,
publisher instruction, review attestation, or permission to bypass repository
protection. Keep the source PR and this authorization PR unmerged/draft until
the independent gates below are met.

## Pinned scope for independent review

| Field | Pinned value |
| --- | --- |
| Source repository | `ores-truffle-oreslang/oreslang-source.java` |
| Immutable source repository ID | `1400792274` |
| Source PR | `#2061` |
| Source head | `47ec6cc375681d7c8a2f820a811ab1b485c90fa8` |
| Source root Git tree | `243d2a78c1698d05f6c640c175119b0a197dea85` |
| Source tree inventory (at pin) | 1,260 entries, including 1,139 blobs, recursive tree not truncated |
| Candidate destination | `beamscale/bmscl-compiler` |
| Immutable destination repository ID | `1383937120` |
| Destination default branch at review | `main`, head `3d1fe5886e3b166163245020acef6af2410ad3d8` |
| Source-main branch protection at review | **false — blocking** |
| Destination-main branch protection at review | **false — blocking** |
| Requested source material | One complete immutable Git source tree at the pinned SHA, not a selected Java subset |
| Proposed output | Isolated JDK 25/GraalVM source compilation and focused WebSocket JUnit evidence only |

Any source change to PR #2061, destination identity change, or revision of the
reviewed carrier control plane invalidates the pins and requires fresh review.
A permission to test PR #2048 does not implicitly authorize PR #2061.

## Evidence for candidate runner, not export authorization

- 2026-10-31 source-free admission run
  [#41234079715](https://github.com/beamscale/bmscl-compiler/actions/runs/41234079715)
  had three executed steps and completed successfully.
- Private Java proof
  [#41233244284](https://github.com/beamscale/bmscl-compiler/actions/runs/41233244284)
  had eight executed steps and completed successfully. That proof installed
  JDK **21**, not JDK 25, and is NOT WebSocket PR #2061 test evidence.
- Destination metadata was PRIVATE and writable by the connected identity;
  this is a point-in-time observation, not a guarantee of visibility during
  publication. Actual running budget/runner capacity requires a fresh
  source-free admission immediately before any authorized test operation.

Existing [PR #2137](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2137)
proposes this destination for **Future pipeline PR #2048**. It is still
unmerged and has no submitted reviewer approval. Its proposed allowlist row
must NOT be treated as authorization for WebSocket PR #2061. Do not duplicate
a globally permissive row merely because a second PR requests the same host.

## Non-bypassable pre-copy admission

1. **First protect source `main` in GitHub.** Require real pull requests,
   verified CODEOWNER approval by independent `@ORESoftware`, stale review
   dismissal, admin enforcement/no bypass, no force push/deletion, and exact
   required security checks including `preflight-regressions` from the
   reviewed issuer. A PR description or self-asserted receipt is not source
   branch protection. See issues #1636, #1659 and #962.
2. **Review destination governance independently.** Protect destination
   `main` with approved CODEOWNERS/required tests, or establish the
   formally reviewed V3 destination-specific exact attestation pathway. The
   present `protected=false` status is not sufficient for a source export.
3. **Require independent exact-identity review for PR #2061.** The reviewer
   must check the pinned head and root tree, source-main protection, all
   current incidents, and exact repository ID `1383937120`. Do not use
   approval of #2137/#2048 as a substitute.
4. **Review and merge a narrowly scoped private-carrier contract first.**
   This must bind the numeric destination ID, permitted source repository and
   PR #2061, source head and tree, reviewed carrier-main commit, GitHub
   workflow blob SHA, immutable test-plan blob SHA, one-use authorization and
   per-write revalidation. No such completed contract is claimed here.
   Update `.github/private-ci-carrier-contracts.json` under an
   independently approved security change before any transfer.
5. **Apply the protected allowlist** only after required review and branch
   rules are live. The operative allowlist is
   `.github/private-ci-approved-repositories.txt`, which currently
   approves no destination. This proposal intentionally does NOT write a
   `beamscale/bmscl-compiler 1383937120` row.
6. **Use only the reviewed V3 publisher and its authenticated metadata
   preflight**, as required by `docs/PRIVATE_CI_MIRRORS.md` and
   `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`. The GitHub connector
   cannot substitute for missing independent admission attestations.
   Revalidate both immutable repo IDs, PRIVATE visibility, non-archived and
   enabled status, and authorized destination before every write. Do not
   bypass this through direct connector Git blob/tree/ref writes.
7. **Keep scope private and isolated.** No public/internal destination,
   cross-org checkout PAT, repository secret copy, source token in logs, or
   ambient carrier dependencies. A local checkout token may access only the
   carrier's own repository. Prepare dependency artifacts in separately
   authorized execution where required; Maven tests should run offline.
   Retain receipts and classify Git object, cache, log and artifact retention
   correctly. Do not assume deleting a branch erases transferred objects.

Security incidents #2217 and #2307 document why successful private-to-private
blob identity alone is insufficient. Existing source publisher or incident
blocks must not be silently waived.

## Required mirror identity after authorization (NOT performed)

- Create the complete source tree under a reviewed carrier subtree such as
  `private-oreslang-source/`, preserving file modes, blob SHA identities,
  subtrees, and all 1,139 source blobs (inventory is pinned to this review).
- Verify the mirrored subtree Git tree SHA equals
  `243d2a78c1698d05f6c640c175119b0a197dea85`. The carrier's
  **root** tree SHA will differ because it also contains workflow/test
  control files; do not falsely claim root-tree equality.
- Record both immutable repo IDs, the source HEAD and tree, carrier base/head
  commits, carrier root/subtree SHAs, exact workflow/test-plan blob SHAs,
  approved one-use receipt, job ID, run attempt, runner identity, and
  actual executed job steps.
- Reject truncated source inventory, incomplete clone/mirror, unreviewed
  carrier workflow replacement, source HEAD drift, and all zero-step
  runner-admission failures as non-test evidence.

## Test plan, pending separately reviewed immutable artifact

Run against the pinned complete source subtree with GraalVM JDK **25**
(`graalvm/setup-graalvm` version and checksum must be separately reviewed),
using the source-pinned `pom.xml` and offline Maven dependency set.

Focused command (representative; exact workflow/test plan must be reviewed):

```sh
mvn -o -B -ntp -Dtest='dev.oreslang.runtime.WebSocketFrameCodecTest,dev.oreslang.runtime.WebSocketHandshakeTest,dev.oreslang.runtime.WebSocketOpeningRequestTest,dev.oreslang.runtime.WebSocketSessionStateTest,dev.oreslang.runtime.WebSocketOutboundGateTest,dev.oreslang.runtime.WebSocketNioDuplexTest,dev.oreslang.runtime.WebSocketActorConnectionLeaseTest,dev.oreslang.runtime.WebSocketActorMailboxDeliveryTest,dev.oreslang.runtime.WebSocketRawUpgradeTest,dev.oreslang.runtime.WebSocketLoopbackListenerTest,dev.oreslang.runtime.AsyncNativeIoTest' test
```

Capture compiler output and JUnit/surefire test counts; specifically exercise
raw-read and fragmented-USER bind gates, ownership transfer races, actor STOP,
saturated mailbox rejection, cancellation, and accepted-ticket cleanup.
Run further full/native validation if required by source merge policy. Executed
host JVM tests are not compiler-backed pure `.ores` guest echo proof.

## Independent reviewer decision checklist

- [ ] Source `main` actually protected and required check issuer verified.
- [ ] Destination `main` protected or exact V3 attestation independently reviewed.
- [ ] All source-mirroring security incidents assessed; no unresolved export prohibition.
- [ ] Exact destination slug and numeric ID independently confirmed.
- [ ] Exact WebSocket #2061 HEAD/tree independently confirmed and current.
- [ ] Source-free runner probe succeeds with **nonzero executed steps**.
- [ ] No broad allowlist row; reviewed scope limited to PR #2061 and its exact tree.
- [ ] Carrier workflow/test plan/publisher contracts have final immutable blob pins.
- [ ] Reviewed authenticated preflight and one-use/per-write controls are in force.
- [ ] Independent `@ORESoftware` approval actually submitted on the final reviewed changes.

**Decision until every box is satisfied: DENY SOURCE TRANSFER.**

This PR is documentation-only and must not, by itself, change that decision.
