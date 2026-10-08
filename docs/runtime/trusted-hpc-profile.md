# Trusted HPC/HFT execution profile (RFC — WIP / DO NOT MERGE)

Status: **design + inert planning utilities only**. Tracking: #469.
This is not an operational kernel-bypass NIC, hard real-time system, lock-free
mailbox replacement, LLVM compiler backend, or garbage-collector-free runtime.

## Goals and hard boundaries

Oreslang should eventually support dedicated-host HPC and market-data/order-path
workloads with explicit memory layout, bounded allocation, cache-line and NUMA
locality, predictable code generation and native NIC queues. These are exceptional
**trusted supervisor-deployed** workloads, not the default language semantics.

Security invariant: a guest annotation, user-controlled profile flag, actor kind,
module import, FFI call or configuration file must NEVER grant privileged access.
Only a host/supervisor with authority over the dedicated deployment can grant
specific physical resource leases, and only after verifying executable identity,
tenant identity, host policy and hardware capabilities. No ambient capability
inheritance across actor/spawn, import, future, IPC or serialization.

| Workload | Ordinary actor state | HPC physical allocations | NIC DMA / device lease |
| --- | --- | --- | --- |
| Trusted actor in dedicated Linux HPC process | Actor-local | Future opt-in | Future supervisor-granted |
| Shared/trusted actor in multi-tenant hosting | Actor-local | Denied by default | Denied |
| Untrusted/isolated actor | Confined | Denied | Denied |
| Mobile runtime / browser / FaaS | Confined/ordinary | Denied | Denied |
| Supervisor/host control plane | Separate privileged plane | May provision | May bind/unbind and revoke |

An actor's logical mailbox serialization remains mandatory. Hardware queues can
feed a trusted actor through a **single-consumer bounded bridge**; they must not
invoke guest actor methods concurrently on NIC poller threads. Driver work must
not borrow mutable actor state without a validated ownership transfer.

Existing runtime placement and physical isolation: see
[actor physical boundaries](actor-physical-boundaries.md). Related: #211
(data-oriented programming), #236 (transferable Regions), #289 (actor-local
heaps), #303 (core/LLC/NUMA placement) and #304 (borrow/take/copy/share).

## Target deployment modes

- DEFAULT: existing interpreter/JVM/hybrid sandbox model; unchanged.
- TRUSTED_HPC_EXPERIMENTAL: dedicated Linux host, explicit signed deployment
  manifest and supervisor authority, runtime control/telemetry plane separate
  from the bounded real-time data plane; **not available in this PR**.
- TRUSTED_HPC_STRICT (future): native ahead-of-time compiled guest binary,
  mechanically checked ownership and bounds, fixed memory budget; no runtime
  guest compilation or moving/reachable managed objects on its hot path.
  Admission fails unless a backend proves all requested properties.

Fail closed on unsupported OS, missing NUMA, wrong CPU cpuset, lack of NIC
IOMMU/queue ownership, insufficient huge pages or locked-memory budget, inability
to pre-fault, lost affinity or driver lease, or unsupported compiler profile.
Explicit best-effort alternatives must be separate and never silently downgrade
strict requirements.

### What JVM/GraalVM currently does NOT guarantee

- Rust-like source-level moves and borrow checks alone do **not** eliminate JVM
  GC or host-side managed objects. JDK heap, Truffle, interpreter and agent work
  can still allocate and trigger GC. Strict *no-GC-pauses* requires measurement
  and eventually a target with no runtime GC in the data path.
- GraalVM Native Image AOT-compiles the **host runtime**, but the existing
  Oreslang AOT/interpreter profile is not yet native machine-code compilation of
  Oreslang guest functions. Hybrid modes may still compile guest code at runtime.
- Source annotations cannot generally command a JIT to never OSR,
  invalidate assumptions or recompile a method. Truffle partial evaluation and
  JVM compilation controls are not a substitute for a static-code property.
- No blanket microsecond or worst-case latency promise is possible on a general
  purpose Linux OS: firmware, SMIs, PCIe, power transitions, IRQs, and contention
  still cause jitter even with kernel-bypass packet I/O.

## Hungry Actors: hot/warm dedicated real-time execution (WIP)

A **Hungry Actor** is a deliberately resource-hungry, supervisor-admitted,
single-writer actor that holds one **dedicated native OS thread** for its
lifetime rather than merely a stable lane in a shared carrier pool. It may be a
SHARED actor or an ISOLATED actor. **The trust grant is orthogonal to actor
kind:** only host-verified trusted deployments may request HPC resources;
UNTRUSTED actors always fail admission, even if labelled shared/isolated or if
their source contains an annotation. Isolated does not mean privileged and
trusted does not mean OS-sandboxed.

| Mode | Thread/actor lifetime | Activation & latency contract |
| --- | --- | --- |
| HOT | Precreated thread, pinned core, initialized actor/arena, active bounded spin/poll loop | No startup allocation, cold compilation, heap GC or class loading in a verified native hot path |
| WARM | Precreated dedicated thread, initialized/pre-faulted arena and code, parked on an eventfd/futex | Wake/transition to HOT without allocating or compiling; measure wake and scheduling jitter |
| Normal | Existing shared-carrier actor scheduler | Ordinary portability/fairness/GC contract, **not** HPC latency |

Both kinds must preserve the **one actor-wide execution lease**. The thread
runs only the owning actor's work; it must not call a different actor's guest
handlers, and no asynchronous completion thread may mutate actor state.
For ISOLATED trusted Hungry Actors, the native data plane must have an actual
approved address-space/isolate strategy and bounded serialized crossings. A
Truffle context alone is **not** physical isolation.

