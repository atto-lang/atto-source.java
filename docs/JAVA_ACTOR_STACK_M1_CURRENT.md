# Java M1 actor-incarnation logical guest stack — current branch

Forward-port the **logical** stack-accounting slice from historical #2624
onto Java M0 + callback-pump PR #3011. The original M0/M1 drafts #2505 and
#2624 are intentionally not mergeable onto main without semantic reconciliation.

Each ActorRuntime.ActorCell now owns one ActorGuestStack for its incarnation.
The execution context binds it to the physical carrier and the guarded
synchronous guest invocations charge both carrier and actor budgets.
A permanently poisoned actor is failed at the mailbox boundary even if
trusted host code attempts to swallow the controlled budget exception.
One-worker tests require that actor B survives after actor A's poisoning,
without a behavior factory capturing mutable state from its host test.

**No actual native per-actor JVM stacks**, no reserve-bytes frame arena, no
source suspension frame charging, and no native StackOverflow recovery.
Actual compiled dual-actor .atto guest, cancellation, supervisor generations,
physical quiescence and AOT/five-backend parity still require independent tests.
The M0 guard remains the physical-call-stack defence; the actor logical budget
does not substitute for OS process isolation of untrusted native/JIT faults.

Require exact full-tree Git SHA and funded executed guest/host tests, preserve
independent review and source-org CI admission gates.
