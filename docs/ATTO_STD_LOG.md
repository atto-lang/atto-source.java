# AttoLang `std/log` — Java-backend native source increment

Implementation: `stdlib/log.atto`, entirely in AttoLang, in module `Log`.

## Contract

- Six emitted severity codes: TRACE=10, DEBUG=20, INFO=30, WARN=40, ERROR=50, FATAL=60. Threshold OFF=70 suppresses everything. Unknown severity and threshold codes fail closed.
- `LogEntry` contains immutable severity, target, message, explicit timestamp, actor ID, trace/span IDs, and **up to two** key/value attributes. Empty keys mean absent attributes. Strings are opaque; no implicit formatting, truncation, time reading, or export.
- `Log.entry(...)` and `Log.entry_with_fields(...)` construct records; `Log.valid_entry(record)` validates numeric metadata. A timestamp of zero is unspecified; a positive timestamp is caller-supplied Unix epoch nanoseconds.
- `Log.buffer(capacity, threshold)` creates an owner-local bounded queue. Capacity must be 1..4096 records; it is **not a byte quota**. Only the owning actor/supervisor may mutate it.
- `offer(record)` returns status 0=FILTERED, 1=ACCEPTED, 2=FULL, 3=CLOSED, 4=INVALID. It does not block or enqueue filtered/invalid records. Only FULL increments a saturating dropped counter.
- `peek()` returns the first unacknowledged record (fails on empty); `ack_one()` retires it **only after** a separate async sink positively accepts it. `close()` rejects new records while leaving pending records readable.
- For memory safety, acknowledged prefix slots remain allocated until the buffer is completely drained. This can cause temporary FULL drops before the final ACK even if there are fewer unacknowledged entries than capacity. No unbounded array growth or implicit compaction is permitted.

## Proposed consumption pattern

The actor owning the buffer serializes producers and a **single** consumer:

1. Check `Log.enabled(level, minimum)` before computing expensive messages.
2. Build an immutable `LogEntry`; pass it to `buffer.offer(entry)`.
3. An independently authorized async consumer calls `peek()`, submits a *complete* record to a bounded sink, **awaits positive acceptance**, then calls `ack_one()`.
4. Before shutdown, call `close()` to fence producers and continue the drain. Handle FULL/INVALID/CLOSED explicitly.

This stage deliberately **does not** provide `Log.flush()`, a sink adapter, STDOUT or filesystem access, JSON serialization, timers, a new runtime thread, or task suspension. Do not confuse an ACK with durability: the consumer must define external delivery semantics. Crash after sink acceptance but before ACK may duplicate output; failed acceptance must never ACK. A sink must authenticate capabilities, apply escaping/redaction, enforce UTF-8 record/aggregate byte quotas and correctly handle cancellation and actor teardown.

## Verification

`node --test tests/log_module_contract.test.mjs` runs source-extracted severity vectors and static queue/source checks. This is **SOURCE-ONLY** verification, not compilation or execution by the Atto guest, actor runtime, Graal Native Image, or another backend. Dedicated guest import/linking, negative ownership tests, byte quotas, async sink integration and AOT/CI receipts are prerequisites for a production-supported `std/log`.

Related but distinct work: Java async stdio PR #1344 and the pending perf/tracing modules. Neither is modified or silently assumed integrated here. The `std/log` module stays owned by this backend; other compilers may implement the same public semantics with different .atto code.
