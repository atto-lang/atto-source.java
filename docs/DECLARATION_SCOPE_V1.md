# AttoLang canonical declaration scope v1

**Status:** source syntax and reference-parser compatibility proposal. Compiler-backed
execution and multi-backend acceptance require separate exact-head receipts.

## Normative source style

At file/package root, start definitions with `define`, then visibility
(`pub` or `private`), then other valid modifiers and declaration kind:

```atto
define pub class Cart as
  pub fnc checkout(): void { return; }
end

define pub fnc main(): void { return; }
```

Inside module, class, actor, interface, trait or contract bodies, do **not**
write `define` before members. For example:

```atto
define module cart_api as
  pub fnc validate(): bool { return true; }
  pub class Item as
  end
end
```

`define pub` is the sole canonical order, not `pub define`. The formatter
normalizes accepted historical spellings, while the compiler parses both.
To avoid breaking existing programs, the reference compiler also continues
to accept existing bare top-level declarations and redundant nested
`define` on declaration members. This is syntactic compatibility, not a
second binding kind, a new lexical scope, or a permission change.

## Boundaries

- Classes and actors do not acquire nested type declarations. Legacy
  `define` on an ordinary method, field or member is compatibility only.
- `define` inside an executable function body does not create a nested
  type or namespace and is never silently stripped by the formatter.
- Duplicate or conflicting modifiers are compiler errors, even when split
  around `define`.
- Comments, string literals, and user identifiers are not rewritten by
  declaration normalization.
- The `new T(...)` expression is intrinsic construction syntax. A
  `T.new(...)` factory exists only when explicitly defined under the
  compiler-owned `Newable[T]` marker; this does not synthesize a factory.
  General user-defined static trait protocols remain out of scope.

## AST and package identity

Modifier ordering and redundant nested `define` must produce identical
AST declaration kinds, visibility, type metadata, and exported symbol IDs.
A compiled package must not encode source formatting as part of its public
ABI identity. The same package manifest, namespace graph and exported
type/method signatures are required for source and binary consumers.

## Acceptance gates

1. `oreslang-format`: root `pub define` -> `define pub`, nested
   `define pub fnc` / `pub define fnc` -> `pub fnc`, nested class ->
   no `define`; idempotence; comment/string isolation.
2. Java/GraalVM: parse canonical and compatibility forms into equivalent
   AST/API, reject duplicate modifiers/nested types, then execute a guest
   consumer of a public API. A parser-only unit test is **not** guest execution.
3. LLVM, .NET NativeAOT, Wasm, Forge/Cranelift, JS and Rust backends:
   run the same `.atto` positive/negative fixture corpus on exact compiler
   heads, record nonzero executed steps and precise backend outcomes.
4. Precompiled-package consumer: publish a binary-only dependency, remove
   sources, import from its logical package/namespace identity and compare
   exported ABI metadata with the source-package build.
5. CI reporting: `steps=[]`, `steps=null`, `runner_id=0`, host-only canaries
   and unrun source fixtures are NOT_EXECUTED, never pass evidence.

Until gate 3 and 4 receipts exist, cross-backend and binary-package parity
remain **UNVERIFIED**.

## Audit hardening — scope boundaries and Newable

The formatter must not reclassify an invalid class/actor nested type as a
valid module declaration. `define pub fnc ... :: ... end` owns exactly
one callable scope frame, even though its header starts with `define`.

The Java reference compiler now enforces a sealed, zero-argument,
type-level `Newable[T]` witness; its first slice is specified below.
General user-defined static trait witnesses and binary-package AOT
metadata are separate outstanding gates. `new Cart()` never forwards
to `Cart.new()`.

## Newable[T] v1 executable admission contract (2026-11-10)

The Java reference frontend now supports a **compiler-owned** static
factory marker, distinct from ordinary instance interfaces:

```atto
define pub class Cart implements Newable[Cart] as
  pub fnc new(): Cart {
    return new Cart();
  }
end

define pub fnc create(): Cart {
  return Cart.new();
}
```

A declared `fnc new` in a class explicitly implementing `Newable[T]`
is lowered to a static member in the AST, even when the optional `static`
keyword is omitted. It is not synthesized by the compiler and is never
called by the intrinsic `new Cart()` expression.

V1 deliberately supports **one zero-argument, public, synchronous,
nongeneric** factory returning precisely its concrete owning class.
`Newable[T]` and the compatible `Newable<T>` are accepted in an
`implements` clause; `Newable` cannot be defined or shadowed by user
source. Incorrect arity, visibility, return type, generic witness,
abstract class and actor-factory attempts are rejected. Ordinary user
class methods named `new` without the explicit marker are rejected.

Backend AOT compilation, target-specific static witness metadata,
portable binary `.atpkg` emission/linkage, parameterized factory
signatures, generic classes, and user-defined static factory trait
declarations remain separate gates. This is **not** a declaration of
cross-backend or binary-package parity. Tests include parser/type-checker
cases and a Graal guest case; passing status requires actual executed
CI receipts, not merely the presence of source.
