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
`Envelope` Java object, **owning proxy mailbox**, and physical `Route`
instance. Before any physical send, the mailbox proves that this same
envelope is currently outstanding under the intended epoch; copied bytes,
matching sequences, and different mailboxes are insufficient. The receipt
waits outside the route monitor for the in-process physical drain to succeed,
then takes the route monitor and verifies the unchanged physical route and
original mailbox ownership before completing proxy credit accounting.
Foreign/queued/already-completed envelopes, stale epochs, consumed receipts,
and failed physical drains cannot refund credits.

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


## October 19, 2026: transaction/route-publication audit

- **One synchronization domain.** Coordinator `prepare`, `close`,
  `quarantined`, and all transaction `drained`/`commit`/`rollback`
  operations use the same coordinator monitor. A competing prepare cannot
  observe partly retired transaction state or publish while prior rollback
  is still in progress.
- **Allocation-failure recovery.** If constructing the transaction fails
  after `code.prepare`, abort the staged code candidate **before**
  thawing the mailbox. If code abort fails, preserve the mailbox freeze and
  quarantine instead of treating it as a successful rollback.
- **Fail-closed gate failure.** Candidate abort, eligibility inspection, or
  code publication errors mark the transaction terminal and the coordinator
  quarantined; an external supervisor closing the code gate cannot silently
  reopen DATA dispatch.
- **No post-thaw throwing validation.** Mailbox freeze, commit and rollback
  now perform structural checks before changing publication flags or route
  epoch. After a successful check, their publication path consists only of
  bounded, nonallocating scalar/reference writes and monitor notification.
  An inconsistent ledger therefore cannot accidentally thaw dispatch.
- **Regression tests authored, not claimed passed:** external gate close
  during commit/abort, closed mailbox during drain inspection,
  concurrent commit/prepare, and injected corrupt-ledger checks before
  freeze/commit/rollback. The independent actor routing/ACK/Task pin
  production gates above remain outstanding.

### CI provenance and private source policy

An exact-head JDK25/Graal build is still required. GitHub Actions runs that
fail or queue before job steps are executed do not constitute test evidence.
`.github/private-ci-approved-repositories.txt` on source `main` has no
approved private destinations. Security issue #962 expressly forbids
cross-organization private tree export pending protected-branch,
security-reviewed source+numeric-destination authorization. Consequently,
**do not mirror this private actor branch into another organization** merely
to obtain Actions minutes, even using identical Git blobs and no personal
access token. Once policy permits a carrier, verify exact nested Git-tree
and immutable commit identity before claiming its JVM/native CI as evidence.

## October 19 — further lifecycle and duplicate-send audit

Additional defects fixed after the route-bound drain receipt change:

1. **Retired coordinator reuse:** a clean call to
   `LogicalActorUpgradeCoordinatorV1.close()` previously left the object
   capable of calling `prepare()` again. Close now irrevocably revokes new
   supervisor transaction admission without implicitly stopping the actor or
   closing its mailbox. Close-versus-prepare tests verify the shared monitor
   picks exactly one authoritative outcome.
2. **Duplicate physical submission:** one logical envelope could previously
   be passed twice through the trusted `sendAndDrainForProxy` seam before
   either receipt settled, potentially delivering two effects for one logical
   message and refunding its credits only once. Each mailbox now tracks a
   **bounded physical send-attempt reservation ledger**. The first physical
   submission consumes the reservation before calling ActorRuntime.send;
   another attempt for the same sequence is rejected under the mailbox lock.
   The reservation remains held on ambiguous send/drain failures. Only
   supervisor-proven-unsent requeue or completion clears it. The reservation
   is rejected once upgrade dispatch has frozen.
3. **Bounded hot-path overhead:** the ledger's invariant check compares counts
   in O(1) and uses synchronized transitions to preserve the subset relation;
   it does not scan every outstanding sequence on each mailbox operation.

Added regression sources for 32 concurrent duplicate physical attempts,
reservation retention across upgrade freeze and recovery after
`requeueProvenUnsent`, 32 coordinator close-vs-prepare races, and the
source-compiled SHARED Counter rejecting a second physical send before its
drain receipt completes. These are **committed tests, not executed test
passes** until exact-head JDK25/Graal CI runs.

**Important limitations:** the ledger is an in-memory trusted-host guard,
not durable deduplication, a cryptographically authenticated
`mail.done()` receipt, or an exactly-once side-effect guarantee. External
transport ambiguity still requires supervisory quarantine/reconciliation.
The coordinator is not yet the physical ActorRef publication authority.
PRIVATE and UNTRUSTED remain disabled for this same-process bridge.

## SHARED proxy route publication linearization — October 19, 2026

`LogicalSourceActorProxyBridgeV1` now binds its mailbox **once** to its
`LogicalSourceActorRouterV1` physical authority. Bound routers reject the
legacy raw `LogicalActorRef.send(Object)` and direct unproxied `upgradeTo`
paths. All proxy physical sends require that exact bound mailbox and the
exact outstanding copied envelope; a foreign mailbox or forged receipt cannot
consume its credits.

The bridge's `upgradeTo` passes its active `FreezeTicket` to
`upgradeToForProxy`. Under **one physical route monitor**, the router first
preallocates its new immutable `Route`, verifies that the ticket belongs to
the bound mailbox and its old route epoch and has drained, commits the mailbox
epoch, then publishes the physical route pointer without allocating or
performing user callbacks in between. The bridge's dispatch monitor prevents
proxy dequeue while this transaction runs; the router's route monitor prevents
any physical route lookup/send from observing an intermediate publication.
Bounded admission remains available throughout the freeze.

A post-publication retirement error may still quarantine the bridge and
retain the retired actor's code-generation lease; it must **never** replay
the old route or refund speculative credits. This is a SHARED-only, trusted
host scalar upgrade proof, **not** a distributed transaction, durable
exactly-once delivery, remote authenticated mail.done receipt, or general
compiler-generated typed message transport.

Regressions authored: raw bound-ActorRef bypass refusal, one-time ownership
binding, foreign freeze rejection, unproxied upgrade refusal, and a concurrent
coherent physical/mailbox epoch observer during a real source-compiled SHARED
actor upgrade with queued mail and preserved state. Pending exact-head Maven
and JDK25/Graal execution; Github Actions failing with `steps: null` or
missing logs supplies no passing/failed Java test evidence.

**Remaining production authority gap:** integrate compiler-owned immutable
ABI-tagged messages, true per-message authenticated delivery/terminal receipts,
and the compiler/scheduler's Task/continuation/FFI code-generation pins into
one CONTROL-owned publication boundary. The earlier
`LogicalActorUpgradeCoordinatorV1` remains a host-only staged seam; it is
not yet the same physical actor-route authority.
