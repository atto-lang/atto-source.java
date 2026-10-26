# Future-aware value pipelines and trap/await ordering

Status: **partial source-level Java/GraalVM implementation, unverified
by exact-head JUnit CI; complete effect, ownership, actor and backend parity
remain unimplemented**. The Java runtime now has the staging
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
which lacks a separate owned lifetime receipt. For Java/GraalVM, non-actor
SourceTask admission now uses tracked child handles; the restricted source
pipeline is admitted through `TaskPipeline.startInCurrentTaskOrRoot` and
joins its active tracked parent Task's cancellation/physical-retirement tree.
Non-actor source Future composition helpers also use tracked parent-aware
admission; a composed Future no longer silently escapes through an untracked
scheduler Task. Canceling an exposed tracked Task result now requests
revocation of its owned descendants as well. A parent whose result has
already settled but still owns physically active children may also revoke
those children while preserving the parent's settled value; its `getDone()`
still waits for physical retirement. Implicitly parent-owned work may not
cross schedulers without an explicit ownership/admission contract.
A synchronous/root host entry with no tracked
parent still creates a **root** pipeline: this is not an actor/lifetime
ownership proof and must not be generalized to escaping borrows or FFI.

The parser retains `Ast.CallExpr.origin` for value-pipe argument injection
and Option/Result receiver combinators, distinct from ordinary calls and actor
`pipe`. Syntax provenance alone is not authorization. The type checker now
issues `TypeChecker.CheckedFuturePipe` identity-bound proofs to a **restricted**
`Future<A> |> pureUnaryFunction()` subset for concrete statically resolved `A`/`B`: synchronous **or asynchronous**,
non-generic, no captures, no executable exceptional effects, no additional stage arguments
and no actor execution. A checked async stage may contain cooperative scheduler
yield points and may `await` a same-module pure async unary helper; the
helper is recursively checked under the same closed restrictions. Each admitted stage gets an identity-bound `PipelinePlan` containing resolved input/output types, whether one Future layer is flattened, per-stage and cumulative `throws`/`raises`/`panics` rows, borrowed-upstream ownership, and stage-result Future cancellation ownership. The plan also records whether its cumulative exceptional-effect row has `COMPLETE_STATIC` provenance or an `UPSTREAM_FUTURE_UNKNOWN` prefix; an opaque `Future<T>` binding must never be silently reclassified as effect-free. Likewise, a synchronous function returning `Future<T>` retains unknown deferred-effect provenance because it may only be forwarding another Future; only a statically resolved async producer currently establishes complete Future-origin provenance. Source exceptional-effect declarations remain globally fail-closed until executable throw/raise/panic lowering exists; carrying the row in the plan is not a claim that those effects execute yet.

Stages must also be declared in the current lexical module:
imported/cross-module execution has not yet proven evaluator/environment
ownership. Adjacent checked stages lower to one TaskPipeline task, preserving
borrowed input-Future ownership. Proofs bind exact checked declarations and
rewritten ASTs lose authorization. Proof issuance is private to TypeChecker:
host code may inspect the checked declaration but cannot call a public
`record` method to authorize an unchecked AST. Chained value pipes are checked from the inner stage outward. Plans are staged
per compiler instance during type analysis but are **not executable authority**
at that point. Full compiler admission invalidates any older proof for those
exact AST nodes, passes the staged plans directly through ownership checking,
and publishes them atomically only after ownership succeeds. Type-only or
ownership-failed compilation therefore cannot mint a runnable pipeline proof. Detection of Future-producing calls is
restricted to known async/Future declarations rather than speculatively
rechecking arbitrary scalar functions. `CallExpr.rewrite` retains that origin but drops identity-bound checked
proofs; replacements require rechecking. The pipeline plan is deliberately outside Ast's copyable checked-metadata
registry; reconstructed or rewritten call nodes therefore lose authorization
and must be rechecked.

The standalone `bash scripts/test-future-pipeline-provenance.sh` check requires
JDK 21 and verifies parser/AST and frontend typing boundaries. The JUnit
`FuturePipelineProvenanceTest` wraps the same checks. Neither proves guest
runtime execution, Future lifting, cancellation, or actor scheduling.

Ordinary (non-Future) source `|>` still injects the left operand eagerly.
If such an eager scalar stage returns `Future<int>`, the next checked pipe
stage switches into Future-aware lowering; for example
`21 |> start_async() |> finish()` invokes `start_async(21)` eagerly and
defers `finish` until that Future succeeds. Only the narrow, checked pure-unary subset above has compiler-to-runtime lifting. Because the initial Future is only observed, not consumed, its payload must
currently prove **Send + Share**. Unique Send-only payloads (for example
`Box<T>`) require a future explicit consuming/`rt take` pipeline mode and
are rejected by this borrowed form.

Within that subset, an async
stage's Future result is flattened by
the same single `TaskPipeline` task, and the compiler marks the stage-created
Future as pipeline-owned so cancellation revokes it while leaving the borrowed
producer Future alone.
Synchronous `Future<B>`-returning stages are admitted only when the returned Future is proven fresh from a checked async helper; returning a borrowed/shared Future does not grant the pipeline cancellation ownership. Future stages with effect declarations, extra arguments, generic stages,
HasFuture projections, actor affinity, non-Share borrowed payloads, or
loan/capture requirements still fail closed. There is no general
`Task.pipeline` source intrinsic or Java/LLVM/JS/Wasm executable parity.
Do not claim the general example below runs.

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
types/effects/ownership and safe actor-bound scheduling. Java/GraalVM now executes this behavior for the restricted checked pure-unary subset, including different concrete input/output types, mixed synchronous/asynchronous stages, synchronous `Future<B>`-returning stages with one-level flattening, and an async stage that awaits a checked pure async helper. General typed pipelines remain intentionally
fail-closed outside that subset.

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

For inline scheduler implementations, the exposed bridge identity must be
published **before** TaskHandle admission. Otherwise the first already-running
await can be omitted from cycle detection, and cancellation can race handle
binding. The runtime now publishes that bridge first, reconciles cancellation
after TaskHandle admission, and includes focused inline-complete/inline-pending
regressions. These tests are committed, but require an executing JDK 25 runner.

**Structured root-task lifetime:** SourceTask functions and methods now
enter tracked scheduler tasks, and compiler-lowered pipelines use
`TaskPipeline.startInCurrentTaskOrRoot`. A running tracked parent captures
its cancellation authority before child admission; cancelling its exposed
result revokes descendant pipeline work, and the parent's `getDone()` waits
for descendants' scheduler-confirmed physical retirement. A top-level
synchronous entry point instead creates a separately tracked root pipeline.
The borrowed input Future is not cancelled. Exact-head host and guest tests
remain necessary: this linkage does not prove physical completion of
arbitrary independently owned FFI operations or actor arena cleanup, and
actor-affine pipeline lowering is still rejected.

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

