# Negative types: first draft (Java/GraalVM)

**Status: draft compiler admission slice; not a completed language feature.**

## Meaning

The prefix operator `!T` is the complement of T in the Oreslang value
universe. It is not a runtime assertion or an implicit cast. When a value
passes to a `!T` parameter, the compiler must **prove** its static type
cannot overlap T; absence of evidence does not satisfy the constraint.

```oreslang
type NonString = !string;
type NoStringOrInt = !(string | int);
type NotThisShape = !struct{foo: int};
type NotThisRecord = !record{foo: int};

fnc accept(!string value): void {}
fnc yes(): void { accept(42); }
fnc no(): void { accept("text"); } // type error
```

`!true` does **not** mean `false`: `42` and `"text"` are also
not the singleton `true`. Boolean singleton types are distinct from
`bool`. At present Oreslang boolean expression inference widens literals
to `bool`, so a general `bool` is **not** yet admitted as `!true` merely
because an argument happens to evaluate to `false`.

Two negations normalize: `!!T = T`. Parentheses govern complements of
unions. Exclusion is structural where the compiler can prove
incompatibility. A missing field in an open/widened record view is **not**
evidence that the runtime object lacks the field; conflicting known field
types are acceptable evidence.

## Security / correctness boundaries

- No success from unresolved, generic, unknown or dynamic types.
- No structural deep reflection, runtime heap traversal, or erased-generic
  promises are used to manufacture exclusion evidence.
- Type exclusion does not bypass actor, channel, mailbox or message payload
  restrictions.
- Type-directed dispatch and equal-arity overloads remain forbidden.
- Syntax for `!record{...}` and `!struct{...}` currently shares the
  existing structural record type representation; distinct value-layout
  semantics belong to the record/struct design, not the negation operator.

## Outstanding before feature promotion

1. Add `A & B`, `never`, and canonical normalization
   (`bool & !true`, De Morgan's laws, and domain-aware finite enums).
2. Implement flow-sensitive `is !T`, `match` / `do match` and `select`
   patterns with safe reifiability checks and mutation/suspension invalidation.
3. Audit every compiler path that reconstructs or inspects the sealed Type
   algebra, including callbacks, return types, nested generic types, and
   channel/message validation. No conversion of `Not` into `Unknown`.
4. Confirm positive/negative tests from the OresCompiler executable front
   door, with genuine source execution and AOT/hybrid validation.
5. Port to LLVM and validate identical guest semantics and disjointness
   diagnostics. Keep this PR **draft** until verified.

## Initial test gate

`mvn -B -ntp -Dtest=NegativeTypeAdmissionTest test`

This draft commits tests but **does not claim that Maven or native-image has
passed**. Do not merge based solely on static inspection.
