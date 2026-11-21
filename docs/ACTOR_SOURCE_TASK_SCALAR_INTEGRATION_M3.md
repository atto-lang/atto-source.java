# AttoLang actor-owned scalar state across SourceTask await — Java M3 integration slice

**Status: AUTHORED, NOT EXECUTED.** The implementation branch is a semantic merge of
[Java PR #2631](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2631)
(actor scheduler frame leases) with
[Java PR #2630](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2630)
(primitive typed invocation arguments), plus this integration bridge.
Both source PRs remain unchanged and draft. This does **not** certify
native/physical stack isolation or five-backend parity.

## Architecture and state ownership

Every actor incarnation still owns one `ActorFrameArena`, a bounded managed
byte store. Its per-incarnation ID allocator is shared by synchronous
invocation chains and actor-domain-bound source tasks so logical frames never
alias, even after carrier migration. Source tasks reserve a **single 64-byte
bootstrap lease** on their first physically admitted scheduler dispatch. It
survives `Await` and `Cooperate`, and is reclaimed only after physical
quiescence/cancellation checks. Synchronous evaluator calls separately reserve
a single typed invocation frame of
`max(32, 16 + 9 * supportedScalarArgumentCount)` bytes, and release that
nested charge when the call returns. The same actor-wide frame/byte quota
accounts for both allocations; no second descriptor frame is allocated.

This integration adds an intentionally narrow continuation-state prototype:

- `ActorSourceTaskFrame.saveTypedScalars(values)` writes a canonical,
  pointer-free tagged payload into the *existing* 64-byte task frame. A
  four-byte big-endian payload-length header leaves up to six 9-byte tagged
  scalars (Byte, Short, Integer, Long, Float, Double, Boolean, Character).
- `restoreTypedScalars()` checks an explicit saved-state receipt, validates
  length/tag encoding and reconstructs those values from actor-owned bytes
  **after** an authenticated `await` or `cooperate` resume. Corrupt data
  poisons the actor generation. NaN payloads retain raw bit patterns.
- An actor-owned `OresScheduler` binds the exact `ActorSourceTaskFrame`
  only to its physically admitted `TaskRunner` carrier turn. Binding is
  restored/cleared in `finally`, including failure paths. External producer
  threads, foreign actors, wrong execution identities, completed tasks and
  stale generations have no authority to read/write the frame.
- Arbitrary references/closures and more than six typed scalar values are
  rejected *before* mutating the frame. A new snapshot overwrites all 64
  bytes, zeroizing leftover payload bytes. Retirement zeroizes the backing
  allocation through the existing frame-arena cancellation path.
- This is a **trusted runtime bridge only**. The Java compiler does not yet
  automatically lower lexical local variables or operand stacks into it.
  The `SourceTask` heap continuation, return program counters, reference
  roots and recursive JVM evaluator frames remain unlowered.

## Authored integration tests

`ActorScalarSourceTaskIntegrationTest` adds five JUnit host/runtime tests:

1. Two frames share the same aggregate actor quota with no descriptor
   double-charge; the task's `ExecutionContext` persists across await.
2. Two independently suspended task leases retain separate identities and
   memory charges; nested synchronous typed calls unwind before await.
3. Cancellation of a waiting task reclaims only its own memory and prevents a
   stale producer completion from dispatching again.
4. An explicitly saved typed scalar continuation snapshot survives await in
   the same task frame; a foreign thread cannot inspect or rewrite it.
5. Reference/oversized snapshots fail before allocation, while a valid
   snapshot survives cooperate and the task frame remains a fixed 64 bytes.

These tests are **NOT_EXECUTED** on an exact PR head. Existing #2630/#2631
authored tests likewise cannot count as compiler-backed guest evidence.
Host task callbacks do **not** establish that a second compiled source
`receive` handler is blocked across suspension. That must be proved by a
separate canonical `.atto` code-actor mailbox test.

## Required exact-head receipts

On an authenticated admitted runner, record PR commit SHA, Git tree, workflow
ID/attempt, runner ID, nonempty steps, compiler/toolchain versions, test XML
and source blobs:

```sh
mvn -B -ntp -Dtest=ActorScalarSourceTaskIntegrationTest,ActorSourceTaskFrameTest,GuestScalarCallFrameTest,GuestScalarActorInvocationLanguageTest,GuestInvocationFrameTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test
```

`steps=[]`, `steps=null`, or `runner_id=0` mean runner-admission
failure. A source-free canary/stand-in host harness does not promote source
execution. This branch remains DRAFT without exact-head stepful tests and
independent review.

## Next P0 compiler/runtime work

- Build a typed heap-resident instruction stream with program counter,
  operand stack, locals, return/cleanup edges and actor-owned reference roots;
  replace recursive JVM guest interpreter dispatch.
- Connect the compiler-generated source `Await` continuation to the
  scheduler-owned task lease and its authenticated snapshot, preserving
  actor handler non-reentrancy and cancellation quiescence.
- Compile two actual canonical `.atto` actors: A exceeds the bounded guest
  budget; B continues on the same carrier; CONTROL supervisor observes A,
  restarts with a distinct generation and rejects all stale resumptions.
- Prove low-native-stack process survival and run equivalent executed
  conformance evidence on Java/GraalVM, LLVM, .NET NativeAOT, Wasm and
  Forge/Cranelift. Untrusted native/JIT code requires isolation into a
  killable worker, never JVM/native stack-fault recovery on a shared carrier.
