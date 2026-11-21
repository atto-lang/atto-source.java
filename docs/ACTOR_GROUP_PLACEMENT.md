# ActorGroup CPU/GPU placement: admission contract (stage 1)

## Status

The baseline policy type is immutable and host-side. This stacked stage
adds **runtime-enforced `maxParallelTurns` limits** through a bounded FIFO
nonparking group gate for lifecycle-owned actors. `CpuAffinity.ANY` with
`Accelerator.CPU_ONLY` admits either bounded or unrestricted turn capacity.
GPU or physical/NUMA locality claims still fail closed **before** group
creation. Source-language group placement remains unavailable.

The backend's existing multi-carrier actor scheduling remains active. These
rules do not enable source `cpu {}` / `gpu {}` blocks, a GPU driver,
NUMA-aware selection, or per-group scheduler permits.

## Design

An OresVM process owns the scheduler/carrier domains; **an ActorGroup never
owns an OS thread, executor, Graal isolate, or dedicated worker pool**.

A group is a cooperation membership domain. An actor may belong to multiple
groups while retaining a single supervisor/lifecycle owner. Membership alone
must not change that actor's heap, runtime scheduler, security domain,
lifecycle authority, CPU affinity or GPU capabilities.

Future group-directed placement must resolve the actor's **single lifecycle
owner placement policy**. Other cooperation-group preferences cannot override
hard limits or grant access to a device. Conflicting hard constraints reject
admission; soft hints are advisory and fairness-bounded. Every actor turn
still runs through the domain's original ActorRuntime execution lease.
Stealing must never cross PRIVATE/SHARED/UNTRUSTED/CONTROL boundaries.

### Types (host only)

- `CpuAffinity.ANY`: no group constraint.
- `CpuAffinity.PREFER_SAME_CORE` and `PREFER_SAME_NUMA_NODE`: reserved,
  rejected until scheduler topology-aware placement is effective.
- `maxParallelTurns`: number of **simultaneous physical actor turns**, not
  actors, memberships, mailbox messages or OS threads. Values below
  `Integer.MAX_VALUE` are admitted by the runtime-owned `ActorGroupTurnGate`
  and enforced before dispatch. A permit is released after the entire
  physical turn exits its TurnExecutor/Truffle boundary. A suspended source
  continuation releases its carrier only after that same physical exit.
- `Accelerator.CPU_ONLY`: default.
- `GPU_PREFERRED` / `GPU_REQUIRED`: require a validated device ID plus a
  positive in-flight limit, but remain rejected at group admission until
  resource brokers implement them. `GPU_REQUIRED` MUST NOT silently
  fall back to a CPU implementation.
- `gpuDeviceId`: opaque constrained identifier for future broker selection;
  never a raw host pointer, file path, PCI authorization token or capability.

### CPU implementation follow-up

1. Discover cgroup/cpuset-visible logical and physical CPU topology once per
   topology change. Respect macOS limitations and Linux allowed CPU masks.
2. Coordinate shared/private/untrusted carrier budgets against the process-wide
   CPU allowance, rather than scaling each domain independently without bounds.
3. The current scheduler-owned gate admits `maxParallelTurns` without
   creating any group threads. Waiting work stays in a bounded FIFO queue,
   and cancellation/close withdraw queued tickets exactly once. Do not allow
   **retroactive adoption of a scheduled/active actor** into a limited group.
   Dynamic `releaseWorker` on bounded groups remains unsupported until
   atomic queued-ticket migration is implemented. No sleeping/busy-spinning
   workers. Continue hardening admission and cancellation race tests.
4. Prefer same-core/LLC/NUMA locality for placement and steals, with guaranteed
   age-based fallback; a hot group cannot starve an older actor.
5. Attribute CPU budgets to actors/source tasks across `await` and
   `rt cooperate`. Untrusted continuations must not refill logical quotas.

### GPU reservation substrate

