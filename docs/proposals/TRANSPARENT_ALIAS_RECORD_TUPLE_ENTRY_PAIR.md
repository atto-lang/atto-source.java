> Integration status (2026-10-14): The Java parser/typechecker/ownership and
> import-visibility integration is on this stacked branch over PR #850.
> The canonical Entry, Pair and Two declarations are in `stdlib/core.ores`.
> Exact-source targeted Java 25 CI has passed; full Maven, GraalVM Native Image,
> transitive cross-file alias chains, full native toolchain validation and LLVM parity remain merge blockers.

# Transparent type aliases, records, tuples and entries

Status: **integrated Java parser/typechecker and import visibility; targeted verification passed; native parity pending**.
This document defines the intended cross-backend contract. It does not claim
native-image, LLVM or runtime serialization parity. Targeted Java linked-import
and formatter verification is documented below.

## Identity and capabilities

- `type Old = Target;` and `define type alias Old as Target;` produce the
  same `TypeAliasDecl`. A transparent alias is expanded in the compiler;
  it does not create a new nominal/runtime type, constructor, vtable or wire tag.
- Generic aliases substitute arguments recursively, preserving type and
  ownership capabilities, field/slot access and exact arity. Alias cycles
  and incorrect type-parameter counts must fail.
- The marker `alias` is contextual (not a reserved lexer keyword). Existing
  `type alias = int;` source must continue to parse.
- `pub` is visibility on the alias name, **not** a visibility upgrade for
  its target. Import/export resolution must reject private target leakage.
- A type alias cannot grant write access to read-only data, convert a borrowed
  reference into an owned value, or circumvent actor sendability restrictions.

## Product types and non-equivalence

| Source kind | Type identity | Arity/field semantics | Representation |
|---|---|---|---|
| `record{key: K, value: V}` | Structural fields | Named keys, field shape | Existing record representation |
| `struct{key: K, value: V}` in type position | Structural fields | Same field contract as `record` | Existing struct/record shape |
| `Tuple[K,V]` and `[K,V]` | Structural positional | Exact **two** slots | Existing tuple representation |
| `FixedArray[K,V]` | Fixed-array kind | Exact arity; distinct from tuple | Existing fixed-array representation |
| `Entry<K,V>` | Defined in `std/core.ores` | Named `key` and `value` | Existing record representation |
| `Pair<K,V>` | Transparent alias if declared | Whatever `Entry` defines | Exactly target representation |

Example of **a named-field** key/value entry:

```oreslang
// library.ores
define module products as
    type Internal<K,V> = record{key: K, value: V};
    define pub type alias Pair<K,V> as Internal<K,V>;
end
```

The `as` keyword is part of the canonical module header (`define module products as`).
The parser continues accepting older module headers without `as` during migration;
new code and examples must include it.

This is **not** equal to the positional type `Tuple[K,V]`.
A separate alias `type Two<A,B> = Tuple[A,B];` names a positional pair.
The core library now defines Entry with named fields; do **not** silently
make every two-element tuple an Entry.

## Compiler invariants to prove and test

1. **Erasure:** resolve(Alias<X>) == resolve(Target<X>) after expansion.
2. **Termination:** finite acyclic alias expansion, deterministic diagnostics
   for cycles, duplicate declarations and incorrect generic arity.
3. **Mutability:** alias expansion cannot weaken read-only/borrow capabilities;
   mutable nested fields and containers require invariance wherever aliases
   can give writable access.
4. **Structural width:** a value with extra fields can satisfy a narrower
   read-only field contract without erasing underlying readonly capability.
5. **Sequence identity:** record != tuple, tuple != fixed-array/list. Tuple
   arity and literal index bounds remain exact after alias expansion.
6. **Actor transport:** the alias does not select a codec. Recursively validate
   the expanded field/slot types for sendability, serialization and cycle
   limits; reject borrows, functions and mutable cross-actor aliasing according
   to the standard actor transport rules.
7. **Visibility:** aliases used by other files must be explicitly imported
   and must not leak private underlying nominal names.
8. **Backends:** Java/GraalVM, Native Image and LLVM must agree on all accepted
   and rejected programs; unsupported features must fail closed.

