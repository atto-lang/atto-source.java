# Local aggregate types v1 — staged source contract

Status: **stage 1 parser/AST only; execution deliberately rejected**.

## Syntax

The following are legal local, compile-time declarations in both `fnc` and
`routine`, including nested lexical blocks:

```oreslang
pub fnc make_point(): T {
    struct T { int foo }
    return T{5};
}

pub routine make_alias(): T {
    type T = struct { int foo }
    return T{foo: 5};
}

pub fnc make_record(): T {
    record T { int foo }
    return T{5};
}

pub routine make_record_alias(): T {
    type T = record { int foo }
    return T{5};
}
```

The field list also accepts `foo: int`. Type declarations are static and
implicitly immutable. `const type` is rejected. Local classes, actors,
traits, modules, and namespaces remain forbidden.

A named `struct T` introduces an identity distinct from a structural type
alias. `type T = struct {...}` refers transparently to an exact closed struct
shape; it must not erase to a readonly record. `record T` and
`type T = record {...}` both name readonly structural record shapes.

`T{...}` accepts either positional values (declaration field order) or
statically named fields, but not a mixture. Duplicates and missing, extra, or
mismatched fields must fail type checking. Construction is never a dynamic
schema factory.

## Signature/ABI contract (future stages)

* Compile-time predeclaration resolves `T` in the enclosing callable's
  return signature even if `T` is declared in its body. Only a direct,
  unconditionally declared local type at callable root may escape through the
  callable's public signature; nested conditional/block types must not escape.
* A public return type must carry stable exported ABI metadata, public field
  access policy, and exact layout/type identity independent of execution.
  Callers may inspect exported public fields without naming the private local
  `T`, subject to the normal field-visibility rules.
* Local types do not allocate or register new runtime type identities on every
  invocation. AOT and JIT must agree on type identity and ownership.
* Value binding capabilities (`const`, `val`, `let`, borrows) determine
  instance mutation and ownership independently of immutable type declarations.
* Reject alias cycles, shadowing, duplicate declarations, nonsensical recursive
  value layouts, and escapes across actor/async/FFI boundaries without exact
  transferable ABI support.

## Stage gates

1. **Stage 1 (this PR):** dedicated `LocalTypeDeclStmt` and
   `LocalAggregateConstructExpr`, parser regression tests, separation of
   prefixed `struct` versus `record` type ASTs, and explicit fail-closed
   type-checker diagnostics. **Do not claim compilation or execution of any
   accepted syntactic example.**
2. **Stage 2:** lexical compile-time symbol registration, forward signature
   lookup, nominal versus transparent semantic identity, exact construction
   typing, definite initialization, public ABI checks, ownership checking,
   reachability, and return-type metadata.
3. **Stage 3:** executable lowering and codegen, exact-head parser/typechecker,
   AOT and runtime fixtures (including private cross-org CI security gates)
   across Java, LLVM, Wasm, .NET, and Forge. Only then enable execution.
4. **Stage 4:** formatter/source round-trip, API/ABI compatibility vectors,
   mutation/borrow/actor isolation tests, and explicit parity evidence.

The stage-1 syntax admits new nodes only to preserve their meaning for future
phases. If they reach execution today, rejecting is mandatory: silently
discarding declarations or coercing `T{...}` to an untyped object is a bug.
