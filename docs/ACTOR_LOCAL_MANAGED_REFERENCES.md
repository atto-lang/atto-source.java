# Actor-local managed references (experimental)

This feature is separate from the guarded recursive-type implementation in
PR #457. A type can be recursive without a cyclic runtime value. Ref<T> adds
an actor-confined identity-bearing cell when circular object graphs are truly
required; WeakRef<T> is a weak observer of that cell.

## Source example

```oreslang
type Node = struct{
  value: int,
  next: Ref<Node>
};

define actor Worker as
  receive(ActorMail<string> mail): void {
    val root: Ref<Node> = Ref.empty<Node>();
    // The node contains a strong handle to its own cell, not an illegal
    // by-value copy of an incompletely constructed Node.
    root.set(struct{value: int, next: Ref<Node>}{value: 5, next: root});
    val weak = root.downgrade();
    val recovered = weak.upgrade().unwrap();
    val owned: Node = recovered.take().unwrap();
    self.end();
    return;
  }
end
```

## Current operations

- `Ref.new(value)` initializes one actor-local cell with an owned value;
  `Ref.empty<T>()` allocates an empty cell for cyclical construction.
- `ref.set(owned)` replaces the value by move; `ref.take()` moves the
  contents out as `Option<T>` and empties the cell. They never duplicate
  ownership. `ref.is_set()` reports occupied state.
- `ref.get_copy()` is only for immutable primitive/string scalar payloads;
  it rejects composite and mutable payloads both statically and at runtime.
- `ref.downgrade()` returns `WeakRef<T>`; `weak.upgrade()` returns
  `Option<Ref<T>>`. Weak handles do not keep a cell alive.
- `ref.same_identity(other)` checks cell identity. Handles have copy
  semantics **inside their owning actor**, not by-value data semantics.
- All methods check their owner actor's stable execution domain; the carrier
  thread alone is not an identity or authorization token.

## Security and lifetime

Ref<T> and WeakRef<T> cannot be allocated outside an actor, cannot be
accessed from another actor (even on the same carrier thread), and cannot
cross message/freeze/async/shared-memory boundaries. This is enforced
statically on known types and dynamically by the transport graph admission
validator. Java host access does not turn them into transportable data.

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
