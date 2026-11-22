# Actor timer ownership, heap placement and timeout semantics

Status: **normative design contract; integration NOT complete** (2026-10-28).
Companion to [timers-and-intervals.md](timers-and-intervals.md), the actor
single-writer/continuation contract, and the critical-callable deadline contract.
This document distinguishes *required* source/runtime behavior from what
Java/LLVM currently implement. It does not claim that `time.*` source syntax
or actor timer callback lowering is live on any backend.

## One timer is not one Task, one actor or one carrier

| Source operation (target syntax) | Resource created | Does it require a separate source `Task`? |
| --- | --- | --- |
| `await time.after(d)` / `await time.sleep(d)` | One cancellable deadline registration + Future; resumes the **existing** async/actor continuation | **No**. Caller needs a legal suspending frame, not a new child Task. |
| `await time.timeout(d, existingFuture)` | Independent observer Future + deadline registration | **No**. Never implicitly owns or cancels the producer. |
| `let h = time.set_timer(d, callback)` | Revocable callback handle and actor-owned admission capability | **No** new guest Task at registration. On expiry the callback runs as a serialized actor CONTROL turn, not on the clock thread. |
| `await interval.next()` | One demand-armed tick registration on a bounded interval capability | **No**. One pending consumer; no catch-up queue. |
| `TaskScope` with owned child work + deadline | Existing child Task(s), cancellation rights, retirement receipts | Tasks are used **because independent owned work exists**, not because timers inherently require them. |

An `async` or `suspends` callable still has a logical scheduler-owned
continuation (some compiler/runtime configurations model it as an implicit
Task frame). **No new source-level Task is spawned solely to sleep or
time out.** A bare `await` is illegal outside a compiler-supported
suspending context. The same principle applies to function recursion:
each resumable invocation must have bounded lifetime/accounting; a timer
does not allocate an OS thread, actor, or new carrier.

`set_timer` callback dispatch may use an internal scheduler work record;
that is not a user-visible `Task<T>` and must not bypass actor admission.
A timer callback may not use an implicitly suspending guest lambda; owned
asynchronous work must be explicitly admitted under the actor's task/scope
rules. Timed waits in a running actor handler should normally use
`await time.after(d)` rather than scheduling a separate callback and
awaiting that callback's completion while holding the actor's serial turn.

## Actor heap vs VM/scheduler heap: split **logical ownership** and **physical storage**

**Timer registration** is a VM/scheduler CONTROL resource, but the **owner**
is the actor instance/generation (or the explicit root context for non-actor
code). Do not put a raw timer driver, clock-thread callback or cross-thread
pointer into an actor's mutable arena. Do not place actor-owned guest closure
captures or a pointer to mutable actor state into a shared scheduler timer
bucket.

Required separation:

- **Actor-local arena/heap:** guest `TimerHandle` capability, callback
  closure/captures and actor state, pending await continuation/lexical
  frame, and lifecycle ownership bookkeeping. They stay subject to the
  actor's borrow checker, memory quota and one-active-state-writer policy.
- **VM/scheduler CONTROL heap/arena:** small opaque timer record with
  monotonic deadline, timer id, owner `ActorRef`/generation/incarnation,
  domain, cancellation state and quota reservation. It must not hold a
  directly invocable guest closure or an unleased actor-state pointer.
  These records can be kept in one shared bounded wheel/queue for many
  actors rather than a timer thread or scheduler instance per actor.
- **Clock driver:** advances deadlines, performs exactly-once
  expiry/cancellation arbitration and enqueues a **token**, never guest
  code. It never enters an actor heap or runs `handle_state`.
- **Actor scheduler/mailbox:** validates owner identity, generation,
  shutdown/hot-reload fence, quota, and actor execution lease. A timer
  Future wakes the **already suspended** logical handler; a callback
  timer admits a *new* serialized CONTROL turn. These are different
  delivery modes and must not be conflated.

On JVM the host-side queue/registration objects physically live in the
managed process heap; they are **not** evidence that mutable guest actor
objects belong to the process's shared heap. On LLVM, equivalent timer
records belong in a scheduler/runtime control arena rather than an actor
arena. On isolated/private/untrusted actors, cross-isolate wakeups must
carry only validated tokens/messages. Actor ownership is enforced
regardless of physical GC/AOT allocation.

The scheduler **controls when an actor executes**, not ownership of that
actor's mutable state: the actor runtime/supervisor retains lifecycle and
arena authority. Timer expiry is *readiness*, not permission to run guest
code, reenter `handle_state`, transfer a borrow, reclaim a heap, or bypass
the actor-state lease. A continuation may resume on a different carrier.

## Admission and race requirements

1. Measure relative deadlines with monotonic time. Validate negative/zero
   durations, overflow and saturation deterministically. Zero-delay
   one-shots still resume on a later scheduler turn. Deadlines are not
   hard real-time CPU preemption.
2. Keep globally and per-tenant/per-actor **bounded** counts for pending
   expiries, idle interval capabilities, CONTROL notifications and deferred
   callbacks. Reject admission before allocating unbounded queue entries.
   Reclaim each reservation exactly once after cancel/expiry/teardown.
