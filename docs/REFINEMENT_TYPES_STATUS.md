# Refinement types: Java/Graal implementation tracking

**Status (2026-10-17): parser and normalized interval-rule prototypes exist on this draft branch; full refinement types are NOT executable.** Compiler execution still fails closed until checked construction, schema lowering, ownership and boundary enforcement are implemented and tested.

Canonical proposed language contract: [oreslang-common-source/docs/REFINEMENT_TYPES.md](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/design/refinement-types-three-syntaxes-20261017/docs/REFINEMENT_TYPES.md).

Java owns the language reference implementation but must not claim syntax support until compile+runtime tests actually pass.

## Syntax to implement

~~~ores
@Size(min=1, max=32)
type Username = string;

type Username2 = string with length gte 1 and length lte 32;

define newtype Username3(string) with length gte 1 and length lte 32;

type NonBlank = string where it.trim().length > 0;
~~~

- Reserve `with` for conjunctions of compiler-known property comparisons; textual `eq/ne/gt/gte/lt/lte` and symbolic `==/!=/>/>=/</<=` operators are equivalent; `ge`/`le` are not accepted aliases.
- Reserve `where` for effect-checked pure deterministic Boolean predicates on immutable `it`; reject impure calls, await and mutation.
- `@Size`, `@Length` and `@NotEmpty` become **enforced type constraints**, not advisory Java Bean Validation annotations. Preserve unrelated existing annotation behaviors (e.g., `@Ret`, `@FromJson`, `@NamedParams`).
- `type` forms create refinements; `define newtype` creates a distinct nominal type. Generate `try_from` returning `Result<T, ConstraintError>`; expose owned `into_inner`, never an unchecked constructor.

## Java/Graal acceptance gates

1. Lexer/parser AST for annotations, constrained aliases, `with`, `where`, newtype declarations; formatter idempotence and unchanged parsing of existing `with`-like code.
2. Typed canonical refinement IR with source-span diagnostics, known-property type checking and intersection/entailment proofs for bounds and exact length.
3. Compile-time literals accepted/rejected; runtime input accepted only after explicit checked conversion; no implicit narrowing from unrefined runtime strings.
4. Unicode scalar value counts (not UTF-16 `String.length()`), with emoji and combining-mark tests.
5. Nominal newtypes, immutable owned unwrap, custom predicate purity, stable constraint ABI fingerprints.
6. Annotation-on-field/parameter/return lowering into same type contract.
7. Actor-message, serialization, FFI, imported module and hot-reload fail-closed validation.
8. Mutable collection refinements cannot be invalidated by aliasing/mutation; reject unsupported writes, do not silently trust one-time validation.
9. Tests with expected diagnostic codes, observed runtime `Result`, and CI artifact evidence.
10. Emit reflection-free Zorde schema/validator descriptors from normalized refinements and route dynamic `try_from`, serialization, FFI, HTTP/RPC and actor DATA admission through the same generated predicate contract. Do not duplicate validation semantics in Java host code.

Until these pass, the correct status is **unimplemented** rather than "supported by annotation" or "parser recognizes syntax".

## Zorde standard-library convergence

Zorde belongs in the Oreslang core/std-lib contract, with the public validation
facade under `std/zorde`. `stdlib/zorde.ores` now provides the first pure,
exact-integer scalar helpers as an incubating stdlib module. The full dynamic
schema/serialization code stays in `oreslang-serialization-and-validation`
until the promotion gates pass; this does not yet make `schema<T>()` or typed
Zorde parsing an executable compiler feature.

Parser normalization on aliases/newtypes recognizes the following contracts:

```ores
@MinLength(3) @MaxLength(32) type Username = string;
@Min(1) @Max(65535) type Port = int;
@Range(min=0,max=100) type Percentage = int;

type Username2 = string with length gte 3 and length lte 32;
type Port2 = int with value >= 1 and value <= 65535;
```

`@Size`, `@Length`, and `@NotEmpty` remain supported parser forms.
All normalize to the same built-in `length`/`value` bounds. Constraints on
unimplemented declaration sites (fields/functions/locals) must fail closed;
no annotation may be silently ignored or treated as validation metadata without
an enforcing validator.
