# Actor preemption and resumable safepoints

OresVM does **not** schedule actors at source-line boundaries. A source line is
debug metadata and may lower to many runtime operations.

## Two different VM concepts

### 1. Checkpoint

`OresContext.schedulerSafepoint()` / `ActorRuntime.schedulerSafepoint()` is a
poll for runtime state:

- structured actor cancellation,
- runtime shutdown,
- untrusted fuel,
- untrusted wall-time,
- untrusted CPU-time.

A checkpoint does not capture a Java stack and does not release the actor's
logical turn. In particular, `Thread.yield()` is not an actor-preemption
primitive and must not be used to model one.

### 2. Resumable scheduling boundary

A compiler-lowered `OresScheduler.Task<T>` owns a heap-resident continuation.
When it returns `OresScheduler.cooperate()`, the current scheduler dispatch
fully unwinds before the same logical task is queued again.

The task object, not a source line, owns the resume state: its program-counter
state, captured environments/locals, and any other compiler-generated control
state. Completed Ores operations are not replayed.

The scheduler keeps a per-task execution lease. A cooperate resume is published
only after the previous carrier/Truffle turn has exited, so two physical
carriers cannot execute the same logical continuation concurrently.

## Actor single-writer invariant

An actor may occupy at most one active actor execution boundary at a time.
`ActorCell.beginTurn()` fails closed if that invariant is violated.

Suspension or carrier migration therefore must never mean:

```text
actor turn A -> suspend
actor turn B -> mutate the same actor
actor turn A -> resume
```

A future compiler pass that preempts a persistent actor turn must retain the
logical turn lease across the suspension, or otherwise prove an equivalent
non-reentrant actor protocol.

### Actor-local continuation handoff

Actor-backed source schedulers use a VM-owned continuation lane distinct from
the ordinary user-message FIFO. When the currently executing actor envelope
publishes a resumable source continuation, that continuation has priority over
later user messages and the current actor batch must leave its
`TurnExecutor`/carrier boundary before the continuation is admitted.

This prevents two otherwise subtle violations:

1. `rt cooperate` cannot be implemented as "append to the mailbox and keep
   draining the same batch", because that would retain the physical carrier.
2. A later user message cannot overtake an immediately-ready preemption
   continuation and mutate the actor before the preempted logical task resumes.

A genuinely pending `await` is different: while no continuation is runnable,
the runtime may apply the language's eventual actor-reentrancy policy. Automatic
preemption itself is stricter: once its continuation is ready, it resumes ahead
of later mailbox mutations.

### CPU preemption never implies same-actor callback admission

Normal synchronous work has no implicit logical suspension. Automatic
reduction-budget cooperation may save its continuation and free the **physical
carrier** for other actors, but MUST NOT release logical admission so that an
unrelated callback of the same actor can enter before the continuation
finishes. This includes checkpoints on loop back-edges and assignment/call
boundaries; neither source lines nor individual assignments are implicitly
atomic or reentrant.

Deliberately synchronous callbacks (such as `map`, `filter` and synchronous
event emitters) may run inline as part of the same continuation. Deferred
callbacks require explicit scheduling through an authorized runtime lane.
Explicit `await`, blocking `select`, blocking channel I/O, and declared
`suspends` calls are distinct *logical* suspension boundaries. Even there,
actor reentrancy is controlled by the actor's configured admission contract
and cannot bypass ownership, state loans, cancellation or single-flight
physical execution.

An infinite synchronous loop without explicit suspension must still expose
bounded CPU-fairness and cancellation safepoints when continuation lowering
permits it: keeping logical ownership does **not** mean monopolizing a carrier.

## Safe automatic preemption

Automatic quantum/budget preemption is valid only at a point whose execution
state is already continuation-lowered. The compiler may insert such points at
statement boundaries, loop back-edges, calls, allocation/runtime boundaries,
channel operations, and explicit `rt cooperate`, but only when resumption has
a heap-owned continuation.

