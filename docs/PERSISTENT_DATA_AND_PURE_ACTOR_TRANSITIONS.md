# Persistent data, protocols and pure actor transitions (Clojure/Elm design)

**Status (2026-10-16):** implementation staging contract. This document does not
claim that proposed Oreslang syntax parses, that native backends are at parity,
or that foreign-memory references are automatically safe to share.

## Adopt, adapt, reject

| Inspiration | Oreslang direction | Boundary |
| --- | --- | --- |
| Clojure HAMT maps | Persistent, path-copying maps; runtime prototype is `PersistentActorMap` | Only approved deep immutable data; not arbitrary Java/Python objects |
| Clojure persistent vectors | 32-way bitmapped vector trie with small tail (future implementation) | Not a HAMT and not a flattened mutable foreign buffer |
| Clojure data/EDN | Data-oriented map/vector/record operations and optional EDN text reader | EDN is neither a memory ABI nor JSON/Protobuf/MessagePack wire replacement |
| Clojure protocols | Extend existing `trait`/typeclass facilities with coherent, explicit adapters | Do not add unchecked global extension or reflection-based actor authority |
| Clojure atoms/refs | Consider local optimistic cells for compiler-proven private state | Do not introduce arbitrary cross-actor atoms; refs/STM and atoms/CAS differ |
| Elm update loop | Actor reducers are the state model **only** for declarations marked `pure actor` | Ordinary actors continue to use `self.handle_state` and scoped `using state.*` access |
| Elm ports | Validated, revocable, effect-typed foreign boundaries | Foreign exceptions are normalized; they never silently grant actor capabilities |
| Elm extensible records | Reuse `structural` subset constraints; no separate row type syntax initially | Preserve existing nominal schemas and Sendable versus Serializable distinction |
| Elm diagnostics | Structured compiler error ID, span, inferred/expected contract, suggested fix | No language-server-only fixups that bypass the compiler |

## Transport invariants

1. A `PersistentActorMap` constructed through `empty().with()` contains only
   String keys and a closed set of immutable scalar values or nested persistent
   maps. Unknown host objects, foreign interop values, buffers, arbitrary
   collections, actor refs and capability handles are not accepted as values.
2. The HAMT uses 5-bit hash segments, bitmap-compressed nodes, path copying and
   explicit same-hash collision nodes. Collision chains are capped at 64 until
   randomized hashing or a proven adversarial policy is implemented.
3. The Java/Graal runtime may retain the **same object root** through its
   SHARED mailbox freeze path after ordinary graph/capability/quota validation.
   This is a local-process read-only optimization, *not* a claim that an
   interop call, FFI buffer, native arena or network transport is zero copy.
4. PRIVATE and UNTRUSTED mailbox paths still build independent isolated
   containers, even when the sender supplies a persistent root. This must not
   be widened without a proof of confinement, identity, lifetime and teardown.
5. Logical mailbox size and per-actor/global quotas still apply even when no
   byte copy is performed. Sharing an object does not mean free admission.
6. Persistent roots may be shared; actor-owned mutable state, mutable closures,
   FFI pointers, memoryviews, synchronized cells and leases may not be smuggled
   into shared payloads via nested collections.
7. This runtime class is not yet an Oreslang source-language builtin; no
   `persistent` keyword or `share` shortcut is implied by the prototype.

## Canonical actor state modes — explicit declaration split

**Decision (2026-10-16):** The **actor declaration's** `pure` modifier
selects reducer mode. This is distinct from `pure fnc` or `pure actor fnc`.
An actor **without** the class-level `pure` modifier does **not** acquire
an implicit reducer or an alternate state path: user state access remains
`self.handle_state`, with the equivalent `using state.read/write(...)`
forms as defined by the existing actor state design.

| Actor declaration | User state model | Side-effect path |
| --- | --- | --- |
| `define pure actor` | Pure reducer receives an immutable state snapshot and typed message, returns a new state and declarative command values | Runtime validates, reserves, commits state and then dispatches admitted commands |
| `define actor` (also shared/private/untrusted variants) | Sealed `self.handle_state` intrinsic and scoped `using state.read(...)` / `using state.write(critical ...)` | Ordinary checked actor effects under `with`, capability and supervisor controls |

**Illustrative future Oreslang syntax only** (not implemented by the
current Java parser, source type checker or LLVM/.NET/WASM/Forge backends):

