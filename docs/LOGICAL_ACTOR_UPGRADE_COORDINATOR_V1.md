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

### Exception boundaries / non-cached sequence hardening

The SHARED bridge's `dispatchOne` and `upgradeTo` now include
`pollDispatch` and `freezeDispatchForUpgrade` respectively inside the
same fail-seal exception handler. Corruption or an unexpected failure during
reservation must quarantine the bridge, not allow a subsequent send to bypass
uncertain credit/freeze state.

For message sequences outside Java's cached boxed-`Long` range, the mailbox
now reserves its `Long` key before changing transport accounting. The
sparse-completion-watermark advance uses existing `TreeSet` entries via
`first()/pollFirst()` rather than constructing a new boxed key after a
credit refund. An additional regression source covers 136 serial logical
sequences, out-of-order sparse convergence and proven-unsent requeue.
These strengthen exception safety but cannot replace fault-injection
execution, full runtime JUnit, or exact-head native/Graal testing.


## October 19: code-gate retirement serialization

A follow-up audit found a lifecycle race in the host-only coordinator: the
code gate and proxy mailbox used separate monitors, so an independent
`ActorUpgradeEpochGate.close()` could retire code between a proxy
freeze/thaw operation and the matching prepare/publish/abort transition.

The coordinator now uses one explicit nested lock order for every cross-ledger
transition: **coordinator → code gate → mailbox**. Preparation holds the code
authority while checking the epoch, freezing dispatch, staging the candidate,
and performing any proven rollback. Commit holds it from the final drainage
check through code publication and mailbox epoch publication. Rollback holds
it from candidate abort through old-route thaw. A code-gate lifecycle refusal
before candidate staging is treated as quarantine and preserves the mailbox
freeze instead of reopening an unverified old route.

This closes the coordinator-local partial-publication/partial-rollback race,
but it does **not** turn this helper into the production source-actor
publication authority. The live SHARED proxy/router transaction has its own
route-lock publication path; compiler-owned immutable typed messages,
authenticated per-message terminal receipts, and PRIVATE/UNTRUSTED protected
worker migration remain required.

## Physical-send replay-proof audit — October 19, 2026

The formerly trusted-only `requeueProvenUnsent(messageSequence)` carried no
proof token. A host caller could call it **after**
`beginPhysicalSendAttempt(envelope, routeEpoch)`, erasing the attempt ledger
and redispatching a message that the real actor may already have accepted.
The helper name did not make the result independently provable.

V1 now permits that sequence-only requeue **only before any physical send
attempt**. After reservation, attempts stay charged/outstanding even if a
caller claims the physical transport refused the send; an ambiguous error is
quarantined. An actual post-attempt retry would require an authenticated,
one-use, exact-envelope/actor-incarnation/route-epoch physical
non-acceptance receipt issued at the acceptance boundary, which is NOT
implemented. No silent retry or exactly-once effect guarantee is claimed.

Authored regression coverage checks no credit/sequence mutation on rejected
post-attempt requeue, repeated replay attempts, the freeze/rollback boundary,
and sequence numbers beyond Java's boxed-Long cache. The preattempt
requeue path remains allowed with original FIFO/credit accounting.

CI status: code and regression tests are committed but an exact-head Java
execution result remains required; no cross-organization copy of the private
tree has been performed because the reviewed destination allowlist is empty.

## October 19 — supervisor authorization and receipt carrier audit

The SHARED router now validates the **current physical route and exact bound
mailbox freeze ticket before candidate generation linking or pinning**. A
second validation occurs under the route monitor immediately before route
freeze, because an independently authorized supervisor or close attempt may
change the route while the candidate loader runs. The preliminary check does
not perform the loader operation under the route monitor: existing DATA sends
on the old route therefore remain independent of candidate compilation.

The preflight prevents foreign, unproxied, stale and already-upgrading requests
from making the compiler/linker perform work they were never authorized to
start. The real-source regression uses a well-formed generation whose actor
type does not match the routed actor: supervisor denial must occur **before**
its source actor can be linked. A denial neither changes the mailbox freeze
nor pins the rejected candidate generation.

The one-use `ProxyDrainReceipt.awaitAndComplete` now also enforces the same
host-only execution-carrier guard as proxy send/upgrade. Its underlying
runtime-minted exact physical terminal Future cannot be consumed from a
guest actor, active scheduler frame, scheduler terminal callback or async
carrier as a backdoor blocking operation. A separate actual scheduler Task
regression checks that rejected waits do not refund transport credits,
followed by successful completion from an authorized host thread.

