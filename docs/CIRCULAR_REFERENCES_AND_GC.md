# Circular references, ownership cycles, and GC: audited contract

The terms **reference cycle**, **ownership cycle**, and **unreachable garbage**
are different. Oreslang permits recursive type descriptions and may eventually
permit cyclic object graphs, but ordinary owned values must not silently gain
shared lifetime or lose move/borrow guarantees.

## Current shipped Java/Graal behavior (main)

- `OwnershipChecker` enforces move-only class/collection values and lexical
  borrows. Borrowed references cannot be stored as class/actor fields; this
  alone is **not** a proof that every possible runtime graph is acyclic.
- `ActorRuntime.freeze` checks and rejects cyclic message graphs. It permits
  repeated acyclic subgraphs; aliasing in the input graph is not itself a cycle.
  Actor transport cycle detection is *admission validation*, not heap GC.
- `RuntimeGarbageCollector` tracks **host/interop cleanup hooks** using Java
  `WeakReference`/`ReferenceQueue`, supports explicit handle close and actor
  domain retirement, and schedules periodic housekeeping. Its periodic pass
  does **not** mark/sweep Oreslang object graphs. `process.gc()` may request a
  throttled JVM-wide collection, not a deterministic actor-local heap sweep;
  `actor.gc()` is scoped cleanup processing, not a local tracing collection.
- The underlying JVM remains free to run tracing GC at any time. Java/Graal
  does **not** currently offer a hard no-GC guarantee, even if guest code
  follows unique ownership.
- The LLVM source compiler does not yet implement a full native guest heap,
  destructors, ownership lowering, actor-local tracing collector, or guest
  cyclic graph values. Do not present its fixed arena prototype as a
  complete ownership or GC implementation.

## Adopt three different ownership/lifetime regimes

1. **Default, deterministic**: a single owner, with `&T` / `&mut T` lexical
   borrows. Enforce one live owner, no move while borrowed, and no escaping
   borrowed field or suspended borrow. Destructors/drop of ordinary owned
   native values are deterministic. No tracing GC necessary.
2. **Explicit arena/region graph**: an arena owns all nodes. Edges use
   non-owning generational handles (arena identity, slot, generation), not
   strong owning pointers. Cycles and self-links are legitimate. Removal and
   arena close invalidate handles, deterministically release contained native
   values, and require neither tracing nor recursive graph destruction.
   Restrict payload types so they cannot own their own arena or escape via
   raw pointers; otherwise an arena can still participate in an *external*
   ownership cycle. Never assume the managed Java arena gives native
   deterministic reclamation.
3. **Explicit traced-managed graph**: if an API permits multiple arbitrary
   strong edges, it needs a cycle-capable tracing collector, explicit cycle
   breaking, or a language-enforced acyclic strong-edge invariant.
   Plain reference counting does not reclaim unreachable strong cycles.
   Prefer weak references for parent/backlinks and observers. Do not make
   this mode an implicit default for all class values.

Do **not** force every reference graph to be a DAG: syntax trees, state
machines, graphs, and actor protocol metadata have legitimate cycles. Instead
make the **ownership** graph acyclic or anchored to one region. A type such
as `Option<Node>` can be recursive without any runtime instance cycle.

## Distinct experimental pull requests (not merged on main)

- PR #457 develops guarded recursive type aliases and static ownership/
  transport checks. Recursive type syntax is separate from graph identity.
- PR #468 develops actor-local `Ref<T>` / `WeakRef<T>`, including strong
  cyclic cells. Its documented *current* cycle reclamation depends on JVM
  tracing, not a future native Rust-like RC collector. This implementation
  must remain an explicit **managed** mode until native semantics are chosen.
- PR #474 develops a bounded Java `GenerationalGraphArena<T>` helper with
  non-owning generation-checked edges. It is not yet source-level Oreslang
  syntax, not off-heap, and not a zero-GC LLVM implementation.

Do not silently change the semantics of `Ref<T>` between Java and LLVM.
Either keep `Ref<T>` as explicitly traced across backends, or split its
type from a nontracing arena/strong-counted handle with explicit diagnostics.

## JVM/Graal private actor memory is not automatically a GC-free heap

The current `ActorRuntime.ActorMemorySlice` enforces an ActorId-scoped logical
memory budget. Its `PrivateMemoryBlock` uses `ByteBuffer.allocateDirect` for
native backing bytes but stores the wrapper and remaining guest state as
ordinary Java objects. Closing the block **zeroes bytes and revokes logical
access**, but clearing the ByteBuffer Java reference is **not** proof that the
physical direct allocation was freed immediately; its host Cleaner may be
involved. Per-actor confinement therefore does not imply an independent
physical Java heap or absence of automatic JVM GC.

