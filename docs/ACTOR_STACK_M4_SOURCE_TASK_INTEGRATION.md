# AttoLang M4 — integrated synchronous call frames and actor SourceTask leases

**2026-11-07 · DRAFT · compiler-backed guest execution NOT VERIFIED**

## Provenance and merge construction

This branch is based on Java stackless scalar interpreter [PR #2633](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2633)
head `12f6475be1690b857b26824d420a39ae9e74a239`. It integrates the
**exact six changed Git blobs** from separately authored SourceTask
[PR #2631](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2631)
head `3ac313ee2e79fbd4b36506525d0f367c4e42a12c`. Their common
underlying actor-frame arena baseline is Java #2627. The six files
had no overlapping modifications with M3 on those paths, so the
combined tree preserves the SourceTask scheduler logic verbatim.

This is an **integration branch**, not an approval/merge of either PR.
The separately authored features still require their own independent
reviews, executed source tests, and exact combined-head evidence.

## Runtime integration

- The actor SourceTask scheduler created by `ActorCell.sourceScheduler()`
  is explicitly tied to one actor-owned `ActorFrameArena`.
- Each SourceTask receives an `ExecutionContext` unique in the actor
  allocation domain, and reserves a persistent **64-byte bootstrap frame**.
  On `await`/`cooperate`, the task's frame remains charged while the
  scheduler releases the carrier; later resumption uses the same logical
  context. Cancellation/termination zeroizes after physical quiescence.
- Synchronous guest invocations use **different** call-chain IDs, reserve
  **32 bytes per call**, and own up to two primitive integer slots and the
  M2 descriptor. During a SourceTask turn, peak actor-frame usage for one
  task plus one call is **96 bytes** (64 + 32). The synchronous call frame
  unwinds before SourceTask suspension, leaving 64 bytes charged.
- A `GuestCallDepthGuard.Scope` now binds to both scheduler
  `currentExecutionContext()` and `currentDispatchId()`, in addition
  to the actor arena/stack and physical carrier. Reusing an open guard on
  a different logical SourceTask or after a continuation gets a new
  dispatch ID fails closed and poisons the actor.
- Before accepting `OresScheduler.Await` or `Cooperate`, the scheduler
  calls `GuestCallDepthGuard.requireNoLiveSynchronousCallsAtSourceSuspension()`.
  An accidentally retained synchronous frame is **NOT** a resumable
  continuation: the affected actor is poisoned, the carrier-local guard
  binding is cleared, and the task fails before scheduling a callback.
  The old actor generation's leaked bytes are retired and zeroized at
  actor quiescence.

## Authored tests (NOT EXECUTED on hosted Java CI)

`ActorStackM4IntegrationTest` provides **6 JUnit methods**:
1. Nested 32-byte scalar calls inside a 64-byte SourceTask, stable task
   identity across await, changing dispatch ID, correct frame high-water
   charge and zero final bytes.
2. Two tasks in the same actor generation: one suspends while the other
   executes a synchronous scalar call and retires; no identity collision.
3. Deliberately leaking a host-only invocation over `cooperate`
   poisons only its actor and disallows dispatch to resume it.
4. Leaking a frame over `await` fails before arming the awaited gate,
   even if the gate later completes.
5. Cancellation of an awaiting task after a correctly unwound scalar
   invocation releases the persistent task frame, while leaving a
   healthy actor usable.
6. A non-actor root's guarded calls do not fake actor-owned task memory.

New canonical guest fixture
`m4-scalar-before-after-actor-suspension.atto` and JUnit method
`actorScalarCallsBeforeAndAfterSourceTaskCooperateKeepCorrectValues`
require source-compiled actors to run Fibonacci(8) → `21`, yield via
`rt cooperate`, then execute 100,000 proper tail calls → `100000`,
with observable `21|100000` output and actor shutdown.

This **does not** suspend an in-flight recursive scalar call frame.
An active M3 `GuestInvocationFrame` is bound to the physical scheduler
dispatch and cannot survive an await. The SourceTask bootstrap frame
is not yet the lowered recursive call stack, guest operand stack, return
PC or local variable spill storage. Do not claim that nested recursive
guest frames survive carrier migration.

## Required next work and acceptance

1. Run on the **exact integrated head/tree** with nonzero executed CI steps:
   `mvn -Dtest=ActorStackM4IntegrationTest,ActorSourceTaskFrameTest,ScalarFrameMachineTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,GuestInvocationFrameTest,GuestCallDepthGuardTest test`.
2. Execute all `.atto` actor source fixtures with compiler-backed receipts
   and capture stdout, process exit, fixture blob hashes, head/tree, JDK
   and runner job/step metadata.
3. Lower **all live scalar frame operands, return PCs, cleanup edges and
   guest locals** into persistent task-owned frames with a stateful
   continuation instruction pointer and scheduler-minted resume lease.
   `await` inside nested recursive calls must save the **whole**
   recursion state before relinquishing the carrier and resume exactly once
   after migration with its original logical task identity.
4. Prove actor A exhausts its own quota while actor B continues, followed
   by supervisor CONTROL restart, generation fencing, no double cleanup,
   native low-stack survival, and no shared-carrier blocking.
5. Prove identical fixtures and safety properties across LLVM, .NET
   NativeAOT, Wasm and Forge/Cranelift; maintain independent evidence
   classes for compiler-backed vs host-only vs source-free canaries.

`steps=[]`, `steps=null` or `runner_id=0` remain **runner-admission
failures**, not executed tests. This branch is **not** merge-ready until
positive/negative compiler-backed tests, independent approval, and
integration receipts are present.
