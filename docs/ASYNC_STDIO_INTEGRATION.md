# Async stdout logging integration (current-main candidate; 2026-10-31)

**Status: DRAFT, no executed native/JUnit/guest test receipt.** This is a source-local integration on Java main `241bd67c39443cc2ef18df7e4ed456454af378c7`, not a cherry-pick of the old `OresContext` from #537.

## Guest APIs and return values

- `stdio.stdout.log_async(values...)`, `stdio.stdout.logList_async(values)`, `stdio.stdout.println_async(value)`, and `stdio.println_async(values...)` format a complete immutable UTF-8 record synchronously on the calling guest task and enqueue it for a dedicated context worker.
- Each returns `Future<void>`. Awaiting confirms the host `PrintWriter` accepted/flushed the data without reporting an error; it does **not** prove ingestion by an external collector, fsync durability, or independent line ordering against other contexts.
- `await stdio.flush_async_logs()` returns a barrier for all records accepted before barrier admission. Concurrently accepted later records are outside its scope. The barrier does not consume or cancel queued records when its Future is dropped. After `close()` fences admission, **both new record submissions and new flush barriers are rejected**; existing accepted records and existing barriers may still finish while a stalled drain unwinds. A failed drain retains the context quarantine until an authorized successful close retry.
- All APIs require the existing `STDOUT` capability. Source ownership checking treats values as ephemeral read-only formatting borrows; only rendered `String` data enters the worker.
- Existing synchronous `stdio` APIs remain available for compatibility and can block guest actor carriers. The synchronous `stdio.stdout.log` path now sends one entire line to the PrintWriter per call, sharing the sink monitor with async writes, but cross-producer total order is not guaranteed.

## Capacity and failure contract

Each context has a queue of 1,024 records, an additional **8 MiB aggregate pending-byte limit counting the in-flight record**, maximum 65,536 UTF-8 bytes per rendered record, and at most 1,024 pending async flush barriers. Admission returns promptly or raises on a full queue, excess record size, writer closure, barrier exhaustion, or terminal sink failure. Queuing is in-memory, non-durable, and bounded by both record count and a per-context byte budget; **there is still no graph-wide or tenant-wide aggregate byte budget across contexts**. Source formatting may allocate before the size check; adversarial size-amplifying renderers remain a separate resource-analysis gate.

Writer I/O happens on a daemon platform thread. Accepted records are FIFO per context, but mixed synchronous writes are serialized only by the shared PrintWriter monitor. A sink failure marks the queue terminal and fails accepted record/barrier Futures. A lost Future does not revoke accepted data.

## Shutdown and quarantine

The current-main `OresContext.close()` retains the physical actor and native-I/O join gate and `QUARANTINED_CONTEXTS` strong-reference retention. A per-context atomic exclusive-close admission guard rejects a second concurrent shutdown attempt without executing teardown stages; failed attempts release the guard for an explicit retry, and completed close is idempotent. After physical actor/native-I/O joins, the production auxiliary/drain controller independently attempts HTTP, async runtime, VM and bounded async writer drain, even if one of the auxiliary stages fails; successful stages are journaled and skipped on retry. If any fails, the context stays quarantined and no final synchronous stdout flush or shared graph release occurs. After actor/native-I/O retirement and auxiliary VM close, the async writer stops accepting and drains accepted records, with a 5-second deadline. If physical retirement or the async drain fails, the context remains quarantined rather than clearing the graph or synchronously reacquiring a potentially blocked host stdout writer. The normal completed-drain path still flushes stdout before releasing the quarantine pin.

This cannot guarantee bounded shutdown when **unrelated** services or the external writer are non-cooperating; it is specifically a guard against an extra synchronous reflush after a known async drain timeout. Cancellation, close retries, in-flight root-task production, and callbacks during retirement still require executed adversarial tests.

## Gate matrix

