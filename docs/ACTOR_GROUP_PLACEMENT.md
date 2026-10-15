# ActorGroup CPU/GPU placement: admission contract (stage 1)

## Status

This change introduces an **immutable host-side policy type**, not executable
guest-language CPU/GPU affinity. The only admitted value for now is
`ActorGroupPlacementPolicy.unrestricted()`. Unsupported options are rejected
**before** group creation and membership registration.

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
  `Integer.MAX_VALUE` are rejected until nonblocking bounded permits exist.
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
3. Add scheduler-owned bounded permit accounting for an owner's group. An actor
   denied a turn releases its carrier immediately and waits in a bounded fair
   queue; cancellation, close and release must reclaim queued reservations
   exactly once. No sleeping/busy-spinning workers.
4. Prefer same-core/LLC/NUMA locality for placement and steals, with guaranteed
   age-based fallback; a hot group cannot starve an older actor.
5. Attribute CPU budgets to actors/source tasks across `await` and
   `rt cooperate`. Untrusted continuations must not refill logical quotas.

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
