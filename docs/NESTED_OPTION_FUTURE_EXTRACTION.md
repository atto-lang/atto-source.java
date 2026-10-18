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
  The ownership checker consumes move-only Option and Future wrappers at
  `flatten()`; no repeated extraction or mutation of an affine handle is allowed.
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

## JVM verification gate (2026-10-17)

Focused language/ownership/scheduler regression command:

```bash
mvn -B -ntp -Dtest=NestedOptionFutureUnwrapTest,NestedFutureSchedulerContractTest,OptionResultSemanticsTest,TrapOptionSemanticsTest,FutureCompositionLanguageTest,AsyncAwaitLanguageTest,OresSchedulerTest test
```

Then run `mvn -B -ntp verify`, GraalVM Native Image smoke tests,
and back-end differential fixtures. Passing static inspection is not passing CI.
A workflow that finishes before any job steps execute does not validate Java source.
Record actual step outcomes and commit/tree SHA with every conformance claim.

**Private CI transfer is denied by default.** The checked-in
`.github/private-ci-approved-repositories.txt` currently has no approved
repositories. `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md` requires a
reviewed protected-branch approval of an exact private destination before
any source-bearing blob transfer. Do not use a public or internal repository,
temporary public refs, caches or logs to bypass the gate. Cross-organization
`GITHUB_TOKEN` checkout without permission is not an acceptable shortcut.

## Cancellation and race conformance

A composed Future has one scheduler-owned continuation with two await
registrations, not two simultaneous registered waits. Once the outer stage
settles, the continuation adopts the inner Future as a new await. Cancelled
compositions must detach the currently active registration and never
re-enter guest code after terminal revocation.

An independently owned producer is **not** automatically cancelled because
a composition stopped waiting for it. A future API for structured
cancellation must specify explicit task ownership separately from
`flatten()`. `NestedFutureSchedulerContractTest` probes the scheduler
part of that contract with pending, failed and cancelled stages, while
`NestedOptionFutureUnwrapTest` probes the actual language-level member.
Neither suite is a substitute for an executed JVM run.
