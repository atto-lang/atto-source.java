# AttoLang actor-owned execution frame arena — M1.5 host primitive

**Implementation:** `ActorFrameArena`, `ActorRuntime.ActorCell` binding,
`ExecutionContext` domain validation. **Status:** host runtime primitive;
**not compiler-lowered per-actor guest execution stacks**.

This change extends the actor-owned call-depth accounting in stacked Java
PR #2624 and the carrier recursion guard in PR #2505.

## Physical memory and identity invariants

- Each actor incarnation owns an independent `ActorFrameArena` object.
  New frame allocation-domain IDs are monotonically minted across
  `ActorRuntime` instances. The domain embeds actor generation and
  `ExecutionContext.AllocationDomain.Kind.ACTOR`. Frame tokens cannot be
  reused across actor generations, execution IDs, or different frame stores.
- Host compiler code `push(execution, bytes)` allocates actual managed
  `byte[]` payload storage; default bounds are **64 frames and 64 KiB per
  actor**. This is a provisional bootstrap limit, **not** AttoLang's portable
  ABI or the actor's full heap quota. A real lowering must account for frame
  headers and all roots/references in one unified memory budget.
- `write/read` copy byte values and reject out-of-bounds access. They expose
  no raw byte array, memory address, guest object, cross-actor capability,
  class instance, callable or closure. The host-only `FrameRef` is an opaque
  non-serializable lease. A foreign actor/old incarnation/other task cannot
  use it.
- Frames obey last-in-first-out pop. Illegal protocol transitions permanently
  poison the actor frame arena; a poisoned arena disallows further allocation,
  reads/writes, resumption and suspension but permits deterministic cancellation
  or unwind. Byte/frame exhaustion raises a stackless terminal
  `ActorFrameArena.BudgetExceeded` (not JVM `StackOverflowError`).
- `suspend` retains the *entire logical execution's* frame stack and byte
  accounting, while `resume` accepts only that exact execution ID and top
  frame. A resumed task can run on another carrier. Scheduler-side
  non-reentrancy and a real continuation handle remain separate obligations.
- `cancel` zeroes and reclaims every frame of one execution exactly once.
  `retireQuiesced` zeroes and invalidates every remaining frame after the
  existing physical-turn, source-scheduler, STOP and child-quiescence gates
  inside `ActorCell.finalizeStopLocked`. Stale tokens cannot access retired
  storage.
- `TrapFailurePolicy` denies guest catching or trapping of the typed budget
  failure, including wrapped causes. Actor mailbox dispatch checks the arena
  poison state after the callback, so host exception swallowing cannot continue
  the actor's user mailbox. The sibling actor keeps its own arena.

## Explicit security and execution limits

This implementation **does not** turn the Java native carrier's stack into an
actor-owned stack. `Evaluator.invoke` and Truffle/AST walking still use
ordinary JVM call frames. The byte frames introduced here are only a storage
primitive; no compiler path currently lowers AttoLang locals, return addresses,
or suspended `SourceTask` continuations into them. `ExecutionContext` is
validated on *host runtime API calls*, not transparently synthesized by the
source compiler. The live stack, actor logical-turn, task continuation and
frame store are not yet one proved end-to-end ownership system.

In particular **do not** treat this as a guarantee against StackOverflowError,
JNI/FFI reentrancy, CLR StackOverflowException, SIGSEGV/SIGBUS, arbitrary native
or JIT recursion, or process OOM. An unchecked path requires prohibition,
verified lowering, or an independently killable OS worker. The JVM's
`byte[]` allocations are managed by GC and are not an actor-native zero-GC
arena.

Do not merge/promote the full feature before:

1. Compiler/trampoline lowering emits actual frame push/pop and suspension
   state-save/restores on every non-tail guest path (including expressions,
   methods, closures, cleanup, exceptions and async tasks).
2. Guest-local values and reference roots have a typed, owner-checked layout,
   not raw movable object pointers smuggled through frame bytes.
