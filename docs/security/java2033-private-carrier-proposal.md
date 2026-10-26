# REVIEW-ONLY candidate: Java PR #2033 exact-tree cleanup validation

This document is **not an approval to transfer private Oreslang source**. It does
not modify the active destination allowlist, publisher authority, branch
protection, repository visibility, GitHub permissions, secrets, or workflow
dispatch policy. Publication MUST remain disabled until every mandatory gate
below is independently satisfied.

## Immutable candidate identity

| Evidence | Immutable value |
| --- | --- |
| Source repository | `ores-truffle-oreslang/oreslang-source.java` |
| Source numeric repository ID | `1400792274` |
| Source PR | `#2033` |
| Source PR head | `9d86b93d53ae1836c60bd230e1935190fe6426cf` |
| Source root Git tree | `d75650c21ca58584b689aae7977f4750074aef8b` |
| Source base/main observed | `f40b9c3a3b5b4691eb4f4a5d5d0558a112f237aa` |
| Candidate carrier | `opto-sync-test/go-java-validation` |
| Carrier numeric repository ID | `1323598177` |
| Carrier visibility observed | `private` |
| Carrier PR | `opto-sync-test/go-java-validation#25` |
| Carrier PR head | `6e63603f104b65d1d949295ec01154900a8414a1` |
| Carrier workflow blob | `4cfef14d5976b193a16c02a574c406defaeac874` |
| PR #2033 test-plan blob | `99719a8e764705829601fc53ad64c6e9f268a8cf` |

The carrier test plan pins the current PR #2033 production/test blobs and runs a
focused ActorGroup cleanup/pipe/supervisor suite followed by the full Maven
test suite. The current plan also pins the exact ActorPipeTransportTest blob
that contains the fatal-sibling structural-close regression, the exact
ActorGroupManagedWorkersReconciliationTest blob that proves runtime admission
sealing does not collapse the active worker-done lifecycle barrier, and the
exact ActorOutputStreamTest blob that proves shutdown admission sealing wins
buffered-output publication arbitration when it acquires the lifecycle monitor
first.

Current source-free validation receipts:
- funded private `sonus-auris-test/clients-api-contract` run
  `40370440955`, JDK 25 job `121739415786`: executed checkout, immutable
  `actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961`,
  Temurin 25.0.4.1 compile, the actor cleanup/worker-barrier model, and the
  shutdown-vs-buffered-output arbitration model; PASS with
  `SOURCE_FREE_ACTOR_CLEANUP_PASS` and
  `SOURCE_FREE_OUTPUT_ARBITRATION_PASS`. The job explicitly asserts no
  `private-oreslang-source` and no `src/main/java/dev/oreslang` tree;
- the same funded run, carrier-control job `121739415441`: asserted all seven
  current carrier/control files byte-identical by Git blob SHA to carrier
  #25 head `b64ba33bd86cde3a434b76278c196694c543adaa`, ran 6/6 carrier
  verifier regressions and 6/6 trusted-plan runner regressions, and validated
  current plan blob `7612dee74280443ef95a99890c057ae7938c65a8`
  pins ActorRuntime blob `66b9a4ab5ebb4006e65a1caef74e33cc66c3fd5f`
  plus ActorOutputStreamTest blob
  `f9079cdcd2615b3716dd923c640c3a72b568acac`; PASS with
  `CURRENT_JAVA2033_PLAN_PASS`.
Both are source-free and therefore do not certify the private Oreslang source.
They prove the cleanup/output semantic model and carrier control plane only;
they do not authorize private source placement and do not prove PR #2033.

Any change to the source head/tree, carrier head, workflow blob, test-plan blob,
repository slug/ID, privacy state, or reviewed command set invalidates this
proposal and requires a fresh exact review.

## Exact prerequisite security stack

Independent review must cover this exact linear control-plane stack before any
destination activation or source-bearing publication:

1. #1028 `3c50b227046c6f2aabbc9fb23960be3810dcf5fa`
   (checkout-controlled authorization hardening), based on current source
   `main`;
