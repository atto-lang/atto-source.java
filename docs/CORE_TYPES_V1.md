# core/types v1 — Java backend

This folder is the Java/GraalVM backend's independent freestanding AttoLang implementation. The public module is `core/types.atto` (`types`). Do not import implementation source from another backend.

## Shipped source surface (not yet certified)

- Logical value width: `int_value_bits() == 64`, `bool_value_bits() == 1`, `byte_value_bits() == 8`. These **do not** claim object/ABI size, alignment, atomicity or physical layout.
- `int_min_value()`, `int_max_value()` for signed 64-bit `int`.
- `fits_i8/i16/i32/i64(value: int)` and `fits_u8/u16/u32/u64(value: int)`: range checks **for an existing signed-64-bit int input**. They do not construct narrowed values or certify a compiler's implementation of sized integer types. In particular, `fits_u64` cannot cover source values outside signed-int range.
- `bool` is not numeric; `void` is only a return marker. No truthiness or null representation is introduced.

## Deliberately unimplemented compiler contracts

`TypeId` must be an opaque, compiler-minted nominal identity (not an integer/string/user field); `TypeInfo<T>`/AOT descriptors require compiler-owned static metadata with package/version/hot-load generation provenance. Never infer identity from source display names or host reflection. `Unit` must be a distinct inhabited singleton value type, not an alias for valueless `void`; add it only once the backend admits singleton aggregates and stable import/export. Sized integer types must have actual checked ranges; these predicates alone do not implement them. `bigint` may not silently alias `int`. Host ABI layout/size/alignment belong in `core/mem`, not these value-bit helpers.

## Acceptance gates

1. Parse/type-check and link `tests/fixtures/core-types-v1/edge-positive.atto` against this actual source, then execute compiled guest and assert exit 0.
2. Reject `tests/fixtures/core-types-v1/void-value-negative.atto` before emitting an artifact.
3. Validate signed/unsigned extrema and negative-to-unsigned cases in interpreter/JIT and Native Image AOT profiles where available.
4. Record exact source and compiler SHA, target/toolchain, nonzero runner steps, and passing/failing/unsupported status. Source-free helper tests or zero-step Actions runs are **NOT_EXECUTED** guest evidence.

Related overlapping work: atto-source.java PR #701 (transparent aliases/Entry/Pair) and #669 (Any/Unknown) are distinct compiler type-system efforts; do not merge or redefine their symbols here.
