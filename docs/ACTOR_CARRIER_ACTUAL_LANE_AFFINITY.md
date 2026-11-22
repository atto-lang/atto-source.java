# Actor physical carrier locality: execute-lane hint (2026-11-18)

## Java OresVM / OresScheduler actor-pool audit

OresVM's prestarted CONTROL/root carrier pool is separate from ActorRuntime's
SHARED, PRIVATE, and UNTRUSTED actor dispatchers. A completion producer or
CONTROL thread never executes an actor guest turn inline. Actor physical-turn
claims, scheduling tickets and bounded STOP/CONTROL admission are unchanged.

The existing ActorAffinityDispatcher has soft lane selection with delayed idle
stealing. Before this patch, ActorCell updated its home lane from the target
selected **on submission**, but a queued turn might actually be claimed by an
idle stealing lane after the grace. The next continuation then favored the
wrong carrier: a predictable loss of L1/L2 working-set locality.

The dispatcher now provides the *executing lane* to an internal callback from
its existing exactly-once queue claim. ActorCell rehomes only if demand growth
is enabled and the exact dispatch ticket remains current with no active actor
turn. A stale gate/executor callback cannot rehome a later actor. The existing
ActorPhysicalTurnGate runnable is still invoked; the callback does not bypass
CONTROL, gate permits, actor ownership, or cancellation. Pinned-CPU lanes
continue to require strict placement and are never rehomed.

This change is a privileged Java host runtime detail: no new .atto guest API or
Java-only guest algorithm. Native C/LLVM parity can use its existing
last-carrier physical-turn hint; no new cross-backend language feature.

## Evidence requirements

The added `physicalExecutionLaneIsReportedAfterIdleSteal` test checks real
steal-lane reporting and exactly-once execution while the home lane is blocked.
Run existing ActorAffinityDispatcher, ActorAffinitySoftSpill,
ActorPhysicalTurnGate, OresSchedulerContinuationLocality and ActorRuntime tests
at the **exact PR head**. Test real guest await/resume on the Java Truffle
backend and record the execution profile. Run hardware L1 and L2 cache-miss
counters, cache misses per resumed continuation, ops/s and p99 latency at a
fixed CPU quota before claiming a performance improvement. A migration or
spill count is only a proxy, never an L1/L2 hardware measurement.

**Execution status:** source-validated patch only; full Maven, Graal AOT,
stepful CI and CPU hardware-counter benchmark are NOT EXECUTED in this pass.
