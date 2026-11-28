# Oreslang reactive streams, Tasks and async/await: interoperability v1

Status: **design and validation contract, not implemented source syntax**.
Tracks draft PR #1887 and issues #1888, #1889, #1890.

Primary research (October 2026):
- Rx.NET IObservable: https://learn.microsoft.com/en-us/dotnet/api/system.iobservable-1
- .NET bounded Channels: https://learn.microsoft.com/en-us/dotnet/core/extensions/channels
- RxJS operators: https://rxjs.dev/api/operators/concatMap and https://rxjs.dev/api/operators/exhaustMap
- Kotlin Flow/SharedFlow/Channel: https://kotlinlang.org/docs/flow.html
- Scala Monix Observable demand/ACK: https://monix.io/docs/current/reactive/observable.html
- Apache Pekko ordered async map: https://pekko.apache.org/docs/pekko/current/stream/operators/Source-or-Flow/mapAsync.html
- caolan async queue/inject: https://caolan.github.io/async/v3/docs.html

## Core invariants

1. One scheduler, ownership, structured cancellation, cooperative-safepoint and
   backpressure substrate for Futures, Tasks, Streams, Observables and queues.
   No extra Rx executor or one OS thread pool per queue/Observable.
2. async fnc denotes ability to suspend; await may await I/O Futures, Tasks,
   an async queue admission, a Stream.next Future, terminal Observable
   collection, Future.all etc. Neither async nor await is limited to I/O.
3. task { } and nb { } produce semantically identical EAGER Tasks. Do not
   pretend that an array of such Tasks can be run sequentially afterward.
   Introduce lazy, single-use TaskStep<T> factories for Task.series, queue,
   DAGs and concurrency-limited operators. CPU Task placement remains
   checked; GPU must fail closed unless physically supported.
4. Core library is host OS/network agnostic. Filesystem, socket, HTTP, timer
   sources live in stdlib. Callbacks undergo static Send/Sync, ownership,
   suspends, throws/raises/panics effect verification; no runtime reflection.
5. Logical Future settlement, a cancellation request, and physical Task
   quiescence are distinct events. Memory loans/arenas stay pinned until
   true quiescence.

## Cardinality and ownership table

| Type | Values | Start semantics | Consumer | Domain owner |
| --- | --- | --- | --- | --- |
| Future<T> | one result/error/cancel | already initiated | await/select | producer |
| Task<T> | one future result | eagerly scheduled | await/TaskScope | owning TaskScope |
| TaskStep<T> | one result | LAZY; single use | Task.parallel/series/queue | admission scope |
| Channel<T> | many, unicast work-sharing | eager producer queue | readch/select | channel owner |
| Stream<T> | many, usually unicast | demand-driven | next / for await | stream scope |
| Observable<T> | many, cold or hot | subscription-defined | subscribe/operators | subscription |
| TaskQueue<T,R> | many jobs and results | lazy job startup | enqueue/worker/result stream | queue scope |

Existing ChannelRuntime in Java main provides bounded admission/select/close.
The OresStream<T> work in stacked Java PR #1789 is a draft source milestone;
do not assume it is merged or that it already has Observable parity.

A Channel with two readers is work-sharing: each item goes to one reader.
It is NOT a broadcast stream. Keep Observable.from_ch compatibility but
document competing-consumer semantics. Only explicit share/multicast creates
independent deliveries to multiple subscribers.

## Source conversion and awaiting (proposed, not yet executable)

- Stream.from_ch(ch): one-reader async sequence view of Channel.
- Observable.from_ch(ch): reactive wrapper with explicit work-sharing rule.
- Observable.from_future(f): one emission then completion; observing the same
  existing Future twice never re-executes the operation.
- Observable.defer_task(stepFactory): genuinely cold; start a fresh
  ownership-checked TaskStep for each subscription.
- Observable.from_stream(stream): one active consumption; refuse a second
  subscription unless stream is a reusable cold factory or share is explicit.
- Observable.to_stream(capacity, overflow): bounded subscription bridge,
  default overflow = backpressure for cooperatively controllable producers.
- await observable.first(): Future<Option<T>>, unsubscribe after first item.
- await observable.single(): Future<T>, reject empty or >1 events.
- await observable.to_array(maxItems): bounded Future<Array<T>>, no unbounded
  collect in strict profiles.
- await stream.next(): Future<Option<T>>, EOS distinct from stream error.
- for await (item in stream): proposed syntax sugar over next() with
  scoped finally cleanup and no-yield region checks.
- await observable itself MUST be a type error: 0..N emissions have no
  canonical single await result.

Source-facing illustrative intent:

    async fnc fetch(int id): User {
        return await api.get_user_async(id);
    }

    let Task<User> a = task { return await fetch(1); };
    let Task<User> b = nb { return await fetch(2); };
    let users = await Future.all([a, b]);

    let stream = Stream.from_ch(events);
    for await (item in stream) {
        await process_async(item);
    }

    let pipeline = Observable.from_ch(events)
        .map_task(
            item => Task.defer { return await process_async(item); },
            concurrency: 8,
            order: #input
        );

