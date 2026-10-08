# Pipelines, recursive aliases, and static namespaces

## Pipelines

The value-first operator is `|>`. It is left associative and is parsed
between logical-or and the conditional (`?:`) expression.

```oreslang
fnc increment(int value): int {
  return value + 1;
}

fnc scale(int value, int factor): int {
  return value * factor;
}

val int result = 2 |> increment |> scale(10);
```

The compiler lowers this to `scale(increment(2), 10)` before static
analysis. The left side is evaluated once; right-hand side must be a
named callable, a direct member callable, a lambda, or a call whose
callee is one of these. Computed callees such as `factory()(extra)`
are rejected: ordinary call evaluation would evaluate the factory
before the piped value. Bind computed callables to a name first.
`value |> f(extra)` prepends `value` to the arguments of `f`. Use
parentheses for intentionally mixed operator precedence. Neither bitwise
`|` nor lambda delimiters `|x|` change. Bindings and expression
statements still require their normal terminating semicolon.

## Recursive types

Recursive aliases are permitted only when every cycle crosses a
concrete storage/indirection type: `Option`, `Result`, array/list,
finite tuple, record, borrow, callable, or a known nominal data type.

```oreslang
type Node<T> = Option<[T, Node<T>]>;
type IntNode = Node<int>;

type First = Option<Second>;
type Second = Result<int, First>;

// Canonical explicit record syntax; legacy { ... } record types also parse.
type NodeRecord = struct{
  value: int,
  next: Option<NodeRecord>
};
```

The resolver terminates guarded cycles using a compiler-internal
nominal recursion reference. It rejects transparent cycles and
non-regular recursion that changes generic arguments:

```oreslang
type Broken = Broken;                       // error
type Left = Right; type Right = Left;        // error
type BrokenUnion = int | BrokenUnion;        // error
type Growing<T> = Option<Growing<List<T>>>;  // error
```

These are static type-graph rules. They do not themselves add ADT
constructors, automatic cyclic allocation, cycle-aware serialization,
actor sendability, or exhaustive constructor-pattern proofs.
One-step unfolding makes a recursive back-edge compatible with its
declared concrete data shape, including nested `Option<NodeRecord>`
and recursive field access. The checker conservatively rejects
recursive markers that reach actor-message or async-transfer signatures:
this is **not yet** a cycle-aware serializer.

## Circular values and ownership

A recursive *type* is not necessarily a cyclic *value*. For example,
`Option<NodeRecord>` permits finite linked lists terminating at `None`.
Actual circular values preserve object identity: two references may
point to the same object, including a node that points back to itself.

Within an owning context, the runtime uses identity-aware and bounded
diagnostic traversal: printing an object, map, list, Option or Result
that contains a cycle emits `<circular>` rather than overflowing the
stack. The JVM heap collector can reclaim unreachable cycles; the
additional Oreslang resource collector is not itself a tracing
collector for guest object graphs.

The compiler must **not** relax move/borrow safety to create cycles.
In particular, an owned `NodeRecord` placed inside its own `Option`
would require invalid duplication of ownership unless a distinct
reference handle is available. A future managed local `Ref<T>` or
weak-reference design must specify allocation, identity, lifetime,
mutation authority, cleanup and actor confinement before supporting
source-level cyclic construction through that handle.

**Current boundary:** cyclic mutable graphs are disallowed in actor
mailboxes, freeze/copy transport, shared-mutex publication and async
task detachment. Repeated references to an acyclic subtree remain
valid and must not be confused with a genuine cycle. Actor-reference
handle cycles are a different kind of graph, not by-value transfer.

Potential DoS via deeply nested values is bounded in runtime display
and async detachment; recursive type comparisons have a compiler
nesting limit rather than exhausting the Java call stack.

## Module/class declarations are not values

Modules and classes are compile-time declarations. Use them as qualified
names, not as objects to pass, store, or return.

```oreslang
define module Math as
  pub fnc double(int value): int {
    return value * 2;
  }
end

val int result = 8 |> Math.double;  // valid: static member invocation
val Fnc<int(int)> fn = Math.double;  // valid: static function value
val m = Math;                       // invalid: module declaration as value
```

Likewise a class declaration can be named in a `new` expression or
accessed for static members, but cannot be stored as a runtime value.
A `Module<T>` runtime handle or first-class module/functor feature is
**not** implied by module contracts. Existing contracts remain separate
compile-time conformance checks.

## Future hardening

1. Recursive alias instantiation caching and generics substitution across
   mutually recursive alias families.
2. Nominal tagged algebraic constructors, nested pattern matching, and
   complete exhaustiveness/unreachable-arm analysis.
3. Cycle-aware tracing, ownership, deep-copy/serialization, and explicit
   opt-in to actor transport for recursive data.
4. Cross-file imported static namespace identity and compiler-enforced
   restrictions at every AOT/linked-program boundary.
