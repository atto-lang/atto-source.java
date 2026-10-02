# Oreslang actor/async model: Erlang lessons and hardening contract

This document records which Erlang/OTP strengths Oreslang keeps and which historical failure modes the runtime deliberately avoids.

## Keep

- cheap actor identities with serialized mailbox ownership;
- supervision/monitoring as explicit message-driven mechanisms;
- immutable or isolation-copied message transport;
- per-actor logical ownership and actor-local memory accounting;
- pattern matching and explicit `Result<T,E>` / `Option<T>` rather than ad-hoc error tuples;
- preemptive/cooperative scheduler safepoints without equating an actor with a thread;
- hot-code generations with explicit migration instead of ambient mutable code state.

## Await is one language contract, many backend adapters

Source Oreslang exposes `Future<T>` and one `await` operator. Java implementation classes are not source types.

The JVM backend currently adapts:

| backend value | await result |
| --- | --- |
| `CompletionStage<T>` / `CompletableFuture<T>` | `T` |
| `java.util.concurrent.Future<T>` / `FutureTask<T>` | `T` |
| virtual thread running a `FutureTask<T>` | `T` through the task handle |
| raw platform/virtual `Thread` | completion only / `void` |
| `AwaitSupport.Awaitable<T>` | `T` |

A pending backend wait is forbidden from blocking an actor dispatcher carrier. Generated async/actor code must suspend the Oreslang continuation and resume it when the completion fires. This keeps virtual threads and CompletableFuture as backend choices rather than language semantics.

`await` removes exactly one awaitable layer. `await Future<T>` produces `T`; `await Future<Future<T>>` produces `Future<T>`, not `T`. Recursive flattening is never implicit because it would hide ownership, cancellation, and failure boundaries.

## Receive without Erlang selective-mailbox scans

Every actor mailbox is FIFO and bounded. There are two receive modes at the runtime/lowering boundary:

- `receive()`: logical blocking receive. It installs one pending receive continuation and returns immediately as a `CompletionStage<M>`. The next FIFO message satisfies that continuation.
- `try_receive()` (runtime `tryReceive()`): non-blocking head poll. It returns immediately with message-present/message-absent state.

Rules:

1. receive is legal only during the owning actor's mailbox turn;
2. only one logical receive may be pending for an actor;
3. `try_receive` cannot race a pending `receive`;
4. neither operation searches past the mailbox head;
5. a receive waiter owns the next message, so that message is not also dispatched through the ordinary behavior callback;
6. stop/failure completes an outstanding waiter exceptionally;
7. mailbox limits and memory quotas still apply before admission.

This intentionally rejects Erlang's "scan old messages until one pattern matches" cost model. When selective routing is needed, Oreslang should use statically typed protocol tags and indexed/channelized submailboxes so dispatch is O(1)/indexed rather than O(mailbox length).

The source front-end exposes two safe receive surfaces. The simple binding form requires an explicit message type until `Protocol<M>` is wired into actor declarations. The pattern form permits an open mailbox protocol only when each payload capture that needs a concrete type is annotated (for example `key: String`) and the cases include `default` or another irrefutable arm. This avoids inventing an untyped string-tag API while keeping protocol-pattern syntax usable before full protocol-union lowering.

### Compiler-visible receive loops and actor state

Receive/send/channel operations are language syntax, not ordinary library calls. The reserved concurrency vocabulary is `channel`, `send`, `try_send`, `receive`, `try_receive`, `select`, `case`, `default`, and `loop`.

The actor mailbox is a special bounded typed channel. An `ActorRef<M>` is the send capability; only the owning actor has the mailbox receive capability.

```ores
pub actor fnc counter(int initial) => void {
  let int count = initial;

  receive loop {
    case :increment => {
      count = count + 1;
    }

    case [:add, amount: int] => {
      count = count + amount;
    }

    case [:get, reply_to] => {
      send(reply_to, count);
    }

    case :stop => {
      return;
    }

    default => {
      stdio.println("unsupported counter message");
    }
  }
}
```

The pattern form is intentionally **not Erlang selective receive**. It dequeues exactly the next FIFO message once, then tests that value against the cases in lexical order. A non-matching head message is never skipped in order to search for a later match. Until a closed `Protocol<M>` can prove exhaustiveness, the checker therefore requires `default` or an irrefutable case. This bounds receive cost independently of mailbox length and prevents old unmatched messages from turning each receive into a mailbox scan.

Patterns support symbol/string/boolean/numeric literals, nested tuple/list shapes, wildcard `_`, ordinary captures, and typed captures such as `amount: int` or `let state: State`. Typed captures are checked statically and re-guarded at dynamic interop edges.

`receive loop (Message msg) { ... }` remains the simple dedicated resumable actor-loop node when every message should run one body. It suspends when no message is available. `try_receive loop { case ... }` and `try_receive loop (Message msg) { ... }` drain immediately available messages without waiting and exit when the mailbox is empty. Both forms contain mandatory operation-accounting/backedge checkpoints; a continuously busy mailbox cannot monopolize a carrier.