| Gate | Required proof | Current state |
|---|---|---|
| CI admission | Real runner and nonempty job steps on head SHA/attempt | BLOCKED, zero-step |
| Focused host tests | `AsyncStdioLogsTest` queued writes, blocked writer, UTF-8 sizes, 8 MiB in-flight accounting, overflow, per-context sink error isolation, closed-record/flush rejection, timeout/drain retry | AUTHORED, NOT EXECUTED |
| Guest tests | `StdioLogAndSpreadTest` detached log, await barrier, spread/list, permission-denied and direct-only | AUTHORED, NOT EXECUTED |
| Whole-context blocked writer | Polyglot `Context.close()` with actual blocked host `OutputStream`, timeout/exception chain, and no reflush deadlock | AUTHORED, NOT EXECUTED |
| Competing context close + quarantine recovery | Real Polyglot context blocked-sink timeout, overlapping close rejection, retained pin, same-object retry, pin removal and idempotence | AUTHORED, NOT EXECUTED |
| Flush/close race | Previously accepted record/barrier completion after close admission fences new flush/record requests | AUTHORED, NOT EXECUTED |
| Auxiliary shutdown retry journal | Injected HTTP/async-runtime/VM/drain failures, suppressed error retention and successful-stage once-only accounting in production helper | AUTHORED, NOT EXECUTED |
| Real auxiliary carrier retry | Actual `AsyncRuntime` virtual-thread close timeout and `OresVM` physical STOP worker timeout; a later close must recheck actual executor termination | AUTHORED, NOT EXECUTED |
| Final host stdout error | `PrintWriter.checkError()` detects a suppressed final flush I/O error before quarantine removal | AUTHORED, NOT EXECUTED |
| Final teardown before reclamation | Verified stdout flush before shared-code/GC cleanup; failure-injected once-only receipts and retry | AUTHORED, NOT EXECUTED |
| Retirement | `OresContextRetirementStagesTest` Error-class fault injection plus actual actor generation/cancellation, native-I/O physical join, quarantine release and retry | ERROR FIXTURES AUTHORED; NATIVE NOT EXECUTED |
| Native | Exact-tree GraalVM guest and native AOT/hybrid builds, carrier liveness | NOT EXECUTED |
| OTel | Exact compiler `SOURCE_REF`, `oreslang-otel` smoke, int64, span lifecycle | NOT EXECUTED |
| Governance | Protected main + independent approval | OPEN |

Preserve backend, repository, head/tree, compiler/toolchain, job attempt and nonempty step ledger for all results. A source-free canary is **only runner admission**, not native or guest proof. Do not update `oreslang-otel/SOURCE_REF` or promote the async exporter until this runtime reaches an executed, independently reviewed exact head.

### Pending-byte admission note (2026-10-31)

The byte counter increases only after a record is accepted and decreases only after that record is processed, including its in-flight write; a dequeued but blocked writer still consumes quota. Failed admissions do not allocate a record or Future into the queue. The quota is **not** a measure of total process heap usage: pre-admission string rendering, Java object overhead, and other contexts remain additional allocations. Physical-isolate and actor scheduling proof requires executed native tests.

### Whole-context blocked-writer test (2026-10-31)

`StdioLogAndSpreadTest.blockedAsyncSinkCannotHangWholeContextClose` invokes an actual Polyglot guest async log against a latch-blocked host `OutputStream`. The test closes the Polyglot context on a separate thread, requires an explicit async-drain timeout failure rather than success or indefinite monitor reacquisition, and releases the test sink in `finally` to prevent a stuck CI worker. This exercises the real `OresContext.close()` path rather than only `flushOutputAfterAsyncDrain`.

This fixture has **not executed**; no guarantee of correct result or lifecycle behavior is claimed. It does not independently prove quarantine release or a successful retry through the Polyglot wrapper after failed disposal. Follow with a separate actor/native-I/O retirement and recovery test once a nonzero-step runner can build the exact head. The five-second drain deadline is not a deadline for unrelated context teardown stages.

### Exclusive context close and recovery characterization (2026-10-31)

`OresContext.close()` now uses an atomic exclusive-admission guard around physical retirement, auxiliary closure, async writer drain, final flush, and quarantine release. A second concurrent closer fails immediately, rather than running these teardown stages concurrently. A failed closer preserves `QUARANTINED_CONTEXTS` and releases only the close-admission guard; after the blocked sink recovers, explicit retry on the same runtime object may complete and release the pin. A successful close is idempotent. No source path can remove the quarantine pin after an incomplete mandatory stage.

`AsyncStdioContextCloseTest.concurrentCloseCannotReleaseQuarantineBeforeFailedDrainRetry` uses a real Polyglot context and latch-blocked host sink, observes the actual strong-reference quarantine, probes a second closer, asserts timeout and closed writer admission, releases the sink, then retries the same `OresContext`. The test uses a package-private snapshot strictly for native JUnit inspection, not guest access. `AsyncStdioLogsTest.acceptedFlushBarrierOutlivesCloseAdmissionRace` checks that previously accepted record/barrier futures settle while later admission is denied.

