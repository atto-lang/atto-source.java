# Cooperative counted loops

`rt cooperative for let i = 0; i < end; i++ { ... }` is a distinct
source statement. It uses the existing counted-for grammar (with or without
parenthesized header). It does not change normal `for`, `for await` or `for-of`.

A loop checks a task-local quantum at each **completed back-edge** (after
body and update, even on `continue`). At 16-iteration boundaries it checks
for a 1 ms elapsed-turn target; it always offers a scheduler handoff after
128 iterations. A handoff uses `OresScheduler.cooperate()`, not `Thread.yield()`,
and resumes from the saved environment without replaying the body, update, or
condition. A terminating condition or `break` does not yield gratuitously.

The bytecode/Graal scheduler fully unwinds the previous carrier before
admitting the saved task continuation. Actor single-writer, mailbox control
lane, structured cancellation and borrow/MutexGuard suspension restrictions
remain intact. `UNTRUSTED` actor code remains fail-closed until logical-task
CPU/fuel/deadline accounting survives continuation hops.

`stdlib/cooperative.ores` provides sequential `map`, `filter`,
`reduce`, and `forEach` for `Array<T>`. Callback order is stable, and
array length is snapshotted once. These are **not** parallel operations, and
there is no per-item async iterator / Future overhead. An exception aborts
the current operation; no rollback of completed side effects is implied.

## Follow-up work

- Measure scheduler handoff counts and tail latency under actor contention.
- Expose actor CPU-share and GPU-work quotas as scheduler metadata. Do not
  infer CPU rights from heap limits, and do not pretend cooperative yielding
  provides hard real-time guarantees.
- Generalize to for-of, stream/observable operators and typed iterators once
  iterator suspension and lifetime rules are fully specified.
- Investigate compiler-generated non-recursive loop continuations to improve
  high-count throughput without Java callback stack growth.
