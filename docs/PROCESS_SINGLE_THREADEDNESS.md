# Single-threaded logical processes

Erlang/OTP supervision is the desired model: a supervisor is an ordinary
mailbox process that executes one user-code turn at a time. Grandaddy, daddy,
and child each need a distinct process identity and a distinct single-flight
lane; their code may run on different carrier threads *between* turns.

The safety invariant is `active_guest_turns(process_id) <= 1`, **not**
`active_guest_turns(runtime) <= 1`.

## What this change enforces

- VM ROOT user-code tasks share a bounded FIFO serial process executor.
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

Some legacy `AsyncRuntime` / `GeneratorRuntime` compatibility producer
paths still run guest bodies on virtual threads and can bypass the ROOT lane.
The next stage must continuation-lower those bodies, or fail closed when they
target process-owned state. A lock held across a blocking await is not a
correct workaround: it can deadlock its own producer.

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
