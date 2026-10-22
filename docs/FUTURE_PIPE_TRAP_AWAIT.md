# Future-aware value pipelines and trap/await ordering

Status: **proposed future-pipeline lowering; not executable parity yet**.
The existing Java source-language `|>` operator eagerly injects the left value
as the first argument of the stage call. This document specifies a *new*
statically checked lifting rule for Future inputs; do not claim it works before
the compiler, runtime, formatter, LLVM, and conformance tests pass.

## Canonical spelling

```oreslang
val Option<Receipt> outcome = trap await (
    future()
    |> validate()
    |> save()
    |> notify()
);
```

The parentheses group the *complete* pipeline. `trap await E` parses as
`TrapExpr(AwaitExpr(E))`, returns `Option<T>`, and covers failures which
occur before, during, and after suspension. It does **not** return
`Future<Option<T>>`. The `Option` element is the concrete final output
type, not a forced `any`.

`await trap E` instead parses as `AwaitExpr(TrapExpr(E))`; when `E`
produces `Future<T>` its operand type is `Option<Future<T>>`, so `await`
must reject it unless a separate explicit extraction occurs. Never quietly
commute `trap` and `await` in the formatter or compiler.

## Statically selected pipeline lowering (new requirement)

If an upstream expression statically has type `Future<A>` (or a statically
resolved `HasFuture<A>` with an authorized projection) and a stage is a
callable `f(A, ...args) -> B`, the stage is *deferred* until successful
completion of the upstream operation. It creates a new `Future<B>` without
running guest code on a producer thread. If `f` returns `Future<B>`, chain
that future and produce `Future<B>` (one level of automatic flattening,
not `Future<Future<B>>`). If `f` returns ordinary `B`, lift that
result into the new future. Preserve synchronous `|>` behavior for ordinary
(non-Future) values and the separate `pipe` actor-stream operator.

For example, with `load(): Future<A>`, `save(A): Future<B>`,
`notify(B): Future<C>`:

```oreslang
val Future<C> pending = load() |> save() |> notify();
val C result = await pending;
val Option<C> safe = trap await (load() |> save() |> notify());
```

Conceptual lowering is `load().flat_map(save).flat_map(notify)`, with checked
types/effects/ownership and safe actor-bound scheduling. The parser currently
lowers `|>` to ordinary `save(load())`, so this is *not* yet supported.

### Evaluation order, failure, and lifetime obligations

- Evaluate the initial producer once; do **not** evaluate stage arguments or
  execute stage bodies until that stage is admitted after upstream success.
  Each stage executes at most once. A failed/cancelled earlier stage prevents
  all later stage bodies and argument side effects.
- Carry typed `throws` and `raises` effects across each composed stage;
  do not erase them by creating a Future or lowering to an untyped callback.
  A failure completes the dependent future without executing later stages.
- `trap await` is *lossy*: `Some(T)` on success, `None` only for ordinary
  **trappable** failures. Cancellation, panic, authority denial, host failures,
  and non-trappable timeouts do not turn into `None`. Typed `raises`
  require their proper recovery boundary; panic routes to supervisor CONTROL.
  To preserve error details use explicit typed handling/`Result<T,E>`
  rather than lossy `Option<T>`.
- `Result.Err(E)` is a successfully produced **value** unless an explicit
  Result-aware combinator handles it. Hence trapping an awaited
  `Future<Result<T,E>>` yields `Some(Err(E))`, not `None`, absent a
  separate exceptional effect.
- Preserve owner actor dispatch affinity and the no-Zalgo rule: a completion
  thread only enqueues continuation work, including already-completed futures.
  Never resume guest code on the producer/JNI/timer stack.
- Cancel or detach *owned* dependent operations and callbacks on scope/actor
  shutdown; do not cancel shared upstream futures merely because one
  downstream observer stopped. Preserve revocation, quiescence, resource
  release, arena and borrow-lifetime fences. A borrow cannot silently cross
  an unproven suspension boundary.
- Pipeline return types and failure effects are inferred statically. Wrong
  port types, unsupported effect propagation, unowned cross-actor values,
  and unimplemented backends **fail closed** at compile time.

## Required executable acceptance matrix

1. Success with Future -> value -> Future -> void and typed `Option<void>`.
2. Mixed synchronous/asynchronous stage returns with exact type inference.
3. Early/late ordinary stage failure short-circuits following body **and**
   stage-argument side effects; `trap await` returns None.
4. Early/late cancel, authority denial, timeout, typed raise, panic and
   source/host exceptions cannot accidentally turn into `None`.
5. `await trap (future-pipeline)` is rejected; `trap (future-pipeline)`
   cannot swallow a future asynchronous failure.
6. Synchronously settled and pending futures execute callbacks only through
   fresh actor/root dispatches, exactly once, under concurrent completion.
7. Cancellation propagation, detach races, shared producer survival,
   retention cleanup, nested Future and actor supervision.
8. Java/GraalVM, LLVM, JS, Wasm, and formatter/conformance parity: the
   *same* source fixtures and observable outcomes, not synthetic AST-only
   or isolated model proofs.

Related Java Future host hardening: PR #1915; source callbacks: issue #1916.
