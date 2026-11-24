# AttoLang `std/log` — Java-backend native source increment

Implementation: `stdlib/log.atto`, entirely in AttoLang, using the flat file namespace `log` (not a runtime module).

## Contract

- Six emitted severity codes: TRACE=10, DEBUG=20, INFO=30, WARN=40, ERROR=50, FATAL=60. Threshold OFF=70 suppresses everything. Unknown severity and threshold codes fail closed.
- `log.LogEntry` contains immutable severity, target, message, explicit timestamp, actor ID, trace/span IDs, and **up to two** key/value attributes. Empty keys mean absent attributes. Strings are opaque; no implicit formatting, truncation, time reading, or export.
- `log.entry(...)` and `log.entry_with_fields(...)` construct records; `log.valid_entry(record)` validates numeric metadata. A timestamp of zero is unspecified; a positive timestamp is caller-supplied Unix epoch nanoseconds.
- `log.buffer(capacity, threshold)` creates an owner-local bounded queue. Capacity must be 1..4096 records; it is **not a byte quota**. Only the owning actor/supervisor may mutate it.
- `offer(record)` returns status 0=FILTERED, 1=ACCEPTED, 2=FULL, 3=CLOSED, 4=INVALID. It does not block or enqueue filtered/invalid records. Only FULL increments a saturating dropped counter.
- `peek()` returns the first unacknowledged record (fails on empty); `ack_one()` retires it **only after** a separate async sink positively accepts it. `close()` rejects new records while leaving pending records readable.
- When the queue reaches capacity after partial acknowledgment, `offer` compacts only the pending records into a new bounded array. This prevents permanent fullness with continuous producers; it is O(capacity) and may temporarily double record-slot storage during compaction. No automatic unbounded expansion.

## Proposed consumption pattern

The actor owning the buffer serializes producers and a **single** consumer:

1. Check `log.enabled(level, minimum)` before computing expensive messages.
2. Build an immutable `LogEntry`; pass it to `buffer.offer(entry)`.
3. An independently authorized async consumer calls `peek()`, submits a *complete* record to a bounded sink, **awaits positive acceptance**, then calls `ack_one()`.
4. Before shutdown, call `close()` to fence producers and continue the drain. Handle FULL/INVALID/CLOSED explicitly.

This stage deliberately **does not** provide `log.flush()`, a sink adapter, STDOUT or filesystem access, JSON serialization, timers, a new runtime thread, or task suspension. Do not confuse an ACK with durability: the consumer must define external delivery semantics. Crash after sink acceptance but before ACK may duplicate output; failed acceptance must never ACK. A sink must authenticate capabilities, apply escaping/redaction, enforce UTF-8 record/aggregate byte quotas and correctly handle cancellation and actor teardown.

## Verification

`node --test tests/log_module_contract.test.mjs tests/log_sink_contract.test.mjs` runs severity and sink-byte vectors through a fail-closed no-eval `tests/atto_pure_subset.mjs` expression parser, plus static queue/source checks. The parser rejects arbitrary Atto statements and host syntax. This is **SOURCE-ONLY** verification, not compilation or execution by the Atto guest, actor runtime, Graal Native Image, or another backend. Dedicated guest import/linking, negative ownership tests, byte quotas, async sink integration and AOT/CI receipts are prerequisites for a production-supported `std/log`.

Related but distinct work: Java async stdio PR #1344 and the pending perf/tracing modules. Neither is modified or silently assumed integrated here. The `std/log` namespace implementation stays owned by this backend; other compilers may implement the same public semantics with different .atto code.

## Namespace and async sink migration

The file begins with `namespace log;` and exports `pub fnc` and `pub define class` at file scope. It does **not** use `define module Log as`, nor the type-only `define namespace ... as` form. This is a deliberate migration to file-root namespaces; other backend compilers may implement the same public contract differently.

The separate `namespace log_sink;` adapter imports `log` using the explicit source file import. It validates a conservative single-line format and submits *one record* by awaiting the runtime's capability-checked `stdio.stdout.log_async(...)`. This uses the pending Java async-stdio work ([#1344](https://github.com/atto-lang/atto-source.java/pull/1344)). It **does not acknowledge** the buffer, guarantee durability, or borrow buffer state across `await`. Caller ACK follows successful write completion only. Any malformed line (control, delimiter or disallowed character) fails closed rather than escaping it automatically. It uses `String.codepoints()`, which creates a snapshot; a trusted ingress quota is still required before processing arbitrary huge payloads.

`log.sink_byte_budget_ok(queued_bytes, record_bytes, max_record_bytes, max_pending_bytes)` performs checked subtraction without integer overflow, but its numbers are **not authenticated byte measurements**. The privileged runtime must independently calculate actual UTF-8 byte sizes, enforce per-record and per-tenant aggregates, and make admission atomic.

## Native guest verification

`examples/log-namespace-guest-smoke.atto` exercises a file namespace import, filter, record factory, queue offer/peek/ack/close and byte-policy boundaries. The GitHub workflow now runs two independent jobs: a source-only Node contract and a GraalVM 25/Maven guest smoke, with exact source commit verification. A successful Node run is not guest proof. The separate async adapter is intentionally **not** invoked in the native smoke until dependency #1344 is integrated and compiled guest tests are added.

Current state: source-only local assertions are passing, while the exact-head GraalVM guest job and Native Image AOT have **no verified executed result**. Private code may not be mirrored into another org without independently authenticated source export authorization, even if alternate GitHub Actions minutes exist.

Local source-only reproduction after parser hardening: **13/13 log checks** and **5/5 sink checks**. The guest fixture now also covers filtered, invalid, full, dropped, compact-on-full FIFO, close-fence and ACK-retirement behavior once the Atto compiler actually runs it.
