# Oreslang topology, data-layout, and execution contract (proposal v1)

Status: design/validation gate only. Nothing in this document enables guest syntax,
new OS scheduling policies, or supervisor CONTROL ingress. Base: draft #1480.

## Independent dimensions

1. **Record layout**: AoS, SoA, AoSoA (tile length validated as positive and bounded).
2. **Storage authority**: a single canonical mutable representation; alternate layouts
   require an exclusive migration barrier or an explicitly versioned immutable snapshot.
3. **Execution ownership**: actor incarnation + allocation generation + loan lifetime.
4. **Work placement**: preferred worker, LLC domain, NUMA node, allowed CPU set.
5. **Execution kind**: actor turn, task, callback, continuation, supervisor CONTROL.
6. **Execution constraints**: actor concurrency permits, TaskScope physical-quiescence,
   trust-domain admission, priority fairness, cancellation and code version leases.

Changing one dimension never implicitly grants permission in any other dimension.

## Placement algorithm (normative relative preferences, not hard pinning)

- At startup, enumerate OS-visible **effective** eligible CPUs (including cpuset /
  cgroup restrictions), real topology if available and runtime worker budget.
- Have a bounded, long-lived pool of pthread carriers, with distinct protected
  CONTROL capacity; no new pthread per actor, Task, callback or continuation.
- Assign each logical runnable a preferred worker, last successful worker and
  memory-home NUMA hint. Logical identity MUST NOT equal pthread ID.
- If the preferred worker is eligible and healthy, enqueue there. Otherwise prefer:
  same LLC, same NUMA, then other permitted workers. Approximate topology and
  SMT-aware policies are advisory; never treat an unknown cache hierarchy as fact.
- Stealing is bounded and starvation-aware. Allow spill on queue pressure, expired
  latency budget or parked/stalled worker; stealing may not bypass actor single-
  execution / configured max-execution leases or lifecycle-group turn permits.
- A resumed continuation may run on another worker only after preserving its
  TaskScope ownership, actor incarnation, active loan state, code-version lease,
  cancellation domain and execution permissions.
- ThreadBound or other native TLS-bound resources require a valid carrier affinity
  constraint; the scheduler MUST refuse unsafe migration (or explicitly marshal
  to the owner), not silently rebind a resource.
- Worker shutdown/hotplug/cpuset changes remap only at safe handoff points and
  drain or requeue outstanding work without dropping accepted CONTROL events.
- `rt cooperate` yields a logical turn, **not** an OS sched_yield() guarantee.

## Data storage

- `define inverted class` denotes eligible columnar representation, not mandatory
  SoA. `InvertedArray<T>` selects AoS, SoA or AoSoA independently of type identity.
- A stable handle includes slot and allocation generation. Dense swap-deletion
  rewrites indexes atomically; no stale handle aliases a recycled slot.
- Field-sensitive borrow claims can be shared only where row/field/range disjointness
  has been proven; unknown alias, layout conversion, and live loan => fail closed.
- Never maintain multiple simultaneously writable canonical layouts.
- Worker/NUMA sharding should partition **runtime metadata**, not transfer
  actor-private heap authority to a worker. Actor state remains actor-owned.
- Storage policy must work for ordinary non-actor data as well as ActorGroup,
  supervisors, Tasks, Futures, timers, mailboxes, registries and connection tables.
- ECS-style systems declare READ/WRITE footprints, chunk ranges and constraints;
  build a conflict dependency graph. READ/READ may overlap; any WRITE conflicts
  with overlapping READ/WRITE; schedule disjoint chunks concurrently.
- A static proof of nonoverlap avoids payload locks, but does not abolish
  scheduler queue atomics, scope barriers, I/O wakeups or reference lifetime work.

## Scheduler admission protocol