State is not represented by recursive calls. Any local that is live across `receive`, blocking `send`, `select`, or `await` is hoisted by lowering into the actor continuation frame. Mutable actor-owned locals therefore remain single-owner and persist across suspension without Erlang-style `loop(NewState)` plumbing. Ordinary stack borrows may not cross a suspension boundary; actor-owned frame state is allowed because ownership is tied to the actor id, not the carrier thread.

### Typed channels and select

General channels are bounded by construction. `channel<T>(0)` is a rendezvous channel; positive capacities are bounded buffers. There is no implicit unbounded channel.

Blocking channel operations are dedicated suspension syntax:

```ores
val Channel<Job> jobs = channel<Job>(64);

send(jobs, job);          // suspend until admitted or closed
val job = receive(jobs);  // suspend until a value or close result

val sent = try_send(jobs, job);       // immediate admission result
val polled = try_receive(jobs);       // immediate Ready/Empty/Closed result
```

`select` multiplexes receives and sends and commits exactly one ready operation:

```ores
select {
  case receive(commands) as cmd => {
    handle(cmd);
  }

  case send(events, next_event) => {
    mark_sent();
  }

  default => {
    do_other_work();
  }
}
```

Without `default`, `select` suspends the actor continuation until one operation can commit. With `default`, it is non-blocking. Channel expressions are evaluated once, registrations are installed without lost wakeups, and losing cases must be unregistered without consuming data or capacity. If several cases are simultaneously ready, selection uses per-actor rotating fairness rather than permanent lexical priority; this avoids starvation while keeping tests reproducible.

A `select` registration, committed branch, `receive loop` iteration, `try_receive loop` iteration, and compiler-injected statement/backedge checkpoint all consume actor operation budget.


## Bounded queues and backpressure

Actor mailboxes are bounded by `IsolatePolicy.maxMailboxMessages()` and by actor/runtime memory accounting. A send that cannot be admitted fails; it never silently creates an unbounded queue.

The non-throwing `try_send` / typed protocol admission work should be preserved when the older Erlang-wart actor branch is reconciled with the newer private/shared dispatcher stack. Dedicated `send(target, message)` is the blocking/backpressured form and may suspend the sender continuation until capacity is available; `try_send(target, message)` is immediate. No sender should be able to force unbounded receiver memory growth.

## Typed actor capabilities, not untyped PIDs

Actor references must carry a message protocol. Compiler-generated sends must reject messages outside that protocol before transport. Dynamic/host interop must validate the same protocol at runtime.

Request/reply should lower to typed correlation + timeout + monitor semantics, with an explicit future for the reply. A one-shot actor function remains different: it returns `ActorHandle<T>`, and callers explicitly await `handle.result()`.

Protocol/state-machine types remain the longer-term direction for proving legal transitions such as Closed -> Open -> Closed.

## Actor is not isolate

A private actor is a language ownership/fault-isolation domain. A strict Graal/native isolate is the hostile-code security boundary. The runtime must never claim that mailbox serialization alone is a tenant sandbox.

Private actors reject explicit shared mutable cells. Shared actors may use synchronized shared cells only with `SHARED_MEMORY`. Ordinary actor fields remain mailbox-owned and do not acquire implicit locks.

## Other Erlang warts Oreslang should not inherit

- strings, bytes, byte slices and Unicode code points are distinct types; strings are not integer lists;
- variables do not use capitalization to distinguish them from protocol tags;
- `:name` symbols are immutable ordinary GC-able values with bounded identifier-shaped names; equal symbols compare by value, but there is deliberately no required process-global intern/atom table that remote input can exhaust;
- mutable bindings are explicit (`let`) instead of overloading pattern-match syntax as reassignment;
- receive pattern matching is local dispatch after FIFO dequeue, never an implicit mailbox scan;
- no process dictionary / ambient actor-local key-value global;
- local and remote actor references should be distinct types/capabilities so network cost is not hidden;
- native/FFI code is an explicit capability boundary;
- errors and stack traces should retain source spans, actor id, protocol/message type, correlation id and causal chain;
- upgrades use versioned code units and typed migrations rather than hand-authored release scripts as a semantic requirement.

## Hardening checklist

Before actor/async v1 is declared stable:

- [x] bounded mailbox count;
- [x] actor/runtime memory accounting;
- [x] private/shared dispatcher bulkheads;
- [x] one mailbox turn per actor at a time;
- [x] FIFO logical blocking receive;
- [x] non-blocking head receive;
- [x] no selective receive scan;
- [x] backend-polymorphic JVM await adapter;
- [x] reject pending host waits on actor carriers;
- [x] actor vs async separation;
- [x] actor vs isolate distinction;
- [ ] reconcile typed `Protocol<M>`, `try_send`, and typed `ask` from the Erlang-wart branch onto the dispatcher actor stack;
- [x] parse and statically check dedicated `receive loop`, `try_receive loop`, channel send/receive, and `select` AST nodes;
- [x] parse/type-check FIFO receive case patterns with typed captures and bounded GC-safe `Symbol` protocol tags;
- [ ] continuation lowering for executable source `async`, patterned actor receive/send/select suspension, and actor-frame state hoisting;
- [ ] typed bounded channels plus atomic/fair select registration;
- [ ] structured actor error reports with source-span/correlation metadata;
- [ ] typed hot-upgrade migration protocol.
