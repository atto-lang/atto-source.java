# Actors, mailboxes, channels, select, and cancellation

This document defines the Oreslang concurrency direction. The central rule is:

> General-purpose concurrency in Oreslang is actor concurrency.

Futures and Awaitables describe completion and suspension. They are not a second
independent "goroutine/task" object model. Schedulers and carrier thread pools
decide where actor work executes. Channels and mailboxes decide how actor work
communicates.

## Mailbox = actor routing around an internal channel

Every actor owns exactly one inbox channel as part of its mailbox.

The distinction is intentional:

- **Channel** is the queue/rendezvous primitive.
- **Mailbox** owns the actor-specific policy around that channel: destination
  identity, message freezing/copying, actor-memory accounting, quotas,
  supervision state, generation/lifetime checks, and scheduling.
- **ActorRef** is the external send capability. It does not expose a receive
  operation.
- The actor runtime/compiler owns the receive endpoint. Foreign actors cannot
  steal messages from another actor's inbox.

So an actor send is conceptually:

1. validate the ActorRef/runtime/generation;
2. validate/freeze/copy the message graph;
3. reserve mailbox and actor-memory quota;
4. write the prepared envelope to the destination actor's internal channel;
5. mark the destination actor runnable.

There must not be a second Java/JVM queue with subtly different semantics behind
mailboxes. The reference runtime therefore backs actor mailboxes with
`ChannelRuntime.Channel<MessageEnvelope>`.

ActorMailman remains routing/control-plane logic. It may decide which actor or
actor group receives an envelope, but it must not invent another transport
primitive.

## readch, writech, await, and select are suspension points

Source-level channel operations are scheduler suspension points:

```ores
let msg = readch incoming;
writech outgoing, payload;
let value = await future;
```

"Blocking" in Oreslang means the logical actor/continuation waits. It must never
mean parking one of the bounded actor carrier threads.

The lowering target is an Ores-owned `OresFuture<T>`. A pending operation:

- registers runtime-owned readiness state;
- releases the current actor/scheduler turn;
- lets the carrier execute other runnable actors;
- makes the suspended actor runnable again when the operation wins/completes;
- resumes only through the actor's scheduler/serialized execution lane.

Producer, timer, JNI, I/O, and channel-completion threads may settle runtime
state, but they never execute Oreslang guest continuation code inline.

## Static select

Canonical static syntax:

```ores
select {
case readch incoming : let msg
    stdio.println("Received:", msg);
case readch payload : const payload
    stdio.println("Received payload:", payload);
case readch ctx.Done():
    return;
}
```

The default policy is **priority**, not Go-style pseudo-random choice.

If several cases are ready when a selection probe occurs, the first ready case
in source order wins. This makes tests, simulations, replay, and latency
reasoning reproducible. Priority selection can starve later cases, so code that
needs fairness should request it explicitly.

```ores
select round_robin {
case readch high_priority : let msg
    handle(msg);
case readch background : let msg
    handle_background(msg);
}
```

A reusable round-robin select site owns its own cursor. There is no
process-global random generator influencing selection.

Future policies may include weighted or deadline-aware selection, but they must
be explicit and inspectable. Hidden randomness is not part of the default
contract.

## Nonblocking select

Canonical spelling:

```ores
nb select {
case readch incoming : let msg
    stdio.println("Received:", msg);
case readch payload : const payload
    stdio.println("Received payload:", payload);
case readch ctx.Done():
    return;
}
```

`nb select` means:

1. build/arm the same select plan as ordinary `select`;
2. return from the current source statement immediately;
3. when one case wins later, enqueue its branch as a continuation on the owning
   actor/scheduler;
4. execute the branch only when that actor next owns its single execution lease.

The winning branch **must not** run on the producer thread and **must not** run
concurrently with another turn of the same actor. Nonblocking selection changes
waiting behavior, not actor serialization.

Semantically this is the same idea as creating a Future and not immediately
awaiting it. The runtime registration remains attached to the actor lifetime, so
actor cancellation automatically detaches it.

## Dynamic select

Dynamic select uses exactly the same select engine. It is not a reflection-only
side API like Go's `reflect.Select`.

A dynamic case is data:

- `readcase(channel, key)`
- `writecase(channel, value, key)`

A list can be built from runtime data:

```ores
let cases = [
    readcase(incoming, "incoming"),
    readcase(payload, "payload"),
    readcase(ctx.Done(), "cancel"),
];

select cases : let chosen;

match chosen.key {
    "incoming" -> handle(chosen.value);
    "payload"  -> handle_payload(chosen.value);
    "cancel"   -> return;
}
```

The nonblocking dynamic form produces/arms the same pending selection without
waiting for it:

```ores
nb select cases : let pending;
```

Map-driven construction is also first-class. Map keys become result keys:

```ores
let cases = readcases(channels_by_name);
select round_robin cases : let chosen;
dispatch(chosen.key, chosen.value);
```

For priority selection the input iteration order is the priority order. Code
that requires reproducibility must use an order-defined collection or sort the
case list explicitly before selecting.

The runtime already exposes list/map case construction and reusable
`SelectPlan` values. Grammar/evaluator lowering must target that substrate; it
must not duplicate selection logic in the parser/interpreter.

## Select correctness

A select cannot be implemented by independently starting N reads/writes,
racing their Futures, then cancelling N-1 losers. A losing read might already
have consumed a value and a losing write might already have published one.

The reference runtime instead uses readiness subscriptions plus a deterministic
re-probe. Losing cases do not commit an operation.

This gives the required properties:

- exactly one committed case;
- no value consumed by a losing read;
- no value published by a losing write;
- cancellation detaches readiness subscriptions;
- static and dynamic select share the same engine;
- selection policy is explicit.

## Parent and child actors

An actor spawned during another actor's turn is automatically that actor's
child. Callers do not pass a cancellation Context object through every API.

Parent/child structure is lifecycle structure, not message-routing magic.
Parents and children still communicate through ActorRefs/mailboxes/channels.

Cancelling a parent cascades to all descendants. Pending awaits, channel
operations, and selects owned by those actors must be detached/cancelled as part
of lifetime teardown.

This is the structured-concurrency property Oreslang wants without creating a
second task abstraction beside actors.

## Trusted actor cancellation

For ordinary trusted actors, cancellation may be cooperative at the guest-code
boundary while still being automatic at every Oreslang suspension/safepoint:

- reject new mailbox writes after cancellation begins;
- detach pending await/readch/writech/select registrations;
- cascade cancellation to child actors;
- raise/observe cancellation at the next scheduler boundary or safepoint;
- unwind Oreslang cleanup/defer/finally when the execution model permits it;
- reclaim actor-owned memory and capabilities after the turn leaves its runtime
  boundary.

User code should not need to manually poll a `ctx.Done()` equivalent merely to
make ordinary cancellation work.

## Untrusted actors: noncooperative hard cancellation

Untrusted actors are different. The runtime must assume hostile code will never
cooperate, never poll, and may intentionally spin forever.

Therefore an untrusted actor must live in a **force-terminable execution
context** whose lifetime contains no unrelated trusted actor. The preferred
topology is:

- untrusted actors execute on the untrusted actor scheduler/thread pool;
- they are placed in the secondary/further Graal isolate;
- each untrusted actor, or an explicitly declared kill-together ActorGroup,
  owns a separately closable Graal `Context`;
- supervisor hard cancellation calls `Context.close(true)` through the
  runtime-owned cancellation domain.

`HotReloadManager.Generation.cancelNow()` is the current concrete forced
context-cancellation primitive.

Hard cancellation order should be:

1. atomically mark the actor/domain cancelled and reject new messages;
2. invalidate/detach all pending Future/channel/select registrations;
3. cascade hard cancellation to untrusted children;
4. revoke runtime-owned capabilities/resources;
5. force-close the guest context with `Context.close(true)`;
6. reclaim the actor memory domain and mailbox storage;
7. publish the termination/exit signal to the supervisor.

Hard cancellation does **not** guarantee guest `defer`/`finally` execution.
That is why untrusted resources requiring guaranteed cleanup must be
runtime-owned and registered in a supervisor-side resource ledger rather than
relying on hostile guest cleanup code.

If multiple untrusted actors intentionally share one force-cancellation context,
they form an explicit kill-together group. The language/runtime must never
pretend it can force-kill only one member of such a group.

## Future cancellation

Cancelling an Ores Future means "this continuation no longer wants the result."
It must:

- detach its runtime waiter/registration;
- prevent later completion from re-entering cancelled guest work;
- propagate cancellation to an exclusively owned underlying operation when
  safe;
- never execute guest callbacks inline.

A Future is completion state, not a separately addressable concurrent job.
When a source construct needs independently cancellable concurrent execution,
that execution is an actor.

## Implementation status of this draft

This branch establishes the runtime substrate:

- Ores-owned buffered and rendezvous channels;
- Future-based read/write suspension;
- deterministic PRIORITY selection;
- reusable ROUND_ROBIN selection plans;
- dynamic list/map case construction;
- cancellation-safe select readiness registration;
- actor mailboxes backed by Ores channels instead of a Java BlockingQueue;
- automatic parent/child actor lifetime attachment and cascading cancellation;
- explicit forced Graal generation cancellation.

The parser/evaluator surface for `readch`, `writech`, `select`, and
`nb select` must land only after the current transitional await path is fully
unified with `OresFuture/OresScheduler`. It is intentionally not implemented
by blocking actor carriers just to make syntax tests pass.
