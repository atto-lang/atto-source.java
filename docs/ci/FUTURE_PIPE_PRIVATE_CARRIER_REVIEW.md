# Proposed private CI destination for Java Future pipelines

Status: destination proposal for independent review. No source transfer is authorized
by this document or by an approval of the proposal alone.

## Exact candidate

- Source repository: ores-truffle-oreslang/oreslang-source.java (1400792274).
- Source PR: #2048.
- Source head: 6af37f70a42d70d16996174317b5bdd8bda07c7c.
- Source tree: 6e94c49cff4fedf80335ce5cbfa13ac174c07411.
- Proposed destination: beamscale/bmscl-compiler (1383937120).
- Destination observed private, not archived, writable.
- Source-free canary commit: 55379aa2e43a27e68557a359aff3bac42e525359.
- Source-free canary run: https://github.com/beamscale/bmscl-compiler/actions/runs/40446512907.
- Canary runner: 1000005704; three successful steps.
- Canary has no checkout, no source payload, no secrets, and permissions: {}.

The source includes the scope-cancellation checkpoint/regression and both native
smoke diagnostic, harness-regression and CI-integration follow-ups. Any subsequent source head
change requires refreshed pins and independent exact-head review.

## Mandatory admission gates

Follow docs/security/CI_PRIVATE_SOURCE_MIRRORING.md and the trusted V3 dispatcher.
The allowlist row proposed by this PR is necessary but insufficient.

1. Source main must have live, independently verifiable protected-branch enforcement
   with all required approvals, CODEOWNER review, checks, stale-review dismissal,
   administrator enforcement and no bypass/force-push/deletion. There is no
   review-attested substitute for source-main protection.
2. Independent @ORESoftware review must approve the exact source PR head.
3. Independently review and admit the exact destination row and carrier contract.
   The contract must pin destination ID, source-free carrier main commit, carrier
   workflow blob, test-plan blob/digest and permitted source PR.
4. Prepare the source-free carrier control plane and test plan for separate exact
   review. This proposal does not supply or attest those artifacts. The current
   canary is capacity evidence only and is not a source-executing carrier workflow.
5. Carrier main must satisfy protected-main requirements or the documented
   carrier-only exact reviewed-PR/root-tree attestation, with exact CODEOWNERS.
6. Preserve all incident and publisher gates. Refresh visibility, immutable IDs,
   source/candidate pins and current executing canary before any source write.

Use only scripts/run-in-private-ci-destination.sh and its reviewed V3 publisher.
Do not use direct GitHub connector writes as an alternative around authorization.
No cross-org checkout tokens, PATs, source secrets or public/internal destinations.

## Tree identity and execution boundary

The approved carrier receives the complete pinned source as the
private-oreslang-source/ subtree. Its Git subtree SHA must equal the source root-tree
SHA above. Record the carrier root tree separately; it includes reviewed control
files and a receipt and therefore is not the source root tree.

Require the standard V3 receipt, one-use authorization ref, per-write governance
revalidation and isolated source execution. Carrier unrelated files/Git objects
and credentials must be unavailable to the test process. Test execution must use
the established network sandbox and a clean HOME/config environment. Prepare
Maven dependencies in the separate reviewed dependency job before offline tests.
Never qualify a synthetic subset as full-source evidence.

## Required execution after admission

Use GraalVM JDK25 with the source-pinned build configuration and reviewed pom.xml.

1. mvn -o -B -ntp -Dores.runtime.carriers=native -Dtest=FuturePipelineProvenanceTest,SourcePureFuturePipelineExecutionTest,TaskPipelineTest,TaskHandleTest test
2. mvn -o -B -ntp -Dores.runtime.carriers=native verify
3. Source ci native-aot, native-aot-ref64, native-hybrid and polyglot-isolate jobs,
   adapted only through the reviewed isolated carrier plan; all Maven invocations
   remain offline. Run scripts/test-future-pipeline-native.sh for the three native
   launchers and the source-free scripts/test-future-pipeline-native-gate.sh check.

Record source/mirror commits, matching source subtree SHA, carrier root-tree SHA,
receipt and plan digests, run ID/attempt, actual runner ID, executed steps and
nonzero test counts. Verify scope-cancellation and #2121 proof invalidation plus
physical retirement independently of Future settlement. Keep #2048 draft until
required execution and independent final-SHA review.

No compiler source has been transferred while preparing this proposal.