This draft now includes a package-private `GpuDeviceBroker` reservation ledger.
It atomically accounts both **per-device** memory/in-flight limits and a
**lifecycle-owner** in-flight limit. Device drain/loss immediately stops new
reservations; existing leases remain charged until their provider operation is
physically quiescent. Owner close similarly stops new reservations without
fabricating completion of live GPU work. Broker shutdown rejects while any
lease is live.

The broker deliberately does **not** make `GPU_PREFERRED` or `GPU_REQUIRED`
admissible yet. A broker lease is capacity accounting, not a driver queue,
device handle, command submission, buffer ownership proof or fence. Provider
integration must atomically bind the broker lease to the typed buffer/fence
RESERVED -> ENQUEUED -> terminal state machine and release the lease only
after authenticated completion/cancellation quiescence. Until that path exists,
`ActorGroupPlacementPolicy.requireSupported()` continues to reject GPU policy.

### GPU implementation follow-up

1. GPU affinity selects compute tasks/kernels only. Ordinary actor mailbox
   turns, callbacks and capability checks always execute on a CPU scheduler.
2. Use typed region/buffer ownership, pinned borrows, allocation generations,
   checked layout/size, device/tenant identity, fences and bounded reservations.
3. Launch asynchronously; an actor waiting on a GPU fence releases its CPU
   carrier. GPU completion can enqueue the owning actor continuation, never
   execute guest actor code on a driver callback thread.
4. Account for in-flight jobs and device memory through a trusted broker, with
   cancellation acknowledgement, device loss, fallback policy, and no leaked
   permits. Device capability remains separate from ActorGroup membership.
5. Fail closed for unsupported devices, kernels, layouts and source lowering.

## Security/admission invariants

- Reject unsupported placement **before** allocating an ActorGroup ID, manager
  slot, creator-membership entry or worker/scheduler.
- A malformed policy fails at value construction (negative GPU budget, zero or
  negative parallel turns, inconsistent GPU fields, invalid device id).
- An immutable installed policy cannot be changed by cooperation-group
  membership, joining a room, or an actor carrying an unauthenticated room ID.
- Placement metadata is observer-protected using existing ActorGroup checks.
- Closing room A cannot stop members merely because they are still in room B;
  only the one lifecycle owner can manage/cancel its workers.
- No separate Graal isolate is created by group placement; the pooled untrusted
  isolate architecture stays independent from carrier CPU affinity.
- No successful group-policy construction is evidence of a real GPU launch,
  OS-level binding, NUMA locality, or a per-group parallelism guarantee.

## Current regression gates

`ActorGroupPlacementPolicyTest` checks default behavior, construction
validation, rejection without group leaks, immutable observer projection, and
independent membership/lifecycle semantics.

Before accepting any non-default policy, add deterministic, exact-source
tests for carrier permits, group close/cancellation races, fairness and
physical CPU use across multiple actors; plus GPU broker tests for device
admission and cancellation. Use native sanitizers, Graal JVM/native runs,
stress/fuzz/race testing and backend conformance before exposing source APIs.

Related work: Java PR #216 (soft carrier affinity), Java PR #301 (physical
CPU lanes), Java issue #303 (NUMA topology), Java PR #674 (source ActorGroup),
LLVM PR #203 (native four-domain scheduler), and GPU mapping PR #285.

## Private CI provenance gate

Before exporting *any* source files to a different GitHub repository, enforce
the reviewed, protected-branch allowlist in
`.github/private-ci-approved-repositories.txt`. This file currently has **no
approved destinations**. A repo being private, writable, or in another
organization with available Actions minutes is **insufficient authorization**.
The checked-in `scripts/check-private-ci-destination.sh` and controlled
publisher are the intended transfer path; do not substitute arbitrary GitHub
connector writes or push a tree into a public temporary branch.

The source repository's historical public-mirror exposure is tracked in issue
#832. Deleting a branch cannot guarantee removal of already-written Git
objects, workflow artifacts, caches, or logs. An explicit security-reviewed
destination and verified private visibility are prerequisites, not things to
work around when runners are unavailable.

