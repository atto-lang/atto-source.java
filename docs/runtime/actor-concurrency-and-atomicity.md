# Oreslang actor admission, serial mode and atomicity

**Status (2026-10-10):** compiler grammar and strict (1,1) admission
validation are implemented. The production source actor runtime currently
serializes receives **across await** through `sourceReceiveSuspended`. Logical
concurrency >1 is a *target model*, not a supported runtime feature. The type
checker rejects higher configured limits rather than treating them as hints.

## Definition and limits

The Actor Model fundamentally provides an addressable computational entity
which can send messages, create actors and designate behavior after receiving
a message. **One-handler-at-a-time is a common implementation decision, not
an axiom of the original mathematical model.** Oreslang imposes additional
contracts: actor-private state and message-only external access; exactly one
native carrier executes any actor's code at once (PRIVATE, SHARED, UNTRUSTED);
a bounded inbox, lifecycle and supervisor relationships.

Separate three dimensions:

1. **Execution parallelism:** always exactly *at most one native thread* at a
   time executes code belonging to one actor, including continuations, timers,
   request callbacks, and cleanup. This is the actor-wide execution lease.
2. **Transport-session concurrency:** number of admitted live HTTP/TCP/
   WebSocket connections *per actor*, including idle but open sessions.
   The parser's legacy spelling for this axis is `requestConcurrency`;
   this must not be confused with HTTP requests. An HTTP/1.1 keep-alive
   connection can carry several sequential requests; HTTP/2 and HTTP/3
   connections can multiplex concurrent streams.
3. **Message concurrency:** maximum simultaneously admitted logical
   message-handler invocations **per connection/session**, including
   suspended handlers. For HTTP, preserve the separate request/stream ID
   within each session. These axes are not a count of native threads.

For an HTTP actor with `requestConcurrency=3` and
`messageConcurrency=4`, up to three live transport sessions and four in-flight message
handlers *per session* (12 total) can exist, yet only one native
thread at a time may execute code of that actor. The scheduler must be fair
between requests and messages and enforce both bounds through atomic admission
tokens. It must not allocate one carrier per continuation.

## Syntax and canonical alias

```oreslang
define serial http actor CriticalSectionActor as
end

// Exactly the same concurrency policy/AST as above:
define http actor CriticalSectionActor as
    int requestConcurrency = 1;
    int messageConcurrency = 1;
end

// Parsed but not yet runnable by the current source actor backend:
define http actor ConcurrentConnections as
    int requestConcurrency = 3;
    int messageConcurrency = 4;
end
```

`serial` is contextual in actor-declaration position; it enforces both values
of one and cannot be overridden by values greater than one. `http`, `tcp`
and `websocket` are similarly contextual actor protocol labels.
**Ordinary `actor` and `isoactor` expose only `messageConcurrency`**;
explicit `requestConcurrency` is a compile-time error for a generic
actor even if assigned `1`. Protocol actors expose exactly the two
legacy-spelled axes; no third scheduling knob exists.
The concurrency names are compile-time declarations, **not actor state
fields**; external callers cannot write them, and user code cannot mutate
them. Values must be constant positive integers. A bare `serial actor` uses protocol `actor` with a strict
single-message admission policy. Internally this may be represented
as `(1,1)` for a shared scheduler layout, but the first dimension is
not exposed to generic actors.

Protocol actor declarations with `(1,1)` are parser/type-checker
contracts only: **no `define http/tcp/websocket actor` declaration can
spawn as a native network listener yet**. The source runtime fails closed
until a connection-aware ingress broker is implemented.
Default declarative policy for protocol actors is (1,1). A future runtime
may choose a different default only with an explicit source-compatibility
decision; do not silently change this.

A serial actor behaves as a **logical handler-wide critical section across
await**: a suspended handler retains its message admission and prevents a
second handler from beginning. It does not keep an OS thread or a native
execution lock held while waiting. A self-call that waits for a new message
to enter this same actor can deadlock; add static diagnostics or timeout and
avoid implicit recursive mailbox delivery.

