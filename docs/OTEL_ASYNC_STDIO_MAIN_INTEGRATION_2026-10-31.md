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
3. After successful physical actor/native-I/O retirement and auxiliary producer shutdown, seal async stdout admission and join the worker with a 5000 ms deadline; a stalled writer is an **incomplete** shutdown and the context stays quarantined.
4. Never call synchronous `PrintWriter.flush()` after a failed bounded drain or incomplete physical retirement: it can block forever on the drain worker's writer monitor.
5. Only after successful physical joins, writer drain, and all auxiliary shutdown/GC cleanup may the context leave `QUARANTINED_CONTEXTS`.
6. The 1024-record queue, 65536-byte per-record cap, and 1024-pending-barrier cap reject excess callers. Future cancellation cannot undo an already accepted output record; physical delivery is not crash-durable.
7. A stdout write failure must fail pending completion futures and quarantine shutdown. No claim of hard real-time latency or guaranteed OS crash flushing.

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

## Dependency order

1. Execute the source-free Java/OTel canaries. Do **not** mirror private source into another organization without explicit authorization.
2. Compile and run this exact branch with JUnit, actor retirement, blocked-writer and native AOT/hybrid evidence. Fix failures locally on this branch.
3. After the Java source path is verified, re-pin OTel `SOURCE_REF` on a separate OTel draft branch and execute `tests/smoke.ores` and `tests/span-lifecycle.ores`.
4. Validate raw JSON member duplication, escaped strings, int64, frozen span duration, identity/correlation and late completion.
5. Request independent approval and enable branch protections before any merge: Java integration first, OTel validator PR #13 second, OTel exporter PR #5 last.

**Current state:** integration source and tests are authored, but executable compiler and runner receipts, actor-generation proof, cross-backend parity, security approvals and merge gates are unverified.
