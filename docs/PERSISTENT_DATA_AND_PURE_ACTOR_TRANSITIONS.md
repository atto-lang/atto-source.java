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
| Elm update loop | Optional pure `update(state, message) -> (nextState, commands)` | Runtime executes commands only after validation/admission |
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

## Actor reducer contract (proposal; not accepted source syntax)

```text
update : (StateSnapshot, ActorMessage) -> Transition<NewState, CommandList>
validate transition state and declarative commands
commit state through actor-owned admission (no loans held across await)
dispatch commands through scheduler/ports after accepted commit
```

A reducer is pure: it does not directly perform I/O, spawn actors, mutate an
actor's `state`, invoke unknown FFI or suspend. `Transition` descriptions
are *data*, not capabilities. Commands such as Send/Spawn/Emit/PortCall must
be checked against `with` effect upper bounds, sender identity, recipient
visibility, backpressure, cancellation and supervisor/control policies. A
conflicted commit must not execute its commands. A replay must not
re-execute external effects without an explicit idempotency boundary.

The existing canonical `self.handle_state` and
`using state.read/write(critical |s| -> ...)` APIs remain authoritative.
`critical` regulates exclusive write admission and `using` acquires a scoped
capability; no loan escapes or crosses `await`, `select`, cancellation or FFI.
A reducer lowers to those mechanisms rather than introducing a second
uncontrolled state path.

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
| Reducer + declarative command lowering | Spec only | Pending | Pending | Pending | Pending |
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
actor isolation and optional pure reducers remain the canonical design.
