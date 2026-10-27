# Proposed private CI destination for Java Future pipelines

Status: review only. This proposal does not authorize a source transfer.

## Exact candidate

- Source repository: ores-truffle-oreslang/oreslang-source.java (1400792274).
- Source PR: #2048.
- Source head: db3fbfc06acd6d88a3ee749fc0ba8ca60ba0d08a.
- Source tree: 247cc9598b4d7c17db265e2b5cda036166e078eb.
- Proposed destination: beamscale/bmscl-compiler (1383937120).
- Destination observed private, not archived, writable.
- Source-free canary commit: 55379aa2e43a27e68557a359aff3bac42e525359.
- Source-free canary run: https://github.com/beamscale/bmscl-compiler/actions/runs/40446512907.
- Canary has no checkout, no source payload, no secrets, and permissions: {}.

## Admission gates

The destination row proposed in this PR is ineffective until independently reviewed
and merged under the source repository's existing private-CI policy.
Require independent @ORESoftware review and protected-main enforcement or the
existing V3 exact-review attestation. Preserve all incident and publisher gates;
no approval or exception is inferred from successful runner allocation.

Before any transfer, refresh source HEAD/tree and destination immutable ID,
visibility, permissions and current executing canary. A changed pin requires a
fresh reviewed plan. Use only a temporary branch inside the approved private
destination. Never use cross-org checkout credentials, PATs, source secrets or
public repositories.

Require the mirror's complete Git tree SHA to equal the pinned source tree above
before execution. Do not qualify a subset as full-source or whole-tree evidence.
If a carrier-only workflow is necessary, keep the pinned source tree separately
verifiable and disclose every harness addition; do not describe its changed root
as the identical source tree.

## Required execution

Use GraalVM JDK25 and the source-pinned build configuration.

1. mvn -B -ntp -Dores.runtime.carriers=native -Dtest=FuturePipelineProvenanceTest,SourcePureFuturePipelineExecutionTest,TaskPipelineTest,TaskHandleTest test
2. mvn -B -ntp -Dores.runtime.carriers=native verify
3. Source ci native-aot, native-aot-ref64, native-hybrid and polyglot-isolate jobs,
   including scripts/test-future-pipeline-native.sh for the three native launchers.

Record source/mirror commits, tree identity, run ID and attempt, actual runner ID,
executed steps and nonzero test counts. Verify the new scope-cancellation regression
and #2121 proof invalidation. Capture physical-retirement results independently of
Future settlement. Keep #2048 draft until required execution and final-SHA review.

No source code was copied to the destination while preparing this proposal.