3. `SourceTask` physical continuation receipts use the same logical
   `ExecutionContext` identity across migration and reject late/stale resume
   after stop/restart/hot-upgrade. No mailbox reentrancy across suspension.
4. Full actor+frame+heap budget accounting and OOM/allocator-failure policy
   are verified. Actor STOP releases frames once after all physical users
   retire, with supervisor CONTROL preserved.
5. Compiler-backed `.atto` actor A stack exhaustion, sibling B survival,
   supervisor generation restart, tail-call boundedness, sanitizer/low native
   stack tests, and exact-head stepful hosted receipts run on each backend.

## Tests and execution

Added 16 JUnit host/runtime methods in `ActorFrameArenaTest` covering byte
storage isolation, defensive copying, frame/byte quotas, LIFO mutation,
foreign actor and stale generation rejection, suspend/resume on another Java
thread, double resume, cancel, retirement, supervisor-like fresh generation,
guest trap policy and actor A/B carrier reuse.

The tests are **authored, not hosted-executed** until a nonzero-step exact-head
runner compiles the full source repository:

```sh
mvn -Dtest=ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,GuestCallDepthActorHostModelTest,ProperTailCallTest test
```

An isolated JDK 21 frame-api smoke model compiled locally with `javac -Xlint:all
-Werror` and passed 19 assertions. The local test used a minimal stand-in
for the existing `ExecutionContext` types rather than the full repository
build. This is **not** full Java compilation, a Maven/JUnit pass, or
compiled `.atto` guest evidence.

Cross-backend parity is tracked in conformance PR #562 and issue #341;
all five backend guest-stack evidence lanes remain `NOT_EXECUTED`.

## M1.6 guarded synchronous invocation bridge (2026-11-07)

`GuestCallDepthGuard.enter()` now reserves **32 bootstrap managed bytes**
in the actor incarnation's `ActorFrameArena` for every admitted synchronous
non-tail guest call. Each *outermost synchronous guard chain* mints a new,
monotonic `ExecutionContext.ExecutionId` in the arena's generation-fenced
allocation domain. Nested call frames inherit that call-chain identity. The
matching `leave()` pops the frame in strict LIFO order before releasing the
logical `ActorGuestStack` depth charge. Guard-depth exhaustion, cross-actor
context misbinding, and illegal unwinds poison the actor's arena as well as
the logical guard, including when a trusted host callback catches the exception.
The carrier ThreadLocal guard still independently caps native JVM recursion.

**Accounting, not a real guest-frame layout:** these 32 bytes are not compiled
locals, temporary object roots, return addresses or `SourceTask` continuation
state. This reservation is allocated on the JVM heap and creates work per call.
Its charge is a provisional constant, not the actual frame size. The
synchronous call-chain ID is deliberately NOT a stable await continuation
identity: only a scheduler-minted `SourceTask` execution ID may survive
suspension, with explicit frame retention and generation-fenced resume.

Four additional authored host/runtime JUnit methods cover nested guard-frame charge/unwind,
suppressed recursion-budget failure and sibling-carrier reuse, non-reused
logical call-chain identities, and wrapped guest trap denial (20 host/runtime methods in
`ActorFrameArenaTest`). A further **authored, not executed** JUnit test
invokes legacy `.ores` recursive guest source through a real GraalVM polyglot
context inside actor A's mailbox callback, requires its guard failure to
poison actor A's frame arena, and then compiles/runs a healthy legacy guest
program on sibling B using the one-worker SHARED carrier. That is 21 JUnit
methods total. No passing Maven/Graal/JUnit or canonical `.atto` receipt exists.

Next integration: introduce typed compiler frame layouts for every guest call,
replace the fixed reservation with actual root/local/continuation accounting,
and wire suspended `SourceTask` state to generation-checked frame leases.
Require the exact-head A/B actor and supervisor restart fixture under a
nonzero-step CI job before any physical stack or process isolation claim.