## Exact preemption/re-entry boundary

Without serial admission, another handler can enter only after the current
actor's execution turn has **explicitly yielded**, reached a recognized
await/cooperate/channel-suspension boundary, or returned to the scheduler.
No second thread can run actor code simultaneously. A native timer or IO
completion *queues* an actor turn; it does not invoke a guest callback directly.

A language **statement** is not automatically an atomic transaction: it might
contain `await`, nested calls that await, or a compound operation across
multiple actor turns. The compiler must use the lowered continuation/state
machine boundaries, not a heuristic based on source line endings.

Until a concurrency-aware implementation proves otherwise, a normal
**synchronous, non-suspending actor turn segment** is indivisible relative to
*other turns of the same actor*. A CPU scheduling safepoint may pause a carrier
to run other actors, but must not permit an unrelated turn of the **same**
actor to run while the paused segment retains its execution lease.
Preemption/cancellation does not commit partially executed actor state:
cancellation can stop further execution at documented safe points, but is
not rollback.

```oreslang
// Desired semantics for a reentrant (3,4) actor:
const before = self.balance;
const approved = await authorize(amount);
// preemption/re-entry may have modified self.balance here
// Re-read / validate / compare version before mutating:
if approved && self.balance >= amount {
    self.balance -= amount;
}
```

A borrow checker prevents conflicting memory aliases but cannot, by itself,
prove the logical check-then-act invariant when reads and writes span an
await with re-entry. An active `rt borrow mut self.balance` across a yield
must either reserve an exclusive re-entrant-safe region (potentially
deadlocking), or be rejected so that code instead copies snapshots,
reacquires on resumption, and validates versions.

For critical operations that cross awaits, prefer a serial actor, an
explicit transaction/reservation protocol, or versioned optimistic commit.
A future explicit `atomic { ... }` must prohibit suspension, blocking
native IO, nested yield and actor-to-actor RPC while inside its scope. It
must never be implemented as an unsafe implicit lock held across await.

## Scheduler model and requirements for (3,4)

- Admission atomically creates a request lifetime or message lifetime token,
  subject to independent request and per-request message ceilings.
- A message may execute one segment, suspend, and resume later; suspension
  retains its lifetime permit but releases the single native actor turn lease.
- Scheduler uses a bounded continuation queue and chooses an eligible turn
  fairly across requests; it must never admit two actor code turns at once.
- Each continuation records request ID, message ID, generation/incarnation,
  cancellation status and deadline, not a mutable actor-wide `current`.
- Actor fields are shared across *that actor's logical message lifetimes*,
  though never concurrently accessed by two threads; write-before-await/read-
  after-await is logically racy and requires revalidation.
- The compiler must distinguish borrow lifetimes crossing suspension and
  guard accesses to actor state; basic exclusive memory safety alone does not
  imply invariant safety.
- Stream `pipe` and `ProxyActor` should propagate backpressure and
  cancellation to admission permits, including remote reconnect outcome
  uncertainty and message/request correlation. The same 1-native-thread
  invariant applies to a locally scheduled ProxyActor.

## Validation / rollout

The current interpreter has a boolean `sourceReceiveSuspended` in
`ActorRuntime.ActorCell` and one `activeMail` slot. This is a real
structural restriction, not merely a missing field or CLI flag. Before
supporting (3,4), replace these with per-message continuation state and
an admission manager, enforce the concurrency-aware borrow rules, and test
fairness, cancellation, nested await, retries, close, backpressure, and
cross-isolation behavior.

The current parser/type-checker accept protocol (1,1) declarations,
reject protocol (3,4) for execution, reject ordinary actor
`requestConcurrency` outright, and
and preserve actor configuration through AST transformation. The next
execution backend must not silently treat (3,4) as (1,1).