```ores
// Canonical intended spelling; the actor-level pure modifier is the selector.
define pure actor Counter as
  state { count: 0 }

  // Suggested reducer contract; final signature/lowering are pending.
  pure fnc reduce(StateSnapshot s, ActorMail<CountMessage> mail):
      Transition<CounterState, CommandList> {
    return Transition(
      s.with("count", s.count + mail.value.delta),
      Commands.none()
    );
  }
end

// Ordinary actor: no reducer; explicit state capability.
define actor CounterWorker as
  state { count: 0 }

  fnc receive(ActorMail<CountMessage> mail) {
    self.handle_state(critical |s| -> {
      s.count += mail.value.delta;
    });
    mail.done();
  }
end
```

The source examples above document **intended** syntax, not an assertion
that `StateSnapshot`, `Transition`, `Commands.none`, actor-level
`pure`, or the shown reducer method currently parse or execute.
Normal modifier ordering is `define [visibility] pure [actor-kind] actor Name
as ... end`; `define pure actor` is the minimal form. Exact formal syntax
is to be stabilized with compiler/formatter admission, not inferred from
this illustration.

### Pure actor reducer invariants

1. **Exactly one message-reducer entrypoint per pure actor**, statically
   resolved and exhaustive across all admitted message variants. An optional
   separate typed CONTROL reducer handles supervisor/child terminal events. No ambiguous
   overloads, dynamic reflection, or guessed handler dispatch. Supervision
   CONTROL messages use a separate typed input/priority path but are
   still processed under the parent's single-flight lane.
2. Reducer input is **explicit immutable data**: state snapshot, typed
   message (and explicit metadata if approved). It cannot inspect ambient
   mailbox, actor identity, time, randomness, scheduler, foreign mutable
   memory, or the hidden `state` object.
3. A reducer may allocate/local-mutate uniquely owned temporaries and may
   return freshly owned immutable/persistent state, but cannot externally
   mutate, `await`, `select`, block, send, spawn, invoke effectful FFI,
   call `self.handle_state`, or acquire `using state.*` loans itself.
   All transitive calls must pass the normal compiler `pure` effect check.
4. A reducer returns `Transition<NewState, Commands>`, with commands as
   **declarative, typed, Sendable data**, never live ActorRefs, handles,
   callbacks or implicit capability grants. Command kinds (e.g.
   Send/Spawn/Emit/PortCall/Stop) are validated against the actor's declared
   `with` effect upper bound, recipient authority, permissions and quotas.
   The `pure` guarantee covers computing the description; it does not
   claim that executing the resulting commands is itself pure.
5. Runtime first validates state and command intents; then **reserves
   bounded command/outbox capacity before committing**. A conflicting,
   rejected or cancelled transition must not dispatch commands. State
   publication and durable command enqueue need a single runtime commit
   boundary; dispatch of external commands afterwards may be at-least-once,
   requiring idempotency keys and explicit failure/supervision semantics.
   Do **not** claim exactly-once external I/O. A pure reducer may be
   replayed after a conflict only when its inputs are version-pinned and
   no externally visible effects were executed.
6. Actor `panic`, `raise`, `throw`, cancellation, stop and parent
   supervision retain the existing typed failure/CONTROL behavior; a
   failing reducer never publishes half a transition. Pure means the
   effect contract, **not** totality or immunity to exceptional exit.
7. Existing physical single-thread-per-actor execution remains intact;
   pure reducers do not introduce parallel actor turns or shared mutable
   `Atom` /`Ref` state. Runtime internal state commit is a compiler-sealed
   operation, not a user-accessible second `handle_state` API.

### Ordinary actor invariants

For all non-`pure` actors, actor instance data remains the **one fixed-key
`state { ... }`**. User code uses `self.handle_state(|s| -> ...)` to read
or `self.handle_state(critical |s| -> ...)` to write; the corresponding
`using state.read(|s| -> ...)` and `using state.write(critical |s| -> ...)`
forms are equally canonical and lower to the sealed intrinsic. Plain reads
do not imply concurrent physical readers. `with` bounds callable effects;
`using` acquires a scoped capability; `critical` requests exclusive
writes. No state loan may escape or cross `await`, `select`,
cancellation, suspension or foreign calls.

