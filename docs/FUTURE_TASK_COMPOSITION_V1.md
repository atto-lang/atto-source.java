# Oreslang Future / Task / Queue / Graph composition v1

Status: **proposal plus host-runtime observer foundation only** (not a guest API, not LLVM parity).
Owner: Oreslang core and stdlib. Applies to actor-free Tasks as well as admitted
actor-local execution. Inspired by Kotlin coroutines, Rust Tokio, Scala Future,
C# Task.WhenAll/WhenAny, Erlang/OTP supervision, and caolan/async.

## 1. Fundamental separation

- **Future<T>** is an observation of a single, already-started operation. A
  Future never schedules work merely because it is passed to a combinator.
- **Task<T>** is a runtime-owned executing computation with scope, scheduler
  domain, continuation, cancellation capability and physical quiescence.
- **TaskStep<T> / TaskFactory<T>** is a *lazy*, single-use recipe that may be
  admitted later. This distinction is essential for Task.series, parallelLimit,
  queues and DAGs: eager task { ... } values are already running.
- **TaskScope** owns child admission, cancellation and join. A result Future
  settling is NOT proof of task physical quiescence.
- A compositional manager is not an actor; ordinary Tasks need no mailbox,
  per-task OS thread pool or actor-specific heap.

## 2. Stable proposed guest names and signatures

The following is a **target surface**, not implemented source syntax:

| Contract | Semantics |
| --- | --- |
| Future.all<T>(futures: Array<Future<T>>) -> Future<Array<T>> | input-ordered fan-in of existing operations; empty succeeds |
| Future.allSettled<T>(futures) -> Future<Array<Outcome<T>>> | inspect every success/failure/cancellation in input order |
| Future.race<T>(futures) -> Future<T> | first terminal wins; empty rejected |
| Future.any<T>(futures) -> Future<T> | first success; aggregate failures if all fail |
| Future.zip<A,B>(a, b) -> Future<(A,B)> | typed tuple fan-in without erasing heterogeneous values |
| Future.map<T,U>(future, fn) -> Future<U> | run checked callback on scheduler, never on arbitrary completion thread |
| Future.flatMap<T,U>(future, fn) -> Future<U> | returned future flattening, with checked ownership and no cycle |
| Future.timeout<T>(future, duration) -> Future<T> | timeout of observation, NOT proof producer stopped |
| Task.parallel<T>(steps: Array<TaskStep<T>>, options) -> Task<Array<T>> | eagerly admit lazy factories up to configured limit; input-ordered results |
| Task.series<T>(steps: Array<TaskStep<T>>) -> Task<Array<T>> | admit next only after previous completed AND safely retired |
| Task.parallelLimit<T>(steps, maxConcurrent) | strictly bounded active admissions |
| Task.waterfall<A,Z>(steps: typed chain) -> Task<Z> | previous result passed to next at compile time |
| Task.pipeline(stages, options) | bounded streaming staged execution |
| Task.retry(step, policy) | bounded attempts and explicit eligible errors; no retries of arbitrary side effects |
| Task.scope(body) | lexical structured children, cancel+join on failure/exit |
| Task.supervise(body) | isolated child failures collected instead of auto-failing siblings |
| TaskGraph / Task.inject | statically checked dependency DAG, no reflection or parameter-name introspection |
| TaskQueue<T,R>.new(worker, concurrency, capacity) | bounded FIFO admission, return Future<R> for each push |
| TaskPriorityQueue | documented stable priority ordering plus aging/starvation bounds |
| TaskBatchQueue | cargoQueue-like payload batches with bounded parallel workers |

The operation namespace can preserve ergonomic Task.parallel / Task.series while
the lower-level TaskScope and TaskStep abstractions carry actual ownership.
An array of eager Task<T> may be passed to Future.all but **must not** be
interpreted as an ordered serial execution plan.

## 3. Host foundation provided by this change