Ordinary synchronous evaluator frames are not transparently suspendable merely
because they call `schedulerSafepoint()`. Until they are continuation-lowered,
their safepoints remain cancellation/quota checkpoints.

Untrusted actor fuel/wall/CPU accounting must also survive any future
cooperative handoff before automatic preemption is enabled for untrusted code;
a handoff must never reset a logical task's quota.

## Automatic loop cooperation in heap-owned source tasks

The source interpreter now lowers `while`, `for`, `for-of`,
destructuring `for-of`, and `loop` back-edges **inside an existing
`SourceTask`** into an iterative callback trampoline. No `await` or explicit
`rt cooperate` is needed in such a loop. Each heap-owned task keeps its own
64-bit saturating reduction counter and a dispatch-local monotonic clock.
A trusted source task checks the watchdog, runtime cancellation and its 2 ms
soft quantum every **64** back-edges until reaching the **512**-backedge
reduction cap. Thereafter, it checks at **every** subsequent committed
back-edge so release of a live no-yield guard cannot be followed by another
64-iteration scheduling delay. An UNTRUSTED task charges fuel at **every**
back-edge and still cannot automatically cooperate until logical quota
continuity is proven. The continuation preserves loop indices, iterator
cursors, locals, and pending callbacks. A new physical dispatch can start
only after the previous guest turn fully unwinds.

This is a **soft** scheduling quantum, not a 2 ms deadline guarantee. Long
single iteration bodies, synchronous helper calls, host/FFI calls, JIT pauses,
GC pauses, and OS scheduling delays may exceed it. The bounded back-edge count
also prevents deep recursive Java interpreter stacks. Nested loops in
`if`/`match`/`switch`/`try`/block contexts are lowered when the enclosing
source task is already continuation-backed. The runtime does not cooperate
while an enclosing lexical environment holds a live `MutexGuard`; these
no-yield regions must remain bounded or the carrier can still starve. Successful
source loop cooperation decisions are counted in
`process.descriptor.source_loop_cooperates`.

**Synchronous actor receive special case:** even if a source-defined
`receive(ActorMail<T>)` method contains no `await` or explicit `rt cooperate`,
the actor mailbox dispatcher starts it in a heap-owned `SourceTask` when a
loop is present. This preserves the external `void` receive protocol, gates
later user mailbox turns until the source Future completes, and permits CPU
loop cooperation inside the continuation. The actor's `on_start` still runs
synchronously as part of the ready/initialization boundary.

**Not automatically suspendable yet:** normal synchronous function calls,
startup callbacks, host Java/FFI executions, and other actor `run`/method
bodies not transformed into a source task. Their `schedulerSafepoint()`
remains a cancellation/quota checkpoint and cannot capture the host Java stack.
Do not make arbitrary synchronous functions implicitly async merely because
they contain a loop; that changes callable types and actor protocol semantics.
A future compiler transformation must produce explicit continuations while
preserving the externally synchronous contract where appropriate.