**Evidence:** source changes and fixtures are authored only; no executed compiler/JUnit/guest/AOT receipt exists. The Polyglot wrapper may not support retry after a failed disposal, hence the test explicitly retries the same retained `OresContext`. Independent execution must validate all assumptions and catch any VM or resource close non-idempotence. The close guard does not establish a global tenant byte budget or eliminate host sink I/O stalls.

### Early shutdown admission fence (2026-10-31)

At the start of the exclusive `OresContext.close()` attempt, **after** retaining the quarantine pin and **before** actor/native-I/O joins, `asyncStdio.sealAdmission()` now atomically rejects new record submissions and new flush barriers. This method never joins the writer or takes the host `PrintWriter` monitor. Previously accepted records and barriers retain their Futures and continue draining; a failed earlier retirement stage leaves the fence closed until an authorized successful retry, which later joins the writer under the existing bounded deadline. Repeated seals are idempotent and do not accumulate semaphore permits.

Authored regressions `AsyncStdioLogsTest.sealingBeforeRetirementRejectsWorkWithoutWaitingForStalledSink` and `sealingAnUnusedWriterIsIdempotentAndRequiresNoDrainThread` cover a blocked sink, surviving accepted receipts, post-fence rejection and an unused worker. **These fixtures have NOT executed on the exact head.** Whole-context actor/native-I/O failure injection, JDK25/GraalVM JUnit, native AOT/hybrid and tenant/graph byte budgets remain separate required gates.

### Auxiliary shutdown fault injection and retry journal (2026-10-31)

The actual `OresContext.close()` path invokes a package-local `AuxiliaryCloseStages.closeRemaining` controller after actor and native-I/O retirement and the early async stdio admission fence. Each successfully completed auxiliary service (HTTP, async runtime, VM) and the async stdio drain is recorded as complete and is not repeated during a subsequent failed-close retry. Failed stages remain retryable. The drain is independently attempted even when an auxiliary service throws, but a failed service or drain prevents the final synchronous stdout flush, shared-code/GC release and quarantine removal. Secondary failures are attached as suppressed exceptions when distinct from the primary failure.

`OresContextAuxiliaryCloseTest` injects deterministic first-attempt HTTP/VM/drain failures and validates primary/suppressed errors, once-only successful service closure, failed-stage retries, drain-only failure reporting and a shared exception instance. The test exercises the **same stage controller that production uses**, but it is **not** a full Polyglot VM/native-I/O failure injection. The existing `AsyncStdioContextCloseTest` covers an actual blocked writer and quarantine retry, not a deliberately faulted real HTTP/VM closer.

**Status: AUTHORED, NOT EXECUTED.** A real nonzero-step JDK 25/GraalVM JUnit run is mandatory, including the focused new suite; native-image and guest runtime proofs, fault injection in the fully constructed production context and independent review remain open. The retry journal is best-effort for non-idempotent underlying APIs: a stage throwing after partial side effects has not been proven physically reversible.

### Physical auxiliary close retry and final stdout error gate (2026-10-31)

Source audit found that `AsyncRuntime.close()` and `OresVM.close()` previously marked themselves closed before their physical carrier joins had succeeded, then immediately returned from a subsequent `close()`. The context-level retry journal could therefore have mistaken **already sealed but still physically running workers** for successful retirement. Both runtimes now keep first-attempt admission/revocation one-shot and revalidate actual executor termination on **every** close retry. The VM preserves root scheduler/discard callback failures, including fatal `Error` subclasses, and still attempts both executor shutdown paths.

New real-service regressions `AsyncRuntimeTest.failedCloseRetryMustWaitForPhysicalAsyncCarrierExit` and `OresVMTest.failedCloseRetryMustRecheckRealPhysicalStopCarrierExit` run interrupt-resistant carriers beyond the first bounded close deadline; they assert failed first close, closed admission, physical worker still present, worker exit following host release, and a successful idempotent retry. These exercise actual virtual-thread and CONTROL/physical-stop executor shutdown rather than injected `Runnable` closures alone.

