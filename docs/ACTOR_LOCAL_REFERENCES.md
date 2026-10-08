# Actor-local managed references (`Ref<T>` / `WeakRef<T>`)

This feature is **actor-domain confined**, not a general purpose `Rc`, `Arc`,
shared pointer, mailbox payload, or unscoped mutable borrow. It is stacked
on the guarded recursive-type work in PR #457.

## Typed API

Inside an actor turn, the following signatures are supported:

```oreslang
val Ref<int> owner = Ref.new<int>(3);
val Ref<int> alias = owner.clone();      // explicit additional strong owner
val WeakRef<int> weak = owner.downgrade();

alias.set(4);                            // replace owned cell contents
val int n = owner.get_copy();           // only approved immutable scalar types

val Option<Ref<int>> upgraded = weak.upgrade();
val bool alive_now = weak.is_alive();   // snapshot, not a lifetime guarantee

owner.drop();                           // consumes this one strong handle
alias.drop();                           // other owners are independent
```

`swap(next)` replaces a cell and returns its previous *owned* value.
`get_copy()` is intentionally narrower: it never returns a mutable alias.
A future scoped lease API is needed before exposing `&T`/`&mut T` access to
non-Copy state without transferring ownership.

## Circular graphs

Recursive **types** are a compile-time concept. Cyclic **values** require
explicit reference edges. A guarded type can describe such edges:

```oreslang
type Node = struct{
  value: int,
  next: Option<Ref<Node>>
};
```

Source actors must import external types explicitly; they cannot capture
ambient same-file type aliases. The Java runtime tests also cover true
identity-bearing cell cycles. A cell owns its value, and a cloned strong
reference may be placed into that owned graph. JVM tracing garbage collection
can reclaim an unreachable strong cycle; a `WeakRef<T>` does **not** keep the
cell or its actor domain alive.

A weak upgrade can succeed only while the cell remains reachable, and only
inside its originating actor domain. Collection timing is nondeterministic:
`is_alive()` must not be used as a substitute for a successful `upgrade()`.

## Security, ownership, and lifetime

- Handles are not implicitly Copy; `clone()` is required for another
  owning strong handle.
- All read, mutation, clone, drop and weak-upgrade operations require the
  original actor execution domain, not a particular JVM worker thread.
- Foreign strong/weak handles nested inside lists, maps, arrays, guest
  records or objects are rejected **before** publishing a new cell value.
  Repeated acyclic subtrees and local circular references remain permitted.
- `Ref<T>` and `WeakRef<T>` are rejected by actor mail transport,
  async task detachment, and shared-memory safety checks, including nested
  forms such as `Option<Ref<T>>`.
- `get_copy()` accepts only vetted immutable scalars; arbitrary Java
  `Number` implementations, complex guest carrier values, lists, maps and
  user objects are not silently copied.
- `drop()` consumes the handle, not all its separately cloned owners.
  Weak references may observe a cell until it is actually reclaimed.
- The implementation is designed for actor turn serialization. It does not
  authorize unrestricted callbacks, Java reflection, cross-actor aliasing,
  cyclic-by-value serialization, or host-resource finalizers.

## Verification

Keep the change as a draft stacked PR until a funded GraalVM runner verifies
the exact Git tree. Required tests include compilation/type checking, actor
lexical isolation, move/borrow ownership, nested foreign-handle rejection,
weak upgrades, cyclic graph representation, actor message admission, and the
existing compiler/runtime regression suite.
