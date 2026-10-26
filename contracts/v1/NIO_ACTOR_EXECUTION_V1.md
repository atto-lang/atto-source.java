# NIO and physical actor-turn semantics — portable v1 contract

Status: **normative target, not a statement of feature completion**.
A backend that cannot implement an operation safely MUST reject it explicitly.
Partial host primitives are not authority to execute source actors concurrently.

## I/O and scheduling

1. I/O guest operations MUST be asynchronous and never block an ordinary
   actor, CONTROL, or root execution carrier. A pending operation suspends the
   logical task and returns the carrier to its runnable-work scheduler.
2. A dedicated reactor MAY wait only in its kernel readiness/completion API
   (for example epoll, kqueue, IOCP, or an appropriate Wasm host event loop).
   Reactor callbacks perform bounded host bookkeeping only. **Guest code must
   execute on its owning actor or CONTROL scheduler, never on a reactor.**
3. The normal throughput profile targets **zero VM-owned reactor threads at
   cold startup**. Reactor/scheduler descriptors and bounded queues MAY be
   constructed eagerly, but the first valid readiness admission lazily starts
   reactor #0. Sustained measured reactor pressure may grow the reactor set up
   to a bounded implementation maximum (initial target: four). Pending handle
   count alone is never a scaling signal. An explicit warm/server profile MAY
   prestart one reactor; that is configuration, not a language semantic.
4. PRIVATE, SHARED and UNTRUSTED execution carriers are demand-driven unless
   a backend/profile explicitly chooses a warm minimum. CONTROL MAY be
   prestarted as a VM bootstrap guarantee. The number of resident CONTROL
   workers is an implementation/profile property, not a source-language
   semantic; whichever policy is chosen MUST be bounded and observable.
5. Canonical normal-profile completion isolation adds **no unbounded private
   completion-worker population**. A reactor publishes only bounded immutable
   readiness/completion records; continuation resumption is enqueued to the
   owning scheduler/domain, or to an explicitly supplied host executor for
   host-only callbacks. Reactor threads MUST NOT execute arbitrary Future
   subscribers, and slow subscribers MUST NOT consume/starve CONTROL. A
   transient bounded bootstrap helper is conforming only when it never runs
   guest completion code and is included in physical-worker accounting.
6. When a backend advertises a VM-wide physical execution-carrier budget, that
   budget MUST be actually enforced rather than inferred from independent pool
   sizes. CONTROL retains guaranteed progress capacity and every additional
   host execution domain included in the claim must consume the same permit
   authority. Pending I/O, actors, ActorGroups, mailboxes and suspended
   continuations cannot consume physical-carrier credits merely by existing.
7. An **explicitly user-created fixed scheduler** is a deliberate exception to
   ordinary VM-pool laziness. Where exposed (for example Java
   `OresScheduler(N)`), `N` means exactly `N` dedicated user-owned carrier
   threads, created/prestarted because the user explicitly requested them.
   Such a scheduler is never created implicitly per actor or ActorGroup and
   its carriers are accounted separately from the normal VM cold-start
   invariant. A backend without this surface must not emulate it with hidden
   per-object threads.
8. The strict profile forbids blocking-worker/virtual-thread I/O offloading.
   Unsupported socket/file/HTTP/stdio operations reject; they must not silently
   use blocking calls or pretend that an async-looking API is OS-nonblocking.
9. Socket, read/write operation, continuation and completion records are owned,
   bounded, generation-fenced and cancel-safe. A descriptor is admitted by at
   most one active I/O owner; a stale completion cannot settle a reused slot.
10. A VM/guest may use existing OS/JVM/.NET/host event loops when dedicated
   reactor ownership is unavailable, but MUST verify that its guest operation
   is genuinely nonblocking. It MUST NOT claim explicit 1..4 reactor-thread
   control if that platform does not expose it.

## Per-actor physical concurrency

Three distinct limits MUST NOT be confused:

- Logically outstanding message lifetimes (including suspended messages);
- `maxActiveTurns` on a single actor, default **1**, future opt-in **1..3**;
- ActorGroup physical `maxParallelTurns` across *multiple* actors.

