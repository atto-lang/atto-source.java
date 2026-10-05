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


## Core/std-lib ownership and runtime boundary

The user-facing channel API belongs to Oreslang core/stdlib (canonically
`std/channels` plus compiler syntax sugar), not to the scheduler or ActorMailman
implementation.

The compiler/runtime primitive boundary is deliberately small:

- `Channel<T>` and rendezvous/bounded channel construction;
- Future-producing read/write registration;
- `SelectSet`, read/write cases, and selection policies;
- immediate `try readch` / `try writech` probes.

Source syntax lowers onto that core surface:

```text
readch ch             -> await (nb readch ch)
writech ch, value     -> await (nb writech ch, value)
select { ... }        -> await (nb select { ... })
```

The OresVM scheduler, ActorMailman, and ActorGroup implementation do **not** use
`select` or `nb select` to scan actor mailboxes. Mailbox writes already know
which actor became runnable, so the runtime pushes that actor onto the relevant
ready queue directly. This preserves O(1)-style wakeup behavior and avoids a
giant dynamic select over all actors.

A future implementation may share lower-level readiness-registration machinery
between channels, timers, I/O, mailboxes, Futures, and scheduler wakeups, but
that does not make scheduler dispatch itself a source-level `select`.

## readch, writech, await, and select are suspension points

Source-level blocking channel operations are scheduler suspension points:

```ores
let msg = readch incoming;
writech outgoing, payload;
let value = await future;
```

"Blocking" in Oreslang means the logical actor/continuation waits. It must never
mean parking one of the bounded OresVM carrier threads.

All channel waits lower through Ores-owned `OresFuture<T>` registrations. A
pending blocking operation:

- registers runtime-owned readiness state;
- suspends/releases the current actor turn;
- lets the carrier execute other runnable actors;
- makes the suspended actor runnable again when the operation completes;
- resumes only through the actor's scheduler/serialized execution lane.

Producer, timer, JNI, I/O, and channel-completion threads may settle runtime
state, but they never execute Oreslang guest continuation code inline.


## Nonblocking and immediate channel operations

`nb readch` and `nb writech` arm the same channel operation but do not suspend
the actor. They return a Future/Awaitable immediately:

```ores
Future<Message> pending = nb readch incoming;
Future<WriteOutcome> sent = nb writech outgoing, payload;

do_other_work();

let msg = await pending;
await sent;
```

No actor is spawned to implement these forms. A Future represents completion,
not independently executing work.

This is distinct from the immediate probe forms:

```ores
try readch incoming
try writech outgoing, payload
```

`try` checks readiness once and returns immediately **without registering a
waiter**. The three-way contract is therefore:

```text
readch       register + suspend actor until completion
nb readch    register + return Future immediately
try readch   probe once; never register

writech      register + suspend actor until completion
nb writech   register + return Future immediately
try writech  probe once; never register
```

Outstanding `nb readch` / `nb writech` registrations are attached to the
owning actor lifetime and are detached automatically on structured
cancellation.

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

The default policy is **fair deterministic rotation**, not Go-style
pseudo-random choice and not fixed lexical priority.

Each logical select site owns a reusable `SelectSet` cursor. If multiple cases
stay ready, successive selections rotate the first probe position so one hot
case cannot permanently starve the rest:

```ores
select fair {
case readch a : let x
    handle_a(x);
case readch b : let y
    handle_b(y);
}
```

Strict lexical priority is opt-in:

```ores
select ordered {
case readch control : let msg
    handle_control(msg);
case readch normal : let msg
    handle_normal(msg);
}
```

With `ordered`, the first ready case wins and later cases may starve. That is
useful when the programmer intentionally wants cancellation/control traffic to
outrank ordinary work.

There is no hidden process-global random generator. An explicit random or
weighted policy may be added later as an opt-in policy, but randomness is never
the default.

## Nonblocking select

Canonical spelling:

```ores
Future<SelectOutcome> selection =
    nb select {
    case readch incoming : let msg
        stdio.println("Received:", msg);
    case readch payload : const payload
        stdio.println("Received payload:", payload);
    case readch ctx.Done():
        return;
    };
```

