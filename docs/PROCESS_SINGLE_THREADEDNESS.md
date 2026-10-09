# Single-threaded logical processes

Erlang/OTP supervision is the desired model: a supervisor is an ordinary
mailbox process that executes one user-code turn at a time. Grandaddy, daddy,
and child each need a distinct process identity and a distinct single-flight
lane; their code may run on different carrier threads *between* turns.

The safety invariant is `active_guest_turns(process_id) <= 1`, **not**
`active_guest_turns(runtime) <= 1`.

## What this change enforces

- VM ROOT user-code tasks, public function entry and source-module init
  share a bounded FIFO serial process executor.
  Distinct ROOT tasks cannot execute at the same time. `await` and
  `cooperate` unwind the turn before another ROOT task is admitted.
- ROOT can migrate across CONTROL carriers; CONTROL carriers are still
  prestarted and parallel, even though ROOT has scheduler parallelism 1.
- `OresVM.createSupervisorScheduler(name)` creates another independent
  single-flight scheduler/identity in the same VM. A daddy process can thus
  run concurrently with sibling daddies; each serializes its own tasks.
- ActorGroup mailmen use a *separate* CONTROL scheduler. Each GroupMailmanState
  already has its own single-execution lease across suspend/resume.
  Strict/adversarial contexts retain their required caller-thread admission
  queue instead of dispatching Truffle/JNI guest work onto CONTROL carriers.
- Child actor mailboxes keep their existing per-actor single-writer gate.
  Independent supervisors and actors can run in parallel.

## Not yet a complete 100% guest-language guarantee

Legacy `AsyncRuntime` / `GeneratorRuntime` virtual-thread producer paths
still exist, but unadmitted guest evaluation now rejects at
`OresEvalRootNode.executeRaw` rather than silently bypassing the ROOT lane.
Full generator and legacy async functionality requires continuation lowering
back onto the owning scheduler. No lock may be held across a blocking await:
that could deadlock the producing process.

Creating distinct supervisor scheduler lanes in one VM is now supported.
However, a future supervisor language construct still needs to bind each
daddy's mailbox, lifecycle and owned state to the right scheduler/identity,
rather than treating a scheduler constructor alone as full process isolation.

Trusted ROOT/supervisor code remains cooperative, Node.js-like: a tight
synchronous loop can monopolize *its process*. Untrusted child actors need
compiler-injected resumable checkpoints and bounded fuel/time budgets.
Runtime-only safepoint polling cannot suspend an arbitrary Java stack.

## Next acceptance tests

- Source-level shared ROOT state, concurrent async functions and `await`.
- Group mailman and ROOT overlap, with group state serialized per group.
- `select`, `readch`, `writech`, timers and I/O completion callbacks never
  execute guest continuations on producer/carrier-control threads.
- Cancellation, stop, hot reload, process tree restarts and async generator
  migration have no bypass path.

## Entrypoints and guest migration

`OresEvalRootNode` routes INIT_ONLY, INVOKE_PUBLIC and default-run source
initialization through `OresContext.runRootGuestCall`. It refuses
cross-process synchronous guest calls to ROOT from actor or other scheduler
turns. This avoids an otherwise unprotected host/polyglot export bypass.

ROOT calls from external host entry points can synchronously wait for the
return value; inside any logical process, suspension must use `await`
continuations and message passing, never synchronous cross-process invocation.

## Fatal carrier and shutdown hardening

- A serial process queue never executes a rejected guest task on its
  producer thread. If a fatal active turn exits and the carrier executor
  then rejects the replacement drain, accepted rejection-aware guest turns
  are settled exceptionally so their Futures cannot hang indefinitely.
  Ordinary caller-provided bare Runnables do not currently have a general
  rejection callback; the OresScheduler bridge uses rejection-aware tasks.
- A failing close hook cannot skip the other close hooks, pending task
  cancellation or owned-carrier shutdown. Failures are aggregated and
  reported after best-effort teardown.
- Close-hook registration and shutdown snapshot use a common lock.
  A hook registered while close races either joins the snapshot or executes
  immediately afterward, exactly once; hooks are runtime teardown callbacks
  and must never re-enter guest code outside an admitted process turn.
- Code actor `concurrency` is frozen permanently when its first user
  message starts. The configured limit governs unfinished message lifetimes,
  **not** the number of simultaneous native guest turns.

Focused tests: `OresSchedulerTest`, `SerialProcessExecutorTest`,
`ActorMailboxExecutionBoundaryTest`. These tests must be executed at an
exact Git commit before any production-strength concurrency claim.
