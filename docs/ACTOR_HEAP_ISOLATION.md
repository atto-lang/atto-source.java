# OresVM actor heap isolation and GC contract

## Status (2026-10-10)

### Selected deployment topology: trusted AOT + one shared untrusted JIT isolate

**Do not create a Graal isolate for every actor.** The intended topology is
one trusted AOT-compiled OresVM host runtime for SHARED and PRIVATE/isoactors,
plus **one separately sandboxed Graal Engine / polyglot isolate for all
UNTRUSTED actors**. The untrusted engine is free to JIT-compile Oreslang
within its own isolated JIT and GC, while the trusted host may be an
AOT-only Native Image. The isolated engine may host multiple actor-group
Contexts; contexts sharing an Engine share that isolate's physical heap,
collector, JIT compiler, code cache and limits.

Untrusted actors inside that shared isolate each retain actor-state ownership,
logical quotas, their own cancellation/supervision epoch, and deterministic
native actor arenas *when native allocation is actually implemented*.
**Their tracing GC is NOT per-actor:** collecting untrusted A can affect other
untrusted actors B/C within the same Graal isolate, but does not invoke
trusted-host GC. CPU contention, shared-resource pressure, or host-mediated
calls may still affect latencies. Strict untrusted-tenant separation is a
different security/performance tier and requires an additional worker
process/isolate by explicit policy, not by default.

The group policy is distinct from the actor policy: `IsolatePolicy.untrustedActorGroup()`
sets a multi-hour **cumulative Context CPU-time budget**, while
`IsolatePolicy.untrustedActor()` retains its 250 ms actor-turn policy.
A long-running group MUST NOT be created from the 250 ms policy;
`SharedUntrustedIsolatePool` rejects that mistake. Groups need a
supervisor-controlled, generation-fenced rotation process before their
cumulative CPU budget expires. One group Context has
`sandbox.MaxThreads=1` and therefore serial guest entry; multiple group
Contexts can run within the same isolated Engine to achieve concurrency,
subject to hard aggregate memory/CPU bounds.

The host's AOT Native Image compilation is distinct from AOT-lowering
native Oreslang actor functions. Current Java/Graal host AOT build modes
exist, but full LLVM Oreslang AOT codegen for actors is still unfinished.
The untrusted isolate must have its own optimizing Truffle runtime and
Oreslang language-isolate artifact. No compilation-mode CLI switch alone
can turn an actor into a new physical isolate.

`SharedUntrustedIsolatePool` constructs one shared Engine and a bounded
number of untrusted group Contexts. This is **hosting infrastructure**, not
yet a working cross-engine actor router. Existing
`ActorRuntime.spawnUntrusted` inside the trusted host still runs on
its parent's JVM heap; production admission must reject that path until
a data-only supervisor-to-isolate actor dispatch adapter is integrated.

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
  |-- one UNTRUSTED Graal isolate (separate physical GC + JIT)
        |-- group context 1: untrusted actors A/B/C with logical heaps
        |-- group context 2: other untrusted actors with logical heaps
        +-- ONE isolate collector shared by all group contexts
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
- **UNTRUSTED actor**: allocate independently owned mutable actor state
  and enforce per-actor quotas *inside the single shared untrusted
  Graal isolate*. One isolate-level collector serves all untrusted actors.
  The trusted host never directly dereferences those actor heaps. Stronger
  hostile-tenant boundaries can request separate worker processes, but we
  do not launch an expensive Graal isolate per ordinary untrusted actor.
  Never claim `spawnUntrusted` in the bootstrap host is already routed
  through the shared isolate.

## Isolated source actor control plane (this PR)

`UntrustedJitCodeUnit` is a host-facing adapter that loads and compiles a
bounded Oreslang source unit inside a `SharedUntrustedIsolatePool.Group`'s
separately sandboxed Engine/Context. The host handles only finite data scalars,
never `ActorRef`, `ActorGraphArena.NodeId`, raw pointers, callback objects
or polyglot guest `Value` references. Guest failures are sanitized before
they cross the adapter. Exactly one Engine owns the group pool's VM-level
collector/JIT, while guest actors retain per-actor logical owned heaps.

The `OresEvalRootNode` guest evaluator has internal commands to spawn a
`define untrusted actor` declaration **inside that guest context** and
retain its source `ActorRef` in the guest's bounded, 1,024-entry actor table.
`UntrustedJitCodeUnit.RemoteUntrustedActor` holds only a local numeric actor
ID and code-unit UUID generation. It supports scalar-only `send`,
readiness/done polling, structured cancellation, and on-close cancel-all.
Messages still go through the guest `ActorRuntime` and per-actor mailbox
quotas; the trusted host never receives a guest object reference.

