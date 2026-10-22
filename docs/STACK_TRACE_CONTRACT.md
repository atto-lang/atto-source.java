# Oreslang logical stack traces: contract and admission gates

Status: **design and targeted Java host-boundary hardening**, not full Java/LLVM guest parity.
`throw`, `raise`, `panic` and `recover` remain reserved in the production Java
parser; LLVM supports only a limited local synchronous throw experiment. Do not
present the examples below as captured Oreslang runtime output.

## Required trace model

An Oreslang failure has a stable **origin** (the first effect site), a bounded
ordered list of **logical source frames**, a typed `throw | raise | panic` effect
classification and zero or more **causal links**. Frame identity includes:
- logical code-unit ID, file path (redacted according to policy), line and column;
- function/method and concrete overload slot, plus code **generation**;
- origin/resume/await/spawn/actor-mail/supervisor-CONTROL boundary kind;
- actor identity and task ID only when safely authorized for the observer.

A rethrow preserves the original origin and appends a propagation marker, not
a fabricated new origin. `raise` is not a downgraded `throw`; `panic` is not
caught by ordinary `catch` or `trap`. All unwinding is tied to a logical
ExecutionContext, never the transient JVM thread or LLVM carrier stack.
Detaching tasks and sending between actors starts a **new** logical stack.
Cross-actor reporting carries bounded immutable diagnostic data, not references
to another actor's exception object or heap.

## Example output **targets**, not observed runtime output

### Ordinary throw

```text
error[E_THROW] NotFoundError: user 42 not found
  at users.lookup(id)             app/users.ores:41:9
  at api.load_user(id)            app/api.ores:88:16
  at main()                       app/main.ores:12:7
```

### Raise across an await

```text
error[E_RAISE] StorageUnavailable: shard 3 unavailable
  at store.fetch(shard)           app/store.ores:74:11
  at jobs.run()                   app/jobs.ores:38:18
  --- await jobs.run()            app/worker.ores:57:12
  at Worker.receive(mail)         app/worker.ores:57:12
  --- actor terminal failure      actor=Worker generation=7
  --- supervisor CONTROL event    supervisor=WorkerParent
```

### Panic with cleanup failure

```text
error[E_PANIC] InvariantViolation: free-list corrupted
  at Cache.remove(key)            app/cache.ores:114:13
  at CacheWorker.receive(mail)    app/cache_actor.ores:43:11
  --- actor terminal failure      actor=CacheWorker generation=4
  suppressed[E_THROW] CloseError: cleanup failed
    at CacheWorker.cleanup()      app/cache_actor.ores:23:9
  --- supervisor CONTROL event    supervisor=CacheSupervisor
```

No trace may imply that `raise` or `panic` is caught by regular
`catch`/`trap`. The supervisor is a new causal segment, not another stack
frame in the failing child's invocation.

## Existing Java source marker limitations

`runtime/SourceBoundaryTrace` currently attaches up to 64 suppressed,
stackless source-boundary markers to an original host Throwable. Tests verify
async `leaf -> middle -> main` and actor `receive -> main` names. It does
**not** yet provide full source file/line/column, causal actor segments, or a
single human-oriented Oreslang renderer. Raw JVM `printStackTrace()` is not
the public contract. Java host stack traces remain available only for
authorized diagnostic modes.

Hardening in this branch bounds and sanitizes metadata before attaching a
marker. Source labels cannot inject newline/ANSI/bidi control characters or
grow without limit; the original Throwable identity/message/cause stays intact.

## Source-location confidentiality

Host-boundary `SourceBoundaryTrace` code-unit labels are metadata supplied to a
diagnostic renderer, not trusted filesystem paths or credential-free URLs.
Absolute Unix/Windows paths are reduced to their final filename; URL
authorities (including userinfo), query strings and fragments are not printed.
Ordinary relative source/module names remain recognizable. All rendered
labels are still bounded and control-character sanitized. For code-unit
metadata, an input longer than **4,096 UTF-16 units** is rejected before
any path or URL parsing: it renders exactly `<source-id-too-long>`.
This guards the error-reporting path against expensive processing of giant
credential-bearing source identifiers. A label that is at most 4,096 units
can still be scanned, but its rendered code-unit component is capped at 160
UTF-16 units (including any ellipsis).