A separate source-level bug was that `PrintWriter.flush()` hides `IOException` by setting the writer's sticky error flag; the prior context code marked the final flush successful and removed quarantine without observing the error. `flushOutputAfterAsyncDrain` now uses `PrintWriter.checkError()` (which flushes and checks that flag) after successful async drain; a reported error raises before the context can remove quarantine. It **never** touches the writer after an incomplete async drain. `AsyncStdioLogsTest.finalHostFlushCannotHidePrintWriterIoFailure` is the focused regression. Sticky host writer errors may prevent recovery; there is no implicit data-loss or quarantine-release bypass.

**Status: AUTHORED, NOT EXECUTED.** The local development container does not provide the exact JDK25/GraalVM/Maven build setup or a private source checkout. Source-org GitHub Actions runner admission remains unresolved. A fully constructed `OresContext` with deliberately failing HTTP/async-runtime/VM service closure is still a separate end-to-end gate; do not treat these real-service unit tests as a completed full-context integration receipt.

### Error-class retirement fault containment (2026-10-31)

A source-level audit found a second shutdown exception-class gap: the production `OresContext.joinActorsAndNativeIo` and `AuxiliaryCloseStages.closeRemaining` sequences previously caught only `RuntimeException`, not `Error`. A Java `AssertionError` or `LinkageError` from an actor-seal/native-revoke/physical-join or HTTP/VM/drain stage could therefore skip later independently required revocation, physical joins or the accepted-output drain.

The production code now catches `RuntimeException | Error` **at each independent stage**, records the primary cause with distinct secondary failures as suppressed, and raises a fail-closed `IllegalStateException` when any required stage failed. Quarantine and the exclusive close guard remain in charge; no final synchronous stdout flush or graph-release receipt follows a failed stage. `joinRetirementStages` is the package-visible same-order stage controller invoked by the production actor/native-I/O routine, so tests exercise the actual control flow rather than a second imitation.

New authored JUnit fixtures: `OresContextRetirementStagesTest` verifies seal/revoke/join ordering, fatal seal/revoke/join Errors, secondary failures and duplicate throwable identity; `OresContextAuxiliaryCloseTest` adds fatal auxiliary/stdio errors and verifies that drain still runs and failed stages remain retryable.

**Evidence classification:** source-reviewed and fixture-authored only; not an executed JDK 25/GraalVM/JUnit result. This best-effort teardown cannot claim recovery from `OutOfMemoryError`, irrecoverable host faults, or successful physical worker exit just because all shutdown callbacks returned. Actual actor/native loan retirement, complete Polyglot disposal, native-image execution and independent review remain separate acceptance gates.

### Final flush before physical graph reclamation (2026-10-31)

The prior finalization order destroyed shared-code images and GC-owned state **before** asking `PrintWriter.checkError()` whether the final host flush succeeded. A flush failure left the context strongly quarantined but with parts of its physical graph already reclaimed. The production `OresContext.closeWithExclusiveAdmission()` now invokes the package-local `FinalCloseStages.closeRemaining` controller **after** actor/native joins, auxiliary retirement, and async stdio drain. It runs final host stdout verification first, shared-code cleanup second, and garbage-collector cleanup last. Each successful stage records a once-only receipt; failed stages are retried on an explicit context close. No premature quarantine removal occurs after any of these stages fails.

`OresContextFinalCloseStagesTest` injects final stdout, shared-code, and garbage-collector failures and a fatal `AssertionError`, proving downstream reclamation never runs after an unsuccessful upstream stage, with exact retry counts. The package-local helper is the **same** controller used by the production `OresContext` close path; the tests are deterministic component-level fixtures, not complete Polyglot VM/native execution.

**Evidence:** isolated OpenJDK 21 harness `OreslangFinalCloseOfflineCheck.java` ran 10/10 checks on an extracted copy of the controller. This is **not** a JDK25 Maven/JUnit, GraalVM native-image, or complete-context run. Source-org checkout-free canary 41227935715 attempt 3 still failed with `runner_id=0` and zero steps. Alternate private checkout-free canaries `scintilla-run/scintilla-run-e2e` run 41217749367 attempt 4 and `zed-pkg-test/oreslang-private-pr-validation-20261012` run 41010014089 attempt 3 also failed with zero steps. Private-source transfer remains blocked by issue #962 and an empty reviewed destination allowlist; no private tree was mirrored.