Java runtime adds **FutureCombinators.all, allSettled, race** for existing
OresFuture values. These:
- take immutable, validated snapshots (up to 65,536 entries);
- observe completion through runtime-only detachable waiters;
- preserve input order for all/allSettled, allow null success values;
- never run guest callbacks from a producer thread;
- detach registrations on early exit/cancellation and never cancel borrowed
  source Futures;
- propagate authenticated source cancellation as cancellation, not simply
  inspect the Throwable class;
- fail-fast on first observed failure in all; allSettled waits for every source;
- give deterministic input-order tie-break for *already-terminal* race inputs;
- reject empty races, while empty all/allSettled succeed.

**Important:** this is a non-owning observer primitive, not the final
structured Task.parallel. The aggregate may be terminal while source tasks are
still physically executing. It must never authorize reclaiming their memory.

## 4. Task.parallel / series semantics

Task.parallel operates on lazy TaskStep<T> recipes; default behavior:
- scoped ownership; maximum concurrency is explicit or a conservative
  scope-defined quota, not unbounded startup;
- results preserve input order, even if finishing order differs;
- on first failure stop new admission, request cancellation of admitted owned
  siblings, then await physical quiescence BEFORE scope completion or releasing
  transferred/borrowed memory;
- surface first causal error plus ordered suppressed sibling failures after drain;
- external cancellation follows the same cancel-then-quiesce sequence;
- options include COLLECT_ALL, SUPERVISE (independent child outcomes), and
  explicit FAIL_FAST_CANCEL_JOIN; no silent detachment;
- bounded submission queues; quota exhaustion must reject with a typed
  admission error or suspend a producer, not allocate unbounded pending Tasks.

Task.series admits exactly one recipe at once, not an already-started Task.
A failed step prevents subsequent admission; valid steps may be individually
asynchronous. For heterogeneous sequences, Task.waterfall is typed at each
edge; avoid coercing every intermediate result through Any.

Task.parallelLimit is Task.parallel with a strict maximum active count
(including logically cancelled but still physically executing children).
Cancellation of one slot NEVER permits overcommit before physical exit.

## 5. Task.inject / DAG: caolan/async autoInject without reflection

Prefer compiler-checked declarations with explicit typed dependency edges:
- each node has stable name, output type and dependency names/expected types;
- generated adapters inject prior results into parameters at compile time;
- build-time validation rejects missing nodes, duplicate names, cycles, wrong
  type edges, incompatible ownership, cross-domain unsafe captures and
  forbidden actor-state escapes;
- deterministic topological readiness queue and maxConcurrency limit;
- independent roots run concurrently; dependent node starts only when all
  inputs succeed; failure skips descendants and drains/cancels admitted owned
  tasks according to policy;
- named results are strongly typed generated records, not Map<string, any>;
- no runtime reflection, source parameter-name guessing, or implicit serializer.

Proposed illustrative graph:

    define TaskGraph BuildAssets as
        node source: read_source();
        node config: read_config();
        node compile(source, config): compile_assets(source, config);
        node upload(compile): upload_assets(compile);
    end

Syntax subject to parser/grammar design; this block is NOT currently executable.

## 6. TaskQueue and backpressure

Queue is a scoped owner of queued and running TaskSteps. Initial API:
- TaskQueue.new(worker, concurrency, capacity): concurrency >= 1 and
  capacity >= 1; capacity covers queued + running items (and result storage
  according to an explicit memory budget).
- push(item) returns an awaitable Future<Result>; when full,
  push_async(...) suspends producers or tryPush(...) returns Full. No unbounded
  allocation, no blocking an actor carrier.
- Workers are Tasks on existing scheduler domains; no per-queue OS thread pool.
- FIFO task admission and bounded in-flight execution, but execution completion
  may be out of order. Offer ordered consumer collection separately.
- pause/resume affects new dispatch only. drain() is a snapshot barrier with
  monotonic accepted sequence/watermark, not a promise the queue is closed.
