# Compile-time units of measure: dimensional proof core v1

Status: Java compiler-core implementation plus JUnit tests; **NOT a source
language feature** and **NOT guest binary / Graal Native Image evidence**.

## Current slice

`dev.oreslang.types.UnitDimensions` provides canonical compiler-side
exponent vectors for up to 16 base units, each exponent in [-32, 32].
Multiplication adds exponents, division subtracts them and integer powers
scale them. Addition and subtraction require the same exact numeric scalar
and canonical physical dimension. Invalid identifiers, overbudget dimensions,
unproven numeric promotions and invalid types fail closed. No runtime unit
registry, reflection, dynamic generic instantiation or guest allocation is
needed for an eventual AOT lowering.

`UnitDimensionsTest` checks canonical ordering, inverse operations,
dimensional incompatibility, provenance-independent immutable metadata,
numeric scalar safety, exponent/basis bounds and malicious identifiers.
The focused CI gate compiles JUnit code using JDK25 at the exact PR head.
This is source-free compiler-helper evidence only.

## Required guest integration (not yet implemented)

1. Parse globally scoped `unit` declarations and dimension-annotated numeric
   types without confusing them with generic type arguments or reified types.
   Explicit unit identity is resolved at compile time and bound to the
   declaring package/module, not to an attacker-provided short string.
2. Map resolved declarations to a canonical typed-HIR dimension representation.
   The helper's base-unit strings are only temporary compiler identifiers,
   **not** cryptographic identities or sufficient cross-package provenance.
3. Run dimension checks for arithmetic, comparisons, assignments, returns,
   generics, function calls, collection elements, records, actor messages and
   FFI. Preserve existing static numeric conversions and integer overflow
   rules independently from the dimension algebra.
4. Support dimension-polymorphic generics with a finite AOT closure and bounded
   compile-time inference. Units carry no runtime type-object authority.
5. Define explicit rational or exact unit scaling (e.g., centimetres to metres).
   Dimension equivalence alone **must not** silently convert different
   quantity scales. Reject affine units (e.g., Celsius offset conversions)
   until a separately verified design exists.
6. Lower valid quantities to the existing underlying numeric ABI, with no
   runtime unit objects or reflective dispatch. Verify equal machine-code
   numeric semantics against unannotated scalar controls.
7. Require both Java/GraalVM *program-specific guest-code* Native Image and
   LLVM compiled guest executable tests. A Native Image of the interpreter,
   host-only helper tests and zero-step CI do not constitute feature parity.
   Track .NET/Wasm/Forge separately.

## Immediate acceptance vectors

- `f64<Meter> / f64<Second>` obtains `Meter^1*Second^-1`.
- The result multiplied by `f64<Second>` has dimension `Meter`.
- `f64<Meter> + f64<Second>` fails during type checking.
- `f64<Meter> + f64<Meter>` type-checks.
- Mixing `i64<Meter>` and `f64<Meter>` requires proven numeric conversion.
- Unknown units, forged dimensions, overflowing exponents or excessive bases
  reject with no guest code emitted.
- No unit type information or runtime lookup is needed for basic arithmetic.
- Cross-package unit identities and scale conversions do not depend on names.

Illustrative syntax only: no claim that `unit Meter;` or `f64<Meter>` currently
parses on Java or LLVM main.
