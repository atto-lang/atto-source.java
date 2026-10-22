# Supervisor tree shutdown and lifecycle hooks

Status: **trusted-host shutdown substrate in this PR; Oreslang guest compiler
and JVM/LLVM/WASM/.NET/JS execution parity are NOT implemented by this PR.**
The existing JVM `ActorGroup` remains a managed lifecycle/cooperation domain,
not an executable supervisor. Do not advertise source `on_shutdown` or
`on_stopped` as operational before typed CONTROL lowering and end-to-end tests.

## Canonical source contract (future compiler gate)

```ores
define supervisor Root as
  async on_shutdown(reason: ShutdownReason): void {
    // Called once after new child/spawn/restart admission is fenced.
    // Existing admitted actors may still process their messages.
  }

  async on_stopped(reason: ShutdownReason): void {
    // Called once after child supervisors and owned actors finalize.
    // Arenas and continuation loans are no longer borrowed.
  }
end
```

These are **reserved supervisor-only lifecycle hooks**, not overridable
actor `receive` methods and not ordinary callable helpers. Hook signatures
must participate in Oreslang's checked `throws` / `raises` / `panics`
effect system; runtime cancellation/deadline is not trap/catch of `panics`.
Until the compiler can prove the effects and build typed CONTROL continuations,
source supervisor declarations with these hooks must **fail closed**.

## Shutdown ordering

1. The root CONTROL coordinator atomically freezes the supervisor tree and
   fences managed-worker admission in every descendant group. Concurrent
   managed-worker spawns either register before the fence (and appear in the
   shutdown snapshot) or fail management and cancel their newly spawned actor.
   No STOP/RESTART reservation may reopen a fenced child generation.
2. Root supervisor `on_shutdown` begins on its serialized CONTROL lane.
   It may await, but may not run reentrantly or park a physical CONTROL carrier.
3. Child supervisors run in **reverse registration/start order**, recursively,
   with each child processing its own `on_shutdown` first.
4. Each child gracefully seals its workers' inboxes, lets accepted work drain,
   awaits `ActorRef.done` after finalization, and runs `on_stopped`.
   No restart is permitted during shutdown. A failed actor or hook is recorded,
   without skipping remaining children or parent teardown.
5. Once all child supervisors have settled, the parent gracefully closes its
   own managed-worker group and runs `on_stopped`. `terminated` is a
   noncancellable future and completes successfully only after these stages
   all succeed. Failure is aggregated and never reported as clean.
6. If the global graceful-shutdown deadline expires, the observer fails and
   the CONTROL host requests structured cancellation and group close. A
   trusted isolation revoker may be used separately for genuinely forceable
   untrusted domains; never use Java thread interruption as evidence that
   private/shared trusted guest code has stopped. Pending physical leases,
   actor arenas, live continuations, and late callbacks stay fenced until
   physical finalization. Timeout **does not** release them.

## Runtime API introduced in this PR

- `ActorRuntime.ActorGroup.fenceWorkerAdmissions()` — manager-only admission
  fence; mailbox work already admitted may continue.
- `ActorRuntime.ActorGroup.gracefulCloseAsync()` — idempotent close barrier,
  managing **only** explicitly owned workers. The existing `closeAsync()`
  retains immediate structured cancellation semantics. Cooperation-only
  members are not lifecycle-owned and must not be canceled as side effects.
- `SupervisorShutdownTree(runtime, serialControlExecutor)` — host-only
  hierarchical coordinator binding runtime-local groups exactly once *within
  one coordinator*. The executor must be the existing **serial CONTROL lane**;
  it must enqueue work, not run guest hooks on producer/completion threads.
  No actor-local OS thread pool is created for supervisors.
- `SupervisorShutdownTree.shutdown(reason[, grace, watchdog])` — idempotent
  barrier; the optional watchdog is **shared**, not one timer thread per child.

The `onShutdown/onStopped` Java interfaces are strictly trusted embedding
bridges for now, not the implementation of guest source hooks, actor `tear_down`,
scheduler CONTROL behavior, or native-supervisor code hot reload.

## Remaining integration/release gates

- Route `OresVM` root/application shutdown and `ActorRuntime.close()` through
  a registered supervisor-tree coordinator **before** terminating execution
  carriers. Today `ActorRuntime.close()` still uses its legacy immediate
  stop/interrupt path and does not run these host tree hooks automatically.
- Formalize the atomic, supervisor-owned association of groups across
  coordinators and logical-child STOP/RESTART generation receipts. Preserve
  parentage and hot-reload turn leases; never allow STOP to be mistaken for a
  restartable panic.
- Fully integrate source `define supervisor`, typed Failure/ShutdownReason,
  `async on_shutdown`, `async on_stopped`, checked effects, `await`
  continuations and supervisor escalation to Grandaddy/Daddy.
- Graceful close currently revokes group event-bus/Mailman capabilities as
  soon as the group closes. Any protocol requiring transport to remain open
  until actor `done` needs a separate two-phase close and conformance tests.
- Add executable fault-injection tests: close-vs-spawn and STOP-vs-RESTART
  races, nested cancellation, hook failure, actor panic, noncooperative actor,
  incomplete native/FFI loans, restart suppression, duplicate signals,
  stale generations, adversarial reentrancy, and exact-head JVM/JDK25 +
  Graal AOT/hybrid and LLVM/ASan/UBSan checks.
- The `on_stopped` source contract must only execute once runtime-owned
  resources are actually safe to reclaim; logical `ActorRef.done` alone
  does not certify native loan/heap reclamation.
- Run the complete Maven project and source conformance against the exact PR
  commit. Isolated model/probe passes are **not** full actor-runtime proof.

**Safety rule:** shutting down is not the same as stopped. A deadline,
cancel request, revoked capability, or cleared worker registry is never a
substitute for physical carrier/continuation quiescence.
