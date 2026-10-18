# Supervisor recovery contexts and nested actor families (v1 contract)

**Status (2026-10-11): shared design target, NOT a claim of executable guest `throw/raise/panic/recover` or native source-level supervision.** The JVM branch adds a first runtime implementation of inherited *managed lifecycle ownership* for nested spawned actors. The LLVM branch has a host-only ActorGroup and must still lower source spawn, await and supervisor CONTROL callbacks. Keep these gates separate.

## v1: flat supervision for recursive spawn

For an actor family A -> B -> C, all descendants inherit the same supervisor S and the same **lifecycle-owning ActorGroup G** from the spawning actor by default. Spawning an actor never implicitly creates a supervisor or a new group. Store `spawned_by` as lineage metadata, not a supervisory authority. Keep actors' own mailboxes, actor states and single-flight user-execution lanes separate. Underlying runtime workers/carriers may be shared, subject to private/shared/untrusted pool and isolation policies.

The root must first be admitted to S/G. The source-level default root-supervisor bootstrap is a future integration gate; don't assume that an ordinary host-created/unmanaged actor already belongs to an implicit cooperation ActorGroup. In the present JVM runtime a managed root passes *structured lifecycle ownership* to recursively spawned children, while the event-bus `members` and its unforgeable join capability remain independent and never auto-inherited.

- A's spawn of B, and B's spawn of C, do not make A/B supervisors.
- C's terminal panic is reported as a typed CONTROL failure to S, not synchronously to B or A.
- A/B/C have distinct invocation contexts and defer/recover stacks. C's failure never directly unwinds B's or A's stack.
- Existing **structured spawn lineage cancellation** remains: stopping/cancelling A can stop descendants B/C, while group close stops the entire managed group. Supervisory failure policy is a separate decision.
- A group owns managed worker *lifecycle authority*, not automatic unbounded access to communication, actor-owned state, or cross-actor cancellation. Source user code communicates with a typed `ref.send(message)`; no direct foreign actor method invocations.
- `spawn T()` returns `ActorRef<T>` promptly; `await spawn T()` is a readiness barrier and also returns the ActorRef. `await ref.get_ready()` and `await ref.get_done()` are the explicit observer method forms. DONE is after finalization, not just after a stop request.
- Group close must fence recursive admission before publishing a child, reject stale/cross-group ownership, and never complete DONE until physical finalization.

## Two independent trees

```text
supervision ownership:      S  ── owns ── G(A, B, C)
spawn ancestry (diagnostic): A  ──spawns──> B  ──spawns──> C
logical execution stacks:   A.invoke#1 | B.invoke#7 | C.invoke#4  (separate)
physical dispatcher:        shared runtime scheduler / bounded pools (actor-kind scoped)
```

## Defer/recover: same invocation, including awaited continuations

Each *admitted actor message invocation* and each *admitted supervisor callback* owns one logical ExecutionContext. Its stack of frame-scoped LIFO `defer` callbacks and its current recoverable failure **must survive `await` / carrier migration**, not reside permanently in a Java ThreadLocal/native thread_local. Carrier-local variables can temporarily point to the context during the admitted turn, but never own recovery semantics.

- Nested calls and an `await` chain preserve the same logical unwind context; a suspension does not create an independent recover scope.
- `recover()` only succeeds when directly invoked by an executing deferred function during an active recoverable unwind, consuming the current recoverable failure once. Outside that situation it returns `nil`. An ordinary helper called by a defer cannot call `recover()` on the defer's behalf.
- `throw` can be intercepted by admitted typed catch/trap or an eligible `defer/recover`. `raise` bypasses ordinary catch/trap (explicit escalation), but a directly executing eligible deferred `recover` can consume it; if neither consumes it, the respective local unhandled hook runs.
- True `panic`, cancellation, process exit and host-fatal failures cannot be consumed by `recover`. Cleanup defers still run under bounded cleanup/failure-priority rules. Child terminal panic reaches its supervisor only as a typed CONTROL event.
- After a successful `recover`, execution does not jump back into the throwing function or re-execute the failed `await`; normal function exit/unwinding completes.
- A detached task, another actor message, or child actor has its own execution context. A future can explicitly *deliver a failure value* to an awaiter and the awaiter can throw a **new local** exception; its recover scope does not rewind the remote execution.
- Supervisor `on_panic(childFailure)` is a fresh **supervisor-owned invocation** scheduled on S's exclusive logical lane. It can recover failures of its own nested awaits, but not the child's original terminal panic. If the supervisor panics, cleanup runs and a typed CONTROL event goes to S's parent; no inline parent callback from a failed child carrier.
- Supervisor CONTROL is priority/reserved, but cannot interrupt existing supervisor user code or execute reentrantly. A suspended supervisor callback may yield the physical carrier and later resume under single-flight admission.

