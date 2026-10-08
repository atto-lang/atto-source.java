# Actor-local managed references (experimental)

This feature is separate from the guarded recursive-type implementation in
PR #457. A type can be recursive without a cyclic runtime value. Ref<T> adds
an actor-confined identity-bearing cell when circular object graphs are truly
required; WeakRef<T> is a weak observer of that cell.

## Source example

For an actor, declare a referenced class in a separate source unit and import
it explicitly. The actor lexical-boundary checker intentionally does not
permit a same-file user-defined type alias to be captured implicitly.

`models.ores`:

```oreslang
pub define class Link as
  pub val Ref<Link> next;
end
```

`main.ores`:

```oreslang
import class Link from "./models";

define actor Worker as
  receive(ActorMail<string> mail): void {
    val root: Ref<Link> = Ref.empty<Link>();
    root.set(new Link(root)); // local cycle through the Ref cell
    val weak = root.downgrade();
    val recovered = weak.upgrade().unwrap();
    val owned: Link = recovered.take().unwrap();
    self.end();
    return;
  }
end
```

This is distinct from the recursive type-alias syntax in PR #457;
a recursive type definition does not by itself create a cyclic runtime value.

## Current operations

- `Ref.new(value)` initializes one actor-local cell with an owned value;
  `Ref.empty<T>()` allocates an empty cell for cyclical construction.
- `ref.set(owned)` replaces the value by move; `ref.take()` moves the
  contents out as `Option<T>` and empties the cell. `ref.clear()` empties
  the cell without returning the former payload and is idempotent. These
  operations never duplicate ownership. `ref.is_set()` reports occupied state.
- `ref.get_copy()` is only for immutable primitive/string scalar payloads;
  it rejects composite and mutable payloads both statically and at runtime.
- `ref.downgrade()` returns `WeakRef<T>`; `weak.upgrade()` returns
  `Option<Ref<T>>`. Weak handles do not keep a cell alive.
- `ref.same_identity(other)` checks cell identity. Handles have copy
  semantics **inside their owning actor**, not by-value data semantics.
- All methods check their owner actor's stable execution domain; the carrier
  thread alone is not an identity or authorization token.

## Ownership graph choices and cycle reclamation

These are **identity-bearing, GC-traced cells**, not Rust `Rc<T>` reference
counts. A cycle `a.set([b]); b.set([a]);` is safe within one actor and is
reclaimable by JVM/Graal tracing GC once unreachable from all roots. A weak
edge is still preferred when it denotes a non-owning relationship (for
example, child-to-parent), and is necessary for portable designs if a
future backend uses reference counting.

- Strong `Ref<T>` edges **keep a cell reachable**; a `WeakRef<T>` only
  observes a cell and cannot keep it or its actor execution domain alive.
- `weak.upgrade()` returns `Some(ref)` while the cell exists, even when
  its payload has been cleared or taken; `ref.is_set()` queries payload
  presence separately. It may return `None` after collection, without
  deterministic timing guarantees.
- `ref.clear()` is a safe way to unlink an owned edge without exposing a
  mutable alias. `ref.take()` unlinks while transferring the payload. Neither
  method promises synchronous finalization or deterministic destruction.
- Actors must not send a `Ref<T>` or `WeakRef<T>` outside their ownership
  domain, including through async tasks or channels. Exchange immutable
  snapshots or actor-reference capabilities instead.
- A future native Rust or reference-counted backend **must** implement a
  tracing/arena scheme or enforce acyclic strong ownership (weak back-edges).
  Plain `Rc`-style strong counting would leak otherwise.
- Arena-like graph storage is a distinct proposed API, not implemented by
  `Ref<T>` here. If added, handles need an arena identity, slot and
  generation counter, so slot reuse cannot make a stale handle point to a
  different node. Context/actor invalidation must revoke those handles.

`Pin` is orthogonal: it protects address-sensitive values during movement,
not reference cycles or strong-reference leak prevention. The Java/Graal
representation uses stable cell *identity* and GC-traced handles rather than
raw pointers into a movable continuation frame.

## Security and lifetime

Ref<T> and WeakRef<T> cannot be allocated outside an actor, cannot be
accessed from another actor (even on the same carrier thread), and cannot
cross message/freeze/async/shared-memory or buffered-channel/select boundaries. This is enforced
statically on known types and dynamically by the transport graph admission
validator. Java host access does not turn them into transportable data.

Runtime buffered-channel admission now traverses recognized language
`SharedState` wrappers, Java maps, iterables, arrays, `Optional`, and
`Map.Entry` containers, rejecting nested managed-reference capabilities.
Java `AtomicReference` and `java.lang.ref.Reference` wrappers are rejected
outright because their referents can change between select registration and
commit. The traversal has a 100,000-node budget, matching mailbox graph
validation, to fail closed on pathologically large payloads.

**Outstanding hardening before production use:** arbitrary opaque host objects
are not reflectively inspected; mutable Java containers may also change after
their initial admission check while a buffered write/select is pending.
Preventing those escapes completely requires a canonical snapshot or
ownership-transfer protocol at channel commit/read boundaries, or a
fail-closed allowlist for every host payload kind. This draft does not claim
those cases are secure yet.

The ordinary JVM tracing collector can reclaim an unreachable group of
strongly cyclic cells. A weak handle never keeps the cell or actor domain
alive. Garbage collection is deliberately not relied upon to enforce actor
ownership or prevent use-after-actor-turn.

There is intentionally **no** general `ref.get()` returning `T`: that
would leak a mutable alias to owned data. A scoped `ref.with_read` or
exclusive `ref.with_mut` API needs dedicated lifetime tracking and
reentrancy/actor-turn audits before it can be introduced.

## Validation

Maven regression suites: `ActorLocalReferencesTest` (Java runtime) and
`ActorLocalReferencesLanguageTest` (Oreslang parser, compiler and actor
execution). Native AOT, native hybrid, and isolate builds must remain
green on the *exact tree* before merging.