A practical future Graal implementation can lower *eligible fixed-layout
Oreslang guest objects* into an explicit foreign/native memory arena owned
by a private/isolated actor. Use checked offsets + generations rather than
raw JVM references. Destructors must run after all mailbox turns, suspended
continuations, callbacks, wakers and FFI/pinned leases quiesce. Only then
should the allocator bulk free/revoke the region. This allows truly
deterministic guest-data release **within a Java/Graal process** while its
JVM control-plane allocations remain managed. A separate Native Image
isolate may furnish a separate managed Java heap, but that heap still GC's;
a confined direct-memory slice alone also does not isolate untrusted native
code against its host process.

The JVM OresVM bootstrap now rejects `ores.runtime.gc.mode=strict-no-gc`,
`zero-gc` or `manual-only` until actual source-object lowering and physical
release exist. The `ActorHeapAdmission` proof is a future backend capability
gate, not a declaration that such lowering has already shipped. A strict
mode must fail closed on unproved ordinary source allocations, escape to
the host heap, FFI edge, resurrection or async lifetime.

## Cross-heap cycle hazard with Graal polyglot isolates

A dedicated GraalVM polyglot isolate has its own **managed** guest heap and
collector, separate from the host. This is useful actor/tenant isolation but
does not remove GC. Neither collector can automatically prove that a
host-to-guest-to-host strong reference cycle is dead: host callback objects,
`Value`/polyglot proxies and leaked handles can keep one another alive.
Avoid unscoped host references; scope callback access to a call, use
generation-fenced, explicitly closed leases and reject long-lived guest
retention of host callback objects. The supervisor must close the isolate and
invalidate all proxies **after** cancel/acknowledge of outstanding turns,
callbacks and foreign memory accesses. Do not use isolate close as a workaround
for leaked active capabilities or as a real-time pause guarantee.

Native guest arenas within Graal can instead use explicit non-owning handles
with actor/arena ownership, while the control plane remains subject to JVM GC.
A standalone process is still the stronger boundary for arbitrarily
untrusted native code. See Graal's polyglot isolate/sandbox reference manual.

## Portable native collector contract (future work)

Only managed graph allocations go into a cycle-collecting heap. Scope that
heap to the smallest *physical* ownership domain that can prove all roots;
an actor's logical accounting domain inside one shared JVM heap is not an
independent physical GC heap.

Roots must include actor state, active mailbox turns, pending messages,
channels, async continuation frames and suspended futures/wakers, supervisor
and hot-reload generations, active host/FFI handles, pin/IO leases, and
temporary compiler stack roots. A suspended task is not reclaimable merely
because no worker thread is currently executing it.

An actor-local collector should mark from *strong* roots, clear dead weak
references, reclaim unmarked managed objects (including cycles), and use
generation validation for reusable slots. Trace incrementally with explicit
safepoints/budgets if requested; if concurrently mutating, provide the
appropriate write barrier and liveness proof. It must never free a reachable
root or introduce cross-actor mutable aliases. Finalizers/destructors and
external FD cleanup must run outside GC graph locks with a clearly defined
ownership and resurrection policy. Do not treat `System.gc()` as that
collector.

**Proposed policy**: default zero guest tracing for uniquely owned and
arena-backed values; explicit `managed` allocations are traced (manually
or with configured bounded automatic fallback). Manual-only mode must
document out-of-memory behavior if the application forgets to collect.
GC policy must not become an escape hatch around borrowing or actor isolation.

## Required verification before declaring zero/near-zero-GC

- Reject move-of-self into a live owned field, escaped borrows, illegal
  strong ownership loops, and unguarded/uninhabitable recursive types.
- Accept finite recursive *types*, acyclic aliases, weak back-edges,
  self-cyclic and bidirectional *arena* links.
- Reject cyclic actor-message graphs; accept shared acyclic subtrees.
- Verify weak upgrade after owner removal/actor termination, stale handles
  after slot reuse (ABA), and foreign-arena handles.
- Exercise strong cycles under an opt-in traced collector; verify exact
  root retention across `await`, cancellation, hot reload, and FFI.
- Verify bounded incremental scan work, predictable manual collection,
  forced OOM path, no double drop, no resurrection/use-after-free, and
  actor confinement with adversarial graph mutation.
- Benchmark allocations, GC CPU and p99/p99.9 pauses on both actual
  language backends, including native AOT; **do not** claim native no-GC
  based on JVM tests or on a fixed-size queue alone.

## Host cleanup closure gotcha

A `RuntimeGarbageCollector.track(owner, cleanup)` cleanup closure must
**not** strongly capture `owner` (even indirectly). The registry retains
the callback; capturing the owner keeps it alive and prevents the weak
reference from becoming collectible. Prefer explicit `CleanupHandle.close()`,
actor-domain retirement, and callback designs retaining only the external
resource to release, not the tracked owner. The generic Java API cannot
prove this by static inspection; do not advertise it as enforced.

See `RuntimeGarbageCollectorTest` for host-cleanup lifecycle tests and
`CircularReferenceContractTest` for deterministic cycle-versus-DAG
boundary checks. These tests intentionally do **not** attempt to prove a
JVM collection occurred at any given time.
