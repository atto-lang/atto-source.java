# Actor immutable mailbox contract

Oreslang SHARED actors execute concurrently in one JVM address space. Unlike
Scala/Akka's primarily convention-based message immutability, Oreslang must
validate/freeze transported data and prevent mutable cross-actor aliases.

## Enforced invariants

1. Exactly one guest-code turn can run for an actor at a time. Different actors
   may execute on different carriers concurrently; no carrier affinity is
   required for correctness.
2. Admission/queue synchronization must provide Java Memory Model
   happens-before edges between production, enqueue, dequeue, and actor turns.
   CPU caches do not require manual flushing.
3. Normal SHARED delivery deep-freezes supported List/Set/Map/array graphs.
   PRIVATE/isolated delivery copies them into detached storage.
   Unknown mutable host values, executable captures, and cyclic graphs are
   rejected rather than passed through as mutable aliases.
4. `ActorRuntime.Shared<T>` is runtime-owned, deeply frozen once, quota
   accounted, and reused without another payload copy across SHARED actors.
   It is not the same as a general mutable shared-memory capability.
5. `rt copy` deep-copies supported owned graphs and never grants a second
   writable reference to the original storage.
6. `rt share` snapshots supported statically safe data-only graphs as a typed
   deeply `Readonly<T>` projection. Nested array/list/map/set snapshots now
   carry explicit immutable runtime provenance through async argument
   detachment; readonly class snapshots also retain their readonly flag.
   Mutating methods/writes on `Readonly<T>` are forbidden. Unsafe host types,
   actor identities, capabilities and cyclic values remain unsupported.
7. `rt take x` consumes a **named owner**. Moving a field or array element
   cannot yet guarantee source-slot invalidation and must fail closed.
8. ActorRef routing via `ActorRef.send` is a dedicated transport capability,
   not a general user-function argument. Ordinary class *instances* remain
   legal callable data; class and module *namespaces* do not.

## Caveats and proof obligations

- JVM garbage collection alone provides neither data-race freedom nor
  deep immutability.
- A mutable Java host collection that another **host thread** concurrently
  mutates while being frozen is outside the snapshot consistency guarantee.
  Only guest-exclusive ownership, synchronized snapshot capture, or already
  immutable host ingress can establish a coherent publication.
- A read-only Java wrapper around an externally mutable backing collection
  is **not** adequate proof of immutability. Oreslang's runtime-created
  snapshots own detached immutable backing storage.
- Module state is actor-local and must stay actor-local when its code image is
  shared. Supervisor module-state ownership and imported cross-unit field
  contracts still need hardening.
- Source-level nested alias, callback capture, host interop, generation-fencing,
  lifecycle and await/resume escapes need further audit. Any unsupported
  transport must reject, not silently share.
- Java compiler and runtime regressions must **execute on the exact PR head**
  and pass before these guarantees are considered proven or PR #519 merged.
  Earlier Rust finite-state proofs concern bounded abstractions only.
