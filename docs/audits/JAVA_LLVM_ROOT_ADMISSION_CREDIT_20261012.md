# Java/LLVM root pipeline admission-credit audit — 2026-10-12

## Scope and root cause

This audit covers the bounded, compiler-bound, single-flow linear pipeline only.
Both Java/GraalVM and LLVM compile guest actors that exchange the nominal
`Signal.Data(int)` and `Signal.Result(int)` MessagePack v2 alternatives using
first-class `self.forward()` and `self.backward()` operations.

A semantic discrepancy was identified across implementations:

- LLVM #239 had been hardened to commit `pipeline_root_sent` only **after**
  successful bounded DATA enqueue under the cancellation mutex. A malformed
  root envelope therefore leaves the unique root-flow credit unspent.
- Java `ActorRuntime.send` claimed `LinearPipeline.phase` 0→1 **before**
  frame encoding/validation and bounded mailbox admission. Its catch path
  always changed 1→-1 after an exception, even when no DATA reached any
  actor. A bad initial typed frame could permanently poison an otherwise
  runnable pipeline.

## Java hardening and linearization contract

`ActorRuntime.send` now tracks `initialPipelinePublished` separately from
the provisional initial ingress claim:

1. Trusted source admission CAS transitions 0→1. Other root sends cannot
   concurrently claim the same single-flow credit.
2. The real nominal MessagePack encoder/decoder, message graph validators,
   bounded mailbox reservations and lifecycle checks run **before** enqueue.
3. When `mailbox.tryWrite(envelope)` returns true, the root DATA envelope is
   published; only that event marks `initialPipelinePublished=true`. The
   recipient may start immediately after the mailbox publication, so a
   published send must not be reopened.
4. If an exception occurs before publication, best-effort CAS 1→0 returns
   the unique credit to the trusted source. No malicious or malformed
   message is admitted. No other phase is rewritten by a stale loser.
5. If publication succeeded but later scheduling/accounting failed, CAS
   1→-1 retains the terminal fail-closed disposition. A committed DATA
   message may already be visible to the recipient. Never retry unknown
   external effects or resurrect another actor's causal turn.
6. Unauthorized direct middle-stage ingress, forged directional sends,
   duplicate root deliveries, stale actor references, and actor termination
   remain blocked independently of the recoverable pre-publication path.

The policy therefore distinguishes **rejected before publication** from
**possibly admitted**. It does not promise successful post-publication
scheduling, reliable exactly-once effects, or multi-inflight flow IDs.

## Executable and formal evidence

The `ActorPipelineRootAdmissionCreditTest` white-box JUnit regression
installs the same internal `LinearPipeline` single-flow state machine on
three trusted host actor refs. It uses the **actual public ActorRef.send,
nominal TaggedMessageCodec and DATA mailbox**, not a fake accepted payload:

- Three malformed tagged int64 sends fail without consuming phase 0, and
  no rejected DATA is observed by the mailbox.
- The next `Signal.Data(5)` is admitted and the receiver observes the
  **complete nominal TaggedValue**.
- A subsequent duplicate ingress is rejected and does not alter phase 1.

The separate `ActorPipelineDifferentialExecutionTest` and Java CLI execute
a *compiler-generated three-actor Oreslang guest* with the real source-level
pipeline binder. LLVM's
`native_pipeline_root_rejected_frame_keeps_credit` CTest likewise injects
rejected envelopes before a compiled guest round trip. The combined gate
compares observed forward values 5, 10 and backward values 13, 14; final 19,
physical LLVM turn completion, and explicit trace mutations. These tests
complement rather than replace each other: the Java rejected-ingress
regression uses a test-only internal route fixture, while the Java positive
flow exercises the actual guest pipeline and binder.

Pinned funded verification:
- [Combined JVM/LLVM #38279936023](https://github.com/zed-pkg-test/java-app/actions/runs/38279936023):
  416 exact Java source blobs, 162 exact LLVM source/test blobs; 1,469
  Maven/JUnit tests, 163 LLVM CTests, strict compiled guest semantic
  differential, seven unsafe trace mutants rejected and JSON Schema 2020-12
  report validation passed.
- [LLVM independent CTest and bounded TLC #38279936108](https://github.com/zed-pkg-test/java-app/actions/runs/38279936108):
  163/163 native CTests and `SourceRootAdmissionCredit` safe/mutant gates
  passed. This existing TLA+ model covers rejected root credit, success and
  cancellation in finite abstract states; it is **not a concrete Java/JMM
  or C11 implementation-refinement proof**.
- Full prior Native Image guest AOT/hybrid at
  [#38273842656](https://github.com/zed-pkg-test/java-app/actions/runs/38273842656)
  passed at the *prior* Java source revision. Current exact-source Native
  Image matrix [#38279936077](https://github.com/zed-pkg-test/java-app/actions/runs/38279936077)
  must be checked independently; do not assert success until completed.

## Remaining blockers and boundaries

The current conformance gate confirms same successful **semantic**
three-actor message route and same recoverable first-send policy. It does
not compare complete binary MessagePack bytes across guest runtimes, cover
arbitrary nested typed payloads, prove concurrent multi-flow correlation,
deliver general `Future<T>` admission backpressure, or establish cancellation
and physical scheduler liveness refinement across Java/LLVM.

Keep Java #645 and LLVM #239 draft until remaining source/runtime contract
and native-mode gates are explicitly established. Do not use a previous
native-image or abstract model pass as a substitute for executing current
source and guest code.
