# Automatic cooperative scheduling for source loops

No syntax annotation or `await` is needed. Ordinary `for`, `loop`,
`for-of`, and destructuring `for-of` use scheduler-controlled, resumable
loop back-edges **when running in a heap-owned SourceTask**.

The current soft policy samples runtime/cancellation state every 16 back-edges,
uses a nominal 1 ms CPU-turn wall-clock target, and permits at most
128 back-edges per cooperative turn (unless a live MutexGuard defers yielding).
The task returns `OresScheduler.cooperate()` after the loop's body and update,
and its saved continuation resumes on a new dispatch **after** the old carrier
has fully unwound. No iteration or side effect is replayed.

These numbers are heuristics, not realtime deadline guarantees. A long single
iteration, blocking native/FFI call, GC or JIT pause can overshoot the quantum.
For UNTRUSTED actors, fuel/cancellation is charged at every back-edge but
automatic cooperation is disabled until logical-task quota continuity survives
scheduler handoff. Memory quota is **not** a CPU/GPU scheduling entitlement.

Synchronous source-defined actor `receive(ActorMail<T>)` bodies containing loops
are continuation-lowered automatically (see PR #479). Other synchronous
functions and unary actor `run` handlers that remain on a Java evaluator stack
cannot be forcibly stacklessly suspended by a timer; extending continuation
lowering to these paths is a separate required hardening step. Hard termination
of native or non-cooperating operations requires external isolation/revocation.

`rt cooperate` remains an explicit optional scheduling point. `await`
continues to mean asynchronous dependency suspension, not voluntary CPU
time slicing.

`stdlib/cooperative.ores` supplies ordered sequential `map`, `filter`,
`reduce`, and `forEach` using **ordinary for loops**. These are no per-item
Future allocations. Underlying loops receive cooperative preemption only
when they are continuation-lowered; until general synchronous helper lowering
is complete, do not describe all helper calls as universally preemptible.

## Next hardening gates

- Verify compilation and integration with GraalVM JVM/AOT/hybrid CI and exact
  head mirrored runs, not runner-admission failures.
- Extend safe continuation lowering to normal synchronous helpers, unary actor
  `run` and lexical callback paths without silently changing synchronous
  return types or promoting actor reentrancy.
- Add per-actor CPU share, GPU work budget and live scheduler pressure as inputs
  to the quantum; do not derive CPU share from heap size.
- Measure actor fairness, cancellation latency, output ordering, and allocation
  cost of collection operators under load.
