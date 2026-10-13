# User-defined tagged enums — staged implementation contract

Status: **front-end syntax/AST implemented; execution deliberately gated**.
This is an implementation contract, **not** a claim of Java/LLVM parity.

## Canonical source

```ores
define enum Expr as
  Literal(int)
  Add(Box<Expr>, Box<Expr>)
  Multiply(Box<Expr>, Box<Expr>)
end

define enum Tree<T> as
  Empty
  Node: {
    value: T,
    left: Box<Tree<T>>,
    right: Box<Tree<T>>
  }
end
```

Nullary, positional tuple, and named record variants are different ways to
declare one case in a nominal sum. The case label is never inferred from its
payload shape: `Expr.Add` and `Expr.Multiply` remain distinct even when both
carry `(Box<Expr>, Box<Expr>)`.

Constructor patterns preserve case qualification, e.g.
`on Expr.Literal(n) -> {...}`. Parsing does not imply they typecheck or execute
yet. The compiler currently rejects all user-defined enum execution.

## Source-level semantic requirements

1. **Nominality:** constructor identity is (`enum declaration`, `variant`),
   never just `variant` or payload arity. A candidate cannot inject a case
   into another enum with the same field signature.
2. **Closed case set:** only declared variants are legal; every variant's
   argument count and names must match its declaration exactly. No implicit
   record-width compatibility when constructing a nominal enum.
3. **Generics:** substitute declared type parameters in the payload at the
   constructed enum's concrete instantiation. Do not infer across unrelated
   enum instances or silently use `Unknown` to accept ill-typed payloads.
4. **Recursion/indirection:** direct inline cycles of positive-size types are
   rejected by the layout checker. A real `Box<T>` must have a specified
   allocation, uniqueness/aliasing and destruction contract before
   `Box<Expr>` is admitted as an executable storage type.
5. **Tag and payload:** at runtime exactly one tag is active and only its
   fields are initialized/read. Wrong-tag projection traps safely rather
   than accessing uninitialized fields. Type parameters cannot be used as
   unsound runtime discriminants if erased.
6. **Matching:** unguarded constructor coverage and nested payload
   refutability feed the bounded matrix coverage checker; guards cannot
   prove totality. Ordered match arms must never be incorrectly marked dead
   due to assignment-only numeric widening.
7. **Ownership and actors:** moving a variant moves its initialized payload
   exactly once; borrowing cannot outlive owner, variant change, actor turn
   or suspension unless lifetime proofs permit it. An actor-boundary message
   may only move/copy/serialize an enum according to its complete payload
   capabilities, with no cross-tenant shared mutable identity.
8. **Backend parity:** Java/Graal and LLVM agree on constructor admission,
   variant identity, payload value semantics and ownership, even if their
   in-memory layouts differ. Stable external tag IDs require an explicit
   schema/versioning contract; source order alone must not silently alter
   serialized wire meaning.

## Next implementation slices

- Registry/indexing: detect same-scope duplicate type declarations, resolve
  `Enum<T>` generic arity, check payload references and recursive layout.
- Constructor values and tagged runtime: allocate nullary and payload
  constructors with nominal tag metadata and checked projections.
- Owned `Box<T>` and recursive `Expr` construction; destruction and GC
  integration with cycle/stack depth safety.
- Exhaustive match lowering for qualified constructors, record destructuring,
  nested `Option`/`Result`, guards, and finite product domains.
- Java/LLVM differential tests; property-based small-state coverage oracle;
  actor serialization and isolation conformance.

## Verification gates

The parser tests cover valid `Expr` and `Tree<T>` forms, duplicate
constructors/fields/generics, forbidden mixed payload shape, empty enums,
qualified pattern identity, and fail-closed compiler admission. The
**runtime milestone remains incomplete** until full executable-source tests
pass; no test has yet validated construction or evaluation of `Expr` values.
