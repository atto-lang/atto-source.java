# OresVM actor heap isolation and GC contract

## Status (2026-10-10)

**Do not claim actors have independent physical GC heaps in the Java
bootstrap runtime.** `shared actor`, `isoactor` / PRIVATE, and `untrusted
actor` are separate logical actor ownership domains, NOT separate Java
heap/collector domains. All are executed by one `ActorRuntime` inside the
parent Oreslang Truffle context. Actor dispatcher pools and logical actor
memory budgets do not change that.

Existing `ActorMemorySlice` is a quota + actor-checked direct-memory region
for PRIVATE, SHARED, and UNTRUSTED actors. `ByteBuffer.allocateDirect` stores data
outside the ordinary Java heap, but the wrapper, actor state, continuations,
mailbox queues and code evaluator remain Java objects. Closing a slice
invalidates the block, but physical native-buffer release can still depend on
the JVM Cleaner/GC. Do not call it a completely deterministic native heap.

SHARED actors now also have a separately owner-checked direct-memory slice
for their mutable state, but may opt into runtime-global `Shared<T>` and
`SyncCell<T>` capabilities.
Those are accounted in a shared runtime budget and need a separate, explicit
lifetime/ownership policy in a native implementation. They do NOT entitle
the actor to dereference arbitrary other actors' heaps.

An **adversarial polyglot context** configured by `IsolatePolicy` can be
backed by a dedicated Graal VM isolate; that is a context/engine boundary,
not a guarantee that every actor spawned *within that one context* gets its
own physical heap. On Graal polyglot isolates, multiple contexts sharing an
engine also share its isolated heap. Native isolate language artifacts,
resource limits, actual engine ownership, and cross-context transfer must
be demonstrated before making a per-actor claim.

## Truthful, enforced behavior today

1. `actor.gc()` processes registered actor-local **host cleanup** entries
   with a bounded scan and never explicitly invokes `System.gc()`.
   It does **not** reclaim unreachable Oreslang guest object cycles or
   prevent unrelated JVM GC pauses.
2. `process.gc()` remains a supervisor-only, capability-gated hint that may
   request JVM process collection; an actor cannot request it even if it has
   `GC_CONTROL`. The source capability checker and host runtime both reject
   actor-originated requests.
3. `ActorRuntime.heapStatusFor(ref)` reports independent physical heap
   collection as **false** for all three actor kinds on this backend.
   `requireIndependentGcHeap(ref)` fails closed. Neither a dispatcher,
   a logical quota, a different actor policy, nor an off-heap ByteBuffer can
   satisfy the independent-GC guarantee.
4. Java tracing GC remains free to run automatically on the shared host
   heap; there is no hard pause-isolation/SLA for any actor from another
   actor's Java allocations.
5. Any external process/isolated-engine adapter must report provenance of
   its verified physical heap/collector boundary; it cannot toggle a boolean
   based on an actor's source declaration.

## Required native OresVM heap layout

```text
host / supervisor heap and control plane
  |-- immutable shared code image / read-only mappings
  |-- shared immutable payload pool and explicit SyncCell capabilities
  |
  |-- actor A heap: unique-owned native allocations / arenas
  |                  optional separately traced MANAGED subheap
  |-- actor B heap: unique-owned native allocations / arenas
  |                  optional separately traced MANAGED subheap
  |-- actor C heap: unique-owned native allocations / arenas
                     optional separately traced MANAGED subheap
```

Actor heap identity must consist of (runtime/isolate, actor id, generation),
not the worker thread ID. Source actor code cannot forge it or install a
separate heap by toggling annotations. Heap leases must survive preemption,
`await`, select, suspended continuations and hot-loading until all dependent
work is completed or generation-fenced.

- **PRIVATE / isoactor**: one actor owns the entire mutable heap. Default
  unique ownership and arena allocation need no tracing GC. If managed
  allocations are used, their collector traces **only this actor's managed
  region**. Immutable code can be shared read-only, not mutable data.
- **SHARED actor**: has its own actor-state heap just like PRIVATE actors.
  An explicitly separate shared region holds shared data with strict type,
  synchronization, ownership, and memory quotas. Per-actor GC must not
  traverse the shared pool as though it owned its allocations. Use handle IDs,
  immutable snapshots and scoped borrows or a separate globally coordinated
  shared-region collector where required.
