# Independent review packet: AttoLang critical Java PR #2422 private CI carrier

**This file is an approval REQUEST, not export permission.** The private-source
destination allowlist and carrier-contract policy remain unchanged and empty
for this candidate. This packet cannot be used as an authorization token.

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
  This is a historical security-hardening candidate. The **current**
  source PR head is `27565e1a5b5c26e23b266e771d752212501b86e8`, with
  complete Git root tree `09e6934c0c14d1a9ae8aee5b836848886b6327da`.
  Only this current exact head/tree can enter a *new independent review*;
  any subsequent source change invalidates that pin.

  The current candidate adds an exact-Program type-check proof, a
  compiler-owned publisher capability, fail-closed ownership-only critical
  admission, and a typing-generation fence so even successful rechecks
  cannot restore stale critical permissions. Seven new adversarial and
  compatibility JUnit tests are authored. The latest focused Java workflow
  run 41396262327 failed with `steps=null`; **no compiler, JUnit or guest
  test has executed**.
  Source-free alternate runner run 41391572866 attempt 2 independently
  succeeded with five executed steps; this does not authorize private export.
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
