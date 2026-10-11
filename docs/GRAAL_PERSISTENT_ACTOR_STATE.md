# Graal actor arenas: one guarded persistent state field

**Status (2026-10-11):** The Graal/JVM implementation in this branch has a guarded, bounded native-backed **i64 -> i64** map on top of ActorArena's persistent region. This is a host runtime ABI, not source-level `Map<K,V>`, `critical suspend` compiler lowering, a separate GC heap, or a zero-JVM-GC guarantee.

## Proposed Oreslang syntax (not yet compiler-enabled)

```ores
define actor Foo as
  private let state: Map<String, int> = new Map()

  critical suspend handle_state(String key, int delta): int {
    const previous = state.get(key) ?? 0;
    state.set(key, previous + delta);
    return previous + delta;
  }

  receive(ActorMail<String> mail): void {
    // Direct state.get / state.set HERE should be a compile error.
    // Enter through the single state-owning critical method instead.
    handle_state(mail.value, 1);
  }
end
```

The compiler must statically enforce that **only** the declared state-owning method (or callees with a non-escaping lexical capability) reads/writes this one field. Field privacy alone is insufficient: reject alias/borrow escape, state captured in closures, unsafe FFI/reflection and returning a mutable state reference. A one-field convention should be *opt-in* for persistent/stateful actors, not a ban on ordinary immutable actor fields.

An actor mailbox is already logically single-flight. `critical` should represent an exclusive **logical actor lane/capability**, not `synchronized`, OS threads, or a semaphore held across `await`. A reused **ActorRef instance** retains state across messages. Two instances of the same actor class retain separate state, even when Truffle code/images are shared. Termination/restart destroys the arena unless state is explicitly snapshotted/restored.

For the initial compiler feature, reject mutable state/arena loans live across a suspend. Snapshot immutable values before `await`; later resume and reacquire on an admitted turn. A truly atomic `critical suspend` spanning `await` requires a scheduler-owned asynchronous exclusive continuation token with fairness, bounded waiting, cancellation and CONTROL-lane termination. Never park a carrier. Treat source syntax as unsupported until these checks and lowering actually exist.

## Executable host ABI in this PR

```java
// Created exactly once inside the actor instance, not per message:
var arena = ctx.actorOwnedMemory().orElseThrow().newActorArena(3 * 64, 8);
var state = arena.newPersistentI64Map(64);

// Reuse state in successive actor turns:
long count = state.critical(view -> {
    long next = view.getOrDefault(7L, 0L) + 1;
    view.put(7L, next);
    return next;
});
```

- Three FFM i64 slots per hash bucket (key, value, status), charged to the actor persistent native quota. No raw `MemorySegment`/`Slot` escapes. Open addressing/tombstones and a ~75% maximum occupancy bound memory use; the hash table has bounded but worst-case linear lookup.
- Only a callback-scoped `Access` can read/mutate the map. Escaped views reject use once their callback finishes, nested entry rejects, and exceptions revoke the scope. Every operation uses the owning actor's semantic ActorId, not the carrier thread.
- This callback is **synchronous and non-suspending**. A callback crossing `await`/FFI is not yet a supported ABI.
- Constructor failure rolls back native slot allocations; explicit close drops/zeroizes entries in reverse order. Actor retirement tears down the whole native arena even if the map wrapper survives on Java's heap; physical FFM close waits for outstanding loans.
- This prototype stores native **long keys and long values only**. `Map<String, Object>`, Truffle guest object graphs, ASTs, futures, callbacks and envelopes remain JVM-GC-managed until a typed native layout/arena-lowering/compiler ownership proof is available. The callback allocates a small Java view object; no "zero allocations" claim is made.

## Audit and shipping gates

Tests cover persistent shared-actor state across 200 mailbox messages, outside-actor denial, stale Access revocation, non-reentrant critical entry, native map tombstones, clear, constructor rollback, callback exception release and physical arena reclamation. The host map also passed a local Java21 preflight of 100,000 operations against java.util.HashMap using API stubs; this preflight **does not** replace real Oreslang Maven/GraalVM CI.

Required before production: exact-tree full Maven/GraalVM25 tests; AOT + hybrid Native Image; source compiler dataflow enforcement; suspend/FFI/cancellation fencing; native typed-map key/value layout, lease bounds and pin tracking; heap/GC/latency profiling under sustained concurrent HTTP traffic; LLVM identical source fixtures; review of generation/ABA and exhausted quotas. Existing trusted HTTP/FFM smoke tests and brief zero-collection measurements are not zero-GC guarantees.
