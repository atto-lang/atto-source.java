# Tasks: better than goroutines — status and roadmap (v1)

Date: 2026-11-03. Status: **audit + roadmap**. The "Implemented" rows below
were executed locally on JDK 21 (`mvn -o test`) at this branch's head; see
"Evidence" at the end.

## Goal

Oreslang concurrency is tasks, actors and async/await multiplexed onto thread
pools. Actors are the sophisticated isolation unit. Tasks are lightweight like
goroutines, but they must be **better** than goroutines:

1. **Composable** — tasks are values you `await`, combine and return.
2. **Structured** — a task has an owner; a scope does not exit until its
   children have physically stopped.
3. **Cancellable** — cancellation is a first-class request with a reason,
   delivered at suspension points, impossible for guest code to swallow.
4. **Self-cleaning** — when a task is cancelled (or fails or panics), its
   `defer`s, `finally`s, iterator closes and **held locks** are released
   automatically, exactly once, before anyone is told the task is done.

Go gets none of 2–4 for free: a goroutine has no handle, no owner, no
cancellation except a manually threaded `context.Context`, and a goroutine
that dies or is abandoned while holding a `sync.Mutex` leaks it forever.

## What we take from whom

| Source | Idea | Oreslang mechanism |
| --- | --- | --- |
| Kotlin | Structured concurrency: parent waits for children; child failure cancels siblings | `TaskScope`, source `task scope { }` / `nb scope { }` |
| Kotlin | Cancellation is delivered *at a suspension point* and runs `finally` | `OresScheduler.CleanupAwareTask`: one authenticated cancellation resume is injected; `SourceTask` unwinds `defer`/`finally`/guards |
| Kotlin | `NonCancellable` for cleanup that must await | Cleanup turns stay in cleanup mode across their own `await`s; cancellation is never re-injected |
| Kotlin / Swift | `Job` / `Task` handle with `cancel()` and `join()` | `TaskHandle.cancel(reason)`, `result()`, `getDone()` (host only today — see G1) |
| C# | `CancellationToken` with linked sources and registered callbacks | `TaskCancellation` tree: first reason wins, one-way links, revoker hooks before observer hooks |
| C# | `IAsyncDisposable` / `await using` | async iterator close-once capability shared by completion and cancellation |
| Rust | RAII guards released on unwind and on drop | implicit `MutexGuard` release at lexical block exit; task-owned leases released on task retirement |
| Rust | `Mutex` poisoning | abandoned *holder* poisons `SharedMutex`; a cancelled *waiter* never poisons; `recover(...)` repairs |
| Rust | `Send`/`Sync`, borrow checker across tasks | owned/`Send` captures, `rt borrow` rules, borrow-across-await rejection |
| Java Loom | `StructuredTaskScope`; cheap blocking without parking OS threads | `TaskScope`; `_blocking` APIs may suspend without parking a carrier; actor carriers never block (`lock()` is rejected in actors) |
| Trio | Cancel scopes, deadlines, shielding, "every await is a checkpoint" | wall deadlines (untrusted actors only today); `rt cooperate`; `cancellationCheckpoint()` in combinators |
| Erlang/OTP | Supervisors, links vs monitors | actor supervisors; task failure reaches scope/supervisor; links ≠ ownership |

Things we do that most of these do not:

- **Physical quiescence barrier.** `result()` may settle before the carrier
  has left guest code; `getDone()` settles only after the task and all its
  children physically stopped and its locks/leases retired. Nothing may free
  actor memory or reuse a lock on `isDone()` alone.
- **Failed cleanup is reported, never faked.** A failed unlock or exhausted
  cleanup budget yields `CleanupIncompleteError` and quarantines the task;
  `getDone()` reports `Failed(CleanupFailure)` with the original outcome kept.
- **Panic dominates.** A panic during cleanup is never demoted to
  cancellation; an ordinary cleanup error never replaces the primary error.
- **Deadlock detection** on `SharedMutex` wait cycles.

## Status matrix

