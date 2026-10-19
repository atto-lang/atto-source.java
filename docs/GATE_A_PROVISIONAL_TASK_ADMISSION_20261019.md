# Gate A — Provisional compiler Task admission and physical retirement

Status: **draft** (2026-10-19); stacked on the currently updated Java guest scalar Task #1484. This is a host/runtime bridge hardening step, not general guest Task or LLVM completion.

## Admission linearization

1. Under the shared `treeAdmissionGate` and then `OresTaskScope.gate`, validate the Task owner remains open and reserve a `pendingAdmissions` physical-exit Future. This keeps the atomic subtree-cancellation preflight from the updated upstream #1484 branch. The record is retained while the scheduler has no bound Task handle.
2. Release both admission monitors before invoking `OresScheduler.start`. A scheduler is permitted to execute work or invoke a Future callback inline.
3. If registration is rejected before any TaskRunner exists, release the already acquired code-generation pin **outside the monitor**, settle the pending physical fence only after cleanup and preserve the rejection plus any suppressed release failure. Never treat a failed release as successful shutdown.
4. On successful submission, atomically exchange the pending reservation for an `OresTask` in `tasks` under `gate`. Cancellation may already have sealed admission; replay a cancellation request against the bound Task after releasing the monitor.
5. The scheduler's `onQuiescent` invokes trusted code-image cleanup and settles the independent physical-exit Future. Cancelled result Futures are **not** physical completion evidence.
6. `close()` snapshots both bound Tasks and unbound pending-frame reservations. Cancellation prevents new reservations but does not drop either type of join slot. The close barrier joins both groups and surfaces trusted release errors.

The host `submit(AsyncRuntime.Task)` path now uses the same pending physical-join slot and dispatches outside `gate`. Its pre-bind reservation is retired only after the host Task's `getDone()` quiescence. `cancelWhen` also detaches discarded registration outside `gate`. Guest execution remains limited to immediate awaited scalar Task root entrypoint.

## Type proof rebase

- Port resolved `ThreadSafetyTraits.SEND` check into the current guest scalar await TypeChecker without overriding newer region/task proof code from #1484.
- Preserve readonly-record view `Sync` denial: readonly views do not prove a frozen backing allocation.
- Reject both `Primitive.NULL` and `Primitive.VOID` for `Send` and `Sync`, including nested containers and borrows. These are sentinels, not standalone owned worker values.
- Do not authorize arbitrary nominal handles or mutable heap captures.

## Regression gates

```sh
mvn -B -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskScopeFrameTest,OresTaskScopeTest,TaskTraitAdmissionHardeningTest,GuestScalarTaskAwaitTest,ScopedTaskTypeBridgeTest,TaskAccessVerifierTest \
  test
```

The additions stress 64 compiler-frame admission/close races, 64 host-Task submission/close races, physical code-image pin release, rejection callbacks running outside locks, cancelled turn-entry fencing, broken cancellation-detach callbacks, and nested NULL/record proof laundering. Tree-wide cancellation still snapshots and atomically seals all descendant scopes before Task revocation. Task revocation occurs before external subscription detachment, preventing a blocking/throwing host callback from stranding siblings. Run these against the **exact PR head**, not older green commits.

Independent **exact blob** `ThreadSafetyTraits.java` compiled under Java 21 `-Xlint:all -Werror` with `Types.java` and its focused Java host-contract harness, and printed `JAVA_SEND_SYNC_NULL_CONTRACT_PASS`. This **does not** count as Maven/JUnit, guest compiler or full JVM runtime execution.

## Release blockers

- No parent-root cancellation-tree authority or guest owned/nontrivial aggregate Task frames yet.
- No compiler-minted lifetime/borrow/region transfer token or awaited-code-generation lease integration for general Task tasks.
- `AsyncRuntime` uses interruptible virtual-thread compatibility carriers; these are not yet a compiler-verified M:N guest-Task cancellation ABI. A pre-bound scheduler submission can only replay cancellation when a handle becomes available. For arbitrary guest code, the safe-point continuation must carry this authority explicitly.
- LLVM needs equivalent compiler/runtime cancellation + physically completed destructor/FFI/borrow proof, not just host Send/Sync shape-model tests.
- Source Actions still produce no executed job steps, so exact-head Maven/JUnit execution has **not** been verified.
- Cross-organization private Git-tree export remains disallowed until protected-source reviews, immutable snapshot and approved numeric destination source-mirror policy have been completed (Java #962, platform-conformance #69). An available private runner does not imply export authority.
