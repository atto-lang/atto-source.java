# Hard-real-time timer expiry, ownership and actor code-start contract

Status: **normative design proposal, not an implemented hard-RT guarantee** (2026-10-28).
Scope: actor timers, `time.sleep/after`, callback timers, interval/periodic activations,
critical-callable timeouts, channel/select deadlines, I/O timeouts, watchdogs and scheduler waits.
Companion: `docs/timers-and-intervals.md` and draft actor-heap contract in Java PR #2203.

## Requirement: deadline expiry != user code started

Every deadline-driven activation has a monotonic, absolute `due_ns` and separate
observations:
- `t_due`: requested absolute monotonic release time (not wall-clock UTC).
- `t_expiry`: driver recognizes it as expired.
- `t_ready`: validated actor/task ready token is admitted; quota/identity verified.
- `t_start`: the owning actor's exclusive execution lease is acquired AND the
  first instruction of the resumed/user callback code actually begins.

Measure `expiry_lateness = max(0,t_expiry-t_due)`,
`ready_lateness = max(0,t_ready-t_expiry)`,
`dispatch_lateness = max(0,t_start-t_ready)`, and
`code_start_lateness = max(0,t_start-t_due)`.
A green clock-firing metric **cannot** satisfy a code-start deadline.
A timer may expire precisely while a carrier remains busy, a queue is full,
a held critical permit blocks admission, or the actor's non-reentrant turn
is executing. All are possible deadline misses. Report them explicitly.

A hard-RT activation must be admitted only if an end-to-end proven upper bound
`B_start_ns` exists such that `t_start <= t_due + B_start_ns`, including:
clock uncertainty/granularity, interrupt and wake-up delay, notification,
queueing, actor-lease contention, bounded preemption, and dispatch.
When a job must **finish** by a deadline, separately include execution WCET,
unwind and any admissible controlled blocking: a bounded start is not a
bounded finish.

`B_start_ns` is a **declared and justified bound**, not an arbitrary runtime
percentile. Example values (10us, 100us, 1ms) are not universal platform
guarantees; reject an unsupported request during compilation/admission.
Tests measure observed worst cases and violations but measurements alone
do not prove a hard-RT bound.

## Physical storage versus logical actor ownership

- Actor-local heap/arena: guest timer/periodic handles, callable captures,
  source continuation frame, future observer state, cancellation ownership
  and lifecycle records. All remain charged to the actor/task scope.
- Bounded scheduler CONTROL arena: deadline index, opaque stable timer ID,
  actor ID + incarnation/generation, domain/priority, bounded ready-token
  slot, cancellation/expiry state and admission reservation.
- Clock driver: wakes or enqueues **only token notification**; it never
  executes user code, invokes a guest closure, dereferences guest memory
  or accepts a raw actor heap pointer. A clock interrupt handler must remain
  bounded and never run language callbacks.
- Actor scheduler: checks generation, lifetime, task/actor readiness,
  cancellation, physical lease, critical permission and admitted priority
  before code entry. Awaiting `after/sleep` resumes the SAME suspended
  logical handler; `set_timer` enters as a NEW serialized actor CONTROL turn.

On a JVM these may share one physical managed heap, but must have distinct
logical ownership, admission, and reachability. LLVM hard RT should use
fixed-capacity scheduler-owned arenas and actor-owned arenas; neither a new
thread nor new user-visible Task is required for each timeout.

Actor teardown and hot reload revoke ready/unclaimed tokens and fence the
old generation; actor memory may only be reclaimed after in-flight carrier
execution and pending handoff paths physically quiesce.

## Profile separation: never silently upgrade best effort to hard RT

| Profile | Status | Timer implementation |
| --- | --- | --- |
| Best effort | Existing Java host and native queue primitives | Deadline is not-before; no code-start bound |
| Soft RT | Candidate Java/GraalVM AOT with allocation/GC and latency measurements | Target latency and recorded misses; no formal worst-case claim |
| Hard RT | **NOT IMPLEMENTED / admission fails closed** | Qualified LLVM AOT + supported OS/CPU configuration, bounded reservations and justified response bound |

The Java `ScheduledThreadPoolExecutor` used in `OresTimers` is two
daemon JVM threads with heap allocation and no end-to-end RT admission.
`OresScheduler` uses general ready queues. Neither qualifies as hard RT.
The draft `ActorTimerWheel` in Java PR #161 uses a 1ms periodic tick,
`ConcurrentLinkedQueue` allocation, raw `Runnable` in timer records,
unbounded `pending.incrementAndGet()`, a single timer driver, and catches
and ignores nonfatal exceptions. This is **best-effort actor-clock substrate
only**, and must not be promoted as a hard-RT timer implementation.

LLVM `NativeTimerQueue` on main has bounded outstanding count, monotonic
ordering and a token-claim API, but uses `std::map`, `std::set`,
`std::vector` and `std::mutex` in registration/poll paths. It is a
useful host CONTROL contract, **not** a constant-time/preallocated hard-RT
driver or source-level actor continuation implementation.

## Required hard-RT architecture and admission

1. Use one clock domain per timing contract, absolute monotonic deadlines
   (Linux `CLOCK_MONOTONIC` and appropriate high-resolution facilities).
   Store deadlines, not mutable relative countdowns; check overflow and
   clock semantics, including suspend/resume policy.
