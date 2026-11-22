# OTel async stdio — current-main integration gate (2026-10-31)

## Provenance and scope

- Base Java commit: `241bd67c39443cc2ef18df7e4ed456454af378c7`.
- Candidate sources: draft Java PR #537 head `78aa824c75c0cddfb17dc1627158d1828e1bfac4`, and stacked safety PR #2349 head `e28a7beba666b41e261426d3827a45d1ae46307f`.
- Do **not** merge/rebase the old `OresContext.close()` wholesale: current main requires both actor and native-I/O physical retirement before releasing the VM, code image, or garbage-collector graph.
- This branch ports only the async stdout drain, bounded queue and flush barriers, core/stdout intrinsics and type/ownership recognition. It intentionally **does not** port the unrelated `defer` parser/evaluator features from Java PR #537, and does not claim general stdout is fully nonblocking.
- The writer only receives rendered immutable `String` records; callers enforce STDOUT capability before queue admission. Explicit `log_async` produces a move-only `Future<void>`; detached records drain during graceful context shutdown.

## Native I/O and shutdown invariants

1. On context close, **keep** bounded async stdout admission open while `joinActorsAndNativeIo` retires actor/native-I/O resources, then while `http.close()` and `asyncRuntime.close()` stop auxiliary producers. Actor STOP/cleanup may emit a final record before completion. No sink I/O occurs under the caller/actor admission path.
2. Preserve `joinActorsAndNativeIo` ordering and the physical retirement/quarantine fences from current main. If a mandatory physical join fails, seal new stdout admissions without waiting on the blocked sink; retain the context in quarantine and **do not** claim completed logging.
3. After successful physical actor/native-I/O retirement and auxiliary producer shutdown, seal async stdout admission and join the writer worker with a 5000 ms deadline. Even when zero async records were submitted, the worker is started on close to flush any synchronous stdout already buffered. A stalled final flush is an **incomplete** shutdown; the context stays quarantined.
4. Never call synchronous `PrintWriter.flush()` on the context-closing carrier, on either the success or failure path. The final flush and `checkError()` run only on the bounded drain worker. Late `flushAsync()` calls after admission sealing return failed Futures.
5. Only after successful physical joins, writer drain, and all auxiliary shutdown/GC cleanup may the context leave `QUARANTINED_CONTEXTS`.
6. The 1024-record queue, 65536-byte per-record cap, and 1024-pending-barrier cap reject excess callers. Flush barriers notify their registered observers in FIFO sequence order. Future cancellation cannot undo an already accepted output record; physical delivery is not crash-durable.
7. A stdout write or final-flush failure must fail pending completion futures and quarantine shutdown. The 5000 ms deadline bounds **waiting in `AsyncStdioLogs.close()`**, not all actor/native-I/O teardown operations, and cannot stop an uninterruptible host writer; the blocked writer daemon and context remain quarantined. No claim of hard real-time latency or guaranteed OS crash flushing.

## Test evidence: never conflate classes

| Gate | Command / observation | Classification |
| --- | --- | --- |
| Runner admission | `.github/workflows/otel-async-stdio-integration.yml` `admission` job; at least one executed step | A: scheduler/runner only |
| Exact head/tree | Head SHA, Git tree, workflow run + attempt, job id, nonempty steps | P: code provenance |
| JUnit source safety | `mvn -B -ntp '-Dtest=AsyncStdioLogsTest,StdioLogAndSpreadTest,ActorEndCleanupTest,FormalContextShutdownOrderModelCheckTest' test` using GraalVM/JDK 25 | H: host compilation + focused test |
| Current Java regression | `mvn -B -ntp -Dores.runtime.carriers=native verify` + native AOT/hybrid | J: Java runtime and native regression |
| OTel native guest | OTel PR #13 `tests/smoke.ores`, `tests/span-lifecycle.ores`, signed int64 boundary and JSONL checker pinned to **this actual compiler tree** | G: guest execution |
| Actor lifecycle | cancel during suspension, stale callback after retirement, generation fence and concurrent `finish` | S: ownership/scheduler safety |
| Backend parity | Independent conformance on LLVM, .NET NativeAOT and Wasmtime | B: separate backends |
| Governance | Protected `main`, required executed checks, independent approval | M: merge gate |

