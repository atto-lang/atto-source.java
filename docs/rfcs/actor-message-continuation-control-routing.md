# RFC: Actor message, continuation, and supervisor CONTROL routing

Status: **Draft proposal — not an assertion of current implementation**

## Motivation

Atto must distinguish (1) ordinary actor messages, (2) completion of an awaited operation, (3) external I/O readiness, and (4) supervisor lifecycle/CONTROL notifications. None should require routing every message through a global main loop or supervisor.

## Proposed ownership

- **Actor mailbox**: admits ordinary messages to the destination actor, with bounded capacity, linearizable closure/admission and explicit backpressure.
- **OresScheduler**: owns runnable task/actor queues, worker dispatch, and continuation wakeup scheduling. A runnable actor must not be concurrently entered in ways that violate sealed-state isolation.
- **Continuation/completion subsystem**: stores suspended await frames and completion state; registers waiters and wakes them through scheduler-owned wakers. This is a scheduler subsystem, not a separate polling reactor.
- **NIO reactor**: detects socket readiness and progresses nonblocking I/O; it publishes completion or delivers a message, but never executes arbitrary actor user code on the polling thread.
- **Supervisor CONTROL mailbox**: receives child failure, panic, exit, restart, and escalation events on a priority lifecycle path. Normal application traffic bypasses supervisors.
- **Root/main control loop**: runtime lifecycle and escalated supervision only, not an application-message hub.

## Routing contracts

### Ordinary message

```text
Actor A -> Actor B mailbox -> schedule B if newly runnable -> B receive handler
```

Mailbox admission success is not equivalent to handler completion. Define explicit results for closed/full mailbox and admission cancellation. Coalesce duplicate wakes; preserve mailbox ordering contract.

### Awaited actor request/reply

```text
A request + correlation/completion handle -> B mailbox
A registers waiter and suspends
B handles request -> completes handle (result/error)
completion -> scheduler wake(A) -> A resumes after await
```

Direct completion-handle delivery is allowed without inserting the reply into A's ordinary mailbox, **provided** task ownership, actor state isolation and cancellation semantics hold. Do not confuse request acceptance with request completion. Define behavior for callee termination, requester cancellation, timeout and late reply.

### I/O await

```text
nio-reactor readiness -> nonblocking I/O progress -> completion handle
-> scheduler wake(waiter) -> continuation resumes
```

Readiness is not necessarily completion; partial reads/writes and re-registration are required. An already-ready completion may return inline without suspension.

### Supervisor CONTROL

```text
child terminal failure/panic/exit -> parent supervisor priority CONTROL
-> scheduler wakes supervisor -> restart/stop/escalate
```

Supervisor traffic must not compete for ordinary mailbox capacity. Specify bounded CONTROL delivery, overflow/fail-safe behavior, priority fairness and escalation when parent is unavailable. A child's panic must not be intercepted by its own application handler.

## State-machine and safety requirements

- Await registration and completion must be race-safe: no lost wakeups when completion races registration.
- Only one terminal completion outcome wins (success/error/cancel/timeout), with idempotent wake behavior.
- A task must not be executing on two workers concurrently; duplicate wakes coalesce.
- An actor must obey single-flight or explicitly permitted concurrency/critical-read/critical-write semantics.
- No callback may run user code on the NIO selector thread.
- Late completions must not resurrect retired tasks or freed actor arenas; use generation/lifetime checks.
- Supervisor events retain child identity, causal trace, kind and parent identity.
- Preserve backpressure and FIFO/priority ordering contracts; CONTROL priority must not permanently starve data work.
- Await across borrowed/critical state must be validated by compiler/runtime ownership checks.

## Required executable tests before implementation is declared complete

1. Actor A -> B ordinary message, B scheduled exactly once under concurrent sends.
2. A awaits B request; successful response resumes A without supervisor/root loop involvement.
3. Callee failure while requester awaits: waiter receives defined error; supervisor receives separate CONTROL event.
4. Completion before waiter registration, during registration and after suspension: no lost wakeups.
5. Duplicate completion, timeout/cancel race, and late reply after actor destruction.
6. Mailbox full/closed races and admission vs processing acknowledgement.
7. I/O partial-read readiness then eventual completion, with no actor callback on selector thread.
8. Supervisor CONTROL flood vs data queue: bounded capacity, escalation and fairness.
9. Actor single-flight/sealed state and borrow safety across await.
10. Exact-head JVM and GraalVM Native Image AOT tests, with meaningful pass/fail reporting.

## Integration plan

1. Audit existing OresScheduler, actor mailbox, SourceTask/continuation, NIO selector and supervisor implementations; document actual entrypoints and ownership.
2. Identify overlapping open PRs and reuse their canonical implementations instead of introducing parallel schedulers/reactors.
3. Add a minimal shared completion/waker contract, only if the existing contract is insufficient.
4. Wire message, request/reply and CONTROL paths; add deterministic race tests and tracing.
5. Verify JVM and native-image behavior and cross-backend semantic conformance.

## Non-goals

- Introducing a universal `continuations-reactor` event loop.
- Routing every actor message through supervisors or the root loop.
- Claiming this RFC's contracts are implemented or tested.

Related existing issues to reconcile: #74, #110, #142, #143, #153, #202, #275, #284, #324.