2. Select a qualified OS kernel and execution policy for the deployment
   (e.g. appropriately configured Linux PREEMPT_RT and real-time tasks).
   Timer delivery has an OS-specific path: ordinary PREEMPT_RT hrtimers
   may execute in a lower-priority timer thread; do not assume an armed
   task's priority automatically transfers to a timer callback.
   The RT wakeup path must be qualified separately.
3. Reserve fixed-capacity timer nodes, ready tokens, actor state and
   continuation frames **before** accepting the hard-RT request.
   No unbounded queue, malloc/new, GC, paging, JIT, class loading or
   blocking lock in the hard-RT critical path. No silent fallback to
   the Java shared executor or coarse 1ms wheel.
4. Admission requires a bounded release-to-start response analysis,
   including the worst-case already-running actor turn under
   **non-reentrancy**, other admitted higher/equal priority traffic,
   bounded CONTROL traffic, critical locks/channels and available
   carrier/CPU reservation. If the current actor turn can run unbounded,
   **reject** its hard-RT callback guarantee. Never bypass actor isolation
   by running a timer callback on the clock thread.
5. Assign explicit scheduling class, priority/deadline and core/domain.
   Use fixed-priority or deadline scheduling with a validated WCET/period
   model and schedulability test; priority inheritance or ceilings must
   account for held runtime locks. RT and best-effort queues have separate
   reservations; a flood of best-effort ticks cannot evict RT work.
6. Timer state is linearizable:
   `ARMED -> EXPIRED -> READY -> CLAIMED -> RUNNING -> DONE`;
   revocation can win before CLAIMED. A CLAIMED token is only the
   scheduler's authorization to attempt execution; generation and
   physical actor lease are checked before RUNNING. Do not confuse
   cancellation, expiry, claim, completed Future or Task cancellation
   with physical-retirement acknowledgements.
7. Fail safe and report with a typed deadline-miss event when a promised
   start bound is exceeded. A hard-RT miss is a correctness failure and
   participates in supervision policy; it is never silently reclassified
   as a successful timer just because the clock callback executed.
8. Fixed-delay `interval.next()` is an async throughput primitive and
   does not provide phase-accurate periodic hard-RT releases. Introduce
   an explicitly admitted fixed-rate periodic activation with epoch,
   period, WCET, start bound, and missed-period policy
   (skip/fail; never unlimited catch-up).
9. `time.timeout`, `critical` budgets, channel/select and network
   timeouts are separate **observation/expiration** semantics. A timeout
   can be delivered on time yet the failure handler starts late. Do not
   promise hard-RT timeout-handler execution without a qualified
   scheduler reservation. Cancelling a shared producer is forbidden;
   cancelling owned work also requires physical retirement before reuse.
10. Enforce static effects: no unbounded allocation, blocking I/O,
    arbitrary await/suspension, non-RT actor call, raw heap capture,
    unbounded recursion, uncontrolled locks or unsupported FFI across a
    hard-RT path. A timer does not grant RT authority to an otherwise
    non-RT handler.

## Release gate: deterministic semantics, then physical deadline evidence

First deterministic model / virtual-clock tests (independent of OS jitter):
- deadline ordering (same epoch and equal deadlines), max tick budget,
  time overflow/zero delay, cancellation vs expiry vs ready vs lease claim;
- actor generation fencing, stop/hot reload and supervisor teardown;
- no callback runs before the owning lease or on an interrupt/clock thread;
- busy non-reentrant actor rejects an inadmissible tight deadline;
- priority inversion, bounded actor-turn interference and dedicated
  RT queue quota rejection; no starvation by best-effort traffic;
- actor heap captures and all timer/ready reservations reclaimed exactly once;
- periodic missed-tick policy and long-duration drift against absolute phase.

Then execute real source+native conformance on the **exact artifact/tree**:
- LLVM full AOT build under the qualified kernel/CPU and CPU affinity;
  sanitizer runs separately from latency qualification;
- timestamp `due,expiry,ready,start,end` with correct clock and per-CPU
  trace correlation, measure failures under saturated adversarial workloads;
- Linux `rtla timerlat`, `osnoise`, wakeup and scheduler traces, preemption,
  CPU throttling, IRQ/load, competing actor work, teardown and hot reload;
- prove the configured WCET and scheduling/resource assumptions, document
  hardware/kernel/toolchain version and reserve headroom;
- fail CI/release admission on ANY supported-envelope missed bound;
  do not treat a zero-step GitHub Actions job as executed evidence.

Sources for OS-level mechanisms:
- Linux kernel real-time timer differences:
  https://docs.kernel.org/next/core-api/real-time/differences.html
- Linux SCHED_DEADLINE admission/WCET contract:
  https://docs.kernel.org/scheduler/sched-deadline.html
- Linux timerlat instrumentation:
  https://docs.kernel.org/tools/rtla/rtla-timerlat.html

## Incremental implementation sequence

P0: actor/timer lifecycle token separation and generation fences; fixed
bounded notification accounting; callback failures reported, never swallowed.
P1: monotonic absolute-deadline + fixed-memory Linux AOT RT driver and
priority/deadline-ready scheduler, with instrumentation of all four stages.
P2: compile-time effects/WCET admission for hard-RT actor jobs, runtime
schedulability and miss escalation; perform kernel/hardware qualification.
P3: differential Java soft-RT / LLVM hard-RT / .NET and WASM best-effort
source conformance with explicit fail-closed unsupported diagnostics.

Until these gates execute, **no Oreslang backend may advertise guaranteed
hard-real-time timer-to-actor-code-start latency**.
