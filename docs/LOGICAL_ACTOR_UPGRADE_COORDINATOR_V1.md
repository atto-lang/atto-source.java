# Logical actor hot upgrade — host-only coordinator v1

The `LogicalActorUpgradeCoordinatorV1` combines two existing trusted-host
admission mechanisms, without replacing either: the bounded immutable
`LogicalActorProxyMailboxV1` transport and the
`ActorUpgradeEpochGate` code-image lease gate.

## Transaction

- Verify the old code epoch equals the proxy route epoch.
- Freeze old-route DATA dispatch before preparing new code; continue bounded
  admission of copied immutable bytes. CONTROL and cancellation stay live.
- Reject incompatible actor state/message/capability ABI before publication.
- Retain already dispatched mail until logical completion acknowledgments
  pass the frozen watermark, **plus** all actor turn/suspends Task/FFI/shared
  region generation pins have physically exited.
- A rejected prepare rolls back the proxy freeze. Explicit abort first
  rejects the staged code and then reopens the old route.
- Publish the code gate and then commit the mailbox route watermark. A
  failure after gate publication must leave dispatch frozen/quarantined,
  never silently re-enable a possibly retired old code route.
- On success both epochs advance together; queued bytes retain monotonic
  sequence IDs and are dispatched using the new route only.

## Known limitations — do not wire to production ActorRef yet

This helper cannot atomically replace physical actor routing: publishing the
code gate and committing the proxy are two separate synchronized objects.
Actual router integration needs a single CONTROL-owned authority/transaction
covering `ActorRef` route pointer, code+state+mailbox version, verified
physical actor readiness, and dispatch. No guest actor may obtain the
coordinator, call `complete`, mint a watermark, or release an arbitrary pin.

The proxy currently uses package-private trusted-host
`pollDispatch()/complete()`; completion must be driven by authenticated
physical `mail.done()` or terminal acknowledgments. An ambiguous physical
send must not be retried. The router's present `send(Object)` path bypasses
this immutable proxy, so the coordinator is a staged safety seam **not**
source-actor E2E hot reload. Per-actor tasks and all await/FFI/heap leases
still need compiler and scheduler generation instrumentation.

SHARED, PRIVATE, and UNTRUSTED can use the same logical transaction only
when the physical deployment provides appropriate actor heap ownership,
protected-worker/sandbox placement and checkpoint restoration.
A per-actor arena is not an OS address space; JIT is optional for hot reload.
Do not admit PRIVATE/UNTRUSTED upgrades under the current SHARED-only
`LogicalSourceActorRouterV1` restrictions.

JUnit tests in `LogicalActorUpgradeCoordinatorV1Test` are written but do
not prove exact-head execution. No private source should be copied outside
the source organization without separate security authorization.

## October 19 transactional and physical receipt hardening

The coordinator now serializes `prepare`, `Transaction.drained`,
`commit`, `rollback`, `close` and quarantine observations under a single
coordinator monitor. Its former Transaction-local locks did not establish
happens-before with supervisor preparation or failure observation. A failure
during drain observation, code publication, abort, mailbox commit, or
postpublication epoch verification now leaves a quarantined transaction:
there is no implicit old-route replay or dispatch thaw. Concurrent
commit-versus-rollback attempts have one winner.

The **actual source-compiled SHARED actor host bridge**, separate from this
host-only coordinator, now routes its canonical copied 8-byte signed scalar
wire through `LogicalSourceActorRouterV1.sendAndDrainForProxy(envelope, epoch)`.
That call checks the expected physical route epoch *before admission*.
It produces a trusted, one-use `ProxyDrainReceipt` bound to the exact
`Envelope` Java object and physical `Route` instance. The receipt waits
outside the route monitor for the in-process physical drain to succeed, then
takes the route monitor and verifies that the route and proxy epoch remain
authoritative while completing proxy credit accounting. A foreign envelope,
stale route, consumed receipt, or failed physical drain cannot refund credits.

This is stronger **local provenance** for the existing single-in-flight host
scalar proof; it is **not** a cryptographic authentication mechanism or a
per-message `mail.done()` acknowledgment. The drain is cumulative, so it
can bind to a single logical message only because the bridge serializes
physical sends and drain waits. Do not parallelize physical dispatch or expose
the receipt to guest code until the compiler emits immutable ABI-tagged
envelopes and ActorRuntime emits trustworthy, per-message completion receipts.

New Java tests cover code-gate close during commit/abort, closed-mailbox
drain observation, 48 concurrent transaction-resolution races, and a
source-compiled SHARED actor rejecting wrong-epoch / same-bytes-foreign
envelope / replayed physical drain receipts. These tests are **committed,
not claimed to pass** until executed on the exact Git head with JDK25 and
Graal; zero-step CI job failures are infrastructure non-evidence.

Still required for production: a single supervisor-controlled publication
authority spanning physical ActorRef routing, mailbox epochs, code image and
Task/continuation lifetime; compiler-owned immutable typed message generation;
physical `mail.done()`/terminal receipt authentication with incarnation and
message-sequence provenance; automated retirement/quarantine reconciliation;
and target-specific PRIVATE/UNTRUSTED worker isolation.
