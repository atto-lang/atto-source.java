# Nested Option and Future extraction (Java/GraalVM audit, 2026-10-17)

## Core decision

Nested wrappers are **real types and real runtime values**, not universal aliases.

- `Option<Option<T>>` has `None`, `Some(None)`, and `Some(Some(v))`.
  Losing the outer distinction changes `trap`, `match`, `is_some`, and
  `unwrap_safe` semantics. The source type and constructors remain nested.
- `Future<Future<T>>` has two completion stages with their own cancellation,
  failure, and task lifetime obligations. Retain its distinct static type.

**Explicit extraction** is transitive in the Java/GraalVM evaluator:

- `Option<Option<T>>.unwrap()` yields `T`. Any `None` layer
  panics, including `Some(None)`; `is_some`, `is_none`, `unwrap_safe`,
  `unwrap_or`, `expect`, constructors and patterns are unchanged.
- `await Future<Future<T>>` yields `T`; any failure propagates and each
  pending stage is resumed on the admitted scheduler. `await ActorRef<T>`
  remains an actor readiness barrier, retaining `ActorRef<T>`.

`trap` **must not flatten** successful Option payloads: `trap someNone()`
remains `Some(None)`, distinct from the outer `None` produced by an ordinary
throw. Likewise, do not silently canonicalize generic types inside `List`,
`Result`, actor mails, WIT schemas, or external ABI contracts.

## Safety notes and remaining work

- The Java runtime iteratively consumes settled nested Futures, retains a
  per-await identity set across suspensions, caps a chain at 256 layers, and
  denies pending suspension with a live `MutexGuard`. Host projection retains
  the no-yield preflight.
- Deep `unwrap` preserves the existing panic pathway and uses an identity and
  depth guard. It does not make `None` a recoverable `throw`.
- Cancellation of one outer Future is **not** implicitly cancellation of every
  independently owned inner Future. A separate structured ownership and
  cancellation-adoption contract is needed before any implicit Future
  canonicalization.
- LLVM and JavaScript backends still need equivalent lowering, type checking,
  panic/error handling, nested-stage lifecycle tests, and cross-backend
  differential fixtures. This document does **not** assert parity there.
- Required follow-up: race tests where a nested stage is pending, cancelled,
  fails, or resolves to an already completed stage; large chains and deliberate
  cycles; affine payloads under `Option`; `trap` nested-state regression;
  compiler and runtime conformance in native-image as well as JVM.
