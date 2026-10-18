# Scheduler-tracked TaskHandle — stage 2 audit

Status: **draft, host API only, stacked on stage-1 PR #1402**.

## Implementation

- `OresScheduler.startTracked(Task<T>)` opts into a new `TaskHandle<T>` without altering the existing `start()` or `OresFuture<T>` ABI.
- `handle.cancel(reason)` owns a runtime-only `TaskCancellation` authority (stage 1); cancellation of the handle's result Future is permitted to win before the active guest turn exits.
- `handle.getDone()` is a distinct `OresFuture<TaskOutcome<T>>`, whose cancellation admission always returns false. Neither callers nor task cancellation can forge a physical-exit witness.
- The completion barrier waits for **both** the OresScheduler `onQuiescent` callback and terminal result notification, regardless of order.
- Cancellation requested before the underlying Future is bound gets replayed after bind, and the wrapper checks cancellation before every resumed guest step.
- Cancellation outcomes preserve a first reason; external cancellation of the result Future produces a generic cancellation outcome. A guest-thrown `CancellationException` without runtime cancellation remains `Failed`, not silently relabeled `Cancelled`. Cancellation requested after a terminal result returns false.

## Tests and limits

- `src/test/java/dev/oreslang/runtime/TaskHandleTest.java` has **21 scheduler-level JUnit cases** (actual Graal/Maven CI execution still pending).
- Cases cover running guest-turn quiescence, cancellation before dispatch, suspended await, cooperative handoff, terminal success/failure, timeout observation, forged `getDone().cancel()`, cancellation races, reason preservation, admission denial on closed schedulers, repeated observation, cancellation requests after terminal outcomes, and guest-thrown CancellationException being diagnosed as failure.
- A self-contained Java 21 host model compiled successfully, and 300 running cancellation/quiescence iterations plus success and guest-exception classification passed. **The model used a minimal scheduler/Future stub, not OresScheduler's real production source; it does not count as integration evidence.**
- Full source compilation, Java 21 Maven/GraalVM execution, LLVM parity, actor scope join, and source-language Task syntax are NOT verified.
- On some paths `OresFuture.cancel` invokes synchronous host notification callbacks; `TaskHandle.cancel` therefore does **not** currently guarantee bounded wall-time. Do not advertise nonblocking source cancellation until publication is decoupled and tested.
- The quiescence callback is the existing scheduler's guest-turn exit signal; it is not a guarantee about unrelated foreign native workers or asynchronous OS I/O.

## Required follow-up

1. Add actor/root `TaskScope` with lifetime ownership, atomic authorized reparenting, cancellation propagation, failure supervision, and a scope join barrier.
2. Harden `getDone()` and cancellation under source actor close, request-local turns, executor rejection, timed wall deadlines, repeated carrier migration, and teardown notification callbacks.
3. Expose `Task<T>` syntax only after parser/typechecker/lowering and source-level conformance; `do task` must retain supervisor authority even if its handle is dropped.
4. Validate exact PR-head Maven/JUnit and full GraalVM execution on an authorized runner. GitHub source-org Actions have been failing before executing steps.
5. **No cross-org private-source mirroring** before security issue #962's independently reviewed policy/provenance and two-private-repo preflight are enforced. Earlier exposure is recorded in issue #832.

## Merge discipline

PR #1402 is stage 1. This change is stacked on the head of #1402 and must not merge into main before stage 1, real compiler CI, and security approval. It is intentionally a host integration scaffold, **not** a claim of complete cancellable Oreslang source Tasks.
