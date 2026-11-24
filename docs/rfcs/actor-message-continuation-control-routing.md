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
- Ordinary actors may admit another DATA receive while a receive is suspended, within bounded logical admission. Each invocation owns its stack. A `serial` actor retains one live DATA receive across suspension and queues later DATA until retirement. Acquire a fresh physical lease for the same invocation when it resumes, potentially on another worker. Physical admission and critical state permissions remain independently enforced.
- No callback may run user code on the NIO selector thread.
- Late completions must not resurrect retired tasks or freed actor arenas; use generation/lifetime checks.
- Supervisor events retain child identity, causal trace, kind and parent identity.
- Preserve backpressure and FIFO/priority ordering contracts; CONTROL priority must not permanently starve data work.
- Await across borrowed/critical state must be validated by compiler/runtime ownership checks.

## Cross-backend actor schedule

Run the same ordered events in Java and .NET. Use barriers or a scheduler oracle to control suspension, wake, worker retirement and restart.

| Schedule | Required observation |
| --- | --- |
| On an ordinary actor, A awaits a pending completion. Submit B as DATA. Wake A. | B may enter before A finishes, on a distinct logical stack. A resumes its original invocation. |
| Repeat on a `serial` actor. | B stays queued until A finishes its logical turn. A may resume on a different current worker. |
| Release worker W1 and resume on W2. Deliver an old W1 wake or release. | The stale generation cannot enter guest code or clear W2's current lease. |
| Fill DATA and leave an internal dispatch receipt behind a DATA head. Publish a fatal child notification. | Reserved supervisor CONTROL reaches the direct parent independently of both queues, once for this child generation. |
| Pause after a wake is queued, STOP and restart, then release the old worker. | No stale execution in either incarnation and no revival of an old frame. |

Use the ordinary-actor interleaving case in [Java #574](https://github.com/atto-lang/atto-source.java/pull/574) and the `serial` receive cases in [Java #612](https://github.com/atto-lang/atto-source.java/pull/612) and [.NET #153](https://github.com/atto-lang/atto-source.net/pull/153). [.NET #158](https://github.com/atto-lang/atto-source.net/pull/158) tests internal FIFO-head dispatch receipts. The separate supervisor failure lane follows [the reconciliation spec](https://github.com/atto-lang/atto-spec/pull/151).

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


## Source audit findings (2026-11-23, Java main snapshot)

This section supersedes any assumption that the mechanisms above must be built from scratch.

1. **Scheduler affinity already exists.** `src/main/java/dev/oreslang/runtime/OresScheduler.java` documents scheduler-owned resumable `Task` / `Await` / `Resume` and explicitly forbids producer, timer, I/O and JNI completion threads from executing continuations directly. Preserve this contract; do not add a competing continuation event loop.
2. **Future completion plumbing already exists.** `OresFuture.whenCompleteRuntime[Cancellable]` supports runtime waiters; `ActorRuntime.enqueueOnCompletion` registers a completion callback that enqueues onto the target actor. Audit the registration-versus-completion and cancellation races rather than adding a second future implementation.
3. **Actor continuation admission is separate from DATA.** `docs/ACTOR_SUPERVISOR_MAIL_CHANNEL_AUDIT_V1.md` describes bounded private actor DATA (policy-sized), continuation (1,024), and SourceTask cleanup (1,024) channels. Do not bypass these bounds by creating a new unbounded reply queue.
4. **Supervisor CONTROL transport exists.** `ActorSupervisorControlLane` implements bounded per-child reservations, generation fencing, single-flight polling, ACK/retry, and root transfer. Earlier blanket statements that no CONTROL lane exists are obsolete. End-to-end compiler-lowered supervisor handling and fair/lossless integration still require proof.
5. **A concrete concurrency hardening candidate:** `ActorSupervisorControlLane.transferPublishedTo(root)` is synchronized on the source and calls synchronized `root.publish`. Opposing concurrent transfers between two lanes can invert lock order and deadlock. Before enabling general lane-to-lane transfers, either enforce a strict acyclic root-only transfer topology with validation or refactor to ordered locking/transactional transfer. Add a deterministic opposing-transfer regression test. **Not fixed by this documentation-only change.**
6. **Transfer failure atomicity:** root publication may succeed for some slots and fail on a later slot; document whether partial transfer is an accepted, recoverable state and test accounting invariants (source pending + in-flight + root pending + in-flight) across failure injection.
7. **CONTROL capacity must be reserved before child publication**, and DATA backpressure must never consume that capacity. A successful `sendAsync` Future signals *admission*, not handler execution; avoid treating it as a request/reply result.

### Hardening gates to add to the implementation PR

- Deterministic concurrent transfer/ACK/retry/retire tests, including opposite-direction transfer and partial failure injection.
- Completion before/after registration, cancellation revocation ordering, and no user callbacks on completion producer threads.
- Verify no DATA or supervisor mailbox capacity is borrowed for continuation replies, and no unbounded waiter allocations.
- Exact-head JDK25 and GraalVM Native Image execution; failed/zero-step Actions do not count as tests.
- Reconcile the existing Java runtime and supervisor PRs before selecting an implementation. Do not duplicate the scheduler, future, mailbox or CONTROL lane.

**Audit status:** source inspection completed for the named files; no runtime patch or executable tests are claimed in this RFC revision.