**Scope:** this redaction applies to source *marker labels*. It does not
rewrite the original Throwable message/cause, arbitrary user-created
exceptions, separate structured diagnostic outputs or externally emitted
logs. Consumers displaying exception messages must enforce their own
privacy policy. Runtime source-path metadata must never confer filesystem
authority or be used as a trusted source-map lookup key merely because a
label is sanitized.

## Critical diagnostic/classification invariant

The Java host `TrapFailurePolicy` has a 64-node limit on real Throwable
cause/suppressed graphs, while `SourceBoundaryTrace` can attach 64 stackless
diagnostic markers. Before this audit, a fully annotated ordinary failure
could exceed the 64-node classifier budget and become non-trappable solely
because tracing was enabled. Trusted `SourceBoundaryTrace` markers are now
excluded from that classifier budget. **All non-marker suppressed failures**
remain part of traversal, including cancellation/panic/fatal during cleanup.
The regression test verifies both sides of the invariant.

## Bounded diagnostic truncation behavior

Java source boundary capture retains a maximum of **64 suppressed entries**.
Once the 64th entry would be used by a source boundary, it is replaced with
the stackless notice `ores source trace truncated: one or more source boundaries omitted`.
At most 63 earlier source boundaries are preserved when no other suppressed
entries exist. The notice is intentionally conservative: it signals an omitted
boundary at the cap, not the exact omitted count. Existing non-marker
suppressed failures remain intact and retain their classification (panic,
cancellation, fatal, ordinary throw).

A source trace that does not reach the limit must not show a truncation notice.
The real Throwable remains the authoritative failure; source markers are
diagnostic-only and excluded from `TrapFailurePolicy`'s classification-node
budget.

## Host-fatal emergency path

Java source diagnostic capture is intentionally **best effort** for fatal JVM
failures. `SourceBoundaryTrace.record` returns the **same** `VirtualMachineError`,
`ThreadDeath`, or `LinkageError` without creating source markers or allocating
additional diagnostic strings. This avoids multiplying allocation and
stack-use failures during VM out-of-memory, stack overflow, or linkage faults.
Such failures are terminal host conditions, not ordinary `throw` values.

This emergency fast path does **not** suppress Oreslang guest panic source
markers, which remain typed and non-trappable. The compiler must never treat a
fatal host exception as an ordinary guest `throw` or convert it to `None`.

## Admission matrix

Before shipping common guest stack traces on both backends:
1. Exact same Oreslang fixture on Java/GraalVM and LLVM: caught throw, uncaught
   throw, trap-to-None, raise bypass, panic bypass; exact typed outcomes.
2. 1/2/64/>64 recursive frames; truncation count must be explicit; no overflow
   and no unbounded allocation under native hard-real-time profiles.
3. Repeated await/resume with carrier migration, nested callbacks and
   structured task joins; preserve source origin and logical caller order.
4. Actor message + supervisor event, full user mailbox, parent restart and
   hot-reload generations; no cross-actor heap pointers or fabricated callers.
5. LIFO defer/finally, suppressed cleanup failures, deterministic precedence;
   cancellation/VM-fatal never silently become ordinary throws.
6. Rethrow/raise conversion carries original provenance and records the new
   escalation site distinctly. No frame splicing across independent actors.
7. Missing debug info, optimized/inlined functions, AOT tree shaking,
   relative paths, source-map lookup: gracefully degrade without leaking
   private absolute paths, values, credentials, or other actors' state.
8. Build `compact`, `full`, `off` presentation modes with bounded work.
   Static no-alloc trace buffers for bare-metal/hard-RT and emergency panic
   paths; optional host-symbolication only outside the critical failure path.

The current tests only prove the limited marker contract; none of these
unimplemented gates should be reported as passing.