A host-only 1..3 credit gate does not enable concurrent guest actors.
The source compiler and runtime MUST prove independent per-turn frames, owned
or Sendable message data, legitimate shared immutable reads, exclusive
`critical` state writes, cancellation/finalizer/lifecycle quiescence, and
Graal/native engine thread safety BEFORE accepting `maxActiveTurns > 1`.
A `critical` loan cannot escape or span await/select/suspension. Contention
must suspend/requeue, not park a carrier. A backend without actors must reject
all actor execution, including a request for a single physical turn.

## Native real-time profile

LLVM and any native RT implementation target zero tracing-GC and deterministic
bounded native lifetimes. Preallocate fixed carrier/reactor sets and bounded
tickets/arenas BEFORE admitting deadline-sensitive work. Do not grow pools,
allocate unboundedly, or block on ordinary locks on the certified hot path.
This alone is **not** a hard-real-time guarantee: platform-specific WCET,
preemption, scheduler priority and device/kernel latency proofs are required.
Backends without proven RT admission must explicitly deny #hard.

## Current implementation classification (2026-10-17)

| Backend | NIO status | Actor physical turns | RT status |
| --- | --- | --- | --- |
| Java/GraalVM | Current main prestarts bounded CONTROL; VM readiness starts reactor #0 lazily. Full OresContext binds terminal notifications to actor carriers, with at most one short-lived off-selector bootstrap helper for a cold bound executor. Standalone readiness retains a bounded lazy 0..2 completion pool. Legacy HTTP/FS paths reject in strict NIO mode | Host credits 1..3, live actor default single-flight; opt-in 5..256 global budget is enforced only for explicit JVM + non-affine topology | Hybrid GC; no hard-RT guarantee |
| LLVM | Merged PR #880 provides Linux epoll VM lifecycle integration, generation-fenced completion/terminal accounting and fixed realtime prestart; source MEDIUM/HIGH actor turns remain fail-closed | Host credits 1..3, native execution authority remains single-owner for source actors | Zero tracing-GC substrate and fixed prestart profile; no WCET guarantee |
| .NET | Source compiler/runtime has no guest NIO implementation | Host credits 1..3 only; no guest parallel actor execution | Managed GC; no hard-RT claim |
| WASM | Zero-import scalar guest; no guest NIO or reactors | No guest actor execution, reject physical-turn requests | No hard-RT claim |
| Forge | Mailbox prototype; no guest NIO/reactor | Host credits 1..3 only; no source parallel actor execution | Native/no tracing-GC host credit path; no hard-RT claim |

## Incremental Java enforcement (current branch)

- Current Java main's CONTROL startup barrier is preserved. This branch does
  not claim zero resident CONTROL workers.
- `OresVM` owns one lazy `NioReadinessGroup`. The first accepted watch starts
  reactor #0; pending descriptors alone do not create actor carriers.
- In a full `OresContext`, NIO completion delivery is bound before first watch
  to `ActorRuntime.executeHostIoCompletion()`. Selector-originated submission
  may target only an already-started carrier through a proven no-growth API.
  The first runnable completion may use one short-lived off-selector helper
  solely to bootstrap the cold actor executor; the completion itself runs on
  actor-carrier capacity.
- `OresContext.close()` drains NIO before actor dispatchers retire.
  `OresVM.close()` rejects self-join from scheduler/actor/CONTROL/NIO worker
  execution and aggregates teardown failures rather than skipping another
  owned domain.
- `-Dores.runtime.carriers.global.max=N` accepts **5..256** and fails closed
  unless `ores.runtime.carriers=java` and `ores.actor.affinity=none` are
  explicit. In that topology a single permit coordinator covers CONTROL,
  PRIVATE, SHARED, UNTRUSTED, and LIFECYCLE/bootstrap host workers. Each domain
  retains one protected reserve; JVM non-affine domains may borrow idle permits
  without consuming another domain's final reserve.
- Dedicated kernel readiness reactors and explicit user-owned
  `OresScheduler(N)` carriers are separate resources and are not included in
  the execution-carrier budget.
