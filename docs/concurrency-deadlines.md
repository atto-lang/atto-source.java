# Concurrency deadlines and deadlock safeguards

Oreslang root execution runs in a structured deadline scope derived from the
embedding policy's `maxWallTime`. The scope follows logical tasks, not carrier
threads: child scheduler tasks, actor lifetimes, pending channel reads/writes,
and select registrations inherit it. A child timeout cannot extend its parent's
remaining budget. Scope completion cancels abandoned descendants/operations.

Deadlines use monotonic time and a watchdog independent of CONTROL, actor, and
user scheduler pools. Timeout is an `OresTimeoutError`; it is not evidence of a
deadlock. Guest code can catch an operation timeout. Expiry of the enclosing
scope revokes that scope, so catching its error cannot reset the execution budget.
Compiler loop checkpoints observe both deadlines and cooperative cancellation.

## Language API

Milliseconds are non-negative integers; zero requests immediate expiry.

```ores
pub async fnc main(): void {
  val Channel<int> channel = Channel.new<int>(1);
  val Future<int> read = nb readch channel;
  try {
    await read.with_timeout(100);
  } catch (err) {
    stdio.stdout.write("read timed out:");
  }
  writech channel, 42;
  stdio.stdout.write(readch channel);
  return;
}
```

* `future.with_timeout(ms)` transfers timeout/cancellation authority over the
  original operation. It returns that same Future; every observer sees its
  outcome. Channel/select cancellation must win the operation's existing
  arbitration before withdrawal and timeout publication. If an operation has
  already committed, its result wins. Guest code cannot complete a Future.
* `future.wait_timeout(ms)` creates a separate bounded observation, leaving the
  producer and other observers untouched. Timeout or cancellation detaches that
  observation's waiter. A channel operation left pending by this form remains
  owned by its enclosing scope and must eventually complete or be cancelled.

Both support `Future<void>` and `Future<SelectResult>`. `await` preserves the
original Future type. Pending waits suspend using the scheduler ABI, including
reads/writes and blocking select lowered by the compiler. `nb select` work and
its underlying registrations inherit the enclosing lifetime; its detached body
must not be invoked from the timer/completion thread.

The Java runtime exposes `OresScheduler.start(task, Duration)` to create a nested
structured scope and `OresFutures.withTimeout` / `waitTimeout` for the same
operation/observation contracts. Ordinary `start(task)` creates a child lifetime
with its caller's remaining deadline, so timing out a task also cancels its
descendants. Inherited child lifetimes reuse the parent timer. Host-started tasks
outside an Oreslang root or explicit scope have no implicit deadline.

Policy arguments include `--ores-max-wall-ms`; this policy parser expects
`--ores-capabilities` as well. Without a capability argument, it selects the
existing developer policy (currently ten minutes). CLI launchers that build an
IsolatePolicy should pass its complete application arguments.

## Runtime invariants

* Expiration removes pending channel/select registrations before delivering the
  operation timeout. Later channel activity cannot consume an expired read or
  deliver an expired write. Cancellation and ready-case races retain channel
  arbitration; values are neither fabricated nor consumed by cancelled arms.
* A task waiting on a shared external Future loses only its scheduler waiter
  when its scope expires. The producer is cancelled only with ownership.
* Pending `Future.get`, timed `get`, and `join` are rejected on scheduler,
  actor, and Mailman carriers with `OresDeadlockError`. Terminal reads and a
  zero-duration host probe remain nonblocking. Timed/interrupted host waits
  detach their registrations instead of retaining them indefinitely.
* Mandatory task-Future dependencies are tracked across schedulers. Self-await
  and cycles fail with `OresDeadlockError` containing scheduler/task identities.
  Completion, cancellation and shutdown remove edges. External I/O and channels
  without a known unique producer are not declared deadlocked merely for being
  idle.
* Expired queued tasks cannot enter a guest context. Deadline failure for running
  tasks is published after the guest boundary exits. A scope owner's completion
  also waits asynchronously for running descendants to leave their boundaries;
  no watchdog/carrier thread performs a blocking join for this purpose.

## Limits and verification

This is a bounded-wait/recovery contract for suspended and cooperative guest
code. It cannot safely kill arbitrary native calls, host hooks or trusted guest
code that ignores checkpoints. Such work can delay scope completion and requires
an isolate/process revocation boundary for a hard guarantee. Runtime cancellation
hooks must remain short, nonblocking runtime plumbing; no guest code executes in
watchdog callbacks. Timers and registrations are detached on normal completion
and cancellation.

Cycle detection currently covers mandatory scheduler task-Future dependencies.
It does not infer arbitrary actor request/reply protocols, channel producers,
locks, multi-way selection dependencies, or source-level wait chains. Broader
wait-graph instrumentation can be added when those ownership relations are
explicit. Persistent actor-class/stream work on the separate actor branch still
needs convergence with this channel/select implementation. One-shot actor
invocation timeout stops its producer; replies publish only after actor
finalization and guest-turn exit.

`DeadlockSafeguardsTest` exercises stuck reads/writes/Futures/select, shared
observation, inherited deadlines, abandoned child cleanup, carrier starvation,
cooperative and non-cooperative execution, actor expiry, cycles, host wait cleanup,
and healthy work after failure. `ConcurrencyDeadlineLanguageTest` executes the
source syntax, catch/recovery, and root deadline through the Polyglot runtime.
JUnit/process timeouts are independent backstops; assertions check the runtime's
own outcome and cleanup.

`examples/concurrency-deadlines.ores` prints `timeout:42`. CI executes it with
an external process backstop in JVM/JIT, AOT, and hybrid jobs. A native build
and its smoke test must succeed before claiming that backend is verified.
