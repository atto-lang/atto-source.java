# Java M2.2 — receive-invocation-owned scalar frames (draft)

**Scope:** Java ActorRuntime source-code-executor integration stacked on [#2704](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2704). This is a real runtime bridge plus authored JUnit tests, **not** compiler-lowered `.atto` receive/await support and **not** Java/GraalVM Native Image proof.

## Implementation

- An admitted `ActorCodeExecutor.receiveActor` now mints a distinct `ActorReceiveInvocation` on the actor's existing `ActorFrameArena` using `newSynchronousExecution()`, which provides a unique execution ID in its actor incarnation.
- The invocation reserves one 64-byte root frame. The existing scalar `ActorScalarCallMachine.execute` now uses that execution ID when called inside the source receive, placing CALL/RET frames **above the same root** rather than minting a separate logical execution.
- Successful synchronous scalar calls leave the root alive. A source receive using `suspendCurrentUserMailboxUntil` suspends this root after its handler returns; it remains charged to the actor's aggregate stack-byte budget while DATA admission is blocked.
- Only the actor's serialized internal continuation lane resumes and retires that exact root, before admitting subsequent DATA. A subsequent receive gets a fresh execution ID and root. Task frames continue to have independent `ExecutionContext` IDs within the same actor-owned arena.
- A failed scalar call poisons the actor's frame arena and cancels its invocation stack, preventing swallowed guest failures from creating a fresh DATA turn. Foreign frames, stale generations, excessive frame depth/bytes, and non-LIFO cleanup fail closed via `ActorFrameArena`.

## Regression tests

- `ActorReceiveInvocationTest`: distinct simultaneous logical stacks in one arena, foreign-frame denial, root retention across suspend/resume, fail-stop leaked child frames, aggregate quota under suspension, restart generations and separate actor safety.
- `SourceActorReceiveInvocationTest`: real source-code-executor mailbox, scalar call under receive root, first receive suspended on a real Future, subsequent DATA blocked, first root reclaimed and second receive assigned a fresh identity.

## Important limits

This is the **host source-executor/scalar machine** path, not complete compiler-to-guest `.atto` lowering. The scalar machine is still synchronous-only. No nested callee AWAIT bytecode, persistent reference roots, arbitrary object closure captures, native stack switching, opt-in `messageConcurrency > 1`, or safe recovery from native/VM-wide stack corruption is claimed. Host executor `receiveActor` frames are ordinary Java frames and return before its Future continuation; arbitrary guest call-stack suspension still requires compiler state-machine lowering.

Tests are **AUTHORED_NOT_EXECUTED** until exact-head JDK 25/GraalVM stepful JUnit and source guest receipts exist. Run:

```sh
mvn -Dtest=ActorReceiveInvocationTest,SourceActorReceiveInvocationTest test
```

(Requires the repository's configured JDK/Maven toolchain; this command has not yet been executed at the PR head.) A GitHub Actions job with `steps=null` is runner admission failure, not an executed test. Coordinate follow-up with [spec #26](https://github.com/ores-truffle-oreslang/attolang-language-spec.md/pull/26), [conformance #635](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/635), and [common gate #255](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/255).
