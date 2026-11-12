# AttoLang suspends/await execution-context contract (v1)

Status: **candidate acceptance, not cross-backend completion**.

## Canonical language contract

- `suspends fnc f(...): T` returns **T**, not an implicit `Future<T>`; calling it may suspend its caller's logical continuation.
- `await x` is for an explicitly awaitable operation (Task or I/O Future), not an arbitrary T returned by a `suspends fnc`.
- A plain `fnc` cannot call a `suspends fnc` without a specified effect-safe boundary. A `critical` block or actor state write must not suspend.
- `task { ... }` creates a **separate execution** and yields an awaitable handle. Current Java guest spells it `Future<T>`; portable `Task<T>` remains separate API work. Tasks and `suspends` call frames are not the same thing.
- `async fnc` remains a legacy compatibility spelling. New canonical `.atto` programs should use `suspends` without breaking existing programs until versioned migration.

## Execution identity and real continuations

Each admitted Task owns a runtime-minted stable `ExecutionContext` (execution ID, scheduler owner, task-local context and cancellation provenance) across all awaits/cooperation. It is **not** a physical thread, carrier, dispatch, or allocation authority. Every fresh scheduler turn gets a new dispatch ID. A child Task has a distinct execution ID, although immutable task-local context can be inherited.

Nested `suspends` calls require bounded compiler-lowered continuation frames with live typed locals, operand stack, return PC, cleanup state, ownership loan state, and actor generation where applicable. A Future completion may only enqueue a one-shot, generation-fenced continuation onto its owning scheduler or actor lane. The producer callback may not execute guest code inline, adopt the producer's ambient thread locals, forge actor admission, or reclaim suspended frame storage.

Nonreentrant actors retain their logical turn ownership across suspension, release physical carriers, and revalidate generation on resume. Cancellation and STOP revoke pending resumes and run owned cleanup before physical quiescence allows reclaiming actor memory.

## New executable acceptance slice

1. `tests/fixtures/suspends-await-v1/nested-task-context.atto`: actual compiler admission plus Graal guest run, nested `suspends` callers and Task awaits, cooperation, inherited immutable Task context, expected `42|req-7`.
2. `tests/fixtures/suspends-await-v1/reject-plain-caller.atto`: actual compiler rejection with effect/suspension diagnostic.
3. `SuspendsAwaitExecutionContextRuntimeTest`: pending Future, foreign producer, stable execution identity and new dispatch, no inline guest reentry, unbound terminal observer, no retained waiter (host-only).

Run with **exact head SHA and nonzero executed steps**, recording JUnit XML:

```sh
mvn -B -ntp -Dtest=SuspendsAwaitExecutionContextGuestTest,SuspendsAwaitExecutionContextRuntimeTest test
```

**Execution at authoring: NOT EXECUTED.** Tests and fixture presence are not pass evidence, nor do these checks establish native AOT or five-backend parity.

## Follow-up gates

- Java/Graal: nested guest call suspension and cancellation/cleanup; carrier migration; then Native Image.
- Java actor-scalar: #2704 only owns bounded CALL continuation frames so far. It still needs AWAIT opcode, typed reference roots, cleanup frame lowering and exact-head guest evidence.
- LLVM/.NET/Wasm/Forge/JS: execute byte-identical `.atto` fixtures using each real backend; enforce one-shot wakeups, generational fences, actor owned stacks/arenas, and no host-only stand-ins.
- Negative tests: `await` holding a critical state loan or borrow, duplicate completion, cancellation-vs-resume, stale actor generation, select arbitration, task scope physical join, task-local isolation after migration.
- Zero-step CI is runner admission failure, not verification.
