# Future-aware value pipelines and trap/await ordering

Status: **partial host/runtime implementation; source syntax and parity
remain unimplemented and unverified**. The Java runtime now has the staging
helper `dev.oreslang.runtime.TaskPipeline.start`: one `OresScheduler.Task`
executes deferred stages, awaiting any Future returned by a stage without
allocating a new task for each stage. The helper returns an exposed Future
and internally also has scheduler and tracked completion Futures; do not
claim that only one Future allocation occurs. It does not yet create a
source-visible Task or TaskScope, although the trusted Java runtime now exposes
`TaskPipeline.startTracked` (a TaskHandle-owned pipeline) and
`TaskPipeline.startInScope` (a TaskScope child), with `PipelineHandle.getDone()`
as the underlying Task's physical quiescence witness. This does **not**
establish physical retirement of any independently scheduled FFI/child work
which lacks a separate owned lifetime receipt.

The parser retains `Ast.CallExpr.origin` for value-pipe argument injection
and Option/Result receiver combinators, distinct from ordinary calls and actor
`pipe`. This is syntax provenance only, not a typed/effect-checked pipeline
plan. `CallExpr.rewrite` retains that origin but drops identity-bound checked
proofs; replacements require rechecking. Explicit checked-metadata transfers
reject changed call origins before copying any proof.

The standalone `bash scripts/test-future-pipeline-provenance.sh` check requires
JDK 21 and verifies parser/AST and frontend typing boundaries. The JUnit
`FuturePipelineProvenanceTest` wraps the same checks. Neither proves guest
runtime execution, Future lifting, cancellation, or actor scheduling.

The existing Java source-language `|>` operator still eagerly injects the
left value as the first argument of the stage call. The proposed *source-level*
statically checked lifting rule remains unimplemented: there is no
`Task.pipeline` source intrinsic or compiler-to-runtime lowering, and no
Java/LLVM/JS/Wasm executable parity. Do not claim the sample below runs.

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

## Host runtime staged helper (Java only)

Trusted runtimes may call:

```java
OresFuture<Integer> result = TaskPipeline.start(scheduler, source,
    List.of(
        value -> validate(value),
        value -> TaskPipeline.owned(saveAsync(value)),
        value -> notifyUser(value)));
```

Each `Stage.apply` closure captures its stage arguments and computes them
only when invoked after upstream success. Bare `OresFuture` results are
**shared observations**; `TaskPipeline.owned(future)` explicitly transfers
cancellation responsibility for that stage's Future. Canceling the pipeline
detaches from shared producers and cancels explicitly owned pending work.
A cancelled source produces a cancelled pipeline, not an ordinary failure.
The source compiler must guarantee stage types, verified ownership transfer,
checked effects, and no unproven borrowed value across await.

`TaskPipeline.startTracked` returns a `PipelineHandle<T>` with `result()`
and `getDone()` observations; `TaskPipeline.startInScope` admits the same
single Task into an existing `TaskScope`. `getDone()` is a TaskHandle
quiescence witness: it says the pipeline's own Task and its tracked descendants
have retired. It does not prove arbitrary stage-originated native/host activity
has stopped. Treat its `TaskOutcome` as the **internal** task's outcome and
`result()` as the public pipeline outcome; the bridge can classify upstream
cancellation as cancelled even though the internal task unwound with a
failure. Do not substitute the internal outcome for the public result.

The runtime also tracks active pipeline bridge-await edges by Future identity,
rejecting both direct self-await and multi-pipeline cycles, and removes edges
after completion/cancellation/resume. Stage implementations must not wrap the
original borrowed input as an Owned child. Ownership tokens for independently
shared futures and physical cancellation receipts remain a separate proof
obligation.

Limitations: this helper does **not** implement source `Task.pipeline`
spelling, compiler lowering, checked-effect inference, or proven ownership
transfer for arbitrary `TaskPipeline.owned` host Future wrappers. In
particular, `TaskScope.getDone()` does not mean independently owned foreign
work has physically stopped; before reclaiming arenas or FFI resources, also
await that operation's quiescence receipt. Do not expose arbitrary host Stage
lambdas to untrusted Oreslang source.

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