- Current-main Java physical STOP revokers use one bounded VM-owned **virtual-thread**
  executor. Those tasks are lifecycle work, not dedicated VM-owned platform
  execution carriers, so they do not consume `OresCarrierPermitPool` permits.
  If that executor is ever changed to dedicated platform/native workers, those
  workers MUST either join the shared permit authority or be called out as a
  separate topology resource rather than hidden beneath the global-budget claim.
- The LIFECYCLE permit domain currently accounts the transient NIO
  bootstrap/completion helper. This branch does not implement #1100's separate
  lifecycle-observer/terminal-journal subsystem.
- The global budget is deliberately unavailable for native/affine actor
  topologies until their individual carrier start/retirement lifecycle can be
  tied to the permit coordinator without speculation.
- Source `MaxActorThreads.LOW` is accepted. `MEDIUM/HIGH` reject before
  executable lowering until per-turn actor state isolation is proven.
- Java exact-head JDK/GraalVM execution is still required before merge.
  Zero-step source-organization Actions failures are not test failures.

## Actor-system reliability and operability invariants

These are cross-backend semantic requirements. A backend may explicitly reject
an unsupported feature, but it must not silently weaken these guarantees.
Several items below (notably terminal journals and dedicated lifecycle-observer
workers) are future/other-slice requirements and are **not implemented by this
Java NIO/carrier branch**.

- **Bounded overload, never hidden growth.** User mailboxes, continuation
  headroom, dispatcher ready queues, lifecycle-observer work and diagnostic
  journals are bounded independently. Saturation returns a typed/reasoned
  admission failure or increments an explicit drop counter; it never creates
  one thread per actor/message/socket or silently expands an unbounded queue.
- **CONTROL and failure progress are independent of user work.** A saturated
  DATA domain cannot consume the final CONTROL progress permit or require a
  parent actor mailbox turn merely to make child failure visible. Stop,
  cancellation, panic and terminal parent bookkeeping must have bounded
  progress paths under ordinary DATA saturation.
- **Post-mortems survive telemetry loss.** Every actor incarnation retains one
  immutable terminal state on its ActorRef/capability after authoritative
  finalization. A bounded global terminal journal may drop records under
  pressure, but drops are counted and cannot erase the per-ref post-mortem or
  delay actor/parent finalization.
- **Failure-time diagnostics cannot poison themselves.** A terminal journal
  must not expose a producer-reserved-but-unpublished head slot that can hide
  all later events if the producer dies. Drain order must be monotonic for
  fully published records, sequence numbers must never wrap, and diagnostic
  publication must never invoke user Throwable formatting synchronously.
- **Callbacks are not lifecycle authority.** Arbitrary ready/DONE/output/exit
  observers never run while actor lifecycle locks are held and never execute
  on actor, CONTROL or selector carriers merely because another executor is
  saturated. Parent structured-finalization progress and callback-free
  termination barriers precede arbitrary observer callbacks.
- **Operational snapshots are race-tolerant and non-mutating.** Implementations
  should expose logical mailbox state separately from physical scheduler
  pressure (started/active carriers, ready backlog, bounded capacity and
  lifecycle-observer backlog). Sampling concurrently changing counters must not
  itself throw merely because two fields were observed at different instants.
- **Restart policy belongs to supervision, not failure loops.** OTP-like
  supervisors must bound restart intensity over a time window and support
  backoff/jitter or an equivalent policy. Generation/incarnation identity must
  make stale messages, monitors, timers and remote replies unable to target a
  replacement actor accidentally. Blind immediate infinite restart loops are
  non-conforming.
- **Finalization is idempotent and generation-fenced.** A stop/failure/cancel
  race publishes at most one terminal state per actor incarnation, drains or
  accounts for every admitted envelope/continuation, and cannot resurrect a
  retired execution domain through a late Future/channel callback.

## Required differential fixtures

- Java current-main startup: bounded CONTROL is prestarted; readiness reactors
  and NIO completion helpers remain zero until demanded. The first valid watch
  starts exactly one reactor. Pending watches alone create no actor/bootstrap
  carrier.
