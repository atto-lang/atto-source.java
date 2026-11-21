# AttoLang actor source-task frame retention — M1.7 runtime bridge

**Scope:** first host-runtime and scheduler integration of actor-owned
execution-frame bytes with actual `OresScheduler.Task` suspension. This is
**not** compiler-lowered frame locals, return continuations, stack switching,
or backend parity. Canonical portable source and fixtures remain `.atto`.

## Previous work and this delta

- Java PR #2505: carrier synchronous recursion boundary.
- Java PR #2624: actor-owned logical invocation depth.
- Java PR #2627: actor-owned managed frame arena, generation fencing, guarded
  synchronous invocation byte accounting and host tests.
- **This PR**: a source actor's `sourceScheduler()` now explicitly binds the
  actor's frame arena to `OresScheduler.runtimeOwned`. A new
  `ActorSourceTaskFrame` reserves 64 **bootstrap** managed bytes for one
  logical source task when the task first actually executes; the charge remains
  through `Await` and `Cooperate` and is reclaimed **after physical
  scheduler quiescence**, not merely after Future completion.

Every actor-owned `TaskRunner` uses a stable, actor-domain-bound
`ExecutionContext` across all scheduler dispatches. The arena mints a shared
per-incarnation logical execution-ID sequence for synchronous guest chains and
source tasks so IDs cannot alias across them. Ordinary root/non-actor schedulers
continue to use `AllocationKind.NONE`.

## Admission and safety invariants

1. Only `ActorCell.sourceScheduler()` opts into actor frame ownership. An
   ordinary scheduler created in an actor turn does not inherit authority
   through an ambient ThreadLocal.
2. The actor-bound scheduler uses **parallelism=1**. Before entering each
   `Task.resume` and before suspending an `Await` or `Cooperate`, the
   bridge verifies **both** the current actor arena and the exact scheduler
   `ExecutionContext`. A foreign carrier cannot activate the frame merely
   by obtaining a scheduler reference.
3. A source-task frame is allocated lazily, on its first physically admitted
   turn. The frame stays charged during suspension; a resumed task must
   authenticate the exact same execution identity and top-frame lease.
4. `Await` and `Cooperate` suspend the lease BEFORE registering a Future
   dependency or requeueing. The existing scheduler retains authority for
   exactly-one resume, producer-callback deferral, cancellation and physical
   carrier handoff.
5. Terminal quiescence (no active guest turn, no incomplete cleanup) is
   required before `ActorSourceTaskFrame.retireAfterQuiescence()` releases its
   charged frame. The actor's existing source-task retirement and STOP gates
   remain responsible for lifetime of other loans and subscribers.
6. Actor generation STOP uses `ActorFrameArena.retireQuiesced()`; stale or
   foreign frame capabilities cannot access the reclaimed bytes.
7. If a task fails with a frame-budget exception, the arena is poisoned and
   no later actor invocation can be admitted. Cancellation cleanup may still
   release the relevant byte charges.

### Quarantine is preserved

A physical cleanup failure or incomplete lexical defer intentionally retains
the source task in scheduler quarantine. The new task frame is **not**
reclaimed while cleanup remains incomplete; frame retirement occurs only
after the scheduler's pre-existing `cleanupIncomplete`, `executing` and
`quiescenceState` gates succeed. A plain terminal Future or stale queued
scheduler ticket is not a reclamation witness.

## Tests authored

Eight new host JUnit regression methods in `ActorSourceTaskFrameTest`:

- same `ExecutionContext` and arena across await/resume, while frame bytes stay
  charged until quiescent terminal notification
- cooperate returns to the same logical execution and releases the reservation
- two independently suspended tasks in the same actor charge two frames and
  cannot alias each other's IDs or prematurely reclaim sibling frames
- cancel a suspended task; release its bytes while leaving the actor alive
- external host thread and old actor generation fail to activate the lease
- synchronous invocation and source-task identity sequences cannot alias
- a forged runtime-owned scheduler handle cannot admit execution on a foreign
  carrier
- actor-bound source scheduler refuses parallelism greater than one

Required hosted command:

```bash
mvn -Dtest=ActorSourceTaskFrameTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest test
```

These methods are **authored only** until an exact-head GitHub Actions run
actually starts a worker with nonempty `steps`. They are not compiler-backed
`.atto` execution receipts.

## Next compiler-lowering gate (not implemented here)

The current parser/evaluator still uses Java native recursion to walk
expressions, closures, nested calls, method bodies and lexical catch/finally
branches. The `ActorSourceTaskFrame` is a *single 64-byte reservation*, not
serialized source locals or an actual captured `SourceTask` continuation.
Before claiming actor-owned execution stacks:

- Lower callable PC, locals, operand roots, return values and cleanup state to
  typed actor-owned frame segments, not opaque byte arrays.
- Convert unbounded recursive evaluator dispatch to a verified iterative
  trampoline with proper tail-call elimination for `fnc` and methods;
  `routine` remains direct-call-only and nonrecursive.
- Ensure compiler-created source `Await` state and
  `ActorRuntime.sourceReceiveSuspended` non-reentrant mailbox turn share the
  frame ownership receipt without granting guest closure transport.
- Prove compiled `.atto` actor A quota failure, sibling actor B survival,
  supervisor restart with new generation, stale-resume denial and low-stack
  native/process survival.
- Execute exact-head CI and backend conformance for Java/GraalVM, LLVM, .NET
  NativeAOT, Wasm, Forge/Cranelift. Distinguish runner-admission failures,
  host tests, and compiler-backed guest receipts; none may be conflated.

The PR must remain DRAFT until compiler integration and stepful test evidence
are independently reviewed.
