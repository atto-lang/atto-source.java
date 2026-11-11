# Value pipelines and tuple-destructuring lambdas (AttoLang)

## One value per stage

Functional `|>` forwards one value. The RHS is an existing callable; there is
no new `|>(name) -> ...` grammar. For a bare inline lambda:

- `value |> |v| -> f(v)` receives the **entire** value, including a tuple.
- `tuple_value |> |a, b, c| -> f(a, b, c)` matches a **single statically
  known** `Tuple[T1, T2, T3]` against three parameters. It is not a
  multi-return calling convention and does not affect an ordinary direct call.
- Exactly one lambda parameter never implicitly unwraps a one-element tuple:
  `tuple(1) |> |whole| -> whole` receives `Tuple[int]`.
- A tuple can be returned by any stage via `tuple(...)` or a function declared
  with `Tuple[T1, T2, ...]`. The next stage receives the tuple as one value
  or explicitly binds its elements with a multi-parameter lambda.
- Heterogeneous and nested tuples keep each slot's static type and order;
  destructuring is one level only.

```atto
fnc fetch(): Tuple[int, int, int] {
  return tuple(4, 2, 3);
}

fnc validate(Tuple[int, int, int] value): Tuple[int, int, int] {
  return value;
}

fnc build(): Tuple[int, int] {
  return fetch()
    |> validate
    |> |users, x, y| -> tuple(users + x, y)
    |> |total, count| -> tuple(total, count + 1);
}
```

The result of `build()` is `Tuple[int, int]`, **not** an array, a
flattened multi-return, or a dynamic/unknown value.

## Strict, AOT-safe admission

For lambda arity `N > 1`, the incoming static type must be exactly
`Tuple[T1, ..., TN]` (tuple kind, not FixedArray, FixedList, List,
Array, Option, Result, Future, or a dynamically typed value).

Reject non-tuples, wrong arity, unresolved tuple element types and incompatible
explicit parameter types at compile time. The evaluated upstream tuple is moved
or copied **once** according to its ownership contract; no element is cloned
implicitly. Destructured parameters are lexical, immutable by default, and
each corresponds to one type-checked slot. Borrowed/guard-bearing and unsafe
escaping values remain fail-closed until an explicit lifetime proof exists.

The runtime checks the actual tuple's slot count before invoking the multi-
parameter lambda. Java/GraalVM currently erases tuple runtime values to Java
lists; consequently runtime list shape is a defensive check, **not** a
replacement for the independent static tuple/type/ownership proof.

Pipeline stages remain eager and left-to-right, and no implicit
`Future<T>`/ `Option<T>` flattening is introduced. The functional
`|>` stays separate from actor transport `pipe`.

An unparenthesized inline lambda body stops before the next top-level `|>`.
To place a nested pipeline *inside* a lambda body, parenthesize it:

```atto
tuple(3, 4)
  |> |x, y| -> (x |> increment) + y
  |> normalize;
```

This is a Java/GraalVM **candidate source contract**. Executable Maven/JUnit
and AOT receipts are required before claiming Java runtime validation or
LLVM/.NET/Wasm/Forge compiler parity. Zero-step CI is NOT_EXECUTED.
