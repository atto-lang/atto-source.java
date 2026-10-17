# Zorde in Oreslang core and stdlib

**Decision:** Zorde is a standard-library capability, not an optional third-party
schema engine. The independent `oreslang-serialization-and-validation`
repository remains its **incubation, conformance and codec-adapter repository**.
A caller should ultimately import the public validation surface from
`std/zorde`, while compiler-generated constraint descriptors and checked
construction are built on the shared core validation contract.

## Single source of truth

The compiler lowers all of the following to the same canonical constraint IR:

```ores
@MinLength(3)
@MaxLength(32)
type Username = string;

@Length(min=3,max=32)
type Username2 = string;

type Username3 = string with length gte 3 and length lte 32;
type Username4 = string with length >= 3 and length <= 32;

@Min(1)
@Max(65535)
type Port = int;

@Range(min=1,max=65535)
type Port2 = int;

type Port3 = int with value gte 1 and value lte 65535;
type Port4 = int with value >= 1 and value <= 65535;
```

These declarations have equivalent predicates for the same underlying
representation, except that `define newtype` additionally introduces a
nominal type identity. The runtime validator cannot reinterpret or silently
drop the compile-time predicates.

### Canonical annotation rules

| Annotation | Allowed base | Normalized predicate |
| --- | --- | --- |
| `@NotEmpty` | string | `length gt 0` |
| `@MinLength(N)` | string | `length gte N` |
| `@MaxLength(N)` | string | `length lte N` |
| `@Length(eq=N)` | string | `length eq N` |
| `@Length(min=A,max=B)` | string | `length gte A and length lte B` |
| `@Size(min=A,max=B)` | string | same inclusive length interval |
| `@Min(N)` | int | `value gte N` |
| `@Max(N)` | int | `value lte N` |
| `@Range(min=A,max=B)` | int | `value gte A and value lte B` |

All bounds are compile-time signed `int64` literals. Length bounds must be
nonnegative; contradictory constraints are compile-time errors. Scalar
comparisons use `gte/lte` or `>=/<=`; `ge/le` are not source aliases.
Integer comparisons are exact; no binary floating-point intermediate is
permitted. String length means Unicode scalar values (code points), not UTF-8
bytes or Java UTF-16 code units; no implicit Unicode normalization occurs.

### Core responsibility

- Canonical constraint AST/IR: property, comparator, bound, source span,
  nominal identity, effective normalized interval and exclusions.
- Pure constant evaluation, range proofs and refinement entailment.
- Compiler-generated static validator/schema descriptors and stable fingerprints.
- Exact UTF-8/Unicode scalar counting and integer range checks.
- Checked-construction error contract and fail-closed admission at untrusted
  boundaries, including typed decoding, actor DATA, RPC and FFI.
- Enforced purity for `where` predicates before any runtime lowering.

### Stdlib responsibility

- A public `Zorde` module / `std/zorde` import facade.
- Familiar builder/parse APIs: string length, numeric range, object fields,
  schema composition, validation reports, `Result`, safe parsing and
  typed `schema<T>()` when compiler generation exists.
- The module delegates to core validators and compiler-generated descriptors,
  never runtime reflection, implicit coercion or independent predicates.
- Format modules (JSON, MessagePack, CBOR, Protobuf, etc.) use the same compiled
  validator and typed metadata at decode boundaries.

## Implemented seed versus future work

`stdlib/zorde.ores` currently introduces a small, pure numeric entrypoint:
`Zorde.int_at_least`, `Zorde.int_at_most`, `Zorde.int_between` and
`Zorde.int_exact`. They operate on exact Oreslang integers, not floats. This
is **not** an implementation of compiled `@Min/@Max` semantics or the complete
schema API. Compiler admission still rejects refined aliases/newtypes until
checked construction, ownership and boundary enforcement are ready.

The existing `src/validation/validation.ores` and `src/zorde/zorde.ores` in
`oreslang-serialization-and-validation` are the native dynamic-model proving
ground. Their full promotion to `stdlib/` depends on public type exports,
correct ownership and immutability, integer-exact dynamic Value rules,
generated schema bindings, hostile-input tests and executed cross-backend
conformance. In particular, do not use the current float-based
`zorde_number(...)` to claim exact `int64` validation.

The current parser supports the annotations on aliases and newtypes only.
Field, parameter, return and local annotation enforcement is **planned**:
until compiler/runtime support is complete, the parser must reject an
unimplemented enforced annotation rather than preserve it as inert metadata.

## Standard-library acceptance gates

1. `@MinLength(3) @MaxLength(32)` must normalize to the same bounds as both
   `with length gte 3 and length lte 32` and the symbolic form.
2. `@Min(1) @Max(65535)` and `@Range(min=1,max=65535)` must match the
   `Port` keyword/symbolic forms exactly.
3. Negative lengths, wrong base types, duplicate bounds, contradictory
   intervals, malformed numeric constants and overflow must be rejected.
4. `int64` extremes and Unicode surrogate/emoji/combining-mark fixtures must
   be covered with actual executed tests.
5. Dynamic Zorde checks must produce the same accept/reject outcome as the
   compile-time proof evaluator, without lossy coercion.
6. Public `Valid<T>` typestate may not be forged or invalidated by mutable
   aliases, FFI or deserialization.
7. Java, LLVM and other backends may not claim stdlib refinement support until
   the same conformance fixtures and trust-boundary gates execute successfully.
