# Nested Option and Future extraction (Java/GraalVM audit, 2026-10-17)

## Decision: explicit flatten, never universal type equivalence

`Option<Option<T>>` is **not equivalent** to `Option<T>`:

- `None`: outer computation produced no optional value;
- `Some(None)`: outer computation succeeded with a missing inner value;
- `Some(Some(v))`: both layers are present.

The distinction matters for `trap`, pattern matching, `is_some`, `unwrap_safe`,
ownership, and serialization. A generic `Option<T>.unwrap(): T` must unwrap
**exactly one layer** to remain type sound when `T` is itself an Option.

Likewise, `Future<Future<T>>` is a distinct type representing two asynchronous
stages and failure/cancellation/lifetime boundaries. A generic
`await Future<T>: T` removes **exactly one layer**. Deep implicit runtime
adoption would disagree with the generic static return type when `T` is a Future.

## New explicit Java/GraalVM operations

| Source expression | Result | Behavior |
| --- | --- | --- |
| `Option<Option<T>>.flatten()` | `Option<T>` | Outer `None` -> `None`, `Some(inner)` -> `inner` |
| `Option<Option<T>>.unwrap()` | `Option<T>` | Panics only if outer layer is `None` |
| `Option<Option<T>>.flatten().unwrap()` | `T` | Panics if either layer is `None` |
| `Future<Future<T>>.flatten()` | `Future<T>` | Composes the two completion stages without blocking an actor carrier |
| `await nestedFuture.flatten()` | `T` | Awaits both stages |
| `await await nestedFuture` | `T` | Two explicit awaits |

`Option<T>.flatten()` and `Future<T>.flatten()` are rejected without
statically proven nested wrappers. The runtime rejects dynamically invalid
`Option.flatten()` payloads rather than fabricating a value.

**No automatic canonicalization** is performed within generic types, aliases,
lists, results, actor messages, WIT schemas or ABIs. `Some(None)` continues to
differ from `None` until the programmer explicitly calls `flatten()`.
The `trap` expression does not flatten successful `Option` payloads.

## Audit and remaining verification

- Java `Future.flatten()` delegates to the existing scheduler-owned
  `compose/flatMap` continuation path; no producer-thread guest execution
  is introduced by a new independent primitive.
- Java `Option.flatten()` is a single-layer value transformation, preserving
  ownership of the nested payload and the existing `unwrap` panic behavior.
- Future stage cancellation remains governed by the existing `flatMap`/source
  task rules; it is **not** proof that cancelling an outer Future cancels all
  independently owned inner work. That needs a separately specified contract.
- LLVM and JavaScript backends require corresponding typing, lowering,
  diagnostics and differential conformance. This patch covers Java/GraalVM only.
- Tests should cover present/absent nested values, generic parametricity,
  pending/error/cancelled inner and outer Futures, rapid completion races,
  host projection boundaries, affine payloads, and native-image execution.
  Until tests execute on the exact commit, results are unverified.

See `docs/TRAP_EXPRESSION_UNWRAP.md` for the existing throw/panic separation
and `docs/ADT_TYPE_SYSTEM.md` for exhaustive nested-pattern coverage.