2. #1743 `597c966208efbf7a6d9745fd11d16ad838667028`
   (strict JSON / exact-head review hardening), based directly on #1028;
3. #2072 `96bbfef8e1de7555ad600b85a90735644af8bad5`
   (isolated GitHub API environment / carrier eligibility / quarantine
   hardening), based directly on #1743;
4. #2071 `380b662b512d96e1bf190cd2db50bb29aa3805e9`
   (terminal exact CODEOWNERS contract), based directly on #2072.

Any movement of one of these heads, a base relationship, or a reviewed
security-control blob invalidates this prerequisite binding and requires a
fresh independent review. These PRs remain non-authorizing by themselves.

## Current HOLD reasons

At proposal creation time:

1. Source `main` reports `protected=false` and
   `protection.enabled=false`. Source security issue #904 remains OPEN.
2. Source security issue #962 remains OPEN and explicitly blocks private
   publication until reviewed protected provenance / publisher authority is
   established.
3. `.github/private-ci-approved-repositories.txt` on source `main` has no
   uncommented approved destination entries.
4. Carrier `main` also reports `protected=false`; carrier PR #25 has no
   recorded independent review submission.
5. Platform-conformance P0 incidents #22, #77 and #127 remain OPEN and require
   independent disposition before private-source export resumes.
6. Source-org Actions for PR #2033 still fail before job steps. On exact source
   head `31bb2de7e84184ad9051844b2cfef1cfe1db32f2`, full ci run
   `40369386035` has Maven test/AOT/ref64/hybrid/polyglot jobs at zero steps;
   fast-ci `40369385985`, Graal conformance `40369385824`, and synthetic
   smoke `40369386021` are likewise zero-step. These are
   admission/infrastructure evidence only and are not implementation failures
   or passing tests.

Therefore **no private source transfer, mirror branch, source-bearing carrier
commit, authorization ref, or source-bearing workflow rerun is permitted by
this proposal**.

## Mandatory gates before any exact-tree mirror

1. Resolve or independently disposition the governing private-source incidents
   and #962/#904 release gates. Keep source private.
2. Establish independently enforceable source merge/provenance governance:
   required independent review, stale-approval dismissal, exact-head required
   checks, no force-push/direct-push bypass, and demonstrated branch/ruleset
   enforcement.
3. Independently review carrier PR #25 on its exact final head. If it changes,
   repin this proposal. Merge only after the required independent review and
   verify the resulting carrier `main` SHA plus workflow/test-plan blobs.
4. Add the exact carrier slug **and numeric repository ID** to the source
   destination ledger only through an independently reviewed authority change
   whose provenance is itself trusted. Do not infer authority from this doc.
5. Use only the reviewed V3 publisher after its own final independent approval.
   Before reading or writing source bytes it must authenticate source repo ID,
   private/active state, exact open PR head and root tree, reviewed policy
   provenance, carrier ID/private state and exact carrier control-plane pins.
6. Transfer only the exact source Git tree into a generation-specific private
   carrier commit/ref. Prove the nested destination subtree tree SHA equals
   `4a2814cf647c0cb1e6da5e3820b86aa9e7af67e8` and pin the destination commit.
7. Execute the immutable #2033 plan and require actual nonzero job steps:
   - focused `ActorGroupCleanupFailureTest` / pipe / supervisor coverage;
   - full Maven/JDK suite;
   - then source-required GraalVM AOT/ref64/hybrid/polyglot gates.
8. Record exact source head/tree, destination commit/tree, workflow/test-plan
   blobs, executed job IDs/steps and test results. Any zero-step failure remains
   `NOT_EXECUTED`.
9. Preserve/clean up carrier refs and retained objects according to the
   independently reviewed incident/retention process. Deleting a branch alone
   is not proof that private Git objects were removed.

## Intended acceptance result

Only after all gates above are satisfied may an exact-tree run be considered
merge evidence for Java PR #2033. Until then the implementation may be
source-reviewed and source-free-model tested, but exact-head Java/GraalVM
execution remains **unverified**.
