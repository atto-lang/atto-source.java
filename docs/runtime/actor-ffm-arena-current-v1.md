# Actor-owned FFM arena — current runtime integration

## Contract

Every Oreslang actor keeps its own mutable allocation authority. This Java/Graal
substrate adds deterministic native storage for data deliberately lowered into
an actor arena without creating an isolate, thread, or collector per actor.

An `ActorArena` contains two bounded FFM regions:

- **persistent** slots survive mailbox turns and carrier migration;
- **scratch** slots are lexical to one mailbox/continuation segment and must be
  closed before returning, awaiting, or otherwise yielding.

Both regions use opaque generation-checked handles. No `MemorySegment` or raw
native pointer is exposed to guest code.

## Retirement

Logical actor retirement revokes all arena access immediately. Before the owning
`Arena` is physically closed, every native slot is explicitly overwritten with
zeroes; deterministic lifetime alone is not treated as a zeroization guarantee.
A persistent read loan may outlive
an actor turn and can therefore defer physical close; its bytes remain charged
to the actor/runtime quota until the final loan closes. Scratch has no loan API
and is reclaimed at the turn boundary.

Completion-thread loan release deliberately does not acquire the actor slice
monitor. Retirement owns `slice -> region`; acquiring `region -> slice`
would create a lock inversion. Native accounting completion uses the runtime
memory-budget lock instead.

## GC and AOT boundary

This is deterministic reclamation for **guest data stored in FFM**. Java wrapper
objects, Truffle nodes, mailbox envelopes, futures, and other host control-plane
objects remain JVM-managed. Therefore this feature supports the near-zero-GC
goal but is not a claim that the GraalVM process has no garbage collector.

The implementation is compiled with Java 21 source compatibility and resolves
the Java 25 FFM API reflectively. If FFM is unavailable, deterministic-native
arena creation fails closed; it never silently falls back to DirectByteBuffer.

LLVM/native backends should implement the same ownership/lifetime semantics
without a tracing collector and remain full-AOT.