## Merge gates

- Parser and `TypeChecker.check` tests for both spellings and generic
  substitution; tests for cycles, wrong arity, out-of-bounds tuple access,
  tuple-versus-record mismatch and contextual `alias` identifier.
- Formatter idempotence, with canonical `define pub type alias ...` ordering.
- Compiler runtime smoke for record/tuple aliases (no new runtime constructor).
- Cross-file public/private aliases and import graph cycle checks.
- Native AOT and LLVM parity, then actor serialization/schema compatibility.
- Exact-commit CI evidence before promoting this draft PR to merge-ready.

## Known audit concerns

The integrated structural ownership model from PR #850 enforces invariant
writable record slots and no-coercion readonly projections; these assertions
are covered by bounded Java type-model tests. Full native parity remains open. The compiler also has an opaque-name fallback when a named type is not
resolved locally; requiring alias targets to resolve conclusively needs a
whole-program/import-linking contract, not a parser-only check.

The stdlib supplies `Entry` and `Pair` as compile-time declarations, not
primitive builtins or new runtime constructors/wire representations.

## Cross-source hardening (October 14, 2026)

A public `import type` declaration is **not** a type-layout witness.
The incremental linker resolves the named exported declaration in the exact
source unit and recursively substitutes all source-local/private helper
aliases before passing a synthetic public signature to the type checker.

- `import type Pair from "./library.ores"` and named `import types Pair`
  receive a checked type ref, not an opaque `Named("Pair")`.
- Direct standalone type checking of an imported type without linked metadata
  fails closed on its first type use.
- Only `pub` alias names are exported. Private helper alias *names* do not
  become public, although their representation can be incorporated into a
  publicly exported structural signature.
- Unknown foreign *nominal* targets are rejected until a verified
  cross-unit nominal ABI witness is supported. Cross-file alias chains that
  require re-exporting foreign imported types remain a documented limitation.
- Cycles, wrong type-parameter counts, unrecognized compiler-synthetic types,
  recursive expansion depth above 64 and total expanded nodes above 16,384
  fail with deterministic type/link diagnostics rather than unbounded work.
- A named entry `record{key:K,value:V}` and positional `Tuple[K,V]` have
  distinct field/index semantics and no automatic conversion.

Verification: the private funded [exact-source JDK25 JUnit run](https://github.com/embedded-alerts-test/web-ui-e2e/actions/runs/38616962504)
passed **24 tests**, and the [cross-file mini-compiler harness](https://github.com/embedded-alerts-test/web-ui-e2e/actions/runs/38616962628)
passed its targeted 11 cases. The mini-compiler harness replaces the
runtime-dependent `OresCompiler` façade with an equivalent
parse/type-check/ownership admission entry point, while compiling the
real Oreslang `Parser`, `TypeChecker`, `OwnershipChecker`,
`IncrementalCompiler`, `ImportGraph`, and `PublicTypeAliasLinker`.
This is not whole-repository Maven, native GraalVM, actor transport or
LLVM conformance evidence.

## Latest exact-source audit (October 14, 2026)

- Generic aliases are lexically scoped: free type parameters inside private
  aliases cannot capture exported alias parameters.
- Instantiation-key cycle detection accepts `Box<Box<int>>`, while cyclic
  and expanding recursive aliases are bounded by both 64-level and 16,384-node
  limits; the main type checker bounds the logical generic DAG *before*
  structural hashing.
- Simple module headers are formatted as `define module Name as`, and new
  fixtures/docs use the same canonical syntax. Legacy headers still parse.
- [Java 25 focused source JUnit](https://github.com/embedded-alerts-test/web-ui-e2e/actions/runs/38624987260)
  **29 passed / zero failures**; [cross-file compiler harness](https://github.com/embedded-alerts-test/web-ui-e2e/actions/runs/38624987037)
  passed. [Exact formatter Rust CI](https://github.com/embedded-alerts-test/web-ui-e2e/actions/runs/38624267047)
  **152 passed**.
- Full-repository Maven, GraalVM Native Image, LLVM front-end/lowering,
  actor transport parity and multiple-hop foreign alias linking are
  **not yet verified or fully supported**. This remains a draft PR.
