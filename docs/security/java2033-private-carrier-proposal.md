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
| Source PR head | `6c54d3c2b2b24c1597fddabcbe0bf98aba074484` |
| Source root Git tree | `39eee009b65772f958d2a401f0cdc224170b03c0` |
| Source base/main observed | `f40b9c3a3b5b4691eb4f4a5d5d0558a112f237aa` |
| Candidate carrier | `opto-sync-test/go-java-validation` |
| Carrier numeric repository ID | `1323598177` |
| Carrier visibility observed | `private` |
| Carrier PR | `opto-sync-test/go-java-validation#25` |
| Carrier PR head | `60d538aa9f80de94658880050bd73bf0f2d0a879` |
| Carrier workflow blob | `4cfef14d5976b193a16c02a574c406defaeac874` |
| PR #2033 test-plan blob | `34463ea91c6ab87d69f4337be23b51ed55b8d7ae` |

The carrier test plan pins the current PR #2033 production/test blobs and runs a
focused ActorGroup cleanup/pipe/supervisor suite followed by the full Maven
test suite. The current plan also pins the exact ActorPipeTransportTest blob
that contains the fatal-sibling structural-close regression and the exact
ActorGroupManagedWorkersReconciliationTest blob that proves runtime admission
sealing does not collapse the active worker-done lifecycle barrier.

Current source-free validation receipts:
- cleanup semantic harness head `0065d36ef776d2f9cfe439adfd06456476e75038`,
  Sonus run `40349883441` / job `121666735498`: executed checkout,
  Java compile, and adversarial cleanup cases including cross-phase VM-fatal
  precedence with retained earlier evidence; PASS;
- exact current carrier-plan mirror head
  `b5375456591881a87e3e3f4cdec5af468f2e062c`, Sonus run
  `40358615664` / job `121697702344`: executed checkout, carrier verifier
  regression, and trusted-plan runner regression against plan blob
  `34463ea91c6ab87d69f4337be23b51ed55b8d7ae`; PASS.
Both are source-free and therefore do not certify the private Oreslang source. Source-free carrier regression code has executed successfully in a
separate funded private repository, but that evidence is **carrier-code only**:
it does not authorize private source placement and does not prove PR #2033.

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
   head `6c54d3c2b2b24c1597fddabcbe0bf98aba074484`, full ci run
   `40358382678` has Maven test/AOT/ref64/hybrid/polyglot jobs at zero steps;
   fast-ci `40358382787`, Graal conformance `40358382564`, and synthetic
   smoke `40358382965` are likewise zero-step. These are
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
   `39eee009b65772f958d2a401f0cdc224170b03c0` and pin the destination commit.
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