**Scope:** this secures two host/supervisor seams; it does not add remote
cryptographic authenticity, compiler-owned message/capability receipts or a
global code-loader/TaskScope/checkpoint publication commit. The physical
runtime receipt remains local and host-only. Keep production hot reload
disabled until the whole SHARED transaction and exact JDK25/Graal tests pass.

**CI policy / provenance:** at the time of this audit, source `main` still
has an empty reviewed private destination allowlist and security issue #962
remains open. The source-owned `ci` / `fast-ci` jobs still fail with no
recorded execution steps. The authored tests are *not* claimed to have run.
Do not export private source into another org without protected-source and
reviewed immutable numeric-destination authorization.

## Exact physical completion authority and credit refund — October 19, 2026

A follow-up audit found the real SHARED proxy receipt checked outstanding
envelope ownership under the mailbox lock, released that lock, then invoked
the generic `complete(sequence)` ledger operation. That operation was not
itself bound to the exact immutable envelope and prior physical send attempt.
Even though the router also held its physical route monitor, the mailbox's
credit-refund boundary was less strict than its runtime-issued exact terminal
receipt.

The router now consumes its runtime-minted exact physical terminal receipt and
calls `completeAfterPhysicalReceipt(envelope, routeEpoch)`. Under one mailbox
monitor this operation validates ALL of:
- the current mailbox route epoch matches the physical accepted route;
- this **same immutable Envelope object**, not just equal bytes/sequence, is
  still outstanding in this mailbox;
- the exact logical sequence has a recorded physical send attempt;
- the existing bounded completion watermark/accounting checks all succeed.

On refusal it refunds **no** credits and advances **no** watermark. The router
consumes its one-use receipt before calling this operation; corruption or
failure requires supervisor quarantine, not a speculative replay. The
sequence-only `complete(sequence)` remains package-private for standalone
mailbox unit models; production source actor acknowledgments use the stricter
physical receipt method.

The new `LogicalPhysicalCompletionAuthorityV1Test` adds three JUnit
regressions: no-attempt refund refusal; copied/foreign envelope and wrong
epoch refusal; and prevention of an old physical route's receipt refunding
queued new-route mail after upgrade.

**Evidence, carefully scoped:** an independent Java 21 oracle reflecting
the receipt/epoch/credit synchronization contract compiled with
`javac --release 21 -Xlint:all -Werror` and passed **910,000 isolated
assertions**, including **5,000 two-thread duplicate-completion races**.
It is not the production Oreslang class or JDK25/Graal runtime, so the new
source JUnit tests remain **authored, not executed** pending CI capacity.

No private cross-organization source mirror was made: the protected source
authorization and immutable numeric-destination approval mandated by
security issue #962 remain unresolved and the source main destination allowlist
is empty. Keep the hot-reload PR draft and production support disabled.

## Supervisor source-image publication witness — October 19, 2026

The live SHARED proxy/router now has a **single coherent host/supervisor
observation of code and transport identity**. The package-private
`LogicalSourceActorRouterV1.SupervisorPublication` snapshots the stable
logical identity and route epoch, the actual physical ActorAddress, the
immutable source Generation ID and SHA-256 digest, and the bounded mailbox
snapshot **while holding routeLock -> mailbox lock**. It rejects unbound or
foreign mailboxes and closed routers. The bridge's `publication()` obtains
that one witness under its existing dispatchLock and exposes the code
Generation ID/digest alongside the logical address/mailbox epoch.

This prevents an observer of the bridge's publication record from combining
the successor's route epoch with the predecessor's source-image digest. It
does not depend on separately reading mutable `router.generation()` and
`router.ref().address()`, which could span different upgrade instants.

`LogicalSourceActorImagePublicationTest` adds a real source-compiled
Counter V1/V2 upgrade with a simultaneous publication observer checking
that epoch 1 always identifies the V1 Generation ID/digest and epoch 2 always
identifies V2, while queued mail and actor state survive the change.
A second test ensures a foreign mailbox cannot obtain the host's physical
actor/code-image observation.

**Safety boundary:** this is an *observation witness*, not a complete
compiler-owned publication transaction, artifact-signature attestation, or
proof that a bytecode/native code image is safe to unload. The compiler
TaskScope, checkpoint schema receipts, native/FFI callbacks, and code-image
retirement still require one CONTROL-owned commit/lease barrier. A new
reader-facing source digest does not authorize PRIVATE/UNTRUSTED migration.

No exact-head JDK25/Graal Maven tests were executed: source GitHub Actions
continue to fail without recorded runner steps. The approved private CI
destination list on source main remains empty and issue #962 is open. No
cross-organization private source copy was performed. Keep #1367 DRAFT.