| # | Capability | Status |
| --- | --- | --- |
| 1 | Cancellation tree, first-reason-wins, links, adoption | Implemented (host), tested |
| 2 | Structured scope joins unawaited children; child failure escapes after drain | Implemented (source), tested |
| 3 | Cancel at `await` unwinds `defer` LIFO, `finally` once, guards, iterator close | Implemented (host + source), tested |
| 4 | Guest cannot catch or forge cancellation | Implemented |
| 5 | Locks auto-released when owning task is cancelled/fails/forgets | Implemented (`TaskOwnedLeases` + `OresMutex`), tested incl. grant-vs-cancel races |
| 6 | Actor STOP runs suspended tasks' cleanup before reclaiming the actor | **Fixed in this change** (was dropped by a merge), tested |
| 7 | Failed cleanup quarantines without hanging actor STOP | **Fixed in this change**, tested |
| 8 | Bounded cleanup (4096 cleanup dispatches) | Implemented, tested |
| 9 | `all` / `race` / `all_settled` cancel losers; bounded `map_parallel`; waterfall; pipeline | Implemented (host), tested |
| G1 | **Source-level task handle**: `task { }` returns `Future<T>` with no `cancel`/`get_done` | **Missing** — PR #1468 is stale/conflicting; issue #2150 |
| G2 | **Timeouts as cancel scopes** (`with_timeout`, `task scope deadline 2s { }`) | **Missing** for ordinary tasks; wall deadlines exist only for untrusted actors |
| G3 | **Scope policies**: supervisor scope (failures don't cancel siblings), first-success scope (Loom `ShutdownOnSuccess`) | **Missing**; scopes are fail-fast only |
| G4 | **Task-local context** (Loom `ScopedValue`, Swift `TaskLocal`, Kotlin context) for trace IDs, deadlines, permissions | **Missing** |
| G5 | Source `try`/`finally` without `catch`; source `throw`; typed `raise`/`recover` | **Missing** (parser requires `catch`; `throw` gated) |
| G6 | Explicit cancel-safety contract for `select` arms and I/O (tokio-style "cancel-safe" ops) | Partial: host revokers exist; no source-level guarantee/annotation |
| G7 | Cancellation checkpoint in long CPU loops without `await` | Partial: `rt cooperate` and soft preemption; no `rt check_cancelled` |
| G8 | Executing CI evidence | Hosted runners still fail admission; this change was verified locally only |

## Roadmap (recommended order)

1. **G1 — source `Task<T>` handle.** Rebase or replace #1468:
   `let Task<int> t = task { ... };` with `await t`, `t.cancel(#reason)`,
   `await t.get_done()` → `TaskOutcome<T>` (`Success | Failed | Panicked |
   Cancelled`). Lower to the existing `TaskHandle`; do not create a second
   runtime. Every `task { }` stays owned by the innermost scope; dropping a
   handle never detaches.
2. **G2 — deadlines as scopes.** `task scope deadline 250ms { ... }` and
   `with_timeout(d, || -> ...)`. Expiry is an ordinary authenticated
   cancellation of the scope (reason `#timeout`), so cleanup and lock release
   reuse rows 3 and 5. Generalize the untrusted-actor wall-deadline timer.
3. **G3 — scope policies.** `task scope supervise { }` (child failure is
   reported, siblings continue) and `task scope first { }` (first success
   cancels the rest). Both must still join physically before exit.
4. **G5 — `try`/`finally` without `catch`.** Smallest grammar change with high
   value for cleanup code; `defer` remains the idiomatic form.
5. **G4 — task-local context.** Immutable, inherited by child tasks, bound per
   scope (Loom `ScopedValue` semantics, not mutable thread-locals).
6. **G6/G7 — cancel-safety and checkpoints.** Document which awaits are
   cancel-safe; add `rt check_cancelled` for CPU loops.
7. **LLVM / other backends** implement the same state machine; the Java
   tests in "Evidence" are the reference traces.

## Bugs fixed in this change

- **Actor STOP never ran suspended task cleanup.** Merge `7ff9948aaf`
  ("reconcile actor STOP cleanup and graceful teardown") kept only the
  graceful-teardown condition in `ActorCell.schedule()`, `runBatch()`, the
  post-batch reschedule, and an early `if (stopped) return` in
  `runBatchEntered()`. The authenticated cleanup turns were enqueued but never
  dispatched, so a stopped actor's suspended tasks never ran `defer`s or
  released lock guards. All four gates now admit the cleanup lane; ordinary
  mail and behaviour initialization remain blocked after STOP.
- **Failed task retirement hung actor STOP.** A task whose retirement callback
  threw (or whose cleanup was marked incomplete) stayed in the scheduler's
  live set forever, so `actorStopPhysicallyQuiescent()` never became true and
  STOP timed out instead of quarantining. Such tasks now move to a separate
  strongly-held `quarantined` set: still reported by `hasIncompleteCleanup()`,
  never reclaimed, no longer counted as running.
- **Tests that never ran.** Eleven task/cancel/lock tests had drifted from
  hardened runtime rules (host guards owned by the acquiring thread, actors may
  not block on `lock()`, stateless actor factories, `block { }` and
  `rt borrow` syntax) or had races. They now encode the intended behaviour.

## Evidence

Local, JDK 21 (Homebrew OpenJDK 21), `mvn -o test`, macOS arm64.

- Focused task, cancellation, mutex, scheduler, actor-STOP, structured-scope,
  source cleanup and formal model suites: **533 tests, 0 failures**.
- Full suite after merging `main` at `009e922826`, 4,122 tests: this branch
  74 failures + 20 errors; untouched `main` 87 failures + 26 errors. **No test fails on this branch that passes
  on `main`**; 19 task/lock/cancellation tests move from failing to passing.
  The remaining failures are pre-existing and outside this area (outbound HTTP
  guest tests, type-checker drift).
- This is local evidence only; hosted CI still fails at runner admission.