**UNTRUSTED gate:** automatic loop cooperation is deliberately disabled
inside UNTRUSTED actor turns while their logical task quota continuity is
unproven (issue #402 / draft PR #461). They still run the periodic quota
and cancellation checks, without resetting fuel, CPU, or wall budgets through
a scheduler handoff. Before enabling cooperation for UNTRUSTED, validate
all source and callback continuation paths and cross-carrier CPU charges.

**Hard real-time:** no worst-case execution-time or hard deadline guarantee is
provided on the managed JVM/GraalVM runtime. A hard deadline would require
bounded allocation/GC, JIT/AOT choices, bounded critical sections, scheduler
priority/reservation admission, interrupt/native execution boundaries, a
suitable OS, and WCET plus end-to-end latency proof. Non-cooperating trusted
code cannot be safely suspended by `Thread.yield()`, Java interruption, or
a timer; forceful revocation requires a dedicated isolated execution boundary.

## Independent 4 ms turn watchdog

`ActorTurnWatchdog` is a daemon monitor separate from ordinary actor and
scheduler carriers. Its 2 ms polling cadence detects a physical dispatch turn
whose monotonic wall time has reached **4 ms** without releasing the carrier.
The threshold is an observation policy, **not a hard timing guarantee**:
OS scheduling, GC, JIT and host execution may delay the monitor itself.

For each dispatch, the runtime registers an identity-unique lease and retires
it when the carrier exits. The monitor synchronizes retirement and inspection
to avoid reporting a previous actor turn against a reused carrier. Its only
authority is to set an advisory preemption bit and publish nonblocking
accounting. It never invokes guest callbacks, interrupts a carrier, performs
signal-based stack rewrites, or forces a VM suspension.

- `OresScheduler.preemptionRequested()` observes the logical source task's
  current dispatch; `OresScheduler.watchdogOverruns()` counts flagged turns.
- `ActorRuntime.watchdogPreemptionRequested()` observes the current actor
  dispatch. `pollWatchdogNotice()` supplies a bounded host-only notice
  containing actor id/kind and wall/checkpoint ages; `watchdogOverruns()`
  counts all detections, including notices dropped by a full notice queue.
  `watchdogNoticesDropped()` counts bounded-queue drops. These methods and
  `watchdogObserverFailures()` require host/supervisor authority: ordinary
  actors and Mailman callbacks cannot drain another actor's diagnostics.
  When an internal observer fails, the watchdog counts the failure, skips
  only that observer and continues inspecting peers and future dispatches.
- `OresContext.watchdogPreemptionRequested()` combines the two sources.
  The source-loop trampoline honors that advisory bit **only** where it
  already owns a continuation and no lexical `MutexGuard` is held.
- Safepoints record checkpoint progress, but checkpoint polling alone **does
  not** count as releasing the physical carrier.

An idle-carrier heartbeat is optional diagnostic optimization, not a reliable
watchdog: no carrier is idle when all carriers are trapped in greedy loops.
The monitor is independent of the guest dispatch pools.

### UNTRUSTED logical source-task quota prototype (issue #402)

`ActorRuntime.startActorTask` now carries a heap-owned
`UntrustedLogicalQuota` through every cooperative or awaited dispatch of
an UNTRUSTED task. It inherits remaining physical-turn fuel, original wall
start and CPU debt on first admission. Nested tasks inherit the currently
active quota instead of minting another budget. CPU measurements are added
**per carrier segment**, never subtracted across unrelated thread clocks.
`schedulerSafepoint` charges the retained logical quota while executing
one of these tasks, while synchronous actor turns continue their existing
physical-batch quota checks. Task entry and step completion independently
check the persistent wall and CPU budgets, even without a loop backedge.

An await cannot restart the deadline; a cooperate cannot refund fuel.
One live top-level source-task quota cohort is permitted per UNTRUSTED actor.
Additional top-level task admission fails closed while it is active; nested
source tasks must share the same actor-bound quota. Each task releases
exactly one cohort reference only after scheduler-confirmed **carrier
quiescence**, not merely when its Future settles. Last-quiescent-task
retirement clears the cohort. Outstanding nested source tasks are
bounded by the runtime's internal continuation-slot cap (currently 1,024);
exceeding it rejects admission rather than overflowing the cohort counter.
In particular `Future.cancel(false)` can
settle while a guest turn is still physically executing; that does not
grant permission to free its quota or start a second root task.
A synchronous actor mailbox turn cannot consume a separate physical-turn
quota at a safepoint while the cohort is live: it must run inside the
authorized continuation or wait for quota retirement.

The quota is bound to the owning runtime and actor incarnation. A
different actor/runtime cannot import it through carrier ThreadLocal state;
a parallel segment entry is rejected atomically. This deliberately
restricts parallel untrusted source-task spawning until aggregate
per-actor/per-isolate accounting can be proven. The budget is bound to
the source task's scheduled execution and does not remain installed on
reusable carrier thread-local storage after its resume exits.

Scheduler shutdown and admission are linearized at the open-state check
and task registration, without holding the admission lock across the
executor's enqueue operation. A blocked enqueue cannot prevent close from
cancelling that registered task. Task references are retained through
terminal Future settlement until the physical guest dispatch is quiescent;
a stale duplicate dispatch cannot clear the legitimate dispatch's lease.

Close uses a two-phase snapshot: it first revokes every task and detaches all
suspended wait registrations, then publishes Future cancellation. A synchronous
completion subscriber blocking on the first cancelled Future must not prevent
other tasks from losing their runtime authority. Terminal callbacks run only
after guest task quiescence, and quiescent-task accounting retires before any
external terminal subscriber executes. Callback-triggered close from the
scheduler's own physical carrier also must not wait for that same executor
thread to terminate (even though the logical task binding is already cleared).
These guarantees are checked by deterministic callback/cancel/close races.

### Independent logical wall deadlines

Each UNTRUSTED source-task descendant inherits the **remaining** cohort wall
budget. A host-owned daemon timer runs independently of scheduler carriers
and revokes the task when the deadline expires, even when its awaited Future
never settles. Expiration detaches the source-task waiter and, if no guest
carrier is executing, retires its quota slot without another actor turn.
An active guest turn may have its completion suppressed immediately but
**must exit its physical carrier before its memory/quota can be released**.

Timed UNTRUSTED source tasks acquire both a **32-per-actor admission**
and one of **256 process-wide timed-admission reservations** before admission.
Admission is fail-closed when either limit is full, preventing a single actor
from monopolizing all process-wide notifier capacity. A rejected task does
not receive a new quota or enter the scheduler. Actor-local reservations
are held until **both** physical guest/quota retirement and the winning
Future's synchronous subscriber notification drain finish. The global
reservation follows the same dual-retirement fence. Visible cancellation
or Future settlement alone cannot recycle either reservation while a guest
carrier or host callback remains active. Cancellation hooks revoke internal
task authority before notifying even blocked synchronous subscribers;
late subscribers registered during revocation are queued for the subsequent
notification pass rather than running early or blocking the hook.

Deadline task revocation runs on dedicated host timer workers; potentially
blocking actor retirement and synchronous Future notifications run on a
separate host virtual finalizer, bounded by those reservations. One actor's
blocked terminal subscriber or lifecycle lock cannot consume a shared timer
worker. Timer registrations are cancelled on task retirement and
cancelled/finalized tasks cannot receive a second deadline result.
A late nested child cannot mint a fresh wall budget.

This is cooperative/structured cancellation, **not** forcible termination of
unsafepointed FFI, native host calls or hung Java guest threads. An external
isolate-level revoker is still required for hard wall-time enforcement.

**Not an authorization to enable UNTRUSTED automatic loop cooperation.**
Other continuation/cancellation/cleanup paths, nested source scopes, early
termination and isolate-level hard revocation still need full cross-backend
conformance and whole-repository runtime stress. This incremental accounting
fix is not a hard CPU or wall-time enforcement guarantee when host FFI
blocks without safepoints. CPU samples may be unavailable on a JVM, so
fuel/wall fallback and host sandbox limits remain mandatory.

**Current limits:** A synchronous Java/FFI call or non-continuation-backed
method cannot be transparently preempted. UNTRUSTED source-loop cooperation
remains disabled until fuel, CPU, and wall budgets are bound to the persistent
logical task across await/resume and carrier migration. The watchdog reports
and requests cooperation, but does not itself revoke an untrusted execution
domain. Supervisors may poll notices and apply an explicit structured or
isolate-backed cancellation policy; only a proven isolated revoker may force
termination.

## Bounded scheduler state-space and adversarial admission gates

The Java regression `OresSchedulerStateModelTest` exhaustively enumerates
reachable states for **two source tasks sharing one actor**, with up to
**two carrier turns per task**, including producer completion, await/cooperate,
timeouts, cancellation, scheduler close, notification drain and retirement.
The model rejects overlapping actor carriers, terminal-state resource reuse
before quiescence and notification completion, and executing already-retired
guest state. Negative-model mutations verify that its assertions actually
detect illegal states.

The live `UntrustedLogicalQuotaTest` actor single-writer regression drives
six nested source continuations through repeated `cooperate` handoffs,
interleaves eight ordinary actor messages, and verifies the observed
maximum number of simultaneously executing guest turns remains one.
This exercises the actual actor mailbox and source dispatcher (including
handoff between physical carriers), rather than only the model's guards.

The model is an **independent, bounded reference specification**. Passing
its exploration does not prove that production CAS operations linearize
according to those transitions, and does not establish a hard interrupt
guarantee for a running FFI call. A production actor-instrumented refinement
proof and JVM/LLVM cross-backend evidence remain separate release gates.

`OresSchedulerQuiescenceTest` additionally exhausts the **256 process-wide
host deadline admissions across four distinct scheduler instances**. It
verifies fail-closed overload for each scheduler, shutdown retirement of
queued and waiting tasks, and detachment of producer Future waiters. This
is separate from the **32-per-UNTRUSTED-actor** cap and existing blocked
host callback/cancellation regressions.

Run the bounded checker and runtime-focused regressions together:

```sh
mvn -Dtest=OresSchedulerStateModelTest,OresSchedulerQuiescenceTest,UntrustedLogicalQuotaTest test
```

## Required invariants

1. **No source-line semantics.** Preemption occurs only at VM-defined resumable
   boundaries.
2. **No replay.** Resumption continues from the saved continuation; completed
   source operations are not repeated.
3. **No overlap.** A logical scheduler task has one execution lease and one
   actor has one active execution boundary.
4. **Carrier release first.** A cooperate resume cannot be admitted until the
   previous guest/Truffle and actor `TurnExecutor` boundaries have fully
   unwound.
5. **Actor continuation priority.** An immediately-ready preemption
   continuation cannot be overtaken by a later user mailbox message.
6. **Carrier identity is irrelevant.** A resumed task may run on the same or a
   different physical carrier.
7. **Fail closed.** Code without a resumable continuation may be checked or
   cancelled at a safepoint, but it must not pretend to have been suspended.


## Control-lane capacity and fail-stop admission

An actor reserves **1,024 continuation slots independently** of
`IsolatePolicy.maxMailboxMessages()`. The counters for user envelopes,
source continuations, and their sum must satisfy:

```text
0 <= queuedUserMessages <= maxMailboxMessages
0 <= queuedContinuations <= 1024
queuedMessages == queuedUserMessages + queuedContinuations
```

All changes are made atomically under the accounting lock. An already-full
control lane cannot borrow an idle user-mailbox slot; conversely, 1,024
queued continuations must not consume even one user-mailbox slot. A failed
internal continuation enqueue fail-stops the actor. Dispatch, stop, cancel,
and finalization must each release every acquired reservation exactly once.

Actor execution-lease admission and retirement are also fallible
invariants. Whether `beginTurn()`, `endTurn()`, or finalization throws,
the carrier must clear `CURRENT_ACTOR_EXECUTION`, `currentActor`, and
`ACTOR_CARRIER` in nested `finally` scopes. A rejected carrier must
not retain ambient actor identity when reused.

## Cancellation is not physical carrier revocation

Structured stop immediately rejects new mailbox admissions and drains
queued work. It does **not** instantaneously release a carrier that is
already running guest code. Final actor termination must be delayed until
that carrier has left the `TurnExecutor`/Truffle boundary and the
single-writer lease is retired.

## UNTRUSTED continuation quota dependency

UNTRUSTED source tasks now retain logical fuel, wall-time and CPU debt
across `await` and carrier migration, including an independent deadline
for never-settling Futures. Physical actor batches remain separately metered.
This does not imply that force-killing an uncooperative native/Java carrier
is safe. `rt cooperate` remains rejected for UNTRUSTED actors until all
source-lowering paths, cancellation races, bounded host-resource admission,
isolate revocation and cross-backend conformance are verified (issue #402).
