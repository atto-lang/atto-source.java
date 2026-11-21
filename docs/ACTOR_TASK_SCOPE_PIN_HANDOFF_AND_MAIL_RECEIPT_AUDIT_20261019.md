# Actor TaskScope lease handoff and physical receipt audit — 2026-10-19

**Status: DRAFT.** SHARED actor host-only proof; not guest task lowering, protected PRIVATE/UNTRUSTED migration, or native AOT proof.

## Production finding and fix

The logical router acquires an ActorRuntime external generation pin under routeLock and then constructs the generation-close callback to pass to canonical OresTaskScope. Constructing that method-reference callback can itself fail with an allocation error before TaskScope is invoked, leaving an unowned pin. The router now retains fallback release responsibility through the entire transfer expression, catching RuntimeException/Error and closing the same idempotent lease on a verified pre-admission failure. Release errors are suppressed onto the original error. The safety proof depends on OresScheduler.start never throwing after TaskRunner registration: later errors must settle via the onQuiescent callback, never through admission throw.

## Real-source regression tests committed but unexecuted

LogicalActorTaskLeaseHandoffTest covers cancelled canonical compiler scopes, injected HashSet ownership-reservation OOME, and stale captured route epochs. These use the real SHARED compiled source actor/router/scheduler; the tests were added but not executed in full JDK25/Maven/GraalVM.

ActorRuntimePhysicalMailReceiptTest now covers an active message that fails and a later already-accepted queued successor: both physical terminal receipts must fail; the queued successor must never run. This is local exact-message acknowledgment, not cryptographic remote mail.done or durable exactly-once effects.

## Offline evidence

A separate source-free JDK21 handoff oracle compiled under javac --release 21 -Xlint:all -Werror, passing **84,000 assertions in 32,000 scenarios**: 10,000 rejected scope admissions, 10,000 synthetic method-reference allocation failures, 10,000 result-versus-physical-exit checks, and 2,000 three-contender release/freeze races. These are NOT execution of Oreslang production source, Maven/JUnit, GraalVM, Native Image, or LLVM.

## CI/private-source gate

No private Oreslang source was copied into another GitHub organization. Open platform-conformance security incidents #22/#77 and bootstrap #69 require independently reviewed exact source+destination authorization and source-branch protection, not merely available GitHub Actions minutes. The Java PR remains Draft until exact-head source-owned/approved private CI, atomic code/scheduler/state/route publication, bounded receipt failure tests, and PRIVATE/UNTRUSTED/native AOT checks pass.


## Exact physical receipt terminal dispositions

The runtime-minted local receipt now has a machine-readable terminal class in
addition to its non-cancellable terminal Future:

- `PROCESSED`: that exact accepted physical sequence completed its actor
  receive. This is the **only** disposition that may refund logical proxy
  credits.
- `STOPPED`: explicit actor stop won before that accepted message completed.
- `CANCELLED`: structured actor cancellation won.
- `FORCE_CANCELLED`: the host/VM force-isolated cancellation authority
  reported successful subtree revocation and that cancellation won.
- `RUNTIME_CLOSED`: ActorRuntime shutdown won.
- `FAILED`: an uncaught actor/runtime receive failure fail-stopped the actor.

The receipt ledger stores the opaque `PhysicalMailReceipt` itself, rather than
only its Future. The runtime installs the disposition before settling the
Future; observing a terminal Future therefore implies a terminal disposition
is already available. Normal message completion removes the exact receipt
under the actor lifecycle lock and publishes `PROCESSED`. Teardown snapshots
and removes all remaining receipts under that same lock, then publishes one
negative disposition outside the lock. A later competing completion or
teardown cannot reclassify a receipt because it no longer owns that ledger
entry.

The original failure object remains the exceptional terminal Future cause.
This preserves detailed diagnostics without making a Throwable class the
protocol identity. In particular, this Java runtime layer does **not yet**
claim distinct source-language `throw` / `raise` / `panic` receipt
classes: an uncaught terminal guest failure maps to `FAILED` while retaining
its exact Throwable. Compiler/runtime outcome tagging can refine that later
without weakening the physical-message identity.

Structured versus force-isolated cancellation provenance is retained in
`ActorCancelledException.mode()`. Force cancellation propagates
`FORCE_CANCELLED` through the cancelled actor subtree. Runtime close
propagates `RUNTIME_CLOSED`. Ordinary parent failure/stop still structurally
cancels descendants, so descendant receipts are `CANCELLED`; the parent's
own outstanding receipts retain `FAILED` or `STOPPED`.

`LogicalSourceActorRouterV1.ProxyDrainReceipt` now requires both successful
terminal-Future settlement **and** exact `PROCESSED` disposition before
calling the mailbox's atomic `completeAfterPhysicalReceipt(...)` credit
refund. Negative terminal classes, timeout, interruption, changed route,
foreign mailbox/envelope, or any receipt mismatch remain fail-sealed.

Regression source covers successful processing, active failure plus queued
successor failure, explicit stop, structured cancellation, force-isolated
cancellation, runtime close, teardown-snapshot OOME, and completion races
against structured cancel, stop, and force cancel. Each race permits only the
two legitimate linearization winners and requires exactly one terminal
notification.

This remains **local in-process provenance**. The receipt itself does not yet
cryptographically bind logical UUID/incarnation, route epoch, compiler message
ABI/schema digest, payload digest, worker attestation, or anti-replay state.
The V1 logical router supplies route/mailbox/envelope binding in-process.
PRIVATE/UNTRUSTED IPC must independently authenticate those fields before this
protocol can be carried across a protection or network boundary.