### Linux scheduling and physical placement

Supervisors, not guest source code, request and verify actual kernel scheduling:

- **SCHED_FIFO**: fixed-precedence real-time thread, priority 1..99, no
  time-slice among equal priority; a continuously runnable thread can starve
  lower-priority work. Appropriate only with owned cores, a separate watchdog
  and explicit poll/spin budgets.
- **SCHED_RR**: priority 1..99 with a time quantum among peers at the same
  priority. Still requires isolation/watchdog and can starve normal threads.
- **SCHED_DEADLINE**: `sched_setattr` with nanosecond
  `runtime <= deadline <= period`, priority 0. The kernel conducts a
  schedulability admission test and throttles a task when it exhausts its
  bandwidth. Suitable for **budgeted episodic** work, not unbounded HOT
  polling. WARM activation may use it only with validated finite jobs.
- **SCHED_OTHER/nice**: not an adequate strict real-time admission request.
  High `nice` priority is not the same as real-time scheduling.

Linux specifics: [sched(7)](https://man7.org/linux/man-pages/man7/sched.7.html)
and [kernel SCHED_DEADLINE documentation](https://www.kernel.org/doc/html/latest/scheduler/sched-deadline.html).
This profile does not hardcode a priority of 99: the supervisor determines
safe priority/CPU allocation from system-wide real-time services and watchdog
requirements. Untrusted source may request a policy as a *proposal* but cannot
set `CAP_SYS_NICE`, `RLIMIT_RTPRIO`, `sched_setscheduler` or
`sched_setattr`, and cannot set process-wide kernel knobs.

Required admission sequence (not implemented yet):

1. Verify signed/immutable compiled artifact and trusted host deployment
   identity, host policy, actor kind, ABI, source-level no-alloc proof, code
   freeze, native binary/arena budget and supervisor-signed resource lease.
2. Choose an exclusive **physical** core; account for its SMT sibling, cgroup
   cpuset restrictions, scheduler domains, NIC IRQ/NAPI/queue affinity,
   housekeeping/RCU work, IRQ-off sections, NUMA node and page residence.
   Logical actor lanes or CPU pinning alone do not reserve an isolated core.
3. Reserve **another** CPU/core for management, termination and watchdog.
   Warm and hot actors must never starve cancellation/control traffic.
4. Allocate and fault-in **all** arena, stack and packet-buffer pages, and
   lock required pages *before* hot activation. Verify actual NUMA page
   placement; enabling `mlockall` alone does not guarantee zero page faults.
5. Spawn the actor's dedicated carrier thread and install `sched_setaffinity`
   + scheduling policy on its native TID, via an authenticated privileged
   supervisor/launcher; check permission and read back
   `sched_getscheduler`/`sched_getparam`/`sched_getattr`, TID affinity and
   effective cgroup constraints. Failure means **no HPC actor admission**.
6. Read back runtime scheduling limits and deadline admission; preserve
   reasonable system RT throttling/watchdog budgets. Never globally disable
   safety throttles for a guest. Do not assume any kernel policy guarantees
   an absolute maximum tick-to-trade latency.
7. Start HOT or activate WARM only after every admission check succeeds.
   Revoke, demote and teardown on loss of CPU/NUMA/NIC lease, actor failure,
   watchdog deadline, or requested cancellation. A hung hot loop cannot block
   the control path or physical resource reclamation.

Potential Linux tuning experiments on dedicated hosts include PREEMPT_RT,
CPU isolation, `nohz_full`, housekeeping/RCU isolation, disabling frequency
scaling/SMT only when approved, and tuned NIC RX steering/interrupt affinity.
These require explicit, measurable deployment settings and may hurt throughput
or availability; they are **not** assumptions or settings this PR applies.

### Strict zero-GC-pauses data plane

The goal is **zero GC pauses attributable to the Hungry Actor's critical
path**, not a false claim that the JVM host or other management threads never
collect garbage.

- Memory for every hot-path object is arena/region allocated during startup,
  with statically verified fixed layout and deterministic lifetime. No JVM
  reference/managed graph, dynamic reflection, dynamic class load, new
  exception object, coroutine closure, heap-based Future, runtime log event
  allocation or hidden FFI allocation can enter the critical path.
- A strict hot loop needs a native artifact that does **not** run guest
  interpreter, JIT, OSR/deopt or managed GC. Today a Graal Native Image
  **host** binary does not prove this for Oreslang guest code. Future LLVM
  native guest AOT plus statically checked call graphs/regions is the
  preferred strict target.
- Keep JVM/GraalVM supervisor and observability processes on other cores;
  instrument crossings and disable callbacks into them on the critical path.
  The control plane may still GC; that must not stall or rendezvous with
  dedicated native Hungry Actor threads.
- Initialization can allocate and use GC or compilation in a non-real-time
  management phase, but the transition into HOT is one-way under a frozen
  code version. A source hot-reload requires draining/replacing the actor, not
  mutating its code while active.
- Compiler annotations like `@Hungry(mode: "HOT")`,
  `@NoHeapAlloc` and `@NoRuntimeCodegen` are **requests/contracts**, not
  kernel privileges or compiler proofs. Without transitive verification the
  strict build fails.

A concrete, separate native-backend proof gate must check: no runtime guest
codegen; no managed-heap allocations in hot call graphs; no GC safepoints that
park the native path; no page faults after activation; zero heap-GC pauses
measured with continuous stress; no actor or NIC queue migrations; bounded
cold-to-HOT transitions; and p50/p99/p99.9/p99.99/max latency together with
jitter, missed deadlines and watchdog intervention. Time spent in OS/firmware,
PCIe, interrupt servicing or higher priority work can still introduce latency:
zero GC pauses are **not** the same as hard zero-latency guarantees.

### Inert planning checks in this PR

`HpcHungryActorPlan` models HOT/WARM, SHARED/ISOLATED/UNTRUSTED,
SCHED_FIFO/RR/DEADLINE, core/control CPU, NUMA, watchdog and zero-GC/codegen
requests. It validates inputs but deliberately always returns a blocking
reason: there is no kernel scheduler call, no resource grant and no dedicated
thread in this PR. `HpcHungryActorPlanTest` exercises acceptance eligibility
without admitting actors, invalid priorities, bad deadline budgets, no
housekeeping CPU, missing watchdog, and untrusted/FaaS denial.

## GraalVM HPC ActorGroup GC policy (practical near-term profile)

**Strict zero-GC belongs to the future LLVM/native critical-path profile.
It is not promised for GraalVM HOT/WARM actors.** GraalVM can still reduce
latency variance with reusable actor pools, preallocation, warmed code, native
memory, controlled admission, and planned cleanup windows. Calling GC a
"user-managed" feature does not disable a JVM or isolate's automatic collector.

The existing HpcHungryActorPlan makes requiresNoGcPauses optional, correctly
rejecting an unproven strict-GC contract on managed JVM/Graal profiles.
HOT/WARM means activation/thread/placement policy, not proof that collection
cannot occur.

### Distinguish physical heaps from logical actor ownership

Current ActorRuntime.ActorGroup is membership, a bounded event bus and logical
Mailman; it **does not allocate a separate VM or physical GC heap**. The
ActorMemorySlice tracks/private-reserves memory and can allocate confined direct
buffers. However host JVM objects and Truffle metadata still share one managed
collector when running inside the same JVM heap.

Current RuntimeGarbageCollector.collectCurrentActor() sweeps actor-domain
cleanup entries and does not call System.gc(). collectProcess() may issue one
rate-limited **process-wide** System.gc() hint; the VM is free to collect
automatically. Periodic cleanup sweeps do not force host process GC.

GraalVM Polyglot Engine isolates can have genuinely separate native-image
heaps and collectors. References:
https://docs.oracle.com/en/graalvm/jdk/25i/docs/reference-manual/embed-languages/
https://www.graalvm.org/dev/security-guide/sandboxing/
This depends on an Oreslang isolate-capable language artifact, correct Engine
placement, and native embedding validation. Ordinary Graal contexts/actor-local
allocation domains are NOT equivalent. Separate worker processes are a strong
reference isolation option.

| Backend for one ActorGroup | Independent managed GC? | User control |
| --- | --- | --- |
| Shared JVM/Truffle heap | No | Actor cleanup and off-heap arena resets only; cannot postpone automatic JVM GC |
| Dedicated Polyglot Engine isolate | Potentially, once wired and verified | Quiesce and request isolate maintenance only if a supported isolate-specific adapter exists; collector still runs automatically |
| Separate worker process | Yes | Quiesce and retire/replenish worker deterministically; any explicit GC request is confined to that process |
| Future LLVM/native region heap | Not applicable | Deterministic region/arena drop after references, DMA and I/O leases end |

**Optional bounded Native Image experiment:** GraalVM Native Image supports
the Epsilon/no-op collector (native-image --gc=epsilon). It performs no
reclaiming GC, but **retains allocated objects forever until the worker
is destroyed**. It is suitable only for measured, short-lived
process/isolate epochs with a strict heap budget and immediate
rotate-and-recreate on pressure, never indefinite HOT operation and
never an implicit FaaS/default GC selection. Verify how this backend
interacts with Oreslang's embedded guest interpreter and isolation
requirements before considering it even for trusted actors.

An independently collected group may never leak raw mutable managed references
to another heap. Messages must be bounded and copied, frozen, or transferred
through explicit native region capabilities and generation-fenced ownership.
Ensure cancellation and host callbacks cannot retain crossed references.

### Proposed user API (illustrative only; not compiling today)

```oreslang
val pool = ActorGroup.pool(
    actors: 4,
    heap_backend: HeapBackend.GraalIsolate,
    gc_policy: GcPolicy.maintenance_windows(
        soft_limit_bytes: 128 * 1024 * 1024,
        hard_limit_bytes: 160 * 1024 * 1024
    )
);

await pool.warm();
await pool.begin_hot_window();  // preference only; NOT "GC off"
await pool.end_hot_window();
await pool.quiesce();           // active turns, I/O and leases all settled
await pool.gc.request();        // best-effort if isolated backend supports it
```

User control would mean choosing workload maintenance windows, pooling/reuse,
memory ceilings, bounded cleanup and pool rotation, not an unlimited GC
suppression promise or untrusted access to JVM System.gc().

### Rotating independent hot/warm pools

1. Provision at least two real independent collector domains, with groups A/B.
   Prewarm code and actor state, pre-fault memory and prepare bounded rings.
2. A runs HOT while B stays ready. Its control/GC threads should not run on
   A's dedicated HPC physical core. No raw JVM references cross the boundary.
3. At a batch end or soft memory threshold, stop admission to A and route new
   input to B under an atomic supervisor-owned generation/lease transition.
4. Drain A's actor turns and pending Futures, I/O and DMA leases. In a
   separate process, teardown and recreate the old heap is deterministic.
   For Polyglot isolates, only use verified backend-specific safe teardown/
   scoped GC facilities, not speculative System.gc() interop.
5. Return A to warm standby. On hard memory budget, unavailable standby,
   failed drain or expired watchdog: backpressure/reject work, not a silent
   downgrade to unbounded heap growth or a forced JVM-wide collection.

This may reduce *observed* pauses during a typical serving window but does
not promise they cannot occur on Graal, nor eliminate OS scheduling jitter.

### WIP source preflight

HpcActorGroupGcPlan is a *pure planner*: heap kinds SHARED_JVM_HEAP,
GRAAL_POLYGLOT_ISOLATE, DEDICATED_WORKER_PROCESS and NATIVE_REGIONS; policies
VM_DEFAULT, MAINTENANCE_WINDOW, ROTATE_GROUPS and DETERMINISTIC_NO_GC.

The planner requires a trusted supervisor for non-default policy, independent
physical heaps for scoped GC, two pools for rotation, complete quiescence,
and soft/hard memory admission. It reports only decisions: VM_OWNS_GC,
REJECT, DRAIN_FIRST, REJECT_NEW_WORK, BACKEND_NOT_IMPLEMENTED. It has **no**
System.gc call, no GC disable flag, no isolate builder, and no resource grant.
No current ActorGroup runtime path calls it.

Integration gate: provision one verified isolate/process per group; reconcile
membership with lifecycle and actor code ownership; guarantee bounded handoff
and safe drain; implement an isolate-scoped adapter if supported; instrument
both host and isolate pause histograms, and test memory pressure and eventual
automatic GC. All FaaS/mobile/untrusted actor behavior remains unchanged.

## Rust Pin/Unpin lessons for Oreslang async state and HPC (WIP)

Related Rust primary references:
- [Rust std::pin](https://doc.rust-lang.org/std/pin/)
- [Rust Pin](https://doc.rust-lang.org/std/pin/struct.Pin.html)

The essential Rust problem is not "all futures must live on the heap".
A compiler-generated async state machine *can* hold a borrow into its own
storage across an await. Such a native frame becomes **address sensitive**
once its internal pointers have been formed. Rust's Future::poll takes
Pin<&mut Self> so an address-sensitive future is not moved after pinning.
The guarantee starts when the value is pinned, **not only after the first
poll**; the first poll may establish self-references that rely on it. Pin
pins the *pointee*, not the pointer/Box handle. Rust's Unpin auto-trait
says moving the pointee is harmless; generated async futures are typically
!Unpin even when a particular future has no self-reference. Stack pinning
can avoid Box::pin allocation. The compiler/safe API must also uphold
structural pin projection and destructor/drop validity.

**Oreslang should adopt the lifetime invariants rather than require all
users to write Rust's Pin<T> API.** In particular, thread/core pinning
for Hungry Actors and *address* pinning for native frames are unrelated.

### Rust stack/local pinning and nested async frames

Primary references:
- [`std::pin::pin!`](https://doc.rust-lang.org/std/pin/macro.pin.html): unlike `Box::pin`, local pinning does not require a *new* allocation. Locals that cross await inside an async function may reside in its **enclosing future frame**, itself possibly heap allocated.
- [Tokio `pin!` source](https://docs.rs/tokio/latest/src/tokio/macros/pin.rs.html): moves the value into its final local binding before constructing the pinned borrow, then shadows direct access to prevent relocation in safe Rust.
- [`futures::pin_mut!`](https://docs.rs/futures/latest/futures/macro.pin_mut.html): soft-deprecated in favor of standard library `pin!` (available since Rust 1.68).

For an identifier, Tokio uses the following conceptual shadowing sequence:

```rust
let fut = async_work();   // may be moved before it is pinned
let mut fut = fut;        // move into final local place
let mut fut = unsafe {
    std::pin::Pin::new_unchecked(&mut fut)
};
```

The macro encapsulates carefully audited unsafe implementation details;
users should normally prefer `std::pin::pin!`. Shadowing is **not by itself**
a lifetime guarantee: the compiler must also ensure the pinned owner cannot
be moved, replaced or destroyed before the pinning borrow is finished,
including in-place destruction of any pinned fields.

The pointee guarantee starts **when pinning is established**, not at the
first poll. An `Unpin` pointee can safely move; futures from async functions
are usually `!Unpin` even if not every specific future is self-referential.
The pointer or Box *handle* is still movable while the pinned pointee remains
in stable storage.

**Crucial Oreslang distinction:** a child locally pinned inside an async
function may be an inline field of the parent compiler-generated state
machine. If the parent has been boxed or placed in a native actor arena,
the child's local slot lives there, **not necessarily on the physical
carrier thread's call stack**. Parent/child pinning and ownership must
survive suspension even if the logical actor resumes on another OS thread.

| Placement | Per-child separate allocation | Constraint |
| --- | --- | --- |
| Synchronous stack-local | No | Cannot survive function return/unwind |
| Nested in enclosing async frame | Not required | Stable owning parent frame while address-sensitive child is live |
| Preallocated actor arena | No per-operation allocation when capacity suffices | Pinned region lease and checked frame/slot size; arena not reset while children live |
| `Box::pin` standalone future | Typically yes | Independent owner whose handle moves while pointee remains stable |

The desired future LLVM/native lowering in Oreslang should:
1. Place nested async state-machine frames into statically laid out
   parent-frame/actor-arena slots instead of boxing every child.
2. Keep purely offset-based frames relocatable, with validated region
   generation and address/size; do **not** create raw interior pointers.
3. Establish native address-stable ownership **before** any operation can
   create self-references. Respect pin projection and in-place drop.
4. Reject borrow escape to another actor, isolate or caller's expired
   scope. Avoid exposing JVM object addresses in the Graal backend.
5. Keep actor-group rotation blocked while a parent, child, waker, native
   borrow, DMA lease or external callback still retains the old heap.
6. Treat `no additional child Box` as different from `zero heap allocations`:
   futures, executors, JNI and Graal may allocate for unrelated reasons.
   An HPC strict build must verify allocations transitively or fail.

`HpcLocalPinPlan` + `HpcLocalPinPlanTest` now cover synchronous local,
nested parent-frame, actor arena and independent storage; invalid parent
pinning, borrowing beyond scope, moving a pinned value, missing in-place
drop, and pending wake/lease before group retirement. They are **inert
validation**, not a compiler macro, a physical native pin, a memory
allocator, or a new guest language feature.

### Actual current Oreslang scheduling

OresScheduler.Task is a resumable logical state machine. It returns Await,
Cooperate or Done; after await, a different carrier can resume the logical
task under the same scheduler/domain. The scheduler's TaskRunner has a
single-execution lease and registers a detachable OresFuture completion
waiter; the I/O/producer callback makes the task ready, but must never run
guest continuation code inline. A fresh dispatch only occurs after the
previous actor/carrier turn fully unwinds.

This existing model **does not require** an address-stable JVM heap object:
Java/Truffle managed object references remain JVM-managed even if GC moves
them. It also does **not** justify exporting JVM object addresses or
native self-referential pointers to FFI. Current compiler semantics reject
ordinary borrowed receivers live across await; this is intentional until
the compiler can prove a suspension-safe owner/lifetime. Oreslang actor
mailbox serialization and the actor-wide execution lease are unchanged.

### Three future frame-storage representations

| Kind | Allowed relocation | Required safety rule |
| --- | --- | --- |
| GRAAL_MANAGED | JVM/runtime may move underlying Java objects | No raw JVM object pointers; use stable logical task identity and managed references; keep current borrow-across-await checks |
| NATIVE_RELATIVE_OFFSETS | Region/frame may move or be remapped after coordination | Internal references are checked offsets/handles, not raw native self-pointers; region generation, bounds and ownership validated after move |
| NATIVE_ADDRESS_SENSITIVE | Cannot move while address-sensitive state is live | Pinned owning region lease, no move/swap/compaction/reuse before completed drop/cancellation and all native/FFI borrows detach |

"Address-stable" is a memory property, not a Java Pin<T> marker.
Moving an arena *handle* is fine if its native pointee remains stable.
Pinning only some fields must respect projected pinned fields and never
replace them by swapping the containing frame. If native code holds an
interior pointer, it must be revoked/cleared before releasing the buffer,
including exception, cancellation and force-stop paths. DMA-pinned buffers
add device fencing and are not equivalent to task-frame pinning.

**Across await:** a borrow of an actor/local frame may survive only when
a future compiler proves that the owner remains live, the reference is
valid during suspension, and the frame is either address stable or the
borrow uses a relocatable checked offset. No borrow may escape to another
actor or heap simply because the group runs trusted code. In a JVM/Graal
backend, preserve today's stricter no-live-borrow-across-await semantics
until the checker and lowering can support a safe narrower exception.

### Cancellation, wakers and group rotation

A task can be quiescent on its carrier **yet still retain its entire heap**
via an awaited future, suspended frame, detached-or-not waiter, or foreign
I/O/DMA lease. Therefore a group is reclaimable only when all of the
following have reached zero or a terminal reclaimed state:

1. Executing actor turns and queued continuations; only actor-owned
   scheduler entry may resume guests.
2. Suspended native/managed frames and the entire recursively reachable
   suspension state graph, including pointers/borrows into group arenas.
3. Await/select/channels/I/O waiter registrations, pending completion
   publication, and any callback retention of the old group.
4. Native/FFI borrows, region leases, shared region memberships, NIC DMA,
   and outstanding cancellation/cleanup handlers.

The "zero counters" snapshot must be **linearized against new admissions
and wake publications**: seal old-group admission, change the active group
generation, detach/cancel registrations, then wait until every active task
and event source confirms quiescence. Never infer safety from counters read
independently during concurrent completion. A late wake must carry
(group ID, generation, task ID, registration sequence), be compared
against the current owner and discarded on mismatch. A stale waker must
never reanimate a canceled frame or a slot recycled under a new generation.
Closing only the actor mailbox does not automatically make the Future's
external producer or all its callbacks disappear.

Hot/warm A/B pool handoff must continue to support *existing logical task
semantics*: transfer a task only if its state is sendable and no pinned/host
reference crosses the heap; otherwise drain/cancel it in the old group
while new independent work goes to B. If a native frame was pinned,
do not unpin/compact/move it merely because its task was suspended.
Prefer bounded graceful drain with timeouts and supervised force cleanup
as a distinct unsafe/emergency phase. Force-stop may reclaim only after
kernel/device and outstanding read/write leases are fenced.

### Implemented only as validation scaffolding

- HpcAsyncFramePlan: deny-activation planning for managed vs checked-offset
  vs pinned native frames, borrow-across-await, illegal relocation,
  cross-actor reference escape, and generation-fenced group retirement.
- HpcActorGroupGcPlan: now counts **suspendedFrames** and
  **registeredWakers** in addition to activeActorTurns, pendingCompletions
  and externalLeases; these are independent reasons to delay retirement.
- JUnit tests cover invalid native pin attempts, managed-pointer misuse,
  safe-shape relocatable offsets, actor-boundary escapes, cancellation /
  stale-waker fencing requirements, and GC rotation while suspended.

No native Pin type, compiler lowering, task-generation registry, waker
revocation, per-group isolate allocation, actual GC controls or live task
migration is enabled by this PR. Proving the real executor still requires
race/model checks for lost wakeups, ABA slot reuse, multiple completion,
cancellation vs wake, JIT/GC induced safepoints, and destruction order,
plus physical native/GraalVM backend tests.

## Async-frame compiler enforcement and ActorGroup generation fencing (WIP)

The current source compiler `OwnershipChecker` already rejects an ordinary
borrow live across `await` and `rt cooperate`, and forbids storing borrowed
values in actor/class fields or sending them through actor mailboxes. This
PR extends that *actual compiler check* to every **blocking** channel read,
channel write, static select and dynamic select (blocking operations yield
the continuation just as await does). Immediate probes do not suspend.
Nonblocking channel/select registration does not suspend the calling frame;
its own retained payloads and captures need separate ownership validation.

No new guest-facing `Pin<T>` or `Unpin` syntax is enabled. Compiler-proven
native pinned frame projection, borrow relocation and in-place native drop
are still a separate future LLVM/native lowering requirement. The Graal
compiler should keep its conservative prohibition on ordinary cross-await
borrows instead of pretending it has native location-stability enforcement.

### Runtime scheduler: fenced suspension epochs

`OresScheduler.TaskRunner` now increments a wake epoch when it suspends and
when it resumes or is cancelled. An awaiting `OresFuture` registration
captures an immutable epoch in a dedicated callback. The callback may only
publish a resume when the epoch still matches and the runner remains WAITING.
A stale callback from a prior await must NOT satisfy a later await; callbacks
running after cancellation must NOT resurrect task state.

The already-completed Future hot path remains a direct immutable-state
observation without allocating a subscription. Pending Futures already had
detachable runtime registrations; this change additionally allocates one
small per-pending-await epoch callback to avoid sharing a reusable, untagged
callback across suspensions. Do not call this path no-allocation or zero-GC.
The future native backend can combine callback metadata into a preallocated
generation-aware waiter slot instead.

`ActorGroupWakeRegistry` is a real runtime-internal bounded ticket registry,
not a guest-visible authority. A ticket contains an unforgeable registry
identity, owner identity, group epoch and monotonic sequence, and transitions
PENDING -> CLAIMED -> RELEASED. Claim is atomic with group seal/owner revoke;
claimed queued continuations remain *retirement roots* until released. Old
tickets must be rejected on close/revoke/leave; repeat claims and cross-group
replays are rejected. Registration has a hard budget linked to the owning
ActorGroup creator policy, avoiding unbounded retained Future/waker memory.

`ActorRuntime.ActorGroup` now owns one registry. Member leave and actor
termination revoke its tickets; group close seals all registrations while
revoking join capabilities. The runtime exposes guarded
`pendingContinuationWakeCount()` diagnostics and internal registration/
claim/recheck/release hooks.

**IMPORTANT integration boundary:** The general `OresScheduler` now fences
its own await callback epochs, but existing ActorGroup source tasks are **not
yet automatically registered** into the group's wake registry. Consequently,
neither registry ticket count nor the GC planner can be claimed to prove
whole-group quiescence today. The remaining source/runtime integration must:
associate each compiler-generated actor coroutine with exactly its owning
group generation; register/detach its await/select/channel waiters; count all
queued and suspended frames; and perform a second ticket/actor lease check
during mailbox scheduler admission. Group rotation must seal admission,
drain existing tasks and DMA/FFI leases, and only then recycle a heap.

Scheduler regression tests now manually replay an obsolete producer callback
during a *second* pending await and after task cancellation. Registry tests
cover replay, group close, actor revoke/rejoin, pending/claimed accounting,
hard admission budget and concurrent seal/claim races. Compiler regression
tests exercise owned versus borrowed frames at blocking channel/select
suspension boundaries.

## Cyclic object graphs, ownership and arena reclamation (WIP)

Rust primary references:
- [Rust Book: reference cycles and Weak](https://doc.rust-lang.org/book/ch15-06-reference-cycles.html)
- [generational-arena (slots + generations)](https://docs.rs/generational-arena/latest/generational_arena/)
- [Rust Pin's drop guarantee](https://doc.rust-lang.org/std/pin/)

Rust's reference-counted strong cycles leak because the strong counts
never reach zero. `Rc<RefCell<T>>` is non-thread-safe and its checked dynamic
borrows may panic on invalid nested borrows; `Arc` coordinates shared
ownership across threads but does not detect cycles. A backpointer that
does NOT express ownership should be weak. `Weak::upgrade()` may fail once
the owner is gone. Immovable raw-pointer structures can model cycles but
place aliasing/teardown proof into `unsafe`; pinning does NOT solve strong
ownership cycles.

Oreslang already has *separate, unmerged* reference work:
- PR #457: guarded recursive type aliases; recursive values are still
  subject to ownership / actor transport.
- PR #468: actor-local `Ref<T>` / `WeakRef<T>` cyclic owned cells, stacked
  on #457. Never silently copy that implementation into this `main`-based
  HPC branch. Reconcile the two independently tested stacks later.

### Bounded, generational graph owned by one arena

The new `HpcCyclicGraph<T>` is a **Java reference prototype** of the
future native region-owned HPC graph. One arena exclusively owns all node
values. Edges store non-owning (slot, generation) targets. Nodes can
reference themselves, two nodes can point at one another and arbitrary
long cycles may be created without allocating any Rc/Arc counters.
On slot removal the generation increments, so stale weak-like links do
not resolve to a new occupant of the same slot (ABA). A graph identity
token prevents handle mixing between arenas. Repeated clear also changes
slot generations; close clears all values without recursive graph walking.

Example conceptual Oreslang syntax, **not compiling yet**:

```oreslang
val graph = GraphArena.new<Node>(capacity: 1024, edges_per_node: 8);
val parent = graph.add(Node("parent"));
val child = graph.add(Node("child"));
graph.connect(parent, child); // non-owning, generation-checked
graph.connect(child, parent); // cycle is okay
val maybeParent = graph.resolve(parent); // Option<Node>, None when stale
graph.close(); // only once all async, FFI, actor and DMA leases are drained
```

This prototype uses Java managed objects, synchronized methods and
allocating snapshots for some read paths, so it **does not** deliver no-GC,
lock-free operation, direct native address stability or portable high
frequency trading latency. Source-level actor confinement, lifetime-safe
borrows, atomic region retirement and a native/LLVM packed representation
must be verified before exposing it to userland or allowing group GC
control. Values held by external guest references or FFI may still have
their own lifetimes after arena close; source/runtime must refuse
dangling arena-backed native references.

A native compiler may choose between:
- `Ref<T>` + `WeakRef<T>` for ownership-aware local identity graphs;
- arena+generational indices for cyclic high-degree, DOP/SOA,
  cache-sensitive graphs whose **arena** owns the entire lifetime;
- an explicitly reviewed unsafe intrusive-pointer implementation only
  where stable addresses, pinned drop and full aliasing proofs are needed.

**Critical integration**: never move/pin/retire an arena with active
native interior pointers, suspended frames, pending wakers, actor mailboxes
or outstanding NIC DMA. Neither a cyclic graph nor a retained Future is
automatically safe to cross ActorGroup or isolate boundaries.

## Region and arena memory semantics

Proposed language-facing constructs (illustrative **non-compiling** syntax):

```oreslang
@TrustedHpc
@NoHeapAlloc
@Layout(kind: "SoA", align: 64, endian: "little")
define struct MarketBatch as
    prices: FixedArray<int64, 1024>;
    quantities: FixedArray<int32, 1024>;
end

// Every operation is bound to a supervisor-provisioned arena lease.
// Allocation happens at bootstrap, not during the hot loop.
val arena = HpcArena.from_lease(lease);
val quotes = arena.alloc<MarketBatch>();
defer quotes.close();
```

Required compiler/runtime invariants:

1. Memory regions are arena-owned and borrow-checked. A value/borrow cannot
   outlive the region; no stale generational handle may be used after release.
   No raw address escapes to actor messages, stream subscriptions, closures,
   reflection, FFI or another privilege domain.
2. Arena creation reserves a hard budget and pre-allocates all pages during
   initialization. Hot operations use preallocated cells only. Close/drop
   deterministically releases leases, deregisters DMA and frees memory after
   outstanding users complete. Cancellation and crashes must not cause reuse
   while DMA is live.
3. NUMA affinity is **physical**, not just an actor scheduling lane. Discover
   allowed CPUs/nodes, bind thread and page allocation, fault/touch every page,
   verify actual placement; fail closed on denied/offline nodes and migration.
   Do not infer actual NUMA locality from an integer request alone.
4. Memory layout is explicit for FFI/on-wire data: fixed width, padding, array
   length, representation (AoS/SoA), alignment and endianness, with compiler
   generated size/offset assertions. Misaligned packed loads need explicit
   handling. No references to JVM objects in DMA buffers.
5. Cache-line size must be probed or specified with evidence; 64 B is only a
   common default. Independent producer/consumer counters occupy separate
   cache lines. Avoid false sharing of per-queue sequence state.
6. Existing actor-local Ref<T>/WeakRef<T> is **not** a DMA, shared-memory,
   atomic, off-heap or trans-actor capability.

Native backing should favor a Rust library with safe owning leases over
unrestricted raw pointer exposure. Placement mechanisms may include
`mbind`/`set_mempolicy`/libnuma, huge pages, `mlock` and pre-touch, but
only after platform-specific validation; allocations must not silently migrate
or fall back to a different node. The present Java planning class computes
offsets and rejects overflow; it does **not** allocate aligned memory.

## Native bounded rings and scheduler coupling

Implement and prove independently:

- SPSC: one producer and one consumer, wrap-safe bounded head/tail indices,
  acquire/release publication, separate cache-line padded counters.
- MPSC: multiple producers reserve distinct slots (CAS or tickets), publish
  independently with sequence state, one consumer, bounded backpressure.
- MPMC: only if real demand justifies additional complexity; per-slot sequence
  numbers, memory reclamation/ABA/wrap tests and documented progress guarantees.

A successful try_push/try_pop must not allocate, block or enter GC on the native
profile. Provide distinct error/status results for FULL, EMPTY, CLOSED and
LOST_LEASE; no blocking, hidden spin forever, or drop-on-full. Spinning must
consume an explicit budget; on budget expiry either propagate backpressure or
schedule off the critical path. Set a practical upper bound on queue capacity.

Every element must be a verified trivial fixed-layout value, or an opaque
owner/lease handle with tested lifetime and generation semantics. Do not place
Java object references, runtime callbacks, borrowed pointers, actor Ref<T>, or
GC-managed graphs into a raw ring.

**Important:** the current OresChannel select implementation uses a coordinator
lock and ArrayDeque; atomic selection semantics do not imply a lock-free ring.
This profile must not globally replace channels or actor mailboxes, because
select, wait registration, cancellation and fairness require separate protocol.
The fast path may coexist with a slow control path, after tests.

## NIC / packet path: two different kinds of "bypass"

- **AF_XDP (Linux):** accelerated packet data path via XDP and UMEM; the Linux
  kernel still sets up and mediates resources, depending on driver and mode.
  Confirm zero-copy support instead of assuming it.
- **DPDK + VFIO / poll-mode driver:** user-space NIC queue access after
  privileged setup and driver binding; grant only specific PCI functions /
  queues, with IOMMU, memory pinning, physical isolation, DMA bounds, exclusive
  ownership, reset/recovery and tenant restrictions. Never bind a shared
  production NIC on behalf of guest code.
- **RDMA verbs:** NIC-assisted zero-copy for appropriate transports, with
  registered memory and rkey/lkey revocation; not equivalent to arbitrary raw
  Ethernet packet access.
- Ordinary sockets and io_uring: valid throughput baselines, **not** direct
  kernel bypass.

The supervisor allocates all buffer memory and grants a generation-scoped
`NicQueueLease` to the trusted data plane; no actor may open VFIO or raw NIC
resources from user source. The control plane must revoke leases, quiesce RX/TX,
fence DMA, reclaim huge pages and enforce crash recovery before reassigning
queues. Separate secrets, management network and telemetry from polling.
NIC descriptor rings have alignment and device-specific memory-order contracts:
CPU-only Java atomics are not enough to prove DMA coherency.

## Compile-time control and reproducibility

Proposed annotations (syntax and exact names subject to language review):

- `@TrustedHpc`: compile-time request only; no authorization grant.
- `@NoHeapAlloc`: compiler-verified transitive hot-path property, including
  callees, allocation through generics, exceptions, logging and async closures.
- `@NoRuntimeCodegen`: reject builds with guest JIT, OSR, deoptimization
  recompilation or dynamic code loading in that path; cannot be guaranteed by
  an unverified Truffle/JVM annotation.
- `@Layout(...)` / `@Align(...)`: explicit ABI layout, checked and manifested.
- `@PinnedCore(...)` / `@NumaNode(...)`: deployment requests verified by
  the host; no implicit scheduler identity or portability assumption.

Stages:

1. Today: keep Graal/JVM profiles for semantics and integration tests, measure
   hot allocation, JIT/OSR and GC. The existing native-image `-Pnative-aot`
   target AOT-compiles the host but does not by itself satisfy strict guest AOT.
2. Native host experiment: isolate a Rust-owned off-heap data plane while
   Truffle remains on a separate management/control path. Strict latency
   guarantees still require proof that no managed callbacks or runtime JIT
   execute on the data plane.
3. Future LLVM lowering of a constrained Oreslang subset: verified MIR,
   ownership/region passes, frozen optimized IR, static machine code, stable
   symbol ABI, deterministic linked libraries. Reject unsupported reflection,
   hot reload and dynamic dispatch rather than silently interpreting/JITing.
4. Keep hot reload and dynamic optimization available in non-HPC profiles;
   trusted HPC is an explicitly different executable/deployment contract.

A provenance manifest should record source/compiler/LLVM backend hashes,
target architecture, codegen options, CPU features, layout schema hashes,
static binary hash and required hardware/OS controls. Hash identity, not
annotations, is the basis for code freeze verification.

## Validation matrix / merge blockers

The PR is WIP until the following independent proof gates are green:

- **Security:** untrusted guest/source annotations/actor kind/FFI/import cannot
  obtain native regions, NUMA control, DMA leases or driver file descriptors;
  denied mobile/FaaS/other-tenant cases; hostile cancellation and revocation.
- **Memory:** compiler rejects use-after-free, escaping borrows and
  reference-bearing DMA records; ASan/Miri where supported; double release,
  shutdown while DMA outstanding, page accounting and reservation overflow.
- **Concurrency:** randomized interleavings; long-running SPSC/MPSC wraparound;
  cross-core acquire/release litmus and TSAN where applicable; producer crash
  after reservation; full/empty/close/ABA/backpressure behavior.
- **Placement:** verify actual bound core and NUMA page placement under
  cpuset/cgroup restrictions, IRQ noise, unplug/hotplug, migration and budget
  exhaustion.
- **NIC:** dedicated hardware with VFIO/IOMMU or AF_XDP capability checks;
  verified zero copy, RX/TX correctness, packet drops, DMA fault/revoke paths,
  exclusive queue ownership, no multi-tenant NIC sharing.
- **Codegen:** disassemble hot paths, monitor allocations/GC/JIT/OSR,
  reproducible binary hashes and no runtime guest compilation in strict mode.
  Bare metal latency baselines (median, p99, p99.9, p99.99/max) must include
  CPU model, frequency/power mode, NIC model, PCIe, OS/kernel, topology,
  traffic mix, message size and run length.
- **Compatibility:** JVM/Truffle reference, native-image AOT/interpreter,
  hybrid, sandboxed private/untrusted actors, FAAS and mobile stay intact.

### PR deliverables in this first slice

- This RFC.
- An *inert* Java planner for cache-line/arena offsets and restrictive
  preflight diagnostics; it grants **no** capabilities and performs no NIC I/O.
- JUnit tests for invalid profiles, alignment, capacity and integer overflow.

No existing production path calls the planner. Do not advertise this PR as
shipping zero-GC execution, hard real-time, direct NIC I/O, NUMA binding,
guest LLVM-AOT, or a lock-free queue.