1. Validate trusted provenance, actor/supervisor identity and incarnation.
2. Validate Send/Sync eligibility and canonical allocation generations.
3. Validate disjoint read/write regions and acquire required actor/group permits.
4. Reserve bounded worker queue capacity *atomically* with logical admission.
5. Transfer/move or establish scoped loans, then enqueue runnable token.
6. At completion/cancellation, unwind guest cleanup and release loans and permits
   exactly once, **after** physical quiescence.
7. At every resumption, revalidate incarnation, code generation and placement
   restrictions before entering guest code.

Do not consider a queued Task quiescent; cancellation request != completed unwind.

## Supervisor/child CONTROL boundaries

- CONTROL messages are runtime-authenticated, not caller-supplied error envelopes.
- Bind child, parent supervisor, incarnation, trust domain and explicit capabilities.
- Maintain bounded, reserved CONTROL capacity and fair service so normal traffic
  is not starved; perform mandatory parent/root escalation on overflow.
- Supervisor metadata may migrate layouts, but accepted CONTROL events retain
  ownership, exactly-once credit settlement and ordered lifecycle semantics.
- Parent-issued control operations are also authenticated/incarnation-fenced.
- A supervisor never gains direct write access into a child-private heap merely
  by owning the child.

## Operating systems and deployment

- Linux affinity via pthread_setaffinity_np or equivalent is applied to **carriers**;
  process allowed CPUs and container cpusets are the upper bound.
- macOS affinity tags are locality hints, not Linux-like absolute CPU pins.
- Portability default is no hard affinity, ordinary host scheduling.
- SCHED_OTHER is default on Linux. Real-time SCHED_FIFO/RR/deadline-like
  modes require explicit trusted host configuration, bounded execution and
  recovery; never expose guest authority to choose arbitrary host priorities.
- Freestanding `core` describes policy/ownership contracts without importing
  pthreads, POSIX, topology discovery or allocators. Native host adapters live
  in opt-in stdlib/runtime layers. LLVM native zero-GC must remain possible.

## Validation matrix / release gates

Correctness:
- AoS/SoA/AoSoA all 3x3 migrations with stable handle/generation checks.
- Concurrent field-disjoint borrows accepted; overlapping claims rejected.
- Loan active during conversion, cancellation, panic and hot reload => blocked.
- 10k concurrent child failures: all accounted for or mandatory escalation.
- Stale child/Task incarnation, forged CONTROL, cross-actor heap access rejected.
- Callback captures and continuation migrations retain loans and code leases.
- Hotplug and effective-CPU-set reduction do not lose accepted work.
- Two/three active actor turns allowed **only** with explicitly configured
  max-execution permits and state access via verified critical loans.
- Enforce bounded queues, control fairness, cancellation and wakeup liveness.

Performance:
- Compare 1k, 10k, 100k, 1M records at 1%, 10%, 100% runnable fractions.
- Separately measure kernel switches, logical handoffs, CPU migrations, LLC
  misses, NUMA remote reads, queue contention, allocations, throughput and
  p50/p95/p99 latency. Include cache-hot, cache-cold and adverse load imbalance.
- Compare pinned, soft-affinity, and unpinned baselines; require repeatable
  measurements before enabling any default affinity or tile-size policy.
- Exact-tree Java/Graal and LLVM guest tests must execute. Static checks,
  parser-only tests, zero-step Actions failures and standalone reference
  tests are not evidence of production runtime parity.

## Integration dependency order

A. Land trust-bound CONTROL routing and the supervisor integration gates of
   draft #1480. B. Recover scoped task access-checker semantics from closed
   draft #1448; do not silently claim it merged. C. Reconcile affinity draft
   #301 with global carrier budget, topology-awareness and execution permits.
D. Generalize the tested supervisor table to typed LayoutStore/InvertedArray
   with the three physical layouts. E. Integrate guest lowering and LLVM parity.
F. Add measured dynamic ECS conflict graph, callbacks/continuations, and NUMA
   optimization behind opt-in flags until conformance is demonstrated.