Local `javac` smoke tests may verify the immutable policy code but **cannot**
substitute for exact-tree ActorRuntime JUnit, Graal or native CI. Keep this PR
draft until those tests run under an approved mechanism.

## Stage-2 implementation and remaining gates

The Java host runtime now routes a managed actor's scheduled mailbox batch
through `ActorGroupTurnGate` before submitting to its unchanged domain
executor. Permit release follows the complete `runBatch` / `TurnExecutor`
exit; the queue cannot directly execute actor code. Single-winner ticket
cancellation and group close are handled without blocking carriers, and
actor-owned groups inherit their parent's lifecycle ownership.

The new `ActorRuntimeGroupParallelismTest` verifies limits of one and two
physical turns, plus rejection of retroactive adoption and unsafe worker
transfer. `ActorGroupTurnGateTest` exercises FIFO, queue bounds, close,
cancellation races, and rejected downstream submissions. These tests
**must run against a real exact-source Java/Graal CI job before promotion**;
standalone smoke tests are not evidence of complete runtime conformance.

Remaining: implement safe quiescent worker migration / transfer, eliminate
underlying carrier oversubscription across domains, exercise cancellation
under native Thread/Truffle context exit, measure fairness under sustained
hot actors, and prove cross-backend LLVM parity. Device placement, soft core
affinity and guest-source lowering are still rejected.

## Race audit and verification addendum — 2026-10-15

The nonparking gate and its runtime integration received a dedicated
cancellation, shutdown and dispatcher-failure audit:

- The actor's pending gate ticket is now published while holding
  `ActorCell.lifecycleLock`. This closes the stop-vs-enqueue window in which a
  WAITING ticket could be missed and leave an actor permanently scheduled.
  The lock is **not** held for guest execution: the gate only submits a
  runnable to the preexisting VM-owned domain executor.
- If the underlying dispatcher rejects a GRANTED or newly promoted ticket,
  fail the entire gate closed before returning a physical permit. Stop both
  the rejected actor and all WAITING actors, instead of recursively promoting
  10,000 failing tickets and overflowing the Java stack.
- `shutdownNow()` can remove already-GRANTED dispatcher work without ever
  invoking its runnable. The runtime's existing scheduled-bit shutdown path
  now cancels any waiting ticket, or idempotently returns the discarded
  granted lease outside `lifecycleLock`.
- Closing a group now continues membership revocation, managed-worker
  cancellation, event-bus/Mailman teardown and registry removal even if a
  gate cancellation observer throws. A cancelled ticket can be reconciled
  without accidentally releasing a still-GRANTED physical execution lease.
- Regression cases were added for queued stop while another actor occupies the
  only permit, gate-close observer failures, abandoned granted turns,
  dispatcher-shutdown promotion, and a bounded parallel-turn contract.

### Verification available without Actions minutes

Run from the *actual pinned PR checkout*:

```bash
bash scripts/verify-actorgroup-turn-gate.sh
```

This test compiles the exact local `ActorGroupTurnGate.java` with `javac`
and executes FIFO, bounded queue, simultaneous cancellation/release, an
injected dispatcher failure with 20,000 WAITING tickets, broken-callback
reclamation and 12,000 turns through eight OS threads. It uses no credentials,
Maven, JUnit, network, GPU, or new repository mirror.

The standalone gate does **not** prove that ActorRuntime's cross-lock order,
Graal context handoffs, cancellation propagation, source lowering or JVM/native
shutdown work correctly. Run the exact-head JUnit tests
`ActorGroupTurnGateTest`, `ActorRuntimeGroupParallelismTest`,
`ActorGroupPlacementPolicyTest`, existing
`ActorGroupMultiMembershipTest`, and broader Graal/native suites before
promoting either stacked PR. Pay special attention to actor-creator retirement
holding a lifecycle lock during group-close callbacks; complete differential
and fault-injection verification is still required.

The repository's protected cross-org CI approval allowlist remains empty.
Never export this private tree to an unapproved destination.
