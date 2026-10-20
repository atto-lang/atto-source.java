# TaskScope cancellation: fail-closed admission and subscriber publication

Status: **draft hardening; exact-head Maven/Graal integration unverified**.
Stacked on task capture branch PR #1468; issue #1613.

A child `OresFuture` cancellation-admission callback may throw before its
terminal CAS. The enclosing scope must still attempt to cancel every sibling,
and it must never report that failed child as physically quiescent. Runtime
admission exceptions are now recorded on the scope failure while remaining
children are processed. A failed child retains its live ticket until a real
terminal completion and post-carrier `quiesced()` witness.

After all successful cancellations have claimed and revoked producer
authority, notification callbacks run off the cancelling carrier. If virtual
thread creation fails, emergency daemon platform-thread publication replaces
the former synchronous `cancellation.publish()` fallback. A blocked user
subscriber must never execute on the actor lane performing sibling revocation.
The emergency fallback is intentionally a liveness safeguard, not a
hard-real-time qualified bounded executor.

**Remaining gaps:** high-frequency cancellation storms could exhaust native
threads. The final fail-closed resource-exhaustion path records failure but
cannot promise notification delivery if no thread can start. A production
fixed-budget/queue notification dispatcher and scheduler-aware ownership
fencing remain prerequisites for hard-real-time and complete no-Zalgo parity.
No test here proves guest Oreslang Task syntax, exact JDK25 runtime or native
LLVM backends.