## v1 nested spawn example (illustrative; not a claim of parsed guest syntax)

```ores
define actor A as
    receive(Message m) {
        const b = await spawn B();
        b.send(infer struct { action: "start" });
    }
end

define actor B as
    receive(Message m) {
        const c = spawn C();
        await c.get_ready();
        c.send(infer struct { action: "work" });
    }
end
```

Both B and C inherit A's managed group G. No implicit new supervisor or group; source message sending remains the only cross-actor invocation.

## Java runtime hardening checkpoint (2026-10-17)

The Java runtime now has an additional **host/supervisor observability plane**
that is deliberately separate from actor mail and from the future source-level
`on_panic/on_raise/on_throw` CONTROL callback contract:

- every physically finalized actor increments one of three aggregate counters:
  NORMAL, CANCELLED, or FAILED;
- a bounded immutable `ActorTerminationEvent` journal records actor id,
  generation, kind, parent lineage, accepted/processed user-message counts,
  queued user/control work abandoned at finalization, and the terminal
  exception *type name*;
- the journal is diagnostic only. Lifecycle correctness never waits for it.
  Java uses a pre-sized bounded deque guarded by an explicit diagnostic lock;
  finalization publishes only via `tryLock()`, so a full **or contended**
  diagnostic path increments `supervisionEventsDropped` and actor teardown
  continues without parking behind telemetry. Trusted consumers serialize
  dequeue + sequence assignment so concurrent drains cannot invert the
  externally visible FIFO sequence;
- terminal publication intentionally does **not** call
  `Throwable.getMessage()`, `Throwable.toString()`, guest formatting, or
  arbitrary observer hooks while finalization owns actor state. Detailed
  failure inspection stays on `ActorRef.failure()`;
- host/supervisor-only `ActorSnapshot` exposes immutable scheduling/mailbox
  counters without guest state: active turn/carrier flags, accepted/processed
  sequences, queued user/continuation counts, child/pending-operation counts,
  suspension/fence state, and bounded memory accounting;
- `ActorRuntimeStats` adds accepted-message, mailbox-full,
  continuation-admission, termination-classification, dropped-diagnostic and
  watchdog counters. These are telemetry, never scheduling authority.

This journal **does not implement the normative source supervisor event path**.
A child panic is still required eventually to become one non-droppable typed
CONTROL event admitted to its supervisor's exclusive logical lane. The
diagnostic journal may drop under observer backpressure and therefore MUST NOT
drive restart/escalation policy.

### Continuation-vs-user-mail fairness

Internal continuations retain priority, and a suspended source receive still
owns actor state and therefore permits only its continuations. Outside a
suspended receive, however, a self-replenishing continuation stream can no
longer starve user mail forever: after at most **32 consecutive internal
continuations**, a waiting user mailbox envelope is selected first. The burst
counter survives carrier migration/batch boundaries and resets when user mail
runs or actor teardown drains the lane.

This complements the existing actor-level `throughput` quantum: the runtime
now bounds both (a) how much one hot actor can execute before returning to the
dispatcher and (b) how long internal control continuations can postpone an
already-admitted ordinary message. Neither bound permits user mail to run while
`sourceReceiveSuspended` is true.

Checked-in adversarial tests cover mailbox-pressure snapshots, mailbox-full
telemetry, normal/failed/cancelled terminal classification, parent/child
terminal ordering, hostile Throwable formatting, a completely full diagnostic
journal, exhausted diagnostic sequencing, child failure visibility while the
parent user turn is blocked, active-failure vs queued-tail accounting, and a
200-step self-replenishing continuation chain with dispatcher throughput=1.

## Normative OTP restart policy requirements

The source-level OTP supervisor remains an integration gate, but its restart
semantics are constrained now so implementation cannot silently inherit common
actor-system failure modes:

- **No unbounded crash loops.** Every supervisor has an explicit
  `maxRestarts` within a monotonic `restartWindow`. Exhaustion escalates one
  typed CONTROL failure to the parent; it never keeps respawning indefinitely.
- **Backoff is bounded and deterministic by default.** Repeated failures use an
  explicit min/max exponential backoff (or an application-provided policy).
  Random jitter is opt-in and must come from an injected/replayable source so
  tests and deterministic replay are not hostage to ambient randomness.
- **Restart means a new incarnation.** A replacement actor gets a new
  generation/incarnation identity. The old `ActorRef` remains terminal
  forever and cannot silently retarget to the replacement. Stale messages and
  stale CONTROL events therefore fail closed instead of reaching a new child.
- **No implicit failed-message replay.** A message that began execution before
  a crash is not automatically re-enqueued. At-most-once volatile mailbox
  semantics stay explicit; durable/replayable processing requires an explicit
  persistence/idempotency protocol above core actors.
- **Strategies are ordered.** `one_for_one`, `rest_for_one`, and
  `one_for_all` operate over a stable declared child order. Teardown proceeds
  in reverse dependency order; replacement startup proceeds in declaration
  order and respects each child's readiness barrier before dependent children
  are considered healthy.
- **Resource failure escalates; it does not spin.** Mailbox, actor-slot,
  carrier, memory, restart-timer, or CONTROL admission exhaustion during a
  restart is a supervisor failure decision. Implementations must not busy-loop,
  allocate an unbounded side queue, or retry synchronously on an actor carrier.
- **Restart policy cannot weaken downward.** Nested supervisors may choose
  stricter limits but cannot exceed parent/process ceilings for restart
  intensity, backoff resources, mailbox/memory, or isolation capabilities.
- **CONTROL is authoritative and non-droppable.** Restart/escalation decisions
  are driven from the reserved typed CONTROL path plus retained per-ref terminal
  state, never from the droppable diagnostic journal.
- **Crash-loop state is observable.** Supervisors expose current restart-window
  occupancy, last restart cause, current backoff, child generation and
  escalation count without exposing mutable child state.

These requirements deliberately separate **fault recovery** from **message
delivery guarantees**. Supervision can recreate computation; it must not
pretend that an in-memory mailbox became a durable transaction log.

## Required test matrix (both backends; fail closed until executable)

1. A->B->C inherits exactly one managed lifecycle group, independently of group event-bus join membership.
2. Concurrent group close vs child spawn cannot publish an unowned child or strand an ownership reference.
3. Group close stops all A/B/C; parent subtree cancellation still cascades. Each get_done waits for physical finalization.
4. A/B/C user-turn lanes stay single-flight, though actor kinds may share carrier pools.
5. Local `defer/recover` unwinds through multiple `await` resumptions, including carrier changes, once and LIFO.
6. S receives one immutable CONTROL failure for C panic; neither A nor B can intercept it using recover.
7. S's on_panic owns an independent invocation context; its own recoverable failure may be handled locally; a true supervisor panic escalates to grandaddy.
8. Cancellation and terminal panic run cleanup but never become recoverable; ensure duplicate notification and stale identity fences.
9. Restart storms are bounded: hit the restart-intensity ceiling, verify deterministic backoff, one escalation, no busy-loop, and no replacement after escalation.
10. Restart creates a new generation; stale refs/messages/CONTROL cannot target the replacement, and a crashing in-flight message is never implicitly replayed.
11. `one_for_one`, `rest_for_one`, and `one_for_all` preserve deterministic dependency order and readiness barriers under concurrent child failure.
12. Verify against the **exact source Git tree** in CI. When out of org Actions budget, mirror exact tree/blobs to a funded test org, using no private cross-org checkout secrets.

## Integration boundary

The present JVM `ActorGroup` is a **cooperation domain plus explicitly managed-worker registry**, not itself a supervisor process or dispatcher. The new inherited-ownership behavior concerns that managed-worker registry only; a real supervisor scheduler/CONTROL callback integration remains a release gate. The LLVM `NativeActorGroup` is a host-side lifecycle authority; it does not yet execute guest spawn expressions or guest recovery frames. Do not conflate tested host primitives or docs with compiler-level parity.
