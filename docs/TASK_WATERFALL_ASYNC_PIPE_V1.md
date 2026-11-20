# Task.waterfall and asynchronous `|>` pipelines — v1

Status: source design + partial **Java host runtime** implementation in draft PR #1887,
not a claim that the Oreslang compiler/source stdlib currently understands
`Task.waterfall(...)` or asynchronously lifts `|>`.

The existing Java parser has `PIPE_FORWARD` in Token/Lexer and
`Parser.parsePipeline`: it rewrites `value |> f(args)` into
`f(value, args)` eagerly, and supports `Option.map` / `Result.map_err`
member lowering. It currently caps value pipelines at 128 stages. **Preserve
this existing grammar for pure/synchronous values; do not create a second
conflicting pipeline operator.** Async-lift needs a type/effect-aware compiler
phase rather than parser-level eager call substitution.

Reference: caolan/async v3 waterfall executes tasks serially, feeds each
callback result to the next task, and stops on error:
https://caolan.github.io/async/v3/docs.html#waterfall .
Oreslang differs: single statically typed return, futures/await, explicit
cancellation and proven ownership, no callback convention or `Any` results.

## Semantics

- `Task.series([a,b,c])`: a,b,c are LAZY `TaskStep<T>` values. Each
  operation starts after its predecessor's physical retirement. Results
  are kept in a position-ordered collection (generally homogeneous T).
- `Task.waterfall(a,b,c,d)`: heterogeneous **typed** lazy functions.
  Step a: () -> Future<A> or checked async fnc returning A.
  Step b: A -> Future<B> / sync B / Task<B>.
  Step c: B -> Future<C> / sync C / Task<C>.
  Step d: C -> Future<D> / sync D / Task<D>.
  The whole computation yields Task<D>, awaitable as D. No intermediate
  result array is allocated except when required for explicit sharing.
  Later functions are never evaluated before earlier success.
- `a() |> b() |> c() |> d()`: preserve existing value pipeline injection,
  but introduce **context-aware async lifting** if and only if an input
  has type Future<T>/Task<T>/HasFuture<T>. In lifted mode:
  resolve a Future<T> to T with the correct scheduler suspension;
  invoke b on T; if b returns U, lift it as a settled stage;
  if b returns Future<U>/Task<U>, await its U before invoking c.
  Continue until completion, resulting in Future<D> or Task<D> according
  to the context (exact source design pending); no implicit detached Tasks.
- A synchronous pipeline with no awaitables is the *existing* `|>`
  meaning, with exactly one evaluation of each operand, unchanged by
  this feature.
- `await` should be explicit at the whole pipeline boundary; the
  compiler-inserted internal stage awaits are derived from checked
  `suspends`/effects and cannot be used in non-suspendable contexts.
- Do not accept `Task.waterfall(a(),b(),c())` as a lazy waterfall
  unless the call syntax is unambiguously a macro that *does not evaluate*
  its arguments (undesirable). Prefer callable references/lambdas:
  `Task.waterfall(a,b,c)` or a pipeline expression.

Proposed illustrative source forms (NOT executable yet):

    async fnc fetch_user(int id): User {
        return await api.get_user_async(id);
    }

    fnc validate(User user): ValidatedUser {
        return verify_user(user);
    }

    async fnc enrich(ValidatedUser user): Profile {
        return await analytics.enrich_async(user);
    }

    let pipeline = Task.waterfall(
        () => fetch_user(42),
        validate,
        enrich
    );
    let profile = await pipeline;

    let same = await (
        fetch_user(42)
        |> validate()
        |> enrich()
    );

For pipeline grammar, `value |> f(arg1,arg2)` injects `value` as
the *first positional argument* even in the async-lift form. Do not
silently rearrange arguments or evaluate side effects out of order.
A pipeline stage that needs explicit choice of which input gets
the previous value can use a checked lambda or placeholder form to
be specified separately. Varargs expansion is not implicit: Oreslang
functions have one return value, which may itself be a typed tuple/record.

## Host implementation in this PR

`TaskWaterfall.from(first).then(second).thenValue(third).thenAwaitable(fourth).start(scheduler)`
creates a statically typed Java Pipeline<T> facade with O(1) immutable
reverse-linked builder steps, and a *single-use start token shared by all
builder prefixes*. Host stages return OresFuture<T>; sync stage values can
be lifted with `thenValue`. `TaskWaterfall.waterfall(scheduler,a,b,c[,d])`
is a convenience for typed three/four-stage chains.

When started, one scheduler-owned tracked Task:
1. invokes stage 0 lazily on the scheduler,
2. awaits its Future without parking a carrier,
3. delivers each completed value to the next stage on the same
   scheduler (continuation can migrate physical worker threads),
