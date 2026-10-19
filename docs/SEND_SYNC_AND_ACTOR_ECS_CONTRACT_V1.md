# Send/Sync and actor-owned ECS: staged safety contract (v1)

Status: implementation slice / provisional. This document is a proposal for the
end-to-end contract, not evidence that parallel actor ECS already executes.

## Thread traits versus actor transport

* `Send<T>`: moving **owned** T between worker/carrier threads is safe.
* `Sync<T>`: immutable references to T may be accessed on multiple threads.
* Neither is a serialization contract, remote-message contract, actor capability
  grant, ability to clone, nor a proof that a reference outlives a thread/task.
* `&T: Send` iff `T: Sync`; `&mut T: Send` iff `T: Send`.
  `&T: Sync` and `&mut T: Sync` require `T: Sync`. Borrow scopes and
  exclusivity are enforced separately by the ownership checker.
* `Copy` is separate from `Send`, `Sync`, and `rt share` (deep read-only).
  A move-only owner can be Send without being copyable or Sync.
* Unknown/generic/erased type parameters, callable captures, host pointers,
  runtime capabilities, and actor instances are **not** assumed Send/Sync.
  Unresolved recursive nominal graphs cannot be accepted by a repeated-name
  shortcut. A later nominal fixed-point proof must inspect instantiated fields.
* Unsafe, guest-supplied trait implementations are out of scope. Auto traits
  must be compiler-known and unforgeable by type-name aliases.

## Current implemented slice

* `ThreadSafetyTraits` is a conservative compiler-internal query for
  structural primitives, tuples, records, sum types, borrowed types, and
  certain explicitly recognized built-ins.
* Owned mutable Array/List/Vector/Slice, ordinary writable record, FixedArray
  and FixedList can be Send when all elements are Send, but are **not Sync**
  just because those elements are Sync. Immutable Tuple and explicitly readonly
  record types derive Sync only when all nested fields are also Sync. A
  readonly outer record containing a mutable list remains non-Sync. Later
  immutable buffer/container APIs must have separate, verified storage and
  aliasing semantics; `rt share` cannot manufacture Sync from read-only
  syntax alone.
* `TypeChecker.validateAsyncBoundaryType` now requires both the pre-existing
  owned/task-safe test and a Send proof; the existing actor message-specific
  rejection rules remain intact.
* A channel handle gets a narrow Send proof when its payload is Send. We do
  **not** claim Sync for the handle until the runtime and immutable reference
  implementation are audited; `Mutex`, `SharedMutex`, futures, actor
  handles and arbitrary Java/host capabilities remain unproven in this slice.
* Nominal owned class Send is currently admitted only through the existing
  transitive `isSharedSafe` class-field check. Nominal class Sync is denied
  until a separate proof covers identity, inherited fields, mutability,
  allocation domain, interior mutation, and host interop.
* This does not yet expose `Send`/`Sync` source syntax, generic trait bounds,
  an ECS, a runtime thread pool grant, or region-disjoint parallel execution.

## Planned actor/ECS design

Actor isolation should remain the outer fault and networking boundary.
One actor owns one persistent state schema containing its shard's ECS world,
not one actor object per simulated entity. Entity IDs are versioned handles,
not direct pointers. Within that world, archetypes/chunks store dense SoA
columns with compatible alignment and predictable iteration.

The scheduler builds access sets for each system: READ(component, region) or
WRITE(component, region). Systems A and B may execute in the same batch only
if every overlap is read/read or their memory regions are proven disjoint.
If partition identity or aliasing is not proven, serialize. Structural ECS
modifications (spawn, delete, archetype migration) occur at explicit barriers
or through deterministic deferred command buffers.

**One whole-state critical loan cannot be implicitly shared by workers.**
A compiler/runtime-owned splitter must temporarily turn the exclusive
ECS-world loan into non-overlapping component/chunk loans; all workers must
join or cancel and physically exit before the world loan is reconstituted.
The same guarantee must cover exceptions/panic, async suspension, actor
termination, hot reload, GC/arena reclamation, and pinned native buffers.

Execution permits are non-forgeable *region capabilities*, not OS thread IDs.
They are scoped, affine, generation-bound and must be revoked on actor
restart/migration/hot upgrade. Cross-actor data move remains by message or
explicit verified immutable sharing, never by exchanging raw arena pointers.
A multi-carrier actor (`min=1, max>1`) still needs the same state loan
exclusivity; request/message concurrency settings are independent.

## Must-pass tests before parallel ECS release

1. Compile-fail: non-Send ownership handoff, non-Sync immutable share,
   nested generics, unsound captures, aliasing `&mut` loans, escaped loans,
   over-broad trait spoofing and unknown host resources.
2. Exhaustive/model: region splitting, borrow return/join, actor state loan
   exclusivity, cancellation, hot-reload/epoch fencing, panics and restarts.
3. Differential/thread sanitizer: randomized component read/write schedules,
   false-sharing cases, dynamic archetype transitions, invalidation of stale
   entity IDs, and 1 versus N worker determinism.
4. Backend parity: Java/Graal, native LLVM, Wasm and freestanding core (with
   optional allocator/actor executor). No OS thread dependency in the core
   auto-trait analysis.
5. Performance: benchmark serial AoS, serial SoA and parallel chunk SoA.
   Report memory bandwidth, L1/L2/LLC misses, cache-line invalidation,
   actor-message overhead, wall-time throughput and p95/p99 latency.
   Any optimization must demonstrate net benefit against the serial SoA baseline.

Do not equate `Send + Sync` with lock-freedom or with cache locality:
synchronization, false sharing, scheduler overhead and memory-domain transfer
costs remain independent engineering obligations.
