# Async stdout logging integration (current-main candidate; 2026-10-31)

**Status: DRAFT, no executed native/JUnit/guest test receipt.** This is a source-local integration on Java main `241bd67c39443cc2ef18df7e4ed456454af378c7`, not a cherry-pick of the old `OresContext` from #537.

## Guest APIs and return values

- `stdio.stdout.log_async(values...)`, `stdio.stdout.logList_async(values)`, `stdio.stdout.println_async(value)`, and `stdio.println_async(values...)` format a complete immutable UTF-8 record synchronously on the calling guest task and enqueue it for a dedicated context worker.
- Each returns `Future<void>`. Awaiting confirms the host `PrintWriter` accepted/flushed the data without reporting an error; it does **not** prove ingestion by an external collector, fsync durability, or independent line ordering against other contexts.
- `await stdio.flush_async_logs()` returns a barrier for all records accepted before barrier admission. Concurrently accepted later records are outside its scope. The barrier does not consume or cancel queued records when its Future is dropped.
- All APIs require the existing `STDOUT` capability. Source ownership checking treats values as ephemeral read-only formatting borrows; only rendered `String` data enters the worker.
- Existing synchronous `stdio` APIs remain available for compatibility and can block guest actor carriers. The synchronous `stdio.stdout.log` path now sends one entire line to the PrintWriter per call, sharing the sink monitor with async writes, but cross-producer total order is not guaranteed.

## Capacity and failure contract

Each context has a queue of 1,024 records, an additional **8 MiB aggregate pending-byte limit counting the in-flight record**, maximum 65,536 UTF-8 bytes per rendered record, and at most 1,024 pending async flush barriers. Admission returns promptly or raises on a full queue, excess record size, writer closure, barrier exhaustion, or terminal sink failure. Queuing is in-memory, non-durable, and bounded by both record count and a per-context byte budget; **there is still no graph-wide or tenant-wide aggregate byte budget across contexts**. Source formatting may allocate before the size check; adversarial size-amplifying renderers remain a separate resource-analysis gate.

Writer I/O happens on a daemon platform thread. Accepted records are FIFO per context, but mixed synchronous writes are serialized only by the shared PrintWriter monitor. A sink failure marks the queue terminal and fails accepted record/barrier Futures. A lost Future does not revoke accepted data.

## Shutdown and quarantine

The current-main `OresContext.close()` retains the physical actor and native-I/O join gate and `QUARANTINED_CONTEXTS` strong-reference retention. After actor/native-I/O retirement and auxiliary VM close, the async writer stops accepting and drains accepted records, with a 5-second deadline. If physical retirement or the async drain fails, the context remains quarantined rather than clearing the graph or synchronously reacquiring a potentially blocked host stdout writer. The normal completed-drain path still flushes stdout before releasing the quarantine pin.

This cannot guarantee bounded shutdown when **unrelated** services or the external writer are non-cooperating; it is specifically a guard against an extra synchronous reflush after a known async drain timeout. Cancellation, close retries, in-flight root-task production, and callbacks during retirement still require executed adversarial tests.

## Gate matrix

| Gate | Required proof | Current state |
|---|---|---|
| CI admission | Real runner and nonempty job steps on head SHA/attempt | BLOCKED, zero-step |
| Focused host tests | `AsyncStdioLogsTest` queued writes, blocked writer, UTF-8 sizes, 8 MiB in-flight accounting, overflow, per-context sink error isolation, timeout/drain retry | AUTHORED, NOT EXECUTED |
| Guest tests | `StdioLogAndSpreadTest` detached log, await barrier, spread/list, permission-denied and direct-only | AUTHORED, NOT EXECUTED |
| Whole-context blocked writer | Polyglot `Context.close()` with actual blocked host `OutputStream`, timeout/exception chain, and no reflush deadlock | AUTHORED, NOT EXECUTED |
| Retirement | Actor generation/cancellation, failed native-I/O physical join, quarantine release and retry | NOT EXECUTED |
| Native | Exact-tree GraalVM guest and native AOT/hybrid builds, carrier liveness | NOT EXECUTED |
| OTel | Exact compiler `SOURCE_REF`, `oreslang-otel` smoke, int64, span lifecycle | NOT EXECUTED |
| Governance | Protected main + independent approval | OPEN |

Preserve backend, repository, head/tree, compiler/toolchain, job attempt and nonempty step ledger for all results. A source-free canary is **only runner admission**, not native or guest proof. Do not update `oreslang-otel/SOURCE_REF` or promote the async exporter until this runtime reaches an executed, independently reviewed exact head.

### Pending-byte admission note (2026-10-31)

The byte counter increases only after a record is accepted and decreases only after that record is processed, including its in-flight write; a dequeued but blocked writer still consumes quota. Failed admissions do not allocate a record or Future into the queue. The quota is **not** a measure of total process heap usage: pre-admission string rendering, Java object overhead, and other contexts remain additional allocations. Physical-isolate and actor scheduling proof requires executed native tests.

### Whole-context blocked-writer test (2026-10-31)

`StdioLogAndSpreadTest.blockedAsyncSinkCannotHangWholeContextClose` invokes an actual Polyglot guest async log against a latch-blocked host `OutputStream`. The test closes the Polyglot context on a separate thread, requires an explicit async-drain timeout failure rather than success or indefinite monitor reacquisition, and releases the test sink in `finally` to prevent a stuck CI worker. This exercises the real `OresContext.close()` path rather than only `flushOutputAfterAsyncDrain`.

This fixture has **not executed**; no guarantee of correct result or lifecycle behavior is claimed. It does not independently prove quarantine release or a successful retry through the Polyglot wrapper after failed disposal. Follow with a separate actor/native-I/O retirement and recovery test once a nonzero-step runner can build the exact head. The five-second drain deadline is not a deadline for unrelated context teardown stages.
