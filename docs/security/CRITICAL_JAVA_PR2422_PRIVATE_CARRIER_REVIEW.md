# Independent review packet: AttoLang critical Java PR #2422 private CI carrier

**This file is an approval REQUEST, not export permission.** The private-source
destination allowlist and carrier-contract policy remain unchanged and empty
for this candidate. This packet cannot be used as an authorization token.

## Authoritative review pin — November 3, 2026 (supersedes historical pins)

**Review-only, never authorization.** The only source identity currently
eligible for a fresh independent review is Java draft PR #2422 head
`892c3220dbc22ae7138d3141e13ea46404430db4`, full Git tree
`01644d97450817417569cdc359826f9cc04b09ee`. Every other source
head/tree pair later in this packet is a historical receipt, **not** the
current candidate or an approval. Any new source commit invalidates this
pin and requires another independent review.

The audit found that the legacy host-callable three-argument Task
capture publisher could mint a positive ownership epoch after a
manually started attempt. Java PR #2422 now preserves that entry point
as an unconditional rejection and adds a constructor-private, exact
Program/epoch `OwnershipChecker.TaskPublishAuthority` used solely by
the checked ownership pass. The four-argument publisher also requires
the coordinated Critical -> Task -> Type monitors. A new negative
raw-AST/forged-epoch test and revised direct-publication/superseded-check
tests are authored. Legacy `async fnc` fixture declarations in the
target Task-capture suite were migrated to `suspends fnc`. Focused
CI requires **seven nonempty Surefire suites**, including
`TaskCaptureAdmissionIdentityTest`, and corrects its evidence label:
JVM-hosted Graal/Truffle is **not** GraalVM Native Image or AOT.

At this immutable source head the focused Java [Actions run 41745711398]
(https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41745711398),
synthetic smoke run 41745711459 and fast CI run 41745711393
all failed before runner admission (`steps=null`); no production
Maven, JUnit, guest, AOT or parity execution is established.

**Non-authorizing holds remain:** source `main.protected=false`,
strict status check enforcement off, zero active destination allowlist
entries, independent `@ORESoftware` reviews unsubmitted. Existing
source-free Java 21 alternate carrier receipt does not grant private
source access. Do not transfer code across GitHub organizations or merge.

### Latest noncritical Task typing-proof and LLVM M0 delta

The current Java candidate additionally requires an exact successful type
generation for **noncritical** Task captures; both successful and failed
typing rechecks revoke earlier executable Task admissions. The exact
Program/epoch ownership token remains mandatory. Three authored JUnit tests
and named seventh-suite CI checks cover this rule. A critical-only actor with
no Task captures is still admitted through its separate critical proof.

LLVM draft `oreslang-source.llvm#1277` currently targets source-only
fail-closed `.atto`/`.ores` compiler tests and native critical gate ticket
retirement receipts plus 512 host-only races. This is **not** LLVM guest
critical execution and does not authorize any private-source transfer.
The conformance evidence record is
`oreslang-platform-conformance/evidence/critical-java-llvm-m0-20261103.md`.

Latest source Java critical run `41755226023` failed before runner
admission (`steps=null`); no Maven, JUnit, guest or Graal AOT result.

The Java candidate now includes two canonical `.atto` guest source files
alongside its `.ores` compatibility fixtures, and two authored JUnit cases
requiring exact outputs `42` and `returned`. This is not yet proof of
`.atto` CLI/import migration or executed guest parity. The latest source
workflow `41755226023` had zero executed steps; hold remains unchanged.

## Candidate identities and source-free executed evidence