- A first runnable bound Java completion may create one transient off-selector
  bootstrap helper. With global budgeting enabled that helper consumes the
  LIFECYCLE permit domain. The callback executes on actor-carrier capacity and
  the helper retires after handoff.
- Warm affine and non-affine Java actor carriers accept selector-originated
  host completion records without worker growth.
- Under the optional JVM/non-affine global budget, concurrently runnable work
  never exceeds the permit ceiling and borrowing cannot consume another
  domain's final reserve. All permits retire only after physical worker exit.
- If a backend exposes a fixed user scheduler, constructing it with `N`
  explicitly creates exactly `N` user-owned carriers; ordinary actors and
  ActorGroups never receive dedicated OS threads implicitly.
- Concurrent connect/accept/read/write, partial writes, EOF, cancel/close
  races, generation/descriptor reuse, ownership and bounded backpressure.
- A reactor publishes completions only; guest callbacks run on the owning
  scheduler. Idle/reactor shutdown never strands accepted operations.
- Source actor LOW remains single-flight. MEDIUM/HIGH must reject until
  independent frames, state loans and lifecycle quiescence are implemented.
- Explicit unsupported-feature denial on .NET/WASM/Forge until source lowering
  exists. Host-only credit/NIO tests do not count as source guest success.
- LLVM RT qualification must retain zero tracing-GC, preallocated hot-path
  resources, bounded admission, clear denial of unproven #hard, and a thread
  census showing no post-admission runtime-thread creation.

## Evidence and source handling

Record immutable PR head commit, source Git tree, exact tested blob/tree,
runner job/step execution, compiler flags, and source-level guest tests.
A zero-step GitHub Actions failure proves neither pass nor fail. An exact-blob
host test establishes only that slice; it is not full source-tree parity.
Private cross-org source transfers require a reviewed protected-main approval
for the specific private destination, independent of Actions minutes.

## Canonical source API: actor-local physical turn maximum

The following built-in enum has fixed values. A backend may recognize its
canonical declaration without supporting unrestricted user-defined enums.
No source package or user module may redefine its values.

```oreslang
define enum MaxActorThreads as
    LOW int = 1     // default: one physically executing actor turn
    MEDIUM int = 2
    HIGH int = 3
end
```

The language-owned enum is implicit when omitted from the file. The actor
declaration annotation is **not** a runtime thread creation request:

```oreslang
@ActorMaxThreads(MaxActorThreads.LOW)
define actor NormalActor as
end
```

The following illustrates the **future opt-in** for a hungry actor; until
the compiler and runtime provide independent per-turn state and proven
mutable access control, every backend MUST reject it before executing
guest code or emitting a runnable artifact:

```oreslang
@ActorMaxThreads(MaxActorThreads.HIGH)
define actor HungryActor as
end
```

- `LOW=1` is the default for every source actor (annotated or not).
- `MEDIUM=2` and `HIGH=3` are hard upper limits on **simultaneously
  physically executing DATA turns in a single actor**, not guarantees of
  assigned/resident threads. No dedicated per-actor thread pool is implied.
- Outstanding/suspended messages do not consume a physical turn.
- `ActorGroup.maxParallelTurns` remains an independent aggregate bound.
  Effective admissible turns must satisfy BOTH the actor-local and group
  bounds, and the actual available shared execution carriers.
- Unknown enum members, ordinal substitutions (e.g. `HIGH=4`), duplicate
  annotations/enum declarations, non-actor targets, literal numeric annotation
  arguments, or MEDIUM/HIGH without compiler-proven isolation MUST be rejected.
- A source actor without `@ActorMaxThreads` is behaviorally identical to
  `@ActorMaxThreads(MaxActorThreads.LOW)`; serial actor aliases remain serial.
- The actor's protected `state { ... }` map remains the sole actor-instance
  mutable state channel. `critical` writes require an exclusive state loan
  across all concurrently executing turns. No mutable loan crosses await.
- Java/GraalVM and LLVM accept and validate the enum/LOW annotation on their
  staged branches; LLVM source actor syntax remains narrower than Java.
  .NET/Forge/WASM explicitly deny all source actor tiers until they implement
  real guest actor scheduling. Host-only 1..3 credit gates are insufficient.
