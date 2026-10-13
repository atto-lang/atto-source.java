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
  Unknown/generic/union static arguments require narrowing.
- Java interpreter repeats the validated scalar/enum tag decision for
  execution and validates variant construction at its boundary. It does not
  compare member names, numeric wire tags or structural shapes.

## Intentional blockers

- The Java interpreter still mirrors the selection from runtime values:
  native AOT, LLVM, Wasm and external imports must bind the statically chosen
  target to one unambiguous compiled symbol. Do not characterize this as
  machine-code static-link resolution or general constant-time native vtables.
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
  overloads that differ only by integer width.
- No claim of passing CI is made until the exact source branch has run Java
  JDK 25 Maven and Native Image AOT/hybrid gates. LLVM's current native
  free-function AST supports restricted typed i64, not general enum values.

## Regression coverage

`NominalFreeCallableOverloadTest` exercises enum identity with identical
numeric values, 0-argument enum member syntax, constructor compatibility,
root and module calls, mixed primitive/enum tags, typed variables, rejection
of same-tag signatures, return-only overloads and generic overlaps.

Further required: import/linker canonical enum ID, static resolved-symbol
binding, mixed async/effect call preemption analysis, compiled-guest tests,
sanitizer/native-image parity and ABI export versioning.