These are intended API shapes. Parser signatures, generic lambda syntax,
Task.defer declaration and guest binders must be separately implemented.

## Operator contracts

| Operator | Semantics |
| --- | --- |
| map/filter | non-suspending synchronous transforms |
| map_async(f, concurrency, order) | bounded async Future mapping |
| map_task(factory, concurrency, order) | reserve credit, then start scoped TaskStep |
| concat_map | sequential inner streams; 1 inner active |
| merge_map | concurrent inner streams; explicit positive limit, bounded source queue |
| switch_map | old subscription revoked on newer input; memory loans retained until Task quiescence |
| exhaust_map | ignore new input while old inner active; explicitly lossy/counted |
| zip | lockstep tuple, terminate on shortest source |
| combine_latest | after all sources have emitted, recompute for every new update |
| merge/concat | merge events fairly or subscribe/run sources serially |
| debounce/throttle/sample | stdlib clock; deterministic virtual-clock tests |
| buffer/window/batch | count/time grouping with finite memory/byte limits |
| share/shareReplay | hot multicast; replay always explicitly bounded |
| catch/retry/take_until | checked effects, cancellation and structured recovery |
| first/single/reduce/to_array | finite terminal Future, explicit limits |

Use Apache Pekko's distinction: map_async(order:#input) preserves input
order, and order:#completion yields completion order. For ordered processing,
a stuck early item must backpressure admission before completed tail
results grow without limit. concat_map must not queue unbounded incoming
inners as RxJS concatMap can under uncontrolled emissions.

switch_map must distinguish unsubscribing from a shared Observable from
cancelling a Task owned by the subscription. Task cancellation cannot refund
memory/permit credits until the task is physically quiescent. A newer
task may overlap only when shared/mutable loans are proven nonconflicting.

exhaust_map is intentionally lossy. Never silently use it for a reliable
task queue, payment flow, message processor or transactional pipeline.

## Backpressure and subscriber demand