- seal() rejects new admission; close(cancelPending, finishInFlight) states
  policy explicitly and getDone() witnesses physical quiescence.
- failure policies: continue (collect per-item errors), fail-queue
  (cancel+drain), configurable retry if explicitly idempotent.
- Optional priority queue uses stable FIFO inside priority and bounded aging.
- Optional cargo/batch queue collects up to payload size and has a bounded
  worker concurrency, with an explicit latency/max-wait flush control.

Avoid emulating caolan callback-style APIs when strongly typed Futures and
compile-time schemas already provide safer result propagation.

## 7. Cooperation, fairness and checkpoint policy

Compiler inserts cooperative safepoints on backedges/long loop iterations and
long-running compiled paths. The existing rt cooperate stays an explicit
yield. For every Task domain:
- a safepoint checks authenticated cancellation, deadline and scheduler budget;
- a completed budget yields by recording a continuation and ENQUEUEING it
  after the current carrier turn unwinds; no recursive inline continuation;
- never yield while holding a native exclusive borrow, actor write guard,
  FFI non-reentrant section or other state that cannot safely suspend;
- await/waiter completion always resumes on the *awaiting task's* scheduler,
  never on timer/channel/producer callback threads;
- round-robin/fair queue credit is per logical task and isolation domain;
  prioritize cancellation/control work without allowing tenant starvation;
- 3–4 ms is a watchdog *target*, NOT a hard preemption guarantee. Measure
  p50/p99/p99.9 run-turn durations, unsuspendable sections, fairness and
  safepoint overhead. Never claim hard RT without bounded native/FFI sections.

Fast-path empty/settled fan-in should avoid new scheduler carrier tasks.
All nonterminal subscriptions must be detachable and bounded; task failure
must never retain all sibling closures after completion.

## 8. Memory and authority restrictions

- Ownership rules: rt take, rt share, rt borrow, rt mut borrow are canonical.
- Task cross-worker captures require proven Send; sharing needs the correct
  Sync/immutable-domain proof. Borrowed scoped tasks require scoped lifetime
  and generation checks, and are not legal detached.
- An observer Future is not a capability to cancel source Tasks.
- Cancellation request != result settlement != physical quiescence.
- Cleanup/arena release requires the final quiescence witness, including
  suspended continuation roots, child memory loans and native callbacks.
- Task graphs, queues and callback adapters must not cross actor isolation,
  isolate/trust or generation boundaries without checked authorization.
- No silent CPU fallback for GPU placed task nodes.

## 9. Cross-backend acceptance plan

A. Host-runtime reference: all/allSettled/race fan-in, immutable snapshots,
   early-error detachment, pending producer, cancel race, input ordering,
   null payloads, concurrent completions (this PR; JUnit tests added).
B. Source API: stdlib declarations, compiler-backed Future<T> generic
   lowering, ergonomic explicit TaskStep<T> and TaskScope integration;
   guest-source positive and compile-negative tests.
C. Task.parallel/series/parallelLimit: scheduler-owning physical join,
   bounded admission, cancel+drain, borrow/region witness and fail policies.
D. TaskGraph/inject: typed DAG builder, static cycle/type validation and
   deterministic readiness worklists; no reflection.
E. TaskQueue/priority/batch: backpressure, admission credits,
   pause/seal/drain/close, stress and memory quota correctness.
F. Fairness: CPU-heavy sibling, deeply nested fan-in, tight non-await loop,
   actor-versus-ordinary-domain isolation, adversarial cancellation,
   deadlines, 100k step chain without recursion, and actor-arena release.
G. LLVM lowering with native continuation/ownership and sanitizers;
   Java/GraalVM guest integration & Native Image; fail closed on .NET/Wasm
   until meaningful parity exists.

Each milestone needs stepful exact-head tests. No private-source mirror is
authorized by this document. Existing private CI allowlist/security review
must be satisfied before cross-organization transfer.


## 10. Follow-up Java host implementation checkpoint (2026-10-22)

