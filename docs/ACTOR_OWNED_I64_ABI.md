# Actor-owned i64 storage: Java/Graal reference ABI

This is the initial typed storage surface for the OresVM **one logical
guest heap per actor** abstraction. It builds on parent PR #561 and the
AOT/JIT isolation substrate in #558.

## Host runtime contract

`ctx.actorOwnedMemory().orElseThrow().newI64Region(slots)` returns an
`ActorOwnedI64Region`, owned by the current actor's stable `ActorId`.
SHARED, PRIVATE and UNTRUSTED actors all use identical typed slot semantics.

- Each scalar is an eight-byte signed integer at a checked slot offset.
- `allocate(value)` publishes a newly owned generational `Slot` handle.
- `read` and `write` require the allocating actor's current turn, even
  when a continuation migrates to another physical carrier thread.
- `drop` zeros the slot and increments its generation. A stale or foreign
  slot fails closed, including after reuse (ABA).
- The fixed direct-byte backing counts against the current actor's own
  memory quota. Slot admission cannot overrun an actor's capacity.
- Explicit `close` releases the owner-checked block's logical reservation;
  actor termination automatically retires any remaining regions.
- Actor slot identities are neither mailbox-sendable guest pointers nor
  capabilities to access another actor's heap.

This is a **host-side ABI/runtime primitive**, not yet source-language
`int` allocation lowering or an actual per-actor JVM collector. Java
`ByteBuffer.allocateDirect` has managed wrappers and the underlying native
allocation may still depend on the host cleaner for physical release.
Compiler lowering should target a supported native/foreign-memory allocator
for deterministic physical release. UNTRUSTED actor code still needs a
real source-to-isolate router: a stateless bootstrap host test is not proof
of execution inside an independent JIT GraalVM heap.

## LLVM difference

The matching LLVM compiler branch supports `const`/`val` `int`
locals with `alloca` and `llvm.lifetime.start/end`, emitting no tracing
GC statepoints or unknown external calls. Trivial lexical scalars are
stack-owned, not heap allocated. The LLVM `DeterministicGraphArena` is
the separate native heap-object foundation.

Neither the Java API nor the LLVM scalar fixture constitutes general
Oreslang class/actor object lowering. Strict LLVM no-GC certification needs
compiler-generated ownership/drop IR, cancellation/async quiescence and
eventual exhaustive backend differential tests.
