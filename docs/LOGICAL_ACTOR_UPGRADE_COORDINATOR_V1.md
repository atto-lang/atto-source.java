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
