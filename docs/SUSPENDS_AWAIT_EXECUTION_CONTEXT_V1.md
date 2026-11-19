# AttoLang suspends/await execution-context contract (v1)

Status: **candidate acceptance, not cross-backend completion**.

## Canonical language contract

- `suspends fnc f(...): T` returns **T**, not an implicit `Future<T>`; calling it may suspend its caller's logical continuation.
- `await x` is for an explicitly awaitable operation (Task or I/O Future), not an arbitrary T returned by a `suspends fnc`.
- A plain `fnc` cannot call a `suspends fnc` without a specified effect-safe boundary. A `critical` block or actor state write must not suspend.
- `task { ... }` creates a **separate execution** and yields an awaitable handle. Current Java guest spells it `Future<T>`; portable `Task<T>` remains separate API work. Tasks and `suspends` call frames are not the same thing.
- `async fnc` remains a legacy compatibility spelling. New canonical `.atto` programs should use `suspends` without breaking existing programs until versioned migration.

## Execution identity and real continuations

Each admitted Task owns a runtime-minted stable `ExecutionContext` identity (execution ID and optional allocation domain) across all awaits/cooperation. The scheduler separately owns admission/cancellation state and source Tasks separately retain `TaskContext` bindings. Today the `parentExecution` and `continuation` fields are not yet fully compiler-lowered frame handles; do not infer that completed work from the type alone. `ExecutionContext` is **not** a physical thread, carrier, dispatch, or automatic allocation authority. Every fresh scheduler turn gets a new dispatch ID. A child Task has a distinct execution ID, although immutable task-local bindings can be inherited. A child started during an authenticated turn of the **same scheduler** records the parent's execution number as immutable causal metadata; a Task started from a foreign producer, timer, or host thread is a root. This field never grants allocation, actor-state, or cancellation authority.

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


## Execution-owned timer suspension / reactor coexistence (runtime candidate)

The **new runtime-only** \`OresScheduler.sleepNanos(OresTimers, delayNanos)\`
returns a compiler-lowered \`Sleep<T>\` Step. It must only be emitted by
\`susp\`/legacy \`suspends\` lowering, with no active non-suspendable
borrow/critical lease. A source \`sleep_blocking(duration)\` inside such a
frame is **synchronous-looking source syntax**, NOT a host synchronous
\`Thread.sleep\`. Its compiler lowering stores a continuation PC and produces
the Sleep step; on resume it returns void. Non-suspending callers and
standalone synchronous roots must fail closed unless a separately verified
parkable-fiber implementation exists.

The timer registration is **owned by the existing TaskRunner**, unlike an
NIO readiness/selector \`OresFuture\` supplied by another producer. A timer
completion merely makes its owning execution eligible to resume; it must
not run the guest on its clock thread. The TaskRunner's scheduler handles
the completion exactly like I/O await:

- Keep the same \`ExecutionContext\` and immutable causal ancestry across
  sleep, NIO await and subsequent nested suspend calls.
- Every wake is a **new dispatch ID** on the owning scheduler; host NIO and
  timer producer callbacks observe no active execution authority or borrowed
  task-local state. \`TraceContext\` is restored from the task and never
  ambiently inherited from a reactor/clock worker.
- Scheduler close, task cancellation and resource failure revoke **only**
  the owned pending sleep registration. A shared NIO Future is observer-only
  and must not be cancelled by the waiter. \`CleanupAwareTask\` can still
  re-enter its scheduler-authenticated defer/guard cleanup lane after
  cancelling a sleeping execution.
- Sleep creates no new source Task, child execution identity or actor
  mailbox message. The clock may settle a Future but cannot grant an
  actor physical turn, reactor dispatch, actor-state lease, or continuation
  heap reclamation.
- For serial actors, retained *logical* continuation ownership and
  generation checks remain mandatory before guest resume; this host
  scheduler foundation does not yet install those claims at the actual
  actor/Truffle guest boundary.
- Existing \`OresTimers\` currently uses shared host clock threads for
  registration callbacks; it is NOT yet unified with a VM-shared NIO
  reactor/timer wheel. This branch makes wait/resume interoperable but
  does not claim no extra timer threads across the complete host runtime.

Host-only proof target:
\`ExecutionAffineSleepRuntimeTest\`, requiring actual stepful JDK 25 CI.
Typed Duration/source parser adoption and true native LLVM/.NET timer wake
continuations are still separate tasks; see atto-spec #47.

## Follow-up gates

- Java/Graal: nested guest call suspension and cancellation/cleanup; carrier migration; then Native Image.
- Java actor-scalar: #2704 only owns bounded CALL continuation frames so far. It still needs AWAIT opcode, typed reference roots, cleanup frame lowering and exact-head guest evidence.
- LLVM/.NET/Wasm/Forge/JS: execute byte-identical `.atto` fixtures using each real backend; enforce one-shot wakeups, generational fences, actor owned stacks/arenas, and no host-only stand-ins.
- Negative tests: `await` holding a critical state loan or borrow, duplicate completion, cancellation-vs-resume, stale actor generation, select arbitration, task scope physical join, task-local isolation after migration.
- Zero-step CI is runner admission failure, not verification.