- Private source: `ores-truffle-oreslang/oreslang-source.java`, repository ID
  **1400792274**, with target draft [PR #2422](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2422).
- Source PR head at packet creation:
  `5c8390a49d3731f0d7e44c042c47a863058a67d5`.
  Its corresponding complete Git root-tree:
  `36907b2812976e79510af38f79e00f6cf14b3719`.
  **Re-pinning and exact-head security review are mandatory after any head move.**
- New source PR head after a documentation-only safety correction on 2026-11-02:
  `6154046b6e38167226aed3c841f732089773c5ce`, complete root Git tree
  `57f7f10f601422bfabddb59b45862eae05dfbe3e`.
  The previous two heads above are historical. The current security-hardening
  candidate is Java PR #2422 head
  `d4e1fd1af08264bf928bde0cdcfddfb3d025eaf1`, complete Git root tree
  `f09ffb5fffac56094a9a9dca68917f807d75fbd4`.
  That security-hardening candidate, and the subsequent
  `b0a8ea1a0835dbe9d4124edec0c5a943900f0b64` tree
  `1f2fa3ca62b4ffd5442de45e8c6a94b61e09a13a`, are historical.
  The intervening concurrent-typing hardening commit
  `84c2829aa3662e9bf5766c40102f7880bc244c68` and tree
  `4cc2363496f486349be87216cf02d7f7452642fb` are historical.
  On 2026-11-03, the **current proposed source PR head** is
  `539c59c60aeea796c6af1b9269dc7c4070da56b5`, with full Git root tree `b8f1239868a0e59ebee375bad9eca0e4e46de88f`.
  Only this current exact head/tree can enter a *new independent review*;
  any subsequent source change invalidates that pin.

  The current candidate retains exact-Program type-check proof, compiler-owned
  publisher capability, and fail-closed ownership-only critical admission.
  It adds a cross-ledger publication lock order (critical -> Task -> typing),
  a stable typing-generation fence, and a pending-ownership/candidate
  preflight before the positive Task execution epoch is published.
  Authored JUnit regressions include exact Program/AST alias rejection,
  concurrent retyping against publication, and superseding concurrent
  ownership checks. These are **authored, not executed**. The exact-head
  Java critical workflow [run 41587524288](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41587524288)
  and source-free synthetic smoke
  [run 41587524290](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41587524290)
  completed with zero executed job steps, so **no Java compiler, JUnit or
  guest test pass is claimed**.
  A subsequent exact-head CI admission hardening commit requires Maven
  `clean test`, `-DfailIfNoTests=true`, five nonempty JUnit XML reports,
  both critical actor guest testcases, and both concurrency race testcases.
  Source workflow blob `67b5dbf3f8e67c6d4e0543a5fca370338cf2555c`
  is part of current source tree `b8f1239868a0e59ebee375bad9eca0e4e46de88f`, not a
  carrier-workflow authorization. New source workflow
  [run 41588063452](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41588063452)
  was still queued when this packet was updated; its verdict is not
  assumed. Source-free alternate runner run 41391572866 attempt 2
  independently succeeded with five executed steps; this does not
  authorize private export.
- Private candidate carrier: `gha-indie-worker/gha-indie-worker-docs`,
  numeric repository ID **1351003564**. Source-free capacity branch
  `ci/critical-source-free-runner-canary-20261101`, commit
  `6b15b333d94058564cbe4040cef32dd27737455c` and workflow blob
  `2efb4212ef233e677ec6f30b2ab0bec2cd63409e`.
- Source-free [Actions run 41391572866](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/41391572866):
  SUCCESS, **five actual executed steps**. Java/Javac 25.0.5 and
  Maven 3.9.15 present. **No AttoLang/Oreslang private files transferred or compiled**.
- The carrier `main` also currently reports `protected=false`; the V3
  protected-main alternative requires independent exact carrier-main
  tree/review attestation. A successful canary is not that attestation.

## CODEOWNER coverage improvement (review-only, not enforcement)

This governance PR also proposes a narrow hardening of
`.github/CODEOWNERS`: `/docs/security/ @ORESoftware`. Previously,
the specific carrier review packet did not match a CODEOWNER rule.
The final, privileged-publisher-specific CODEOWNERS lines retain
their existing order. This added ownership rule **does not turn on
branch protection**, grant export authority, or substitute for the
reviewer's actual approval. Independently verify effective CODEOWNER
enforcement after enabling source-main protection, including a
synthetic negative test before allowing any private transfer.

## Source-free Java 21 compatibility receipt (not an executable carrier)

A second, independently isolated **source-free** workflow was added on a
temporary branch of the private candidate carrier, without any Git checkout:

- Repository: `gha-indie-worker/gha-indie-worker-docs` ID `1351003564`.
- Branch: `ci/critical-java21-source-free-canary-20261102`;
  review-only [carrier draft PR #42](https://github.com/gha-indie-worker/gha-indie-worker-docs/pull/42), with independent `@ORESoftware` review requested but not approved.
- Commit: `6e8a1419b5517aa47d88eedbc8b49018560c128d`;
  root tree `fcea5555c27d384d992f293d874f018fbcc7e48f`.
- Workflow: `.github/workflows/critical-java21-source-free-canary.yml`,
  exact Git blob `ed18c13bd7c5dbc49a9caf394292404c6d715b2f`.
- [Actions run 41396452614](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/41396452614):
  success with **seven executed job steps**, OpenJDK/javac `21.0.13` and
  Apache Maven `3.9.15` shown in captured job logs.

This confirms current Java 21 toolchain admission **without private
AttoLang/Oreslang source**. It does not authorize a private tree transfer,
establish remaining paid Actions minutes, approve a source-bearing carrier
control plane, or replace independent security-owner approval. The
source-executing carrier workflow/test plan remains separately unreviewed.

## Mandatory independent actions before any source-bearing Git object or ref

1. Protect the source `main`, and verify live required checks, CODEOWNER
   approval, stale-review dismissal, administrator enforcement, no bypass,
   no force pushes and no deletion. Source `main` currently reports
   `protected=false`; **export is DENIED** until independently verified.
2. Independent `@ORESoftware` security review of the *current exact head*
   of the source PR and any export-governing changes. A review by this
   packet's author or user intent is not a substitute.
3. Create a dedicated reviewed **source-free carrier control plane** that
   implements the requirements of
   `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`: isolated checkout,
   exact dispatcher authorization ref, pinned carrier commit/tree/workflow
   blobs, tightly-scoped test plan and permitted PR, payload receipt,
   network/credential isolation, and privilege-separated cleanup.
   The current canary is intentionally **not** a source-executing workflow.
4. Independently pin an exact source-approved carrier contract for
   `gha-indie-worker/gha-indie-worker-docs 1351003564`, the reviewed
   carrier-main commit and root-tree, full workflow blob, exact test-plan
   digest and allowed source PR 2422.
5. Only after items 1–4 have been independently reviewed/merged into the
   appropriate trusted protected control plane, add the exact numeric
   destination allowlist line by a separate protected-source CODEOWNER
   change. It is deliberately **not** proposed as an active line here.
6. Use the reviewed V3 publisher, never raw connector `create_blob` /
   `create_tree` / `update_ref` as an alternate privileged exporter;
   verify source and destination IDs/privacy/permissions before *every*
   source-bearing write, full non-truncated source tree and exact PR head.
   Never transfer into public/internal destinations.
7. Execute only the authorized immutable test plan, then record its
   executed steps, exact source head/root-tree, compiler SHA, test results,
   failed first actionable step, artifacts and cleanup receipts.
   A `steps=null` run is runner-admission failure, **not** a test pass.

## Requested bounded Java test plan, once independently approved

- Build the exact head's `pom.xml` with a reviewed network-isolated
  dependency-fetch phase; execute Maven `-o` in a token-free, gitless,
  non-root, network-isolated sandbox.
- Run `CriticalBodyEffectsTest`, `CriticalSuspendsV1SyntaxTest`,
  `CriticalGateTest`, `ActorCriticalGateIdentityTest`, and
  `CriticalActorGuestExecutionTest`.
- Execute the exact native `.ores` fixtures
  `examples/critical-actor-checked-v1.ores` (stdout `42`) and
  `examples/critical-actor-early-return-v1.ores` (stdout `returned`),
  and verify physical actor cleanup.
- Record exact-head, stepful evidence. Do not conflate source-free
  runner admission, host-language unit tests, and compiler-generated
  guest parity. LLVM/.NET/Cranelift/Wasm are independent gates.

## Review requested

Security-owner review is requested on this non-authorizing packet to
resolve branch-protection and carrier-control requirements. No source
mirror should be created from this proposal alone.

Related tracking:
[private-export issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962),
[critical contract issue #210](https://github.com/ores-truffle-oreslang/oreslang-common-source/issues/210).

## 2026-11-03 failed-suspension critical admission cleanup

Latest Java source head: `539c59c60aeea796c6af1b9269dc7c4070da56b5`; exact full Git root tree:
`b8f1239868a0e59ebee375bad9eca0e4e46de88f`. `OresEvalRootNode.java` blob
`78faa483ad79076a39d8f28244afe71bd35a449b` adds immediate
ticket revocation when critical cleanup registration fails or
`SourceTask.suspend` rejects an invalid second terminal step. The
change prevents a failed scheduler transition from retaining a gate ticket,
subject to actual compiler/guest regression execution. Neither source
testing nor private-source export is authorized by this packet.

## 2026-11-03 focused ticket-unwind regression evidence (source remains private)

Current restricted Java source PR #2422 head `539c59c60aeea796c6af1b9269dc7c4070da56b5`; root Git tree
`b8f1239868a0e59ebee375bad9eca0e4e46de88f`. This head extracts the exact `SourceTask` critical orphan
ticket revocation into a package-private `CriticalTicketCleanup` helper
(blob `da1365b7d3e31fc831779a92b3c6e2016996e22e`).
Four authored JUnit tests cover immediate release, queued cancellation,
retired admission, and 256 promotion/cancellation races. The exact-head
workflow requires their non-empty Surefire report and method case entries.

A **local JDK 21 standalone harness** compiled the SHA-matched helper
against separately authored local test doubles and passed **1,027
scenarios** (3 cases + 1,024 racing iterations). This does **NOT**
execute the repository's production `OresFuture`, `CriticalGate`,
`SourceTask`, Maven, JUnit, guest code, or GraalVM AOT.

Exact-head Java critical [run 41589835415](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41589835415),
synthetic smoke [41589835505](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41589835505),
and fast CI [41589835375](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41589835375)
failed with `steps=null`. These are runner-admission failures, not
executed source test results. Source protection, exact independent security
approval, carrier review, and the zero-active-entry source allowlist
remain hard gates. **NO PRIVATE SOURCE EXPORT IS AUTHORIZED.**

## 2026-11-03 all-suite Java test evidence hardening

Latest source commit `539c59c60aeea796c6af1b9269dc7c4070da56b5`, exact root tree
`b8f1239868a0e59ebee375bad9eca0e4e46de88f`, workflow blob
`a92ed18acadff1313fcc9bf6938c544f8a3320da`.
The CI verifier now refuses zero-test Surefire suites and any nonzero
skips/failures/errors across **all six selected JUnit reports**. Existing
guest/race test-case identity assertions remain. These source-free static
changes are not proof that Maven, JUnit, actors or GraalVM executed.

Independent approval, source-main protection, carrier authorization and
private destination allowlist requirements remain unchanged and unsatisfied.


## 2026-11-03 follow-up: validated lock preconditions (pending CI)

Current Java PR #2422 at the exact head and tree recorded above adds an
explicit monitor-contract check to both critical publication stages. The
compiler transaction must hold the **Critical -> Task -> Type** monitors
through pending-proof verification and both commits; a host caller without
those locks is rejected before publication. The additional controlled
interleaving JUnit case blocks the ownership checker at the critical monitor,
revokes typing with a failing linked-metadata check, then asserts no positive
Task or critical permit escapes after the failed publication. A second negative
assertion rejects an unguarded direct critical preflight.

This is source review and **authored test code, not JUnit execution**.
The source org's CI runner admission must still yield a nonzero executed-step
receipt before compiler and guest results can be accepted. The independent
review/branch-protection/private-source export holds remain unchanged.