Default managed-stream policy is finite, lossless backpressure. A subscriber
can request N additional items or send async ACK (#continue / #stop);
the runtime optimizes this as a primitive credit counter rather than
allocating a managed wrapper per event.

- Count queued events, active mapper Tasks, ordered-reassembly slots and
  downstream retained events against finite, byte-aware budgets.
- When credit ends, an Ores Task/Channel producer suspends cooperatively;
  never park an actor carrier or spin on a full queue.
- For externally uncontrollable hot event emitters, require an explicit
  bounded #drop_oldest, #drop_newest, #latest, or #fail mode; impossible
  to promise upstream backpressure when the source cannot pause.
- All callback execution is deferred to the OWNED scheduler/actor mailbox;
  no guest code on producer I/O/JNI/timer completion threads or under
  channel coordinator locks.
- Cancellation removes pending demand, waiter registrations and callbacks.
  Unsubscribing does not cancel shared producers; owned producer TaskScope
  cancels and drains before memory release.
- Cold Observable: fresh execution on each subscription, with proper
  copy/ownership capability checks. Hot Observable: source may already be
  producing, does not replay unless explicitly configured.
- share: one upstream, bounded N subscribers, per-subscription buffer.
  Slow subscriber policy #block_all, #drop_for_slow, #disconnect_slow
  is explicit; NEVER silently retain unbounded per-subscriber values.
- shareReplay(replayLimit): finite item and byte budget.
- StateObservable<T>, analogous to Kotlin StateFlow, is a future extension:
  store a latest immutable value and conflate updates explicitly.

## TaskQueue/Observable/Stream unification

                ChannelRuntime (credits/select/close)
                     |                   |
                Stream<T>            TaskQueue<T,R>
                     |                   |
              Observable<T>        results(): Stream<Outcome<R>>
                     |                   |
           bounded map_async       events(): Observable<QueueEvent>
                     \                   /
                  OresScheduler + TaskScope

- TaskQueue is a work-sharing *job scheduler*, not multicast.
- queue.enqueue_async(item) suspends until an admission permit exists, then
  returns JobHandle<R>; await job means waiting for item processing.
- Optionally offer ergonomic queue.push_async(item) -> Future<R>, but
  document that its Future completion means job completion rather than
  admission. Avoid hidden unbounded pending producer registrations.
- Concurrency credits cover queued+running until physical job retirement.
  Results awaiting collection have a distinct bounded retention budget.
- results() is a UNICAST Stream, with explicit completion/input order mode.
  events() is an optional HOT multicast diagnostics Observable; do not
  conflate queue item ownership with event subscribers.
- pause/resume only dispatch, seal rejects input, drain is a sequence
  snapshot barrier, cancel+close joins admitted Tasks before releasing
  their scoped memory.
- Batch/cargo queues need max count, max bytes, time-based flush and
  bounded number of active batches. Priority queues need starvation
  limits and stable same-priority FIFO.

## Compatibility with actors, GPU, memory and compiler

- Serial actor callbacks remain serial across await. Reactive callbacks
  must enqueue through the actor-owned scheduler/mailbox and never
  silently re-enter an actor that retains an exclusive actor-state lock.
- Canonical ownership: rt take, rt share, rt borrow, rt mut borrow.
  No cross-actor transfer of local Stream handles, subscriber callbacks,
  native borrowed views, or private Task cancellation authority.
- Effect contracts must distinguish catchable throws, raised failures,
  non-trappable panics and ordinary cancellation; panics cannot be
  transformed into benign Observable.onError without allowed semantics.
- Operator AST and generic contracts are compiler-checked and AOT
  tree-shakeable, not inferred via reflection or string parameter names.
- Compiler safepoints in long CPU loops, between stream batches and
  worker Tasks must not yield under live non-suspendable borrow/MutexGuard.
  Watchdog 4-ms request is advisory, not a hard real-time guarantee.
- GPU mapping remains rejected without real device execution and
  fence/physical completion support.

## Acceptance tests before release

1. Two subscribers on a Channel adapter compete, while explicit share
   multicasts; no accidental duplication or loss in reliable mode.
2. Cold source starts twice on two subscriptions when permitted; hot
   Future observed twice completes once from a single producer.
3. Task + I/O Future + Stream.next may be awaited in one async function.
   No new pool/worker created per await.
4. Ordered async mapper with 3,1,2 completion emits 1,2,3; unordered
   emits 3,1,2; concurrent active <= configured maximum.
5. One slow first mapper and 10k fast tail jobs remain within finite
   ordered-reassembly, memory and task-slot budgets.
6. concat_map starts next child only after predecessor completion; no
   premature arena-loan reclamation. merge_map remains fair across sources.
7. switch_map cancels owned prior Task, detaches shared source only, and
   waits for physical quiescence before unsafe memory reuse.
8. Slow hot subscriber triggers explicit block/drop/disconnect policy;
   no unbounded queue; cancellation detaches exact subscription token.
9. first on empty -> None, first on data unsubscribes, single with >1
   values errors, terminal failures remain failures rather than EOS.
10. Queue full producer sleeps via nonparking await; canceled waiter
    releases registration without consuming a phantom item/credit.
11. 100k emissions / nested operator chains avoid recursive stack
    growth and never execute guest code on foreign producer threads.
12. Tests for no-yield guards, source Task error effects, actor
    confinement, SourceLoopSoftPreemption, LLVM native continuation
    parity, GraalVM AOT/Native Image and leak/reclamation conditions.

Sequence: typed demand/credit contract -> Stream.next / for await ->
Future/Stream/Observable adapters -> bounded map_async/map_task ->
merge/concat/switch/exhaust -> TaskQueue streams/hot queue events ->
GraalVM/LLVM conformance and performance.

DO NOT CLAIM this document introduces implemented runtime operators.
Draft PR #1887 now also has a bounded host stream collector and scheduler-owned
Future map/flatMap/zip operations. None are source-level guest operators.


## Implemented Java host substrate checkpoint (2026-10-22)

Related Java draft PR #1887 contains `AsyncSequences.Cursor<T>` with
`fromChannel`, `owningChannel` and `fromFuture`, plus nonparking
`nextAsync(): OresFuture<Optional<T>>` and focused JUnit source tests.
This is an *unicast observer* bridge, **not Observable multicast**, not an
Oreslang guest `for await` implementation and not TaskQueue job ACK/NACK.

A channel read committed before consumer cancellation cannot be un-read;
reliable queue execution needs a separate reservation/ack/retry protocol
before delivery can be described as at-least-once. A single borrowed
cursor close does not close a source shared with other consumers.

`FutureCombinators.any` additionally performs first-success
composition; `Future.all`/any/race compose already-started operations.
None supplies physical Task quiescence authority.

No CI passing claim until jobs actually execute tests.


## Strict finite collector and scheduler-affine mapping checkpoint

Java draft PR #1887 now contains:
- `AsyncSequenceCollectors.collect(OresScheduler, Cursor<T>, maxItems)`
  with a bounded, explicit scheduler Task, no recursive callback chaining,
  a `CollectionHandle.result()` and a distinct `getDone()` barrier that
  completes only after physical task retirement AND cursor close;
- strict count admission: maxItems+1 observed values detect overflow,
  return a `LimitExceededException` and do not build an unbounded List;
  the extra value is consumed (at-most-once) and cannot be rolled back;
- task cancellation tears down only its borrowed stream read, never a
  caller-owned Channel producer;
- `FutureCombinators.map/flatMap/zip` with scheduler ownership for mapper
  execution. The host Java lambdas are trusted callbacks: they DO NOT prove
  effect/ownership safety for Oreslang guest captures.
- `AsyncSequences` retains its sequential-next reservation through
  terminal/revocation publication; runtime callbacks may reenter next
  once the last result is truly terminal, never while cancellation
  revocation is still pending.

**Remaining:** source `await observable.first/single/to_array`
and `for await` compiler and stdlib glue; backpressure-aware mapping
with concurrency >1; ownership/effect-proofed mapper captures; ordered
reassembly quota, full Observable subscription, multi-target parity,
per-actor native arena reclamation proof; executed proof of cancellation races in scheduler-mapped Futures, plus
byte-aware collect quotas.
Neither this design nor draft source files constitutes a passing test run.
