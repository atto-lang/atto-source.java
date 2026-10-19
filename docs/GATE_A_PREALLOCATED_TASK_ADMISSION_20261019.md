# Gate A — Preallocated Task ownership with lock-free scheduler admission

Status: **draft** · 2026-10-19. This integration starts at guest scalar Task #1484 commit `208bc9805db1647aa1e90b38ca448a8abd64553e` and retains its newer ownership-slot/OOME fail-closed architecture.

## Linearization and cancellation

1. Under shared `treeAdmissionGate` then `scope.gate`, pre-register a Task ownership shell with its independent physical-quiescence fence before executable admission. Upstream `OwnershipSlot` and `OresTask` prepare-before-submit invariants are preserved.
2. Host `AsyncRuntime.prepareTask` allocates the exit witness and Task shell before scope admission. `PreparedTask.start` dispatches outside BOTH scope/tree monitors and binds the completion Future by reference.
3. Compiler `OresScheduler.start` likewise executes outside BOTH monitors with a pre-registered `OwnershipSlot` and independent code-generation lease. A failed scheduler registration releases the pin outside monitors before settling the shell's physical fence; failed release remains an explicit fence failure.
4. Atomic subtree cancellation may observe the unbound shell; `OresTask.cancelDeferredNotification` returns no notification until its completion is attached. The admitted producer rechecks the **volatile** cancelled bit and replays cancellation after binding, outside locks. A cancelled scope is also denied at the entry to a host or guest continuation turn.
5. `close` snapshots owned shells even when not yet bound, and waits on their preallocated physical fences. It must never equate a cancelled result with physical resource exit. Failures in owner-set cleanup are suppressed on the originating admission failure; a failed pin retirement is not successful cleanup.
6. Preserve upstream two-phase cancellation preflight, atomic tree-wide admission seal, and coherent `physicallyQuiescent()` read. Revoke all Task producers before running potentially blocking/throwing external token detachment, and defer Future subscriber notifications until all siblings have been revoked.

## Integrated security checks

- Compiler-resolved scalar Task capture requires owned value and `ThreadSafetyTraits.SEND`; no guest nominal callback can bless runtime capabilities.
- `Primitive.NULL` and `Primitive.VOID` do not inherit Send/Sync, including within aggregate or borrowed types.
- Readonly structural record views cannot acquire Sync absent a compiler-proven physically frozen backing value.
- Keep upstream `OresTaskScopePreAdmissionOwnershipTest`, `OresTaskScopeFailedSlotCleanupTest`, `OresTaskScopeCancellationOrderingTest`, and `OresTaskScopeCoherentQuiescenceTest`.
- Include 64 host submit-vs-close trials, 64 compiler frame-vs-close trials, fail-injected cancellation detachment and code-pin release, and 10,000 host-only prebound shell cancellation/bind checks.
- Upstream `OresTask` contained an erased Java overload clash: constructors taking `OresFuture<T>` and `OresFuture<?>` are the same JVM signature. Pre-admission now uses the explicit `OresTask.<T>preAdmitted(exitFence)` factory; ordinary result-only construction retains the other one-argument constructor. The three source call sites and regression fixture have been migrated.
- `AsyncRuntime.PreparedTask` admission/rejection now uses one atomic CAS claim to prevent concurrent double-scheduling. The JUnit suite adds 128 two-thread start-vs-reject trials.
- **Independent local Java 21 erasure harness executed:** original generic overloads rejected by `javac`, distinct factory shape compiled with `javac -Xlint:all -Werror`. This test confirms the Java signature issue/fix pattern only; it is NOT a full Oreslang source compile or runtime test.

## Required source-local exact-head execution

```sh
mvn -B -Dores.runtime.carriers=java -DskipTests test-compile
mvn -B -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskScopePreparedOwnerTest,OresTaskScopePreAdmissionOwnershipTest,OresTaskScopeFailedSlotCleanupTest,OresTaskScopeRetirementBookkeepingTest,OresTaskScopeCancellationOrderingTest,OresTaskScopeCoherentQuiescenceTest,TaskTraitAdmissionHardeningTest,GuestScalarTaskAwaitTest,TaskAccessVerifierTest,ScopedTaskTypeBridgeTest \
  test
```

After this, execute the full Maven suite with GraalVM JDK25, real positive/negative `.ores` guest programs, agent-affinity and physical-shutdown concurrency tests, and any native-image gates. **The tests above are committed but are not claimed executed.** The source org's Actions runs have repeatedly failed without executing steps, so the cause is not inferred to be exhausted minutes or faulty Java.

## Physical-retirement audit (2026-10-19)

Physical Task quiescence is an **owner-retirement witness**, not just an
executor-turn witness. A generic `OresFuture.whenCompleteRuntime` listener
swallows host callback exceptions; it is forbidden as the sole authority to
remove physical owner slots and then claim a successful `getDone()`.

- **Compiler frames:** the scheduler's `onQuiescent` first releases the trusted
  code image, then checks that its `OwnershipSlot` was removed exactly once,
  and only then completes `exitFence` successfully. Failed pin release,
  thrown slot removal, or a slot removal returning `false` settles the
  physical fence exceptionally, retaining the unsafe owner.
- **Host compatibility Tasks:** `AsyncRuntime.TaskExit` installs a pre-dispatch
  physical-retirement callback through `PreparedTask.onPhysicalExit`. Its
  single-claim `markExited()` executes the callback before settling
  `quiesced`; failed bookkeeping makes `getDone()` fail and keeps the slot
  retained, instead of being swallowed by a Future listener.
- **Cancellation:** unbound provisional Task shells still defer result
  revocation until completion attachment, without ever claiming physical
  quiescence before the worker exits or is proved not to have entered.
- **Fault injection:** `OresTaskScopeRetirementBookkeepingTest` covers the
  compiler rejected/accepted paths and the host physical-exit path for both
  thrown and silently refused (`remove == false`) owner retirement.

### Verification boundary

Java 21 local smoke code (isolated types contracts) was compiled using
`--release 21 -Xlint:all -Werror`, then ran the `SendSyncNullContractTest`
and a 10,000-case `TaskResolvedSafetySmoke` check. A separate, **source-free
algorithm model**, `PhysicalExitModelTest`, compiled with the same flags and
passed 10,000 competing retirement pairs and injected error/order checks.
Neither is a Maven/JUnit execution of this full PR head.

The private source-org Actions runs are still failing before any recorded
runner steps. The alternate organization's successful exact-tree CI run
`39326561975` used designated fake source commit
`aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` and a source-free canary tree.
It establishes runner health, **not** Oreslang PR code validation. The reviewed
source-org destination allowlist currently has zero uncommented entries,
so no cross-org private-source transfer was performed or authorized.

## Remaining limitations / release blockers

- `AsyncRuntime` remains a compatibility virtual-thread carrier, not a fully compiler-proven guest M:N Task backend.
- Upstream source Task/region/actor lifetime proofs and LLVM continuation/borrow parity remain separate gates. Physical quiescence is a logical owner fence, not proof of independently isolated OS address space.
- Exact-head source-native CI, CODEOWNER review and branch protection are required before merging; the reviewed source mirror authorization ledger must remain private and fail-closed.
- A funded alternate organization does not authorize transferring the private Git tree. See [Java issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962) and [CI issue #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963).