3. The actor-owned callback handle follows `ARMED -> READY -> RUNNING ->
   DONE` or pre-start `CANCELLED`. Cancellation remains possible after
   clock expiry but before actor CONTROL **claims** callback execution.
   A lower-level wheel's `poll()` / `claim_delivery()` is not a guest
   dispatch or physical-retirement receipt.
4. Bind every registration to immutable actor id, incarnation/generation,
   scheduler domain and owner lifetime. A timer scheduled by a replaced,
   stopped, or quarantined actor may **never** run in its successor actor.
   Hot reload defaults to revocation; migration requires an explicit
   supervisor-authorized, validated checkpoint transfer with new tokens.
5. `await time.after(d)` resumes its original suspended handler under the
   actor execution lease, preserving its mailbox ordering and state-loan
   invariants. It must not enqueue a new independent callback handler
   behind that suspended handler (which could deadlock).
6. `time.set_timer(d, callback)` enters as a new actor-owned CONTROL
   callback turn, not a standalone `OresScheduler.startSync` job that
   bypasses the actor mailbox. If the current serial handler waits on
   `handle.get_done()` while preventing CONTROL entry, reject/report the
   self-dependency; do not park a carrier.
7. `time.timeout(d, future)` owns **only the observer** and its timer.
   Deadline wins -> fail observer with typed `TimeoutError` and detach;
   producer wins -> preserve its exact result, error or cancellation;
   observer cancel -> detach. In **all** cases keep the shared producer
   untouched. A true **owned-work timeout** needs explicit child Task or
   TaskScope cancellation authority and a physical-retirement receipt.
8. Untrusted actor timeouts are not authorization to kill a running host
   thread. Keep existing independent fuel/watchdog and supervisor policy;
   elapsed deadline can request cooperative cancellation, but physical
   heap reclamation waits for actual carrier/continuation exit.
9. Callback errors propagate through the owning actor's supervision/error
   path and `get_done()`; no dropped/silently swallowed callback failure.
   Clock threads execute **runtime-only, non-guest, bounded** callbacks.
10. Registration/expiry/cancel/actor stop/upgrade/owner close are raced
    under a single per-handle state transition and validated delivery
    claim; no resurrection, double release or stale callback.
11. Borrow-checked captures may not leave actor scope via scheduler
    registry. No live actor-state mutable borrow, `rt borrow` loan or
    FFI pointer may cross suspension except where compiler lifetime and
    exclusive-lease proof explicitly permit it.
12. Timer shutdown revokes outstanding capabilities, but **revocation is
    not quiescence**. The supervisor must drain in-flight CONTROL delivery
    and await scheduler physical retirement before dropping actor arenas.

## Current implementations vs blockers (audited 2026-10-28)

- **Java main:** `OresTimers.afterNanos/sleepNanos` and demand-driven
  `Interval.next()` are host-only; `OresTimers` owns a shared JVM
  `ScheduledThreadPoolExecutor`. The host `setTimerNanos` deliberately
  rejects registration **during** actor execution pending actor-local CONTROL
  lowering. `OresScheduler` owns ordinary Task continuation resumption.
  `OresVM` / `OresContext` do **not yet** automatically register/close
  actor-specific `OresTimers` on actor retirement. This is **not** proof of
  actor heap/timer integration.
- **Java draft #2202:** adds host-only observer `timeoutNanos` and global
  interval-capability quotas. Neither guest `time.timeout` nor actor-owner
  callback admission is established by that PR. Exact-head tests are still
  required.
- **Java draft #161:** proposes actor continuations and a shared
  `ActorTimerWheel`, but remains stacked/draft, not a merged actor timer
  implementation on main. Audit its wheel admission, tick/registration
  races, callback failure delivery and actor stop quiescence before integration.
- **LLVM main:** `NativeTimerQueue` provides bounded monotonic queue
  registration, per-domain cancellation and claim/discard of ready tokens;
  it does not yet implement the guest `Future`/actor CONTROL resumption
  contract. .NET/WASM/Forge require separate parity attestations.

## Minimal executable gates before publishing source support

- A single actor awaits `after`, yields its carrier and resumes as the
  **same** logical handler, including already-settled/zero-delay waits.
- A timer callback never executes on a clock thread or generic scheduler
  task outside the actor lease; a suspended serial handler self-awaiting
  its own callback is diagnosed.
- Cross-actor timers cannot access actor-local captures via raw pointers;
  stale id/generation and post-reload expiry fail closed.
- Repeated cancel-vs-expire-vs-CONTROL-claim-vs-stop/upgrade races return
  every admission slot once and never run a canceled callback.
- Exhaust global/per-actor/tenant timer and idle-interval budgets, including
  rapid create/cancel with concurrent actor shutdown; assert no unbounded
  retained queue/capture growth.
- Timeout observer never cancels shared producer; owned-child timeout
  requests cancellation **and waits for actual retirement** before reclaim.
- Actor heap and lexical captures remain alive while a registered callback
  or suspended continuation can use them, but teardown releases them after
  physical retirement; verify Java AOT and native sanitizer tests.
- Run **same Oreslang source** under Java, LLVM, .NET and WASM (or emit
  explicit unsupported diagnostics). Do not count a GitHub Actions run
  with zero executed job steps as test evidence.

These are required gates, **not** claims that source-level APIs pass today.