4. fails on first stage error, without invoking any further stages,
5. tracks authenticated cancellation of stage Futures and requests
   cancellation of its own TaskHandle, while never cancelling borrowed
   stage sources,
6. detaches listeners at resume or TaskHandle physical `getDone`
   retirement, and maintains a separate quiescence barrier for the
   pipeline Task itself.

**Important limitations:**
- An async stage may create an independently owned Task that continues
  running after this pipeline is cancelled. The pipeline only borrows
  its Future: `getDone` certifies the pipeline's own Task, NOT that
  separately-created child. Full source `Task.waterfall` should run in
  an owning TaskScope, cancel owned children and await physical child
  quiescence/borrow release before reporting its scoped `get_done`.
- A producer Future cancelled and settled synchronously during an
  inline scheduler startup can require special handling to avoid a
  mere CancellationException being mistaken for an authenticated
  cancellation: this race remains a release gate.
- Stage callback generic type proofs are Java compile-time-only; the
  source compiler must validate all guest types, ownership,
  actor/isolate transfer, error effects and control-flow reachability
  before granting analogous APIs to Oreslang programs.
- The Java host implementation intentionally uses internal Java
  Object-erased staging for its *execution* array; type safety at
  `then` construction is compile-time generic. The Oreslang compiler
  should lower typed continuations directly, not emit guest Any.
- No reflection, string-key function dispatch, arbitrary callback
  invocation on Future notification threads, global replacement pool,
  or implicit GPU fallback.
- AOT tree shaking should resolve each stage's implementation statically
  and only retain its reachable code.
- `throws`, `raises`, and `panics` must propagate the exact
  checked effect category. A panic cannot be transformed into catchable
  stream failure by a convenience pipe operator.
- Stream or Observable stage operations may be inserted only with
  explicit cardinality `first`/`single`/`collect(maxItems)` or
  an explicit stream-level composition operator. No implicit
  Observable<T> -> Future<T> coercion.

## Proposed conformance tests

1. `a` yields int; b returns string; c returns validated struct;
   d returns Future<Report>: compiler preserves every type and
   selects a Task<Report> output.
2. Sync pipeline behavior remains unchanged, including first-arg
   injection, named/member calls, Option/Result and 128-stage parser
   cap. No double-evaluation of side-effectful a().
3. Tasks start lazily, strictly one by one; a pending Future blocks
   b and c without parking or starving a one-worker scheduler.
4. A failure in b does not invoke c; a genuine panic remains untrappable.
5. A source whose cancellation is authenticated cancels the owned
   pipeline, not a shared independent source; user-raised
   CancellationException is an ordinary error.
6. Cancellation of pipeline while a child still physically runs
   does not free a borrowed arena until child getDone.
7. 1,024 host pipeline stages do not recursively invoke guest
   callbacks; dynamic source pipeline stage cap remains 128 until
   explicitly revised and justified by compiler resource accounting.
8. Reused builder/prefix must fail single-use admission before
   spawning a second Task.
9. No guest continuation executes on producer I/O, timer or
   channel completion threads; actual continuation uses OresScheduler.
10. Java/GraalVM parser + typed lowering and LLVM native continuation
    parity; native AOT checks, TSan/ASan when applicable.

No exact-head JVM build or JUnit run has yet been verified. Keep PR #1887
draft and do not claim this is source-executable until typed lowering,
ownership/safepoint/cancellation evidence and cross-backend tests exist.


## Exact host typed example (implemented API, not Oreslang source yet)

    TaskHandle<Report> result = TaskWaterfall.from(
            () -> loadUserAsync(42))
        .thenValue(user -> verifyUser(user))
        .thenAwaitable(validated -> profileAwaitable(validated))
        .then(profile -> generateReportAsync(profile))
        .start(scheduler);

    Report report = result.result().join();  // host-only blocking bridge
    result.getDone().join();                  // host-only quiescence bridge

In an actual Oreslang task or actor, both waits must lower to nonparking `await`, NOT `join()`; a scheduler carrier must never call the Java blocking host bridge. Future stage callbacks execute on the waterfall's scheduler; the owner of an independent producer is not implicitly transferred.


## Startup cancellation hardening

The Java host waterfall now begins with a one-time nonparking `ownerReady` Future. Its initial Task turn suspends on the gate, and only after the TaskHandle owner, task-completion cleanup waiter and cancellation state have been installed does the gate resolve. Thus even an inline runtime executor cannot start a user-supplied first stage before its cancellation authority exists. Tests include an already-cancelled first-stage Future, lazy factory scheduler affinity, and staged cancellation. This does not by itself prove every host callback race or release gate; run the exact JVM tests on an authorized runner before merge.