- **UNTRUSTED actor**: gets a distinct authenticated heap and hard quotas;
  avoid same-process untrusted native code without a validated sandbox.
  Use one isolated VM/engine per actor for per-actor collector independence;
  stronger hostile-tenant boundaries require a separate worker process with
  OS memory/address-space limits and capability-restricted IPC. Never claim
  `spawnUntrusted` on a shared JVM alone provides hostile-code memory
  isolation.

## GC roots, safety and correctness

Each independent collector must observe every strong root from its own
actor-state fields, mailbox messages, outbound send leases, active and
suspended frames, future waiters/wakers, dynamically registered select
plans, supervisor-owned hot-reload generations, FFI/native handles, and
outstanding async I/O buffers. A native caller holding a loan or pinned
buffer is a root until the lease ends. Unreachable cycles are traced only in
the explicitly managed region; unique ownership and non-owning graph arena
edges are reclaimed without tracing.

Cross-heap *strong* object pointers are disallowed by default. Cross-actor
messages are serialized/copied or move-owned through a fenced transfer that
completes before the previous heap drops its responsibility. Shared immutable
memory has distinct global ownership and must not retain actor-local mutable
objects. Never have two independent tracing collectors each believe they own
the same object. Where two heaps can retain each other through host/guest
interop, require scoped handles to avoid cross-collector reference cycles.

Actor termination must first close admission; fence and drain future/IO
completions and live leases; finish finalizers/drop exactly once; revoke weak
and generational references; then retire the heap. Forced cancellation must
not free live memory or resume stale continuations. Untrusted out-of-memory
must terminate/restart the actor or isolate, not trigger a process-wide GC
or corrupt other actors. For actor reentry with concurrency >1, collection
needs a per-actor safepoint/epoch barrier across all active handlers.

GC stats must distinguish `host_cleanup`, `native_owned_drop`,
`native_arena_retire`, `actor_managed_trace`, and
`supervisor_process_gc`. Report memory reserved, committed, collectible,
last GC pause, bytes reclaimed, true collector identity, and owning actor.
Do not report an actor-local *host cleanup sweep* as guest-heap collection.

## Backend admission strategy

**Java/Graal bootstrap**: keep `heapStatusFor` truthful, forbid actor-wide
JVM collection, enforce actor isolation/quotas, and do not promise independent
JVM pause behavior.

**Graal dedicated isolate**: build and publish an Oreslang language-isolate
artifact, select distinct Engines per actor (not one Engine per tenant if
per-actor isolation is promised), configure max isolate memory/CPU,
bridge messages through data-only scoped handles, and test that forced
collection of actor A leaves the host and actor B heaps' counters/liveness
unchanged. GraalVM's documented `engine.SpawnIsolate` capability is not
proof this codebase's actor dispatch currently uses it.

**LLVM OresVM**: lower owner identity, deterministic drop, bounded native
arena/region allocation, weak generational handles and (where explicitly
chosen) actor-local tracing heaps. Ensure root maps and write barriers
for native execution, add memory poisoning/ASAN checks, and test pause
independence. Native GC is not implemented in the current LLVM compiler.

**Process-backed**: strongly recommended as the default for hostile tenants.
One worker process per untrusted actor or security boundary; enforce OS-level
CPU/memory limits, filtered I/O, explicit IPC, reliable teardown and fencing.
A process crash or memory exhaustion must not corrupt the supervisor.

## Mandatory end-to-end proof before claiming success

- Spawn all three actor types and inspect actual heap/collector IDs; verify
  distinct collector domains when the strict policy is selected.
- Trigger actor A tracing collection under ongoing actor B/host allocations;
  observe B/host GC counters, wall-clock latency and GC logs before claiming
  independence. No benchmark can eliminate the possibility of shared CPU
  contention, but it must establish that B/host **collector activity** was
  not requested by actor A.
- Verify all heap roots across `await`, `select`, cancellation, mailbox
  backpressure, hot upgrades, FFI and actor termination.
- Validate cyclic weak/strong graph handling, generation-checked ABA,
  nested shared capabilities, OOM, poisoned heaps, resource cleanup retry,
  double-drop, use-after-free and cross-actor escape rejection.
- Gate production deployment and merge on real CI/tests in both Java and
  LLVM backends; never treat GitHub Actions zero-step runner failures as
  passing (or source-test) results.

Related: `docs/RUNTIME.md`, `docs/CIRCULAR_REFERENCES_AND_GC.md` (PR #557),
PRs #457, #468 and #474, and the separate LLVM roadmap.