`nb select` means:

1. build/arm the exact same `SelectSet` as ordinary `select`;
2. return immediately with the pending Future/Awaitable;
3. when one case wins later, enqueue its continuation on the owning
   actor/scheduler;
4. execute that continuation only when the actor next owns its single execution
   lease.

The winning continuation must not run on the producer thread and must not run
concurrently with another turn of the same actor. Nonblocking selection changes
waiting behavior, not actor serialization.

Ordinary blocking `select` is simply the source-level await/suspension form of
the same runtime operation.

## Dynamic select

Dynamic select uses exactly the same `SelectSet` runtime primitive as static
select. Oreslang does not require reflection.

A dynamic case is ordinary typed data:

- `readcase(channel, key)`
- `writecase(channel, value, key)`

A list can be built from runtime data:

```ores
let cases = [
    readcase(incoming, "incoming"),
    readcase(payload, "payload"),
    readcase(scope.cancelled(), "cancel"),
];

let set = selectset(cases);
let chosen = select set;

match chosen.key {
    "incoming" -> handle(chosen.value);
    "payload"  -> handle_payload(chosen.value);
    "cancel"   -> return;
}
```

The same set can be armed nonblockingly:

```ores
Future<SelectOutcome> pending = nb select set;
```

Map-driven construction is also first-class. Map keys become result keys:

```ores
let set = selectset(readcases(channels_by_name));
let chosen = select set;
dispatch(chosen.key, chosen.value);
```

For `fair`, the `SelectSet` owns the rotating cursor. For `ordered`, input
iteration order is priority order. Code using ordered map selection must
therefore use an order-defined map or sort entries explicitly.

Static compiler syntax and dynamic list/map construction both lower to
`ChannelRuntime.SelectSet`; there is no reflection side path and no duplicate
selection engine.

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
lifecycle child. Callers do not pass a cancellation Context object through every
API.

Parent/child structure is supervision/lifetime structure, not message-routing
magic. Parents, children, and grandchildren still communicate through ordinary
ActorRefs/mailboxes/channels.

There is **not** a scheduler or full ActorMailman per actor:

- OresVM owns the physical scheduler/thread-pool domains;
- each actor owns its mailbox channel plus small scheduling metadata;
- each ActorGroup owns one logical serialized ActorMailman;
- a normal child/grandchild inherits its parent's ActorGroup and scheduler
  domain unless spawn explicitly crosses a trust/isolation/resource/placement
  boundary.

The supervision tree and ActorGroup topology are intentionally different
dimensions. A child may remain supervised by its parent while being placed in a
different group/domain because it is untrusted, isolated, quota-separated, or
remotely placed.

Cancelling a parent cascades to all descendants. Pending awaits,
`readch`/`writech`, `nb readch`/`nb writech`, and selections owned by those
actors are detached/cancelled as part of lifetime teardown.

This gives structured actor lifetimes without creating a second task abstraction
beside actors.

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
- Future-based read/write registration;
- explicit `nb readch` / `nb writech` lowering targets;
- immediate non-registering `try readch` / `try writech` probes;
- reusable `SelectSet` as the single static/dynamic selection primitive;
- deterministic FAIR selection by default;
- explicit ORDERED selection for strict lexical priority;
- `nb select` as the same Future-producing operation without source-level await;
- dynamic list/map case construction without reflection;
- cancellation-safe select readiness registration;
- actor mailboxes backed by Ores channels instead of a Java BlockingQueue;
- automatic parent/child actor lifetime attachment and cascading cancellation;
- explicit forced Graal generation cancellation.

The scheduler and ActorMailman remain push/ready-queue based and do not implement
their actor loops with `select` or `nb select`.

The parser/evaluator spelling for `readch`, `writech`, `select`, and the
`nb` forms should lower directly onto these core/stdlib primitives once the
transitional source `await` path is fully unified with
`OresFuture/OresScheduler`. It must not be implemented by parking carrier
threads merely to make syntax tests pass.

