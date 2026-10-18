# Runtime Task handle bridge (draft)

This branch adds **host runtime scaffolding** for Oreslang's proposed Task blocks. It does not enable new source syntax.

- `AsyncRuntime.submitTask(Task<T>) -> OresTask<T>`: awaitable, cancelable completion handle. `OresTask<T>` implements `Awaitable<T>` by exposing its runtime-owned `OresFuture<T>`.
- `AsyncRuntime.submitDetached(Task<?>, Consumer<Throwable>) -> void`: no caller handle, supervised failure/cancellation delivery. A non-null error handler is mandatory.
- `AsyncRuntime` owns and drains both forms on close. Neither form introduces actor identity, state access or a mailbox.
- `Task<void>` corresponds to Java `OresTask<Void>` with a null completion payload and can be awaited.

The existing compatibility executor uses Java virtual threads, **not a proven custom M:N continuation scheduler**. These entrypoints intentionally share existing task execution and cancellation code. Further lowering must supply actor-affine dispatch, safe ownership captures, cleanup ordering, Task type admission, nonparking waits, and structured parent/child lifetime before user source `nb` becomes executable. Do not allow a source Task to capture actor heap pointers just because this Java API exists.

The detached error sink is a bridge to the owner/supervisor, not permission to silently ignore its failures. Tests exercise nonblocking launch, result await, void completion, owner error delivery, and cancellation on context close. Full exact-head CI still required.

## Explicit Future projection for IIFE Task lambdas

The **proposed source** `(|int a, string b| -> nb { return a; })(5, "xyz")` returns `Task<int>`, not `Future<Task<int>>`. It is unnecessary (and currently unsupported) to prepend `async` to the lambda.

For consumers requiring a `Future<int>`, write `let Future<int> f = (|int a, string b| -> nb { return a; })(5, "xyz").asFuture();` after syntax/typing is enabled. Host-side `OresTask<T>.asFuture()` now returns the same completion future as `getAwaited()`, without creating a second computation or changing Task ownership. This is **not yet guest-source lowering** and must preserve move-only result rules.

**Capability audit:** `OresTask<T>.asFuture()` currently returns the *same cancelable `OresFuture<T>`*. The projection therefore carries cancellation authority, not a read-only observation right. A future source-facing conversion must not leak it across actors, tenants, or trust domains unless authorized; adding a truly read-only Future view requires a distinct capability/interface and cancellation-independent subscription. No extra task is created by this current projection.


## Async Task lambda integration (parser draft #1377)

The new source syntax `(async task |int a, string b| -> { return compute(a, b); })(5, "xyz")` intends one `Task<int>`. The compiler must lower the *entire lambda body* as the Task invocation, **not** wrap a second Future or call an ordinary synchronous lambda and then schedule its return. Argument evaluations and capture transfers happen once, before the Task becomes runnable.

An ordinary `(async |int a, string b| -> nb { return compute(a, b); })(5, "xyz")` instead intends a `Future<Task<int>>`. Distinct completion/cancellation scopes remain visible and require separate joins; do not flatten them.

Current Java `AsyncRuntime.submitTask` and `OresTask<T>` can provide the Task completion primitive, but the frontend in #1377 still rejects execution. Any backend wiring must verify nonparking `await`, lexical return/finally cleanup, actor-private memory isolation, ownership of ephemeral lambda arguments, and child task cancellation on context/actor teardown. `asFuture()` exposes cancellation authority; its current host implementation is not an actor-safe read-only Future view.


## Structured compiler frames and physical quiescence (October 18, 2026)

`OresTaskScope.startCompilerFrame(OresScheduler, OresScheduler.Task<T>)` is a host/compiler-only bridge to the existing cooperative, nonparking scheduler. Each submitted Task belongs to its context scope. Result settlement and physical execution exit are distinct: a cancelled Task's `getAwaited()` may already be cancelled while its guest frame is still executing cleanup, but `getDone()` observes the scheduler's `onQuiescent` callback.

`OresContext.startCompilerTaskFrame` now registers frames in that scope rather than returning an unowned Future wrapper. Context shutdown cancels and physically joins those frames **before** destroying the root scheduler or shared immutable code image. A frame's continuation is scheduled through `OresScheduler.Await`; it never blocks a carrier on a pending Future. `OresTaskScope.close()` rejects invocation from actor turns, async carriers, and scheduler carriers to prevent a child waiting on its own owner thread. Added `OresTaskScopeFrameTest` for single-carrier await progress, cancellation/cleanup races, the blocking-close guard and late-admission rejection.

**Release limitations:** The Oreslang parser/type checker still rejects source `task {}`, `nb {}` and `async task` execution because no general-purpose source AST-to-continuation-frame compiler exists yet. Task scopes cannot be exposed to guest code until actual captured values are moved/copied/shared into heap-owned frames and return/error/defer/finally are lowered correctly. Actor-affine child supervision/cancellation and untrusted fuel/deadline inheritance remain fail-closed. These new JUnit tests are committed but not yet verified in full GraalVM CI.

**Do not mistake a successfully scheduled host frame for executable guest source lowering** or a cancelled result Future for a physically quiescent Task.


## October 18 CI carrier / security audit

The exact runtime draft must be tested using Java 21/GraalVM, on the **exact checked PR commit**, before source lowering can be enabled. Run the following in an authorized *private* clone with no production secrets:

```sh
mvn -B -ntp -Dtest=OresTaskTest,OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskCooperativeSchedulerTest test
git rev-parse HEAD
```

A private GitHub test carrier may host a temporary source-tree mirror when its owning organization explicitly authorizes cross-org access, has a usable private runner/budget, and the exact source head, destination visibility and file scope have been verified. A foreign `GITHUB_TOKEN` cannot directly checkout a private source repository. Do not use PATs, deploy keys, or cross-org secrets as a workaround. Mirror only the needed, reviewed code; do **not** copy credentials, binary blobs or CI workflow files implicitly. Require SHA/manifest equality, run explicit local-only test commands and delete temporary refs once the audit is complete.

As of October 18, the source PR GitHub Actions jobs and inspected alternate-carrier recent jobs **failed with zero steps**. One alternate private carrier had executed steps days earlier, but no current funded runner was established. A source-free carrier probe was blocked, so no private source transfer was performed and no executable CI result is claimed.

### Additional hardening after inspection

`OresContext.startCompilerTaskFrame` now rejects a launch while any `OresScheduler` task turn is current, preventing nested child Tasks from silently becoming context-owned siblings. Correct parent/child Task scope propagation must be introduced before relaxing that restriction. A regression test asserts that a scoped child cancelled while waiting on a Future cannot resume if that Future settles after quiescence.

**Do not remove the TypeChecker Task execution gate yet**: capture/frame lifetimes, actor-affine child shutdown and untrusted deadlines remain unimplemented in compiled guest source.