This PR now includes runtime-only `FutureCombinators.any`:
first successful existing Future wins; if all sources fail, an
`AnyFailedException` retains input-ordered causes; if all are authentically
cancelled, the aggregate is cancelled. Consumers only detach non-owned source
observers. See `FutureCombinatorsTest` for success/failure/cancel regressions.

The same branch adds `AsyncSequences.Cursor<T>` adapters:
- `fromChannel(channel)` observes a pre-existing MPMC Channel as one
  sequential, nonparking `nextAsync(): Future<Optional<T>>` cursor;
- `owningChannel(channel)` additionally owns channel close, whereas
  borrowed cursors NEVER close caller-owned channels;
- `fromFuture(future)` observes a single existing Future only once,
  preserving failure vs cancellation and rejecting null as an event;
- normal channel closure => empty Option (EOF); channel.close(cause)
  => a failed Future after any buffered items drain;
- source listener cleanup and runtime callbacks remain host bookkeeping
  only, never guest continuation execution;
- in-flight next cancellation detaches the read without closing the
  borrowed producer. Concurrency on one cursor fails closed.

**Delivery safety boundary:** `Channel.readAsync` commits an item
atomically before the consumer Task necessarily processes it. Cancellation
AFTER channel read commitment cannot roll that item back into the queue.
This cursor is an *at-most-once delivery* view and MUST NOT be used to
claim at-least-once, exactly-once or durable TaskQueue processing. A
reliable TaskQueue requires job reservation, explicit ACK/NACK,
requeue/dead-letter policy, and credits refunded at physical quiescence.

These adapters are Java host-runtime building blocks ONLY. Source
`Stream<T>` generic binding, `for await`, full Observable, scheduler-aware
operators and LLVM parity remain follow-ups. Exact-head full Maven
and GraalVM tests are not yet verified.


## 11. Scheduler-bound Future chains and strict bounded collector

The Java host foundation adds `FutureCombinators.map(scheduler, future,
mapper)`, `flatMap(scheduler, future, mapper)`, and `zip(scheduler, a, b)`:
- mapper callbacks execute only as Task turns on the specified scheduler,
  NOT inline on a producer-completion/I/O/JNI/timer thread;
- a dependent Task awaits without parking an OS carrier; a flatMap's
  returned Future is dynamically awaited through the scheduler's
  dependency/cycle guard;
- zip is a heterogeneously typed Pair of already-started Futures and
  preserves positional order;
- cancellation of a dependent Task does not revoke either shared input
  Future; the Java host now registers an upstream cancellation-only witness
  *before* starting map/flatMap Tasks; authenticated source cancellation
  requests downstream cancellation without revoking upstream. flatMap also
  observes authenticated nested-source cancellation. These changes are
  **committed but not executed against production CI**; source/LLVM parity,
  physical retirement stress and guest effect checks remain release gates.
- guest callback effects, ownership and Send/Sync proofs are **not**
  established by the Java host-only Function API. Do not bind this
  directly to untrusted guest functions before source checking.

The Java host `AsyncSequenceCollectors.collect(scheduler, cursor,
maxItems)` uses a resumable Task for nonrecursive, nonparking
`Cursor.nextAsync` iteration; reads at most maxItems+1 values so an
oversized input raises `LimitExceededException` rather than allocating
unbounded result storage. The extra consumed value cannot be rolled back
into an at-most-once Channel cursor. The collector takes the cursor's
consumption lifetime, and closes it only after the tracked Task retires.
`CollectionHandle.result()` observes logical result while
`CollectionHandle.getDone()` is a separate physical-quiescence plus
cursor-close barrier. Cancellation releases that cursor's pending read;
borrowed Channel producer remains live. Limits are item counts only:
byte quotas are still required before guest-facing or strict zero-GC use.

No source generic binders, native continuation lowering, full reactive
multicast, Task.parallel TaskScope lifecycle or native/java performance
claim is provided by these host primitives.