**Not yet production complete:** Native Graal Oreslang language-isolate
packaging and physical isolation/JIT instrumentation are still unverified.
The actor execution test is gated on
`ORES_TEST_SHARED_UNTRUSTED_ISOLATE=true`; no compiler-generated host
`spawn SandboxedWorker()` is yet automatically routed through this API.
Guest output streams, code-image hot reload, group context rotation and
per-actor hard-kill semantics still require additional protocol work.
A long-running guest call is constrained by group Context resource limits,
not independently hard preempted per mailbox invocation. Legacy host
`ActorRuntime.spawnCodeActor(UNTRUSTED,...)` remains a compatibility/testing
path and is **not** a substitute for the isolated deployment.

## Executable actor-owned cyclic graph prototype (this PR)

`ActorGraphArena` is now attached to every `ActorCell` and accessible
during a guest actor turn via `ActorContext.actorGraphArena()`.
The returned arena belongs to the actor's `ActorMemorySlice`, regardless
of PRIVATE, SHARED or UNTRUSTED kind. Payloads are bounded, copied byte
arrays backed by actor-confined direct memory. Edges hold generation-checked
`NodeId` values rather than owning Java references. Self/mutual cycles
can be represented without a tracing collector. Node deletion increments
its generation, invalidating stale handles after slot reuse. Actor
finalization retires the graph and revokes all outstanding handles,
then zeroes/invalidates the actor's owned memory blocks.

**Limits:** the graph's slots, edge sets, actor state and scheduler are
still host JVM objects, and `ByteBuffer.allocateDirect` relies on the
JVM for actual native allocation release. An actor's deterministic
logical retirement is not proof of deterministic physical `free()`.
The prototype stores *bytes*, not arbitrary source class instances,
does not lower Oreslang destructor/ownership semantics, and does not
remove the need for Java JVM GC or an opt-in tracing heap for strongly
owned guest objects.

`ActorRuntime.ActorHeapPlacement` standardizes
`ACTOR_ARENA`, `VM_ISOLATE` and `PROCESS_ISOLATE`.
`spawnWithHeapPlacement` enforces the requirement in the bootstrap
runtime: trusted actors may use `ACTOR_ARENA`, but an isolated
backend request or untrusted inline host-arena request fails closed.
The older `spawnUntrusted` API remains for tests/compatibility
and is **not** a production isolate-routing implementation.
`heapIdentityFor` exposes unique actor/arena identities for supervisor
verification, not guest memory pointers.

The LLVM C++ runtime companion, `ActorOwnedGraphHeap.hpp`, provides
an inline fixed-capacity storage proof: non-owning generational node
handles, deterministic byte zeroing and retirement at a verified
quiescent actor boundary, rejection of cross-actor heap access, bounded
nodes/edges and ABA-safe slot reuse. It still needs source actor-object
lowering, drop glue, and a scheduler-produced quiescence proof.

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

**Graal shared untrusted isolate**: build and publish one Oreslang
language-isolate artifact, select one explicitly sandboxed `Engine`
with `spawnIsolate(true)`, and reuse it for bounded untrusted group
`Context` instances. The host image can be AOT while the guest isolate
uses its own JIT. The new `SharedUntrustedIsolatePool` is the hosting
substrate, but a production actor router and physical-isolate tests are still
needed. Configure a hard pool-wide `engine.MaxIsolateMemory`, soft
context-specific resource limits, forbid Java interop, and exchange only
data-only values via a scope-limited protocol. An untrusted isolate's
GC can affect *other untrusted actors*, but it must not trigger GC of
the AOT trusted host. Engine sharing is deliberate, not a per-actor GC
guarantee.

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

- Spawn trusted/shared/isolated actors in the trusted AOT host and multiple
  untrusted actors in the single shared untrusted Engine. Verify physical
  collector identity is shared among untrusted actors but disjoint from
  the host, while per-actor ownership and quota identities stay distinct.
- Trigger untrusted group A collection while trusted host tasks and
  untrusted group B run; prove host GC counters are unaffected. Other
  untrusted groups may observe shared-isolate GC pauses; do not assert
  impossible intra-isolate GC independence. Benchmark AOT trusted p99
  latency under heavy untrusted JIT + GC activity.
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