### Source-free JDK25 protocol oracle

A separately authored source-free oracle in
`gha-indie-worker/gha-indie-worker-e2e` commit
`650bc9ff22008bcdf2a0f7e80e7c2ab1153734bb` ran on Temurin JDK
**25.0.4+1** (workflow run `39308750628`). It compiled with
`javac --release 25 -Xlint:all -Werror` and passed:

- 2,000 route publication/admission races;
- 10,000 physical terminal-disposition completion-versus-termination races;
- the 1,024-slot public drain exhaustion / exact-receipt isolation case;
- teardown allocation ordering;
- `private_source_bytes=0`.

This is independent model execution only. It is not Maven/JUnit/Graal/native
execution of this private source tree.


### Runtime-close retry boundary

A follow-up audit found that `ActorRuntime.close()` had tied dispatcher
`shutdownNow()` to the first successful global `closed` CAS. That was not
retry-safe: if an actor's pre-stop receipt/operation snapshot threw OOME after
the runtime had become globally closed, the first call exited before dispatcher
shutdown; a later `close()` observed `firstClose == false` and could skip
dispatcher shutdown permanently.

The close path now allocates the actor snapshot before publishing global close
and maintains a separate `dispatcherShutdownLock` / `dispatchersShutdown`
publication. Every actor must first accept its fail-closed
`RUNTIME_CLOSED` teardown. Only then are dispatchers stopped. The dispatcher
shutdown bit is published only after all three dispatcher shutdown calls and
queued-schedule cancellation complete; a failure before that point remains
retryable.

`runtimeCloseRetryAfterTeardownOomeStillShutsDispatchersAndReceipts()`
injects OOME into the first actor teardown attempt, verifies the actor remains
live and its exact receipt has no terminal class, restores the ledger, retries
runtime close, and requires that the receipt becomes `RUNTIME_CLOSED` and the
full close succeeds. This source regression is committed but still awaits
exact-tree JUnit execution.


## Completion / rollback authority surface hardening

A follow-up authority audit removed two package-private sequence-only mutation
surfaces from the production logical mailbox.

First, `LogicalActorProxyMailboxV1.complete(long)` was deleted. Production
credit refund now has only the exact-envelope path
`completeAfterPhysicalReceipt(envelope, routeEpoch)`, which requires the
same outstanding immutable `Envelope` object, the current route epoch, and a
recorded physical-send attempt. Synthetic sparse-watermark unit models use
`LogicalActorProxyMailboxTestAccess` from `src/test` to invoke the private
ledger state machine reflectively; reflection is not part of the production
mailbox API. A regression inspects declared production methods and rejects any
return of a one-argument `complete(long)` capability.

Second, pre-send rollback changed from
`requeueProvenUnsent(long sequence)` to
`requeueProvenUnsent(Envelope exact, long expectedRouteEpoch)`. A copied
envelope with equal sequence/payload, an envelope from another mailbox, a
stale route epoch, a completed envelope, or any envelope whose physical-send
attempt was already recorded fails closed. Only the exact latest outstanding
object in the current epoch can be restored before physical submission.
Low-level sequence-driven watermark tests again use a test-source-only adapter;
real router/bridge integration tests exercise the exact production API.

The reflective test adapter also acquires the mailbox's real private monitor
before reading its synthetic outstanding ledger or invoking the private
completion state machine. This keeps test concurrency semantics aligned with
the production monitor instead of creating an unsynchronized surrogate.

### Post-acceptance allocation boundary

The router previously constructed `ProxyDrainReceipt` only after
`ActorRuntime.sendWithPhysicalReceipt(...)` returned. That left an avoidable
allocation after successful physical mailbox acceptance: OOME constructing the
router receipt could strand accepted work with no returned host receipt handle.

The router now allocates the `ProxyDrainReceipt` shell before
`beginPhysicalSendAttempt(...)` and before physical admission. ActorRuntime
already prepares its own exact receipt before mailbox acceptance. After a
successful runtime send, the router performs only a volatile reference write
to bind that runtime witness into the preallocated shell, then returns it.
Therefore failure to allocate the router shell is still a definitely-unsent
case and occurs before the physical-attempt ledger is changed.

This does not turn an ambiguous failure after physical submission into a retry
permit. Once the physical-attempt ledger is recorded, timeout, cancellation,
carrier submission failure, terminal failure, or any other uncertain outcome
remains outstanding/fail-sealed until supervisor reconciliation.

### Executed source-free JDK25 authority oracle

The independent public source-free oracle was extended without copying private
Oreslang source. Commit
`8a6c8f0799991cb1f2434ecdf69d0c34d21b5b6f`, workflow run
`39313327723`, job `118268313042`, executed on Temurin JDK
**25.0.4+1**. It compiled with
`javac --release 25 -Xlint:all -Werror` and reported:

```text
PASS source-free hot-upgrade oracle
route_race_rounds=2000
terminal_race_rounds=10000
runtime_close_retry=pass
exact_envelope_rollback=pass
send_vs_requeue_races=10000
public_drain_slots=1024
private_source_bytes=0
```

The new model rejects copied/foreign/stale rollback capabilities and races
physical-send reservation against exact-envelope unsent rollback for 10,000
rounds, requiring exactly one authority to win. This remains independent model
evidence, not execution of the private Java source, Maven/JUnit, GraalVM,
Native Image, or LLVM.
