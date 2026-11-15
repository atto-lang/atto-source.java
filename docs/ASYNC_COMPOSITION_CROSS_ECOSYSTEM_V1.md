# Cross-ecosystem Task, Future, Stream and queue composition (v1)

Status: researched design plus preliminary Java host-runtime code in draft PR #1887.
Not a claim of source-language compiler support, full JVM testing, LLVM parity,
or correct zero-GC arena reclamation on every backend.

## Actual references checked (October 22, 2026)

- https://github.com/async-java/async.java : lowercase readme.md; AsyncFut.java,
  NeoQueue.java, NeoInject.java, AsyncLoom.java, NeoWaterfall.java.
- https://caolan.github.io/async/v3/docs.html : Node async series, waterfall,
  parallelLimit, mapLimit, queue, inject/auto, cargoQueue, priorityQueue.
- https://docs.rs/tokio/latest/tokio/macro.join.html and
  https://docs.rs/futures/latest/futures/stream/trait.StreamExt.html
- https://learn.microsoft.com/en-us/dotnet/core/extensions/channels and
  https://learn.microsoft.com/dotnet/api/system.threading.tasks.paralleloptions.maxdegreeofparallelism
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/flat-map-merge.html
- https://typelevel.org/cats-effect/docs/std/queue
- https://vertx.io/docs/5.0.10/apidocs/io/vertx/core/Future.html

## Semantic corrections to common examples

Rust Tokio join! polls concurrent Futures within ONE Task, on one thread.
It is concurrent but not necessarily multithreaded parallel work. Spawning
Tasks enables scheduler-level parallelism. join! waits for all; try_join!
fails fast. futures::StreamExt.buffer_unordered(n) caps in-flight Futures
and emits in completion order; buffered(n) emits in input order. Neither
creates threads merely by using the operator.

C# Task.WhenAll usually observes already-started Tasks, not lazy functions.
Parallel.ForEachAsync is a bounded concurrent foreach, but has no ordered
mapped result vector. .NET Channel.CreateBounded default FullMode.Wait
provides real asynchronous producer backpressure on full capacity, distinct
from an advisory saturation notification.

Kotlin flatMapMerge(n) merges concurrently collected inner Flows, with
potential output reordering, not the same operation as ordered parallel
map of one value per input. coroutineScope ensures its children complete
before returning; async creates a Deferred, and ordinary suspend calls
compose serially.

Scala Future.traverse generally creates/evaluates many eager Future
operations, not a bounded structured child TaskScope. Cats Effect IO
with bounded parTraverse and its Queue is closer to the target model.
Erlang processes/OTP mailboxes are not bounded durable job queues by
default. Supervision does not imply automatic ACK/retry/backpressure.

Vert.x Future.all is fail-fast whereas Future.join waits until each
future settles. Creating all Futures before invoking Future.all does
not bound ongoing work. EventBus.send is not by itself a reliable,
bounded, acknowledged work queue.

## What the real async-java code adds

AsyncFut.Parallel(List<Supplier<CompletionStage<T>>>), ParallelLimit,
and Series take LAZY suppliers. AsyncFut.ParallelF observes eager,
already-started CompletionStages. Preserve this Oreslang distinction
exactly: Future.all OBSERVES, Task.parallel OWNS new lazy TaskSteps.

NeoWaterfall / Asyncc.Waterfall use a NAMED ACCUMULATOR, where an
intermediate stage publishes a key/value and later stages can read
any earlier named value. AsyncFut.Waterfall exposes a map counterpart.
Oreslang should retain fully typed linear Task.waterfall(A->B->C),
and use Task.inject / TaskGraph for named multi-parent DAG dependencies.
Do not copy its erased Map<String,Object> as guest source types.

NeoInject uses named task dependencies and detects invalid/cyclic
edges at runtime. Oreslang should resolve dependency names and type
signatures at compile time, using an O(nodes + edges) ready-queue
executor at runtime and explicit ownership when consumers share results.

NeoQueue.push() accepts streaming work, enforces a maximum number of
active workers, and emits saturated / unsaturated / drain events.
Its pending tasks are held in a growable list, however; saturation
events do NOT enforce a bounded pending capacity and push() does not
suspend producers on full queue. Oreslang TaskQueue must independently
cap waiting + running work, quota retained outputs, and implement
nonparking enqueue_async admission, explicit drop/fail policies,
seal, drain, cancellation and physical child Task retirement.

AsyncLoom uses JDK 21 virtual threads for BLOCKING Callable bridges.
This is appropriate for trusted host stdlib adapters, but not Oreslang
guest Tasks, which use compiler-lowered cooperative suspension.
Do not introduce per-Observable pools, reflection or implicit blocking
inside the Oreslang core.

## Oreslang APIs: exact distinction

Future.all(futures): observe already-running Futures and return results
in input order; observer cancellation must not cancel shared producers.

