# M3.3b — checked scalar suspends CALL/await into actor-owned SourceTask frames

**Status: DRAFT, source compiler/runtime extension; no executed JUnit or compiled guest CI receipt yet.**

Stacked on [Java PR #2722](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2722), which itself depends on [Java #2704](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2704). Do not merge this child directly onto main without dependency reconciliation.

## Implemented

- `ActorScalarSuspendingLowering.compile(source,entry)` uses the **production** `OresCompiler.parseAndTypeCheck` (including ownership checking), then lowers a narrowly admitted checked `Ast.FunctionDecl` subset: `suspends fnc`, `int` args/locals/results, local bindings, direct nested CALL, checked i64 ADD, and `await async_gate()` with zero arguments.
- Async `int` gate declarations are **symbolic scheduler port bindings**; they are NOT arbitrary guest source functions executed in the native host. Runtime grants them by exact `Map<String,OresFuture<Long>>` and refuses unknown/missing port authority.
- `ActorScalarSuspendingMachine.task()` creates an `OresScheduler.Task<Long>`, whose `ActorSourceTaskFrame.callContinuation()` contains the active nested CALL chain. Function IDs, parent return PCs, local cells, operand cells and PCs persist in bounded actor-owned `ActorFrameArena` memory while Future completion re-enters the authenticated task scheduler. The carrier is free while suspended.
- Await from inside the callee returns `OresScheduler.await(future)`, preserving both the callee and parent frames. On resume the awaited scalar value is inserted into the same frame operand stack and execution continues from the saved PC.
- Every 2,048 interpreted scalar instructions the Task cooperates to yield the carrier; the same actor-owned continuation survives. Unsupported AST constructs fail closed **before a task is admitted**.
- A source-level `ActorCodeExecutor.receiveActor` may start the compiled Task and call `suspendCurrentUserMailboxUntil(future)`. This blocks subsequent DATA while the SourceTask owns an independent stack, in keeping with default serial receive semantics.

## Evidence contract

`ActorScalarSuspendingLoweringTest` includes:
1. Actual production parsed, type-/ownership-checked source with outer→child CALL and nested `await gate()`.
2. A real `ActorRuntime.spawnCodeActor` source receive, SourceTask scheduler, pending Future, separate receive and Task roots, two live CALL frames, no DATA overtaking, completed result 42, and physical frame retirement.
3. Rejections for await non-Future, unsupported AST blocks, missing entry, forbidden borrowed local; denial of host-thread task resumes and missing async port authority.

Run on a verified JDK25/GraalVM environment:
```sh
mvn -B -ntp -Dtest=ActorScalarSuspendingLoweringTest,ActorReceiveInvocationTest,SourceActorReceiveInvocationTest -DfailIfNoTests=true test
```

The exact source/head/tree/blob and nonzero runner/job steps must be authenticated before marking any case executed. Source-org CI has historically failed before runner admission. The checked-source test is **authored**, not yet executed. No private source is copied into alternate organizations by this PR.

## Explicit limitations

- **Not yet general compiler lowering of source-level `receive()` or `receive(Message)`**. The source receive entry is hosted by `ActorCodeExecutor` and starts the compiled scalar SourceTask as a structured task with its **own** stack; its parent receive root is separately retained. Full receive-as-continuation lowering is still needed to make all nested calls part of the receive's own stack.
- No arbitrary `await` expression or Future result types, no object roots/closures, no `defer` or catch/unwind continuation lowering, no exported frame capabilities, no compiler-emitted JVM bytecode/native image evidence, no VM-wide overflow containment.
- The portable syntax remains the existing `suspends fnc` and `async fnc`; no new keyword or weakened ownership rule.
- This is not five-backend parity or full guest execution until independent exact-head source tests and native/AOT lanes run.
