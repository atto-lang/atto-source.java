# Supervision trees, supervisor actors and hot code replacement

Status: **design contract and incremental host implementation** (2026-10-11).
The Java runtime currently implements immutable supervisor trees and immediate
ActorGroup binding. The source compiler deliberately rejects the new syntax;
there is no production hot-reload implementation yet.

## Guiding rule: OTP supervisor != OS child process

Erlang/OTP supervisors are BEAM *processes* in the same runtime as their
children, not necessarily Unix processes. Oreslang should use an isolated,
serialized, CONTROL-scheduled **supervisor actor** by default. An OS process
boundary is deployment topology, not a prerequisite for hot code replacement.
A supervisor can supervise child supervisors and ActorGroups. A supervisor
actor is never allowed to catch its own panic or consume its own reserved
supervision failure notification.

Use stable supervisor identities/refs as the control plane, and separate
swappable policy code from those identities. Ordinary actor group membership
remains orthogonal: any group member may communicate through the event bus
subject to capability checks, but cannot acquire restart/termination authority.

## Canonical surface language (TARGET; currently compiler-rejected)

```ores
define supervisor actor Grandaddy as
  // Small, checked policy, never an unbounded user scheduler loop
end

define supervisor actor Daddy1 as
  // Hot-replaceable policy and versioned actor-local state
end

define supervisor actor Daddy2 as
end

define proxy actor SupervisorTransport as
  // A typed, authenticated remote endpoint, NOT a local heap pointer
end

// Compatibility spelling is a possible shorthand for supervisor actor:
define supervisor ExtraSupervisor as
end

// Rare advanced extensions (not required for typical apps):
define mailman CustomMailman as
end
define scheduler CustomScheduler as
end
```

A supervisor is a **nominal actor subtype** with privileged, runtime-minted
supervision capability. The compiler/runtime own supervision registration,
CONTROL lane, crash escalation and shutdown. `define supervisor actor`
is not an ordinary actor with a method called `on_panic`, and a `proxy actor`
is not automatically a supervisor.

Suggested typed source construction, subject to admission and borrow-checking:

```ores
val grandaddy = supervisor.root()             // SupervisorRef<Grandaddy>
val daddy1 = supervisor.spawn<Daddy1>(parent: grandaddy)
val daddy2 = supervisor.spawn<Daddy2>(parent: grandaddy)

// The ActorGroup is supervised from its construction, not after creation.
val a = ActorGroup.new(supervisor: daddy1)
val b = ActorGroup.new(supervisor: daddy1)
val c = ActorGroup.new(supervisor: daddy2)
```

Names are illustrative APIs, not yet implemented. No separate Unix process
is created by any of these operations.

## Tree constraints

1. Every supervisor except the runtime root has exactly one immutable parent
   supervisor and a stable SupervisorId, independent of code revision.
   Supervisors cannot arbitrarily reparent themselves. Root bootstrap is a
   trusted runtime operation; ordinary actors cannot mint supervisor handles.
2. A supervisor may directly own child-supervisor identities and advise
   ActorGroups. Both identities and group bindings are established before
   publication/spawn. Nested supervisor registration and publication are
   atomic under the runtime lifecycle gate.
3. Read-only `directGroups`, `childSupervisors` and `subtreeGroups` snapshots
   may be exposed to the supervising policy but not as mutable child registries.
   Cross-runtime handles must fail even if numeric IDs happen to collide.
4. Child specs have a fixed ordering, type (`supervisor`, `ActorGroup`, or
   worker), restart policy (`permanent`, `transient`, `temporary`), shutdown
   deadline, required capabilities, and named module/revision ownership.
5. Default strategy is `one_for_one`. Also support `one_for_all` and
   `rest_for_one` following child-spec insertion order. A **bounded**
   restart-intensity window escalates to the parent; repeated failures never
   loop indefinitely at CONTROL priority.
6. Stop and restart in reverse child-spec start order, waiting for the
   `get_done()` physical cleanup barrier. ActorGroup event-bus members are
   *not* its supervised workers unless separately managed.
7. A supervisor's own panic escalates directly to its parent via reserved
   CONTROL, not to the failing supervisor's own `on_panic` handler. Ordinary
   actor panic also bypasses actor-local `on_throw/on_raise`.
8. After a group closes, its supervisor association is not reassigned,
   nor does closure implicitly reparent child supervisors. Root still exists
   until complete runtime shutdown.

## Hot code loading without OS process replacement

The supervisor actor is a stable runtime endpoint with two separate version
counters: **actor incarnation** (changes on process restart) and **code
revision** (changes on an in-place successful policy upgrade). A hot upgrade
does not by itself stop Daddy1's ActorGroups or restart their workers.

A valid upgrade protocol should be:

1. Compile and signature-check a replacement code image, including typed
   handler ABI and capability manifest. Its deserializer/state migration and
   dependency graph must be validated without touching live state.
2. Publish a *pending* image, then enqueue an `UpgradePolicy` CONTROL request
   to Daddy1's logical lane. No upgrade callback runs on a file-watcher,
   watcher Future completion thread, JNI thread or another scheduler's carrier.
3. At a scheduling safepoint, stop admitting new policy invocations, drain
   or pin old policy turns and continuations, and snapshot policy-private
   state (not actor-owned heaps or cross-actor mutable borrows).
4. Run `code_change(oldVersion, snapshot)` in a bounded migration domain
   with explicit failure/rollback behavior. For a no-state policy, this may
   be trivial. Do NOT attempt to mutate an arbitrary suspended stack frame.