Task.parallel(steps, maxConcurrency:N): start lazy TaskSteps, return
ordered results; fail-fast stops admission, cancels owned children,
and joins physical child lifetime before reclaiming owned regions.

Task.series(steps): parallel with concurrency exactly 1, but all
intermediate values are retained as a position-ordered collection.

Task.waterfall(a,b,c): heterogeneous typed composition A->B->C; each
step gets previous value, uses one nonparking scheduler state machine,
and no later factory begins before preceding step succeeds.

Task.map_parallel(items,f,concurrency:N,order:input|completion):
bounded lazy mapping. Input order requires bounded result slots or
a finite reassembly window. Completion order should be a Stream
for unbounded sources; do not collect infinite results into a List.

Task.inject(graph): statically typed named DAG; all prerequisites
must finish before dependent runs; no reflection or dynamic strings.

TaskQueue(worker,concurrency:N,capacity:M): concurrency and capacity
are independent. queue.enqueue_async(item) waits for an actual credit,
then yields a job handle. queue.results() is a unicast work-sharing
Stream; queue.events() is a bounded hot diagnostics Observable.
Reliable execution needs reservation+ACK/NACK/retry; Channel.readAsync
alone is at-most-once receipt, not reliable processing.

Stream.map_async / Observable.mergeMap/concatMap/switchMap/exhaustMap:
same scheduler and credit substrate. mergeMap bounded and potentially
unordered; concatMap sequential; switchMap cancels ONLY owned old
work, not shared producers, and retains loans until quiescence;
exhaustMap is intentionally lossy and not a substitute for TaskQueue.

## Lifetime and effect contracts

- Future result settlement is NOT physical Task retirement.
- TaskMapParallel slots are held until TaskHandle.getDone confirms
  physical quiescence, not when a child Future merely settles.
- Task.parallel failure may settle the caller-facing Future early,
  but the separate MapHandle.getDone must await coordinator and scope
  retirement before memory/arena reuse.
- CancellationException raised by guest is a failure, not authenticated
  cancellation authority. Oreslang throws/raises/panics/suspends must
  be checked through all callbacks and pipeline operators; panics may
  not be demoted to catchable Observable errors.
- Task factories, captures and actor state access require strict
  ownership, isolation, source effect and checked callback proofs.
- Oreslang core is OS-neutral; filesystem, HTTP, virtual-thread
  blocking adapters belong in trusted stdlib. No guest FFI.
- Concurrency (number of in-flight suspended computations) is distinct
  from OS thread count and CPU parallelism; default bounds apply
  per tenant, scope, memory byte budget, and source profile.
- Long coordinator admission loops must cooperate with the scheduler
  but not yield inside a non-suspendable borrow/MutexGuard region.

## Concrete Java host-runtime milestone in PR #1887

TaskMapParallel.mapLimit(scheduler, inputs, limit, taskFactory) adds
a source-agnostic host-only coordinator Task and an owning TaskScope.
It uses lazy Task factories and admits at most limit children,
recycling a slot only after getDone() physical retirement.
It preserves input ordering and limits admission bursts to 32
per coordinator scheduler turn.

TaskMapParallel.mapSeries is the concurrency=1 specialization.
The MapHandle exposes result() plus a separate getDone() that
settles only after coordinator AND child scope quiescence.
Child errors halt admission and cancel siblings without cancelling
borrowed source Futures. Cancellation of a child is currently surfaced
as a failed host composition; source cancellation classification
remains a parity/release gate.

This host API does not implement a streaming map operator, byte-based
quotas, guest callback type/effect proofs or LLVM lowering. Array/Task
snapshots are finite and capped by MAX_INPUTS = 65,536; unbounded
items require a Stream + credit-based demand API.

## Mandatory conformance

1. Empty input, invalid limits, genuine fail-fast, cancelled child.
2. Input-order preservation under reverse completion order.
3. Simultaneous running/admitted Task count never exceeds limit,
   independent of scheduler carrier count.
4. Concurrency credits released only after physical Task retirement;
   cancelling while a CPU task is still executing does not free arena.
5. Parent failure/cancel seals its scope and joins all owned children.
6. One worker can run an arbitrary number of nonparking suspended
   tasks without deadlock or thread-per-task assumptions.
7. No recursive callback stack with 1,024 precompleted Futures.
8. Real queue capacity and admission waiting plus bounded backpressure
   even when slow consumers retain results.
9. Typed DAG cycle detection, multi-parent dependencies, guest
   ownership transfer, actor exclusivity and effects.
10. Java JVM/GraalVM Native Image and LLVM conformance on authorized
    runners; performance tests include p99 and memory/resource caps.

Keep PR #1887 DRAFT until actual exact-head compiler/JUnit and
cross-backend source tests execute successfully and are reviewed.
