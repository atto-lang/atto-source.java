# Actor Supervisor Restart Circuit v1

Status: runtime policy contract under review.

This contract defines the fail-closed restart-admission policy used after a
generation-fenced terminal child CONTROL notice has been accepted. It is
separate from actor creation, timers and guest callback execution.

## Invariants

1. **Generation fencing.** A failure can affect restart state only when its
   child index and actor generation equal the currently live generation.
   Duplicate/stale notices return STALE and consume no restart budget.
2. **No partial group restart admission.** `one_for_all` and `rest_for_one`
   calculate the complete replacement span before consuming budget. If the full
   span does not fit, the decision is ESCALATE and no replacement is admitted.
3. **Budget is charged per replacement generation.** Restarting N selected
   children costs N entries in the intensity window. Large group strategies
   cannot disguise resource amplification as one restart.
4. **Monotonic time only.** Runtime orchestration supplies a monotonic timestamp.
   Clock regression fails closed and consumes no restart budget.
5. **Deterministic window boundary.** Restarts exactly at `now-window` still
   count. Entries expire only after they are strictly older than the cutoff.
6. **Exponential bounded delay.** Repeated unstable generations increase the
   delay exponentially up to the configured cap. Arithmetic must saturate
   instead of overflowing.
7. **Deterministic dephasing.** A reproducible generation/child/attempt hash may
   add at most 12.5% spread without exceeding the cap. No RNG or ambient entropy
   is required, so traces are replayable while simultaneous supervisors are
   less likely to herd.
8. **Stable-period reset.** A generation that runs for at least the configured
   stable interval resets the next restart attempt to one.
9. **OTP policy semantics.**
   - permanent: restart for normal, shutdown and abnormal child exits;
   - transient: restart abnormal exits only;
   - temporary: never restart.
   Parent/supervisor shutdown must bypass restart admission and tear the tree
   down directly.
10. **OTP strategy spans.**
    - one_for_one: failed child only;
    - one_for_all: every declared child;
    - rest_for_one: failed child and all younger declared children.
11. **Physical quiescence is proven before replacement.** A RESTART decision
    logically fences all selected old generations. The failed child's terminal
    CONTROL receipt is itself the physical-finalization proof for that child.
    Selected live siblings require explicit generation-fenced quiescence
    acknowledgements before replacement admission.
12. **Ordering is enforced, not merely documented.** Selected old siblings must
    quiesce in reverse declaration order. Replacement generations must then be
    admitted in declaration order. Out-of-order orchestration fails closed.
13. **Restart episodes cannot overlap silently.** If a new strategy span
    intersects an already-admitted pending restart episode, the new episode
    escalates instead of overwriting fences, double-charging ambiguously, or
    coalescing without a proof.
14. **Escalation beats thrash.** Restart-intensity exhaustion or an overlapping
    restart episode escalates to the parent supervisor/root CONTROL plane. The
    runtime must not silently reset counters, evict old budget entries early,
    or continue restarting.
15. **Bounded allocation-free history.** Child tables are bounded by the
    declared child count (maximum 4096). Restart timestamps live in a fixed
    primitive ring sized to the configured maximum restart count; a failure
    storm does not allocate boxed timestamp nodes.
16. **Arbitrary monotonic clock origin.** A separate presence bit tracks clock
    initialization; no signed timestamp value (including Long.MIN_VALUE on the
    Java substrate) is reserved as a sentinel.
17. **Deterministic observability.** Every decision has a monotonically
    increasing decision sequence. Host snapshots expose accepted replacement
    restarts, window occupancy, pending/quiesced replacements, escalations,
    overlap escalations, stale terminal notices, stale quiescence receipts,
    no-restart decisions and clock regressions. Long-lived counters saturate
    rather than wrapping negative.

## Orchestration boundary

The circuit does not sleep, create actors, allocate a carrier, invoke guest
code, or own a timer thread. A supervisor implementation must:

1. consume a child terminal CONTROL notice;
2. obtain one restart decision;
3. if RESTART, stop selected old siblings in reverse declaration order;
4. as each sibling physically finalizes, acknowledge its exact old generation
   to the circuit; never block an actor carrier while waiting;
5. await the deterministic delay on the runtime scheduler;
6. start replacements in declaration order; circuit admission rejects a start
   whose old generation lacks quiescence proof or whose earlier sibling in the
   same episode has not yet been replaced;
7. if ESCALATE, publish/propagate supervisor failure instead of restarting.

## Cross-backend parity

Java, LLVM and later hardware/runtime targets must produce the same decision
trace from the same child table, generations, exit classes and monotonic time
trace. Scheduler implementation details are not part of this contract.