**Do not** auto-select reducer behavior merely because an ordinary actor
has a method named `update` or `reduce`. Conversely, `pure actor`
must not silently fall back to direct `handle_state` on reducer failures.
A compiler lacking reducer lowering **rejects** `pure actor` rather
than running it as an ordinary actor.

### Admission and parity tests still needed

- Parse/format `define pure actor`, `define pub pure shared actor`
  (exact accepted ordering to be normalized by the formatter); reject
  accidental use of pure as an identifier or method-only purity as an
  actor-class selector.
- Reject pure actors with direct `handle_state` / `using state`,
  direct I/O/FFI/actor messages, mutable aliases, `await`, `select`,
  unknown call effects, non-exhaustive reducers or conflicting signatures.
- Reject ordinary actor reducers as implicit state handlers; keep
  ordinary `handle_state` semantics and state-loan lifetime proofs.
- Check command permission and `with` upper bounds, mailbox capacity,
  conflict/replay safety, sealed commit+outbox atomicity, typed
  supervision/CANCEL/STOP traces, and shutdown/heap retirement.
- Run the same admitted and rejected **Oreslang source programs** against
  Java, LLVM, .NET, WASM and Forge before calling this five-backend parity.
  A host-side compiler policy helper or AST parser probe is not source
  execution or actor runtime integration.

## Polymorphism and polyglot contract (proposal)

Prefer `trait`-based static specialization and coherent typeclass evidence,
with a fast protocol dispatch cache only for trusted dynamic target shapes.
Adapters must declare data schema, ownership, lifetime, mutability, effects,
error conversion and capability provenance. A Truffle `InteropLibrary`
capability probe is *not* a verification of deep immutability, thread safety
or lifetime. Unknown dynamic dispatch remains an `FFI_UNKNOWN` effect,
and foreign host exceptions become typed Oreslang boundary failures.

Use `structural` to demand subset fields in data records; require explicit
conversion or checked proxies when crossing Java/Python type systems.
Keep `Sendable` (actor-local message transport proof) distinct from
`Serializable<Format>` (encoded network wire contract). Do not implicitly
convert user data to EDN, JSON, MessagePack or Protobuf.

## Parity plan: Java, LLVM, .NET, WASM and Forge

| Gate | .java | .llvm | .net | .wasm | .forge |
| --- | --- | --- | --- | --- | --- |
| 32-way persistent map, immutable value checks | **Java prototype PR** | Pending | Pending | Pending | Pending |
| Private/untrusted isolation remains enforced | Regression test added in Java PR | Verify | Verify | Verify | Verify |
| Full persistent vector API and equality/hash semantics | Pending | Pending | Pending | Pending | Pending |
| Validated EDN parser/reader (opt-in, with quotas) | Pending | Pending | Pending | Pending | Pending |
| Trait/protocol coherent adapter dispatch | Existing foundation only | Pending | Pending | Pending | Pending |
| Actor-level `pure` mode selector, reducer checker and atomic transition/command lowering | Design + compiler-policy staging only | Pending | Pending | Pending | Pending |
| Validated polyglot port borrowing/lease semantics | Spec only | Pending | Pending | Pending | Pending |
| Uniform structural record diagnostics | Pending | Pending | Pending | Pending | Pending |

## Verification gates

- Java: `mvn -B -ntp -Dtest=PersistentActorMapTest test` and full
  `mvn -B -ntp verify`; source-tree exact commit SHA and real executed test
  steps required before claiming passing CI.
- HAMT oracle: randomized `Map` equivalence, prior-version invariance,
  equality/iteration, signed highest-bit bucket ordering, same-hash collisions,
  deletion compaction and hash-flood ceiling.
- Actor oracle: SHARED root identity, PRIVATE data isolation and deep nested
  immutability; reject mutable/foreign/capability elements and do not relax
  graph depth, node count, logical bytes or cancellation.
- Cross-backend: same Oreslang source fixtures with accepted/rejected
  diagnostics, data equality, cleanup order and actor traces. A native helper
  compiling alone is **not** language-feature parity.
- Add differential performance benchmarks measuring allocation *and* throughput
  for 1K/100K entries and 1/10/100 actors; reject any blanket zero-copy
  assertion unless measured and proved for the exact transport path.

**Non-goal:** promising Haskell-style monads, unrestricted STM, implicit
Java/Python object sharing, or zero runtime errors. Explicit, typed effects,
actor isolation, pure-actor reducers, and ordinary-actor `handle_state` remain the canonical design.
