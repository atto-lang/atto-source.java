# Nominal free callable overload implementation (stacked on typed-enum PR #623)

This draft implements a **local-source Java/GraalVM interpreter subset**, not
a completed Java/LLVM/linker feature and not a change to class/trait/interface
method slots. The dependency branch is still unmerged. PR #818 independently
fixes a root/module `fnc`/`routine` namespace collision on current main.

## Accepted local syntax

```oreslang
define enum OfPrimitives { Foo = 1 }
define enum AlsoPrimitives { Foo = 1 }

pub fnc x(OfPrimitives f): int { return 10; }
pub fnc x(AlsoPrimitives f): int { return 20; }

pub routine main(): void {
    stdio.stdout.write(x(OfPrimitives.Foo));
    stdio.stdout.write(x(AlsoPrimitives.Foo));
}
```

- Braced or `as ... end` enum syntax; payloadless `Enum.Member` and
  constructor `Enum.Member()` values share the existing nominal TaggedValue.
- For file/root or a named module, `fnc` and `routine` share
  `(scope, name, arity)`; same-arity declarations must be pairwise disjoint
  by **at least one common position** holding different concrete primitive
  or root named-enum type identities. Per-case wire tags and return types do
  not select an overload. Structural, generic, unknown and aliases are not
  discriminators; ambiguous declarations are rejected early.
- Static type-checking selects a unique declaration by primitive/enum
  signature before ordinary generic, parameter, effect and ownership checks.
  Unknown/generic/union static arguments require narrowing. A dotted variant
  name is accepted only if its owner and case are explicitly registered in
  the closed enum registry; invented `Enum.Missing` type names cannot
  impersonate a registered enum.
- A lexical generic type parameter shadows the enum of the same spelling.
  Such a parameter is **not** a nominal discriminator even when a root enum
  exists with that name. We reject apparent overload disjointness caused by
  this spelling collision.
- Overload registration verifies each new candidate against previously
  admitted signatures. This makes cumulative admission quadratic rather
  than repeatedly comparing all old pairs after each insertion (cubic).
- Java/Graal frontend returns an immutable **identity-keyed source call
  binding map**. OresLanguage passes that map to OresEvalRootNode, and the
  evaluator executes only the already-selected declaration; it validates
  runtime primitive/enum tags as a consistency check, never as a reason to
  pick another target. Forged/stale enum case tags or payloads fail closed.
- Unverified host/dynamic/imported nominal overload calls are rejected
  because they lack a trusted static call binding and cross-file manifest.
  Class/interface/trait method selectors are unchanged.
- Speculative argument typing used to choose an overload rolls back
  definite-field-initialization metadata before the normal argument checker
  commits its effects. Qualified module calls must select public callables,
  consistent with the interpreter's access checks.

## Intentional blockers

- Local Java execution now honors a compiler-provided source AST call
  binding, but **LLVM, Native Image, Wasm and cross-file imports** still need
  a canonical versioned symbol manifest with owner-qualified enum identities.
  No evidence of native machine-code static-link dispatch is claimed.
  A legacy evaluator constructor without compiler bindings rejects overloaded
  calls rather than falling back to reflective runtime selection.
- The tagged-enum branch currently accepts enums only at file root.
  Same-named enums in different files/modules do not yet have canonical
  **package/module-qualified** nominal IDs, import/export manifests or ABI
  guards; source-unit cross-file enum overloads remain unsupported.
- Generic aliases to enums are *not* discriminator tags yet; no implicit
  narrowing or fallback to a structural overload.
- Higher-order overloaded function references require explicit typed
  specialization; root/module calls with statically expandable spreads
  need explicit desugaring and ABI verification first.
- This subset uses Java's current erased primitive category model:
  `i8`/`i16`/`int` share the same tag. This is intentional and rejects
  overloads that differ only by integer width. Decimal/complex cannot yet
  distinguish same-arity overloads because their static tags and runtime
  representations are not proven equivalent. Each same-arity family is capped
  at 256 declarations and 64 explicit arguments. Different scheduling/effect
  envelopes (async, suspension, generator, trap, actor) are rejected.
- No claim of passing CI is made until the exact source branch has run Java
  JDK 25 Maven and Native Image AOT/hybrid gates. LLVM's current native
  free-function AST supports restricted typed i64, not general enum values.

## Regression coverage

`NominalFreeCallableOverloadTest` exercises enum identity with identical
numeric values, 0-argument enum member syntax, constructor compatibility,
root and module calls, mixed primitive/enum tags, typed variables, rejection
of same-tag signatures, return-only overloads and generic overlaps.

Further required: import/linker canonical enum ID, a native compiled
symbol selector, static slot binding for imported call sites, alias ABI rules,
compiled-guest tests, sanitizer/native-image parity and ABI export versioning.
A dedicated exact-blob LLVM selector-only proof is mirrored to a funded CI
repository; this must **not** be treated as full LLVM guest execution.
