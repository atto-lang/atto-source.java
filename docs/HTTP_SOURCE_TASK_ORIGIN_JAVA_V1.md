# Authenticated SourceTask HTTP origin — Java/Graal candidate

Status: **implementation and adversarial tests committed, exact-head guest execution pending**. This branch stacks on Java #2329, #2327, and #2325.

## Where the missing continuation identity lived

`OresEvalRootNode.receiveActor` lowers a potentially suspending actor `receive` into `startSourceMethodTask`; the resulting `OresScheduler.TaskRunner` is scheduled through `ActorCell.sourceTaskMailbox`. The prior path sent `MessageEnvelope.continuation(command)` with source sequence `-1`. Thus `http.current_request_id` succeeded in the user mailbox but was correctly denied when the resumed SourceTask lacked an authenticated mailbox sequence.

## Implemented change

- `ActorRuntime.captureSourceMailOrigin()` mints a record with runtime identity, immutable actor ID/incarnation, and current mailbox sequence only when the carrier has authenticated `CURRENT_ACTOR_EXECUTION`, an exact matching `activeMail`, and the scoped dispatch sequence. This **does not capture a mutable HTTP Exchange or copy request-local data**.
- `OresScheduler.TaskRunner` captures the record **once at task construction**, stores it for all later resume dispatches, and wraps the queued runnable in package-scoped `SourceTaskTurn`. No completion callback can replace the captured origin.
- `ActorRuntime.enqueueSourceTaskContinuation` verifies that the origin targets the exact actor incarnation and that the same mail is still active. Only then does the scheduler-controlled envelope receive the original mail sequence. Unbound commands get `-1`, so they never gain request lookup authority.
- During STOP, already-admitted source cleanup continues in its existing protected lane, but origin is discarded (`-1`). Cleanup cannot revive HTTP authority.
- `NativeHttp.claim(token)` now demands that the actual `ActorInboxMail.value()` equal the claimed transfer token. This prevents pooled-worker reuse of an older, still-unclaimed token. #2329 retains transactional token rollback on rejected binding.

The `CURRENT_HTTP_MAIL_SEQUENCE` value is a **temporary dispatch check**, not request storage or a durable capability. `NativeHttp.Exchange.checkOwner()` continues to validate owner, generation, and physical request state on each operation; a callback-producer thread does not own that admitted actor scope.

## Exact-head regression matrix

`ActorSourceMailOriginTest`: real Java actor with a real `TaskRunner`; its `await` resolves on a foreign platform thread. Checks stable logical ExecutionId, stable actor/mail origin, and absence of origin on the foreign producer thread.

`NativeHttpTest`: actual Oreslang guest through Graal polyglot tests (1) pooled actor reuse with three serial requests, (2) 3/7/15 concurrent requests with `http.current_request_id` and `http.current_request_local` before/after awaiting a body, and (3) an earlier **still-unclaimed token** rejected when claimed from a later message on the same pooled actor.

The dedicated `.github/workflows/http-source-task-origin-guest.yml` verifies exact SHA, clean source, recorded Git blobs, JDK25/GraalVM installation, nonzero Surefire tests in four suites, and zero skipped/failures/errors. Failures with no runner and `steps:[]` are **runner admission, not guest failures**.

Run manually on the exact head where permitted:

```sh
mvn -B -ntp -Dtest=ActorSourceMailOriginTest,NativeHttpTest,HttpRequestLocalsTest,ActorCarrierIdentityCleanupTest -DfailIfNoTests=true test
```

## Limitations and next gates

No JDK25/Maven/Graal toolchain was available in the local environment at authoring; full project compile, Graal native image and guest tests remain unverified. The new `SourceTaskTurn` allocation per dispatch is a correctness baseline, not a near-zero-allocation result.

Before merge: execute real guest tests, run cancellation, double-claim, stop/drain and hot-reload races, review all `SourceTaskTurn` delivery paths, and check that detached tasks only inherit request authority when explicitly scoped and lifetime-pinned. Preserve private-source carrier allowlist and source-main protection gates before any cross-org mirroring.

The five-backend parity requirement remains separate and unverified: use canonical source fixtures and trustworthy runtime histories for LLVM, WASM, .NET Native AOT and Forge, not source-free model passes.
