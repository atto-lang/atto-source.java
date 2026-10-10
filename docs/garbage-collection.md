# Hybrid ownership-first memory management (Oreslang / OresVM)

## Contract, not a zero-GC claim

Oreslang's target is **deterministic memory lifetime first**: compile-time ownership,
moves, borrows, immutable sharing and deterministic drop for ordinary guest values.
Runtime tracing/weak-reference cleanup is a narrow fallback for host interop,
reflection metadata, uncaught reference cycles and legacy/unmanaged allocations.

**Backend distinction:** Java/Truffle/Graal currently keeps ordinary Java objects
on a JVM/Native-Image managed heap, whose collector can still run automatically.
A correct borrow checker does **not** turn that heap into Rust's allocator.
The separate LLVM compiler currently implements only a restricted i64 subset;
its fixed native POD arena is experimental. Neither backend has demonstrated
general Oreslang allocations with zero automatic tracing GC.

## Desired value lifecycle

| Value/domain | Primary release mechanism | Secondary reclamation |
| --- | --- | --- |
| Lexical owned native value | Compiler-proven last use / ordered drop | None if acyclic and fully lowered |
| Borrowed value | Never frees storage; expires before owner | Rejected statically on escape |
| Taken/moved value | Exactly one new owner, original invalidated | None for supported native values |
| Copied value | Independent allocation in receiver domain | Backend-specific |
| Immutable shared value | Shared immutable/explicit reference ownership | Weak/cycle fallback if enabled |
| Actor-local private/shared region | Quiescent domain retirement after all outstanding leases | Host cleanup only for unmanaged wrappers |
| Explicit runtime shared region | Shared-object lifetime, NOT owner actor exit | Bounded fallback for orphaned objects |
| Untrusted isolate | Revoke capabilities, cancel and join, then destroy independent heap | Isolate-specific collector if necessary |
| Host/FFI handle | Explicit idempotent close/drop and retry | Weak-owner/retired-domain cleanup registry |

A value that may have cyclic strong ownership needs one of: rejected cycle,
explicit weak edge, explicit break/drop, independently owned reference counting
with cycle management, or an opt-in tracer for that *specific* allocation domain.
No safe implementation can simply ignore reachable cycles or free all cyclic
nodes without checking outstanding references.

## Actor and async lifetime barriers

The semantic ActorId/domain, not the physical carrier thread, determines ownership.
A carrier can change at `await`. Suspended continuations, registered wakers,
mailbox in-flight messages, native buffers, pinned addresses, callbacks and active
borrow/FFI leases remain roots until their cancellation and acknowledgement
handshake completes. Retirement must require quiescence and generation fencing:
an old callback may **never** revive memory from a retired epoch.

Only one native carrier may execute a particular actor's code at a time; multiple
suspended message lifetimes do not confer permission to free their referenced data.
A strict native no-GC region must forbid unsupported FFI/host allocation and reject
unproven heap escapes **at compile/admission time**. It cannot be inferred from
source syntax alone.

## Runtime fallback registry

`RuntimeGarbageCollector.track(owner, cleanup)` registers an idempotent
host/interop cleanup hook. The owner is weakly referenced; **do not** strongly
capture it in `cleanup`. The resulting `CleanupHandle.close()` is the primary
deterministic drop path. Failed drops remain logically retired and are retried,
even if a stale Java reference keeps the owner reachable. Completed cleanup
releases its closure so a retained handle does not pin captured host resources.

The context registry and actor registries are bounded. The current JVM collector
is independent of these limits and may still run automatically.

## Collection control and maintenance budgets

- `actor.gc()`: actor-execution-domain cleanup only; no `System.gc()` call.
  One call examines no more than its configured 256 live entries by default.
- Actor exit: at most **32** hook attempts synchronously (or fewer if the actor
  quantum is configured smaller). Retired remainders are processed later.
- Periodic maintenance: at most **1,024** inspected entries per invocation;
  bounded retry, reference-queue and retired-domain shares avoid starvation.
  No automatic request for global JVM GC is made.
- `process.gc()`: capability-gated to supervisor/root by the runtime, with
  **4,096** fallback-registry entries per invocation and a per-context
  minimum five-second interval between requested JVM collections.
  This method is an explicit process-wide *hint* on JVM backends, not a
  physical ActorGroup-local collection and not a guarantee that heap is free.
- Periodic scheduling uses a shared daemon sweeper, not a sleeper per context.
  Stale queue notifications and hole scans are themselves bounded so registry
  churn cannot silently defeat maintenance budgets.

The budgets limit inspected work **counts**, not execution time: arbitrary host
cleanup callbacks can block. Use nonblocking, bounded, cancellable cleanup
operations and route slower finalization to an out-of-band resource manager.
Never run an untrusted finalizer on an actor's hot execution lane.

## Validation / release gates

Before describing a production configuration as `no-gc`, require evidence of:
(1) compiler-checked ownership and deterministic drops on all control-flow paths;
(2) allocator placement of all ordinary guest objects outside tracing heaps;
(3) rejection or explicit cycle management; (4) async/actor/FFI quiescence;
(5) bounded allocator and destructor latency under pressure; (6) zero GC events
from instrumented long-running workloads; (7) parity across LLVM and Graal
or a backend-specific capability refusal. Use allocation/collection histograms,
actor tail latency (p99/p99.9), bytes left behind per actor, failed cleanup retry
counts and shutdown leaks as acceptance criteria.

A suggested engineering performance target is >=99.9% of ordinary guest-value
lifetimes resolved without runtime tracing, **not a currently measured result**.

## Shutdown

Context shutdown retires actors/regions after outstanding continuations and
resource leases quiesce, cancels the periodic sweep and attempts remaining
idempotent cleanup hooks. Cleanup failures must be surfaced via telemetry;
best-effort fallback does not replace explicit durable-resource close APIs.
