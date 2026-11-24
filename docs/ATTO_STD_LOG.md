# AttoLang `std/log` on Java and GraalVM

Import the guest logging library with `import * as log from "atto.std/log";`.

## Contract

- Six emitted severity codes: TRACE=10, DEBUG=20, INFO=30, WARN=40, ERROR=50, FATAL=60. Threshold OFF=70 suppresses everything. Unknown severity and threshold codes fail closed.
- `log.LogEntry` contains immutable severity, target, message, explicit timestamp, actor ID, trace/span IDs, and **up to two** key/value attributes. Empty keys mean absent attributes. Strings are opaque; no implicit formatting, truncation, time reading, or export.
- `log.entry(...)` and `log.entry_with_fields(...)` construct records. Call `log.valid_entry(rt borrow record)` to validate metadata. A positive timestamp is caller-supplied Unix epoch nanoseconds. Zero leaves it unspecified.
- `log.buffer(capacity, threshold)` creates an owner-local bounded queue. Capacity must be 1..4096 records; it is **not a byte quota**. Only the owning actor/supervisor may mutate it.
- `offer(rt borrow record)` returns status 0=FILTERED, 1=ACCEPTED, 2=FULL, 3=CLOSED, 4=INVALID. Admission takes a snapshot of an accepted record and leaves ownership with the caller. Only FULL increments the saturating dropped counter.
- `peek()` returns an owned snapshot of the first unacknowledged record and fails on an empty buffer. Call `ack_one()` after the async sink successfully completes. `close()` fences production while pending records remain readable.
- When the queue reaches capacity after partial acknowledgment, `offer` compacts only the pending records into a new bounded array. This prevents permanent fullness with continuous producers; it is O(capacity) and may temporarily double record-slot storage during compaction. No automatic unbounded expansion.

## Consumption pattern

The actor owning the buffer serializes producers and a **single** consumer:

1. Check `log.enabled(level, minimum)` before computing expensive messages.
2. Build an immutable `LogEntry` and pass `rt borrow entry` to `buffer.offer`.
3. An independently authorized async consumer calls `peek()`, submits a *complete* record to a bounded sink, **awaits positive acceptance**, then calls `ack_one()`.
4. Before shutdown, call `close()` to fence producers and continue the drain. Handle FULL/INVALID/CLOSED explicitly.

The stdout sink confirms completion at the process output boundary. A crash between sink completion and acknowledgment can cause duplicate output.

## Verification

`examples/log-namespace-guest-smoke.atto` runs the filter, queue, acknowledgment and close behavior through the compiler. The Maven `PackagedAttoCliIT` test checks the same packaged namespace imports, writes a record across an await and rejects a multiline record. The Node source contracts cover additional filter and byte-budget boundaries.

## Async stdout adapter

Import `import * as sink from "atto.std/log_sink";` and call `await sink.write_record(buffer.peek())`. The adapter validates the record and awaits the capability-checked `stdio.stdout.log_async` operation. Control characters and format delimiters fail validation. Field validation uses `String.codepoints()`, which allocates a snapshot, so callers should bound input size at ingress. The stdout runtime measures UTF-8 bytes and enforces its pending byte limits.

`log.sink_byte_budget_ok(queued_bytes, record_bytes, max_record_bytes, max_pending_bytes)` checks limits on counts supplied by the caller. Use counts measured by the sink.