5. Commit the new immutable code-revision pointer atomically after all
   admission and migration checks, increment the revision, and resume policy
   turns. Messages pending before the commit are processed exactly once
   according to the chosen generation/revision admission policy.
6. On failure, keep the previous implementation authoritative. Retain old
   code frames until all outstanding turns, futures and native loans drop
   their references. Reclaim only after quiescence; a stuck old continuation
   must be reported/contained, never silently freed.
7. Audit and telemetry record old/new revision, target SupervisorId,
   structural ActorGroup children, migration outcome and rollback reason.

This is **code replacement**, not restarting a supervisor actor. A separate
opt-in rolling replacement may restart Daddy1 (new actor incarnation) while
preserving/rebinding child identities under an explicit handoff protocol;
do not conflate the two mechanisms.

## Protected user extension points

A custom supervisor policy receives typed, immutable reports:
`on_child_failed`, `on_child_ready`, `on_child_done`,
`on_restart_budget_exceeded`, and `code_change`. All authority-bearing
actions are submitted as compiler-checked CONTROL requests. Handler execution
must be bounded/cooperative; waits suspend and release a carrier. Neither
mailmen nor an ordinary actor can directly invoke privileged policy code.

A custom Mailman is a bounded serialized output router per ActorGroup with
cooperative suspension. A custom Scheduler defines **policy**, not dedicated
threads or a direct ability to run guest continuations inline.

## Proxy actor and OS subprocesses

`define proxy actor RemoteEndpoint` is a process-boundary transport entity:
it forwards authenticated, typed, bounded and incarnation-fenced messages to
a separately owned endpoint. It is optional for local supervision trees.

If a remote child supervisor runs in a Linux subprocess, its local process
supervises its own local descendants, and the parent registers a remote
**proxy/monitor** child specification. The proxy is not itself permission to
mutate another process's actor memory. On link loss, the parent may mark the
remote subtree *unavailable*; it must not blindly spawn competing replacement
children before proving the old incarnation fenced/terminated. Network
partitions are not equivalent to definitive process crashes. Use epochs or
leases with fencing and authenticated reconnection to avoid double owners.
Never claim exactly-once cross-process message processing merely from a proxy.

## Java/LLVM parity gates

- Java host registry: root, parent/child registration, same-runtime object
  identity, direct vs subtree group observation, construction-time group binding.
- LLVM host registry: runtime-affine supervisor references, immutable parent,
  bounded creation, construction-time group association, weak observation.
- Both compiler frontends: fail-closed on experimental source keywords for now.
- Future gate: compiler-level nominal entity AST + imports/type identity.
- Future gate: priority reserved CONTROL + actor panic escalation and
  restart-intensity policy across both runtimes.
- Future gate: code revision pointers, epoch-fenced hot swap and migration,
  pinned async frames/borrow checking and physical cleanup under ASan/UBSan.
- Future gate: real cross-process proxy transport and partition tests.

The host-level tests are not evidence that the above source features or
hot-reload protocol already execute. Full parity is NOT claimed.

## Existing Java hot-reload substrate and integration risk

`HotReloadManager` already stages checked source generations using separate
Graal Polyglot `Context` objects and tracks an active generation per code
unit. Its explicit `retire` operation closes that generation's context.
This is useful for compiling/staging a replacement, but it is **not** an
in-place replacement of a live supervisor actor's policy, and it cannot
preserve actor-local state or suspended continuation frames by itself.
Do not close the old context while an old supervisor policy turn is
executing or an old frame is pinned. The outstanding cross-thread context
close assertion is tracked at
[Java issue #507](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/507).
ABI pinning across hot reload generations is tracked at
[Java issue #494](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/494).

A future bridge from staged `Generation` to a supervisor's authoritative
`CodeRevision` must therefore be atomic at a CONTROL safepoint and
quiescence-aware; avoid directly calling `Generation.start()` inside the
currently executing supervisor turn. Test old-code drain, paused Future
completion, context-close races, capability narrowing, failed migrations
and rollback before enabling compiler-level hot reload.

## Incremental executable code-revision substrate (new)

`SupervisorCodeSlot<P>` now implements compare-and-activate of immutable
host-controlled policy revisions. Acquired leases pin the exact revision that
a logical supervisor CONTROL turn was admitted under; old code is not
reclaimed until the last lease releases. A mismatched revision or ABI fails
closed; pinned retired generations consume a bounded revision budget.
Enqueue-only reclamation callbacks must run resource teardown on the owning
runtime; guest code must never execute from the releasing thread.

`ActorSupervisor.installPolicyCode(slot)` binds a code slot once to a
stable runtime supervisor identity, and `policyRevision()` exposes a
host-only immutable descriptor. A slot cannot be shared by Daddy1 and Daddy2.
ActorGroup bindings and supervisor parentage are unchanged when the
slot's active policy revision changes. ActorRuntime shutdown retires
supervisor slots after the ActorGroups have closed.

**Not yet done:** the compiler still rejects source supervisor-actor entities.
The code slot does not itself execute user source, call `code_change`,
migrate actor-owned state, track automatically compiled continuations, or
bind to a live Graal `HotReloadManager.Generation`. Real guest source
upgrades remain gated by the compiler's suspend-aware ownership and
scheduler-owned CONTROL safepoint work, and full Java runtime tests are
required before merge.
