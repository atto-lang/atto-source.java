# Runtime-bound queue sender authority (host-side only)

This proposal is stacked on the Java actor-state draft PR #760.

ActorRuntime.QueueCommandAuthority is created only by the runtime for a
structured child actor. It records the actual spawning parent as supervisor
and binds its own receiving actor incarnation. The actor's currently active
transport envelope supplies the sender; guest payload IDs are never used
as proof of authority.

ACTIVATE, RECEIVE, CLOSE, TERMINAL_ACK, RETIRE and producer grant/revoke
require the authenticated spawning parent. SEND and CANCEL require a live,
explicitly granted actor ID in the correct producer slot. Deny host-origin
mail, missing sender, cross-actor usage, startup-time usage, retired parent,
stopped producer, replayed grants and stale saved authority.

This is intentionally ONLY the Java host ActorContext API; there is no
source-language lowering yet. The default TypedQueue from queue PR #19
must remain deny-all until the compiler passes verified authority to a
sealed queue method. The queue itself must separately validate request
identities, cancellation after admission, close, output reservations,
waiter notifications and terminal delivery.

Source regression: QueueRuntimeAuthorityTest.java. This test is authored,
but was not executed on JDK25, GraalVM or backend guests in this turn.

Remaining release gates: actual compiler guest lowering, authenticated
producer delegation lifecycle, output-full failure atomicity, bounded
mailbox + reply budgets, exact CI runner execution and independent
five-backend conformance.
