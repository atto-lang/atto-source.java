# Garbage collection and actor lifetimes

Oreslang is **ownership-first, GC-assisted**.

The ownership/borrow checker is the primary lifetime mechanism. Ordinary owned
values should become reclaimable when lexical ownership ends, an actor-owned
allocation is released, or an actor terminates. Garbage collection is a fallback
for VM-managed objects, reflection/dynamic metadata, host interop, cycles that
are explicitly admitted by a future safe abstraction, and other runtime objects
whose lifetime cannot be reduced to ordinary ownership.

GC is never required for language-level correctness.

## Lifetime model

Oreslang currently uses inferred lexical lifetimes. Actor methods add an
implicit **actor-turn lifetime**:

- actor `self` is an exclusive, non-escapable borrow for one serialized mailbox
  turn;
- actor state may not store a borrow;
- public actor mailbox methods and actor entry functions may not accept or
  return borrowed values;
- a borrow rooted in actor `self` cannot be returned, captured by a closure,
  or passed across an ordinary callable boundary;
- private actor state must be created inside the actor domain and may not retain
  arbitrary mutable JVM/host aliases.

This gives the actor model the important part of Rust-style lifetime safety
without forcing named lifetime parameters into ordinary Oreslang source. Named
lifetime syntax should only be added later if APIs need to express a borrow
relationship that inference cannot represent.

## Explicit API

```ores
actor.gc();
process.gc();
```

`actor.gc()`:

- is legal only during a live actor mailbox turn;
- must be called directly; the `actor.gc` capability cannot be extracted as a
  first-class function value;
- is not inherited by escaping lambdas/closures merely because they were
  created during an actor turn;
- requests collection for that actor domain only;
- never falls back to `System.gc()` on the JVM;
- works for private and shared actors, but only actor-owned state belongs to the
  actor-local collection domain;
- does not collect `Shared`, `SyncCell`, or other explicitly shared state.

`process.gc()`:

- is an explicit whole-runtime/host-VM collection hint;
- requires the `PROCESS_GC` capability;
- is denied by strict FaaS;
- is stripped from private actors;
- coalesces concurrent requests so actors cannot fan out simultaneous full-heap
  collections;
- may map to `System.gc()` only for an explicit authorized request on the JVM.

## Automatic VM maintenance

The actor runtime has configurable message-count checkpoints. The defaults are:

- actor-local maintenance every 256 processed actor messages;
- process-level maintenance every 4096 processed actor messages.

A backend can map those checkpoints to generational tracing, region cleanup,
reflection-cache cleanup, or native/private-heap collection. The JVM host
backend deliberately treats periodic process maintenance as a no-op because the
JVM already schedules its own GC; Oreslang does not periodically force global
`System.gc()`.

The intervals are policy/VM tuning knobs, not source semantics.

## Private versus shared actors

Private actors have a confined actor memory slice and no `SHARED_MEMORY`,
`ACTOR_SHARE_READONLY`, or `PROCESS_GC` authority. `actor.gc()` therefore
cannot become a cross-domain operation.

Shared actors still own their ordinary fields. Mailbox serialization gives one
writer at a time for that actor-owned state. Explicitly shared mutable state must
use synchronization-capability types such as `SyncCell`/`SharedMutex`; GC does
not relax those aliasing rules.

This follows the useful actor invariant from Akka/Actix/Ractor—state mutation is
owned by the actor and messages cross a boundary—while making shared-memory
escape hatches explicit and separately capability checked.

## Backend contract

`ActorRuntime.GarbageCollector` receives:

- `actorStarted(actorId, kind, policy)`;
- `collectActor(actorId, kind, reason)`;
- `actorExited(actorId, kind)`;
- `collectProcess(reason)`.

A native/isolate backend can map a private actor id to a real arena or private
heap. The JVM backend must not pretend that HotSpot/Graal can trace only one
arbitrary actor's Java object graph; actor-local GC is therefore a semantic
checkpoint there, while actor termination still releases the actor's explicit
private memory slice.

## Design rule

Prefer, in order:

1. lexical ownership and move semantics;
2. inferred borrows/lifetimes;
3. actor ownership and actor-turn confinement;
4. deterministic release for explicit resources;
5. tracing GC only for the residual graph the compiler/runtime cannot reclaim
   deterministically.

Do not make application code call GC to prevent ordinary leaks. If an ordinary
owned value survives too long, that is an ownership/runtime bug to fix rather
than a reason to sprinkle `gc()` calls through user code.