The focused tests now include a real actor STOP cleanup log and a deterministic delayed-producer/shutdown-seal boundary, but neither substitutes for a complete Truffle context-shutdown or actor-generation race proof. No local or hosted test receipt is claimed by the source patch alone. Historical OTel `steps=null` jobs are runner-admission failures, never green tests. GitHub org billing/runner eligibility must be checked by an administrator if new source-free canary also has zero steps.

## Exact-source isolated Java execution — limited evidence (2026-10-31)

This evidence is deliberately **not** the pinned, full Oreslang runtime/JUnit/native receipt.

- A local JDK 21 `javac` compiled the **byte-identical** `AsyncStdioLogs.java` source using a minimal `OresFuture` test double, because the local environment lacks Maven and the required GraalVM/JDK 25 installation.
- **New patched source blob:** `0c65fa7795a05682d1d7db616ad6c1654535c354` (verified against GitHub's returned content SHA after patch).
- **Behavior harness:** 15/15 checks passed, covering bounded records and barriers, FIFO output/flush, blocked-writer nonblocking seal, error reporting, 5000 ms shutdown deadline and cleanup record preservation.
- The old unmodified source blob `da0dff18320254b9b86bc8b4c7984e8a85b66f88` failed the 64-barrier registration-order assertion (exit 1). The patched byte-identical blob passed it (exit 0).
- The production JUnit test `flushBarrierSubscribersReceiveFifoNotifications` registers 64 `OresFuture.whenCompleteRuntime` observers while stdout is blocked and asserts FIFO callback order after release. It is **authored but not yet executed with the real OresFuture implementation**.
- **Latest GitHub exact-head CI:** head `a82f713cfac02de4cd37505458e7faaa61ea5ae6`, Git tree `8b820ce292df63304da3898658eebabda1980975`; workflow run [41218819134](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41218819134), attempt **1**: `focused-java` job **124588900884**, `admission` job **124588901171** both failed before any step (`steps=null`). No compiler, JUnit, guest or native proof from Actions.
- The harness stub does **not** prove OresFuture completion/cancellation semantics, actor scheduler safety, GraalVM compilation, JVM/Native Image compatibility, nor telemetry export durability. All must be checked on the real compiler and source tree.

## 2026-10-31 bounded final-flush hardening — source only

- Java source change: `AsyncStdioLogs.close()` starts the daemon worker even with zero queued async records. That worker performs the final host `PrintWriter.flush()` and error check before returning; the closing actor/carrier thread performs only a **bounded join**. `OresContext.close()` no longer synchronously re-flushes the host writer. The old successful-drain final-flush helper was retired.
- Seal semantics: `flushAsync()` now fails closed after shutdown admission has been sealed, even when there are no pending records; accepted barriers retain their original FIFO semantics.
- Focused new regression cases: `synchronousOnlyOutputStillGetsFinalFlushFromDrainWorker`, `blockedSynchronousOnlyFinalFlushIsBounded`, and `sealedWriterRejectsNewFlushBarrierEvenWhenNoRecordsArePending`. Existing blocked-write timeout and actor cleanup tests remain.
- Exact source head before this documentation update: `f49f073dd101dd1440f95d78759e5261878d2483` / tree `d6ac72e888d38ac119f7ce2543a962365dce02ea`. [Run 41227129159](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41227129159), attempt **1**: `focused-java` job **124613515323**, `admission` job **124613515652**, both `steps=null`. The new Java source/tests have **not** run under real JUnit or GraalVM. The earlier isolated-JDK21 evidence above applies to the **previous logger blob only** and is **not** proof of this final-flush patch.
- Open concerns: full Truffle/actor shutdown, VM native I/O, JDK 25 compilation, callback/generation fences, and Native Image remain unverified. Do not promote PR or claim completed merge gates.

## Dependency order

1. Execute the source-free Java/OTel canaries. Do **not** mirror private source into another organization without explicit authorization.
2. Compile and run this exact branch with JUnit, actor retirement, blocked-writer and native AOT/hybrid evidence. Fix failures locally on this branch.
3. After the Java source path is verified, re-pin OTel `SOURCE_REF` on a separate OTel draft branch and execute `tests/smoke.ores` and `tests/span-lifecycle.ores`.
4. Validate raw JSON member duplication, escaped strings, int64, frozen span duration, identity/correlation and late completion.
5. Request independent approval and enable branch protections before any merge: Java integration first, OTel validator PR #13 second, OTel exporter PR #5 last.

**Current state:** integration source and tests are authored, but executable compiler and runner receipts, actor-generation proof, cross-backend parity, security approvals and merge gates are unverified.
