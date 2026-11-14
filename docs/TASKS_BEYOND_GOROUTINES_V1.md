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
| 10 | **Source task handle**: `t.cancel()`, `t.cancel_with(#reason)`, `t.is_cancel_requested()`, `await t.get_done()` | Implemented (source), tested |
| 11 | **Timeouts**: `t.cancel_after(ms)`; `task scope deadline(ms) { }` | Implemented (source), tested |
| 12 | **Scope policies**: fail-fast by default (first failure cancels siblings); `task scope supervise { }` | Implemented (source), tested |
| 13 | **`try { } finally { }`** without `catch` | Implemented (sync, async, cancel unwind, generators), tested |
| 14 | **Task-local context**: `task context(#key, "v") { }`, `task_context(#key)` | Implemented (source), tested |
| 15 | **Checkpoints**: CPU loops without `await` are cancellable (loop back-edges); `task_cancel_requested()` for early exit | Implemented, tested |
| 16 | **First-success**: `Future.first([...])` returns the first result and cancels losing task handles (Loom `ShutdownOnSuccess`) | Implemented (source), tested |
| G5b | Source `throw`; typed `raise`/`recover` | **Missing** (gated in the parser/checker) |
| G6 | Cancel-safety contract for `select` arms, channels and native I/O at source level | **Partial** — see "Cancel-safety rules" |
| G8 | Executing CI evidence | Hosted runners still fail admission; verified locally only |

## Source API (v1)

```ores
let Future<int> t = task { ... };       // owned by the innermost task scope, if any
t.cancel();                             // request; cleanup runs in the task
t.cancel_with(#no_longer_needed);       // request with a reason
t.cancel_after(250);                    // timeout in ms (reason: timeout)
t.is_cancel_requested();                // bool
val outcome = await t.get_done();       // #success | #failed | #panicked |
                                        // #cancelled | #cleanup_failed
await t;                                // value, or the failure/cancellation

task scope { ... }                      // joins children; first failure cancels siblings
task scope supervise { ... }            // failures stay with the child; panics still escape
task scope deadline(250) { ... }        // children cancelled at expiry; scope then exits
task scope deadline(250) supervise { }  // options combine

task context(#trace_id, "abc") { ... }  // inherited by child tasks and awaited calls
task_context(#trace_id)                 // Option<String>

task_cancel_requested()                 // inside a task block: has cancel been asked?

await Future.first([a, b])              // first result wins; losing task handles are
                                        // cancelled; borrowed Futures only unsubscribed

try { ... } finally { ... }             // failure propagates after finally
```

Semantics worth knowing:

- `get_done()` never throws. It resolves only after the task has physically
  stopped and its locks/leases were released. `await t` may resolve earlier.
- Only a Future made by `task { }` has these members. Calling them on any
  other Future (I/O, timers, `map`, channels) is an error: an observer cannot
  cancel a producer it merely borrowed.
- A cancelled child does not fail its scope (Kotlin semantics). A failed child
  does, unless the scope is `supervise`. A failure is never hidden behind an
  earlier sibling cancellation.
- A task that returns after its cancellation was requested reports
  `#cancelled`: the request won.
- A deadline cancels the scope's *child tasks*. The scope body keeps running
  to its own exit, then joins (Trio `move_on_after` for children). Use
  `t.cancel_after(ms)` to bound a single task.
- Context values are immutable `String`s in v1, so context can never carry
  mutable state around the ownership checker.

## Cancel-safety rules

1. Cancellation is delivered only at suspension points: `await`,
   `rt cooperate`, task scope exit, and loop back-edges (soft preemption).
   Straight-line code between them is never interrupted.
2. The compiler rejects suspension while a `MutexGuard` is live. So a
   cancelled task can never be caught holding a lock; guards are released at
   block exit before the next suspension point.
3. Cancelling a task never cancels a Future it was awaiting; the waiter is
   only unsubscribed. A borrowed producer's result is not lost.
4. Cleanup (`defer`, `finally`, iterator close) may itself `await`; it runs
   shielded (no second cancellation), bounded by 4,096 cleanup dispatches,
   after which the task is quarantined as `#cleanup_failed`.
5. Not yet proven at source level: exactly-once arbitration of a `select`
   arm that wins at the same moment its task is cancelled, and native I/O
   that cannot be interrupted. These stay open (G6).

## Remaining work

1. **`select`/channel cancel-safety tests** at source level (G6).
2. **Source `throw` and typed `raise`/`recover`.**
3. **Other backends** (LLVM, .NET, Wasm, Cranelift, Forge) need the same
   behaviour; the Java guest tests listed below are the reference traces.
4. **Formatter and syntax highlighters** need `task scope deadline(..)`,
   `supervise`, `task context(..)`, `Future.first` and catch-less `try`.

## Bugs fixed (PR #2521)

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

## Bugs fixed (source task API change)

- **Cancelled tasks skipped their `defer`s.** `TaskHandle.start` wrapped every
  task in a plain lambda, dropping the cleanup-aware marker, and killed a
  cancelled task by throwing at its next checkpoint. Every task in a
  `task scope` and in `map_parallel` was cancelled without running cleanup.
  Cleanup-aware tasks now keep the marker and are unwound by the scheduler.
- **Lost wakeup on cancel-before-first-turn.** The checkpoint path set a
  cleanup-aware task TERMINAL with no outcome; its deferred-cancellation
  Future never settled (about 1 in 150 runs). Covered by
  `SourceTaskCancelRaceTest` (200 runs).
- **A cancelled child failed its scope**, and the first recorded non-success
  could hide a later sibling failure. Both fixed.

## Evidence (source task API change)

Local, JDK 21, `mvn -o test`, macOS arm64, after merging `main` at
`16813b448b`.

- New guest-source tests: `SourceTaskCancelTest` (16),
  `SourceTaskCancelRaceTest` (200 cancel-vs-first-turn races),
  `SourceTryFinallyTest` (6), `SourceTaskContextTest` (6). All pass,
  repeated 3-5 times.
- Full suite, 4,227 tests: this branch 56 failures + 17 errors; untouched
  `main` (4,198 tests) the same 56 + 17. **No test fails on this branch that
  passes on `main`.** The failures are pre-existing and unrelated.
- A 1,200-run stress of cancel-vs-first-turn found the lost wakeup before the
  fix and none after.

## Evidence (PR #2521)


Local, JDK 21 (Homebrew OpenJDK 21), `mvn -o test`, macOS arm64.

- Focused task, cancellation, mutex, scheduler, actor-STOP, structured-scope,
  source cleanup and formal model suites: **533 tests, 0 failures**.
- Full suite after merging `main` at `009e922826`, 4,122 tests: this branch
  74 failures + 20 errors; untouched `main` 87 failures + 26 errors. **No test fails on this branch that passes
  on `main`**; 19 task/lock/cancellation tests move from failing to passing.
  The remaining failures are pre-existing and outside this area (outbound HTTP
  guest tests, type-checker drift).
- This is local evidence only; hosted CI still fails at runner admission.