## Compiler-ABI upgrade compatibility gate — October 19, 2026

The compiler-ABI scalar SHARED proxy binds accepted immutable byte frames
to a compiler-issued `actorMessageAbiSha256` digest for the actor's original
receive contract. The existing route-upgrade path preserved queued frames
but did NOT compare the new candidate actor's compiled message ABI to that
binding. It could therefore publish a logically incompatible successor
that receives previously admitted bytes under a different type contract.

The router now checks `candidate.executor().actorMessageAbiSha256(actorTypeName)`
against the immutable `scalarWireBinding.messageAbiSha256()` immediately
after candidate linking and **before acquiring the candidate's code-generation
route lease, freezing any physical actor ingress, or transferring state**.
Mismatch fails closed. This does not run user code or allow a speculative
candidate actor to execute on mismatched mail. The existing compatibility
path for a behavior change with an identical message type remains allowed.

The new real source-compiled `SourceActorUpgradeMessageAbiGateTest` stages
an `ActorMail<int>` Counter and a well-formed `ActorMail<string>` Counter,
verifies the compiler-issued ABI digests differ, then attempts the invalid
upgrade under a genuine proxy freeze ticket with old mail queued.
It checks the request is rejected without changing logical identity, epoch,
ticket ownership, or mailbox credits. After the legitimate freeze is rolled
back, the queued old-frame message still executes on the original actor and
produces the expected result, and the rejected candidate can retire without
a leaked route pin. The integration regression is committed but NOT verified
by Maven/JDK25/Graal due to runner-admission failures.

Local limited execution: the exact unchanged production
`SourceActorScalarWireV1.java` Git blob `31f99f4be4531c8c16f3a5a69ead3f7ee37e6832`
was copied into a local ephemeral Java 21 test workspace and independently
checked with `git hash-object`. `javac --release 21 -Xlint:all -Werror`
compiled it, and an isolated executable codec harness passed **240,001**
round-trip, canonical-byte-length and incompatible-ABI rejection assertions.
This verifies the **real wire codec only**; it does not compile or execute the
actor compiler, hot-upgrade router, or JUnit integration test.

Private cross-org Git-tree mirroring remains prohibited: source main's
reviewed destination allowlist is EMPTY and issue #962 is unresolved.
The latest inspected source Actions have failed without job steps, and issue
#904's independent required-check/branch-protection gate also remains open.
Keep the PR DRAFT and production hot reload disabled.

## Coherent compiler-image/state/message publication checks — October 19, 2026

The live SHARED proxy's immutable `publication()` record now includes the
compiler-issued **linked code digest**, **persistent state-schema digest**,
and **message ABI digest**, in addition to the source-generation digest,
logical route/physical actor identity, and bounded mailbox epoch. All three
compiler receipts are captured against the same authoritative physical
route under `routeLock` and exposed while the bridge holds its
`dispatchLock`. This prevents a host supervisor from constructing a
purported consistent upgrade observation by reading old and new compiler
identities across separate route lookups.

For compiler-ABI-bound scalar bridges, `publication()` also fail-seals
admission if the current linked message ABI disagrees with the original
immutable proxy binding, even when physical-route and mailbox epochs match.
The existing candidate-ABI preflight remains authoritative **before**
checkpoint migration; this additional read-side guard prevents a false
healthy publication certificate if a compiler/code-identity invariant is
violated.

Source-compiled regressions now verify that V1 and V2 linked code digests
follow the route epoch, while state schema and message ABI stay unchanged
across a compatible source upgrade. The ABI-bound queue test also checks
the exact source-generation and linked-code identities on both sides of
the transition. These are authored JUnit tests; **no full private Java
source JDK25/Graal execution result is yet available**.

This is a verified *design boundary*, not a claim of global atomic
publication across code-loader executable pointers, scheduler TaskScope
continuations, checkpoint bytes, native/FFI call leases and physical image
retirement. The actual code migration still relies on
`ActorRuntime.SourceActorTransfer` verification and old-generation
physical-finalization fences. Extending that authority into one
CONTROL-owned global commit remains a separate release gate.

CI truth: source-owned `fast-ci` and `ci` fail with no recorded job
steps, and job log retrieval fails; this does not demonstrate a Maven
test failure or prove Actions minutes were exhausted. Draft source CI
publisher PR #954 and private transfer issue #962 remain under independent
security review. Source `main` has **zero approved destination entries**;
no cross-organization private source transfer was performed.
