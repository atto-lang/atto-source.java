# SHARED actor terminal teardown preparation and partial-close quarantine

**Status:** DRAFT. Source regression tests committed but not executed on the private
product JDK25/GraalVM runner. This is not a production certification receipt.

## Audit finding

`ActorRuntime.ActorCell.terminateTree` publishes an exclusive
`TerminalSettlementBatch` and `stopped/sealing` before invoking channel
close, continuation close and mailbox reservation drain. The new retryable
settlement batch protected callback notification releases, but failure while
**preparing** physical teardown left `terminalSettlementActive` true and
never called `settleTerminalBatch`. A subsequent supervisor stop could not
acquire the retryable batch, leaving the old generation pinned indefinitely.

A second, distinct hazard exists in `ChannelRuntime.Channel.close`:
`closed=true` is published before `pumpLocked` and `runCompletions`
finish. A failure during that interval is **ambiguous**; a repeated channel
close may immediately return without delivering the missing completions.
Blindly retrying and certifying physical finalization would be unsound.

## Production hardening

- Persist the original mailbox close cause within the preallocated terminal
  batch. Never take a second snapshot after stopped/sealing publication.
- Run physical teardown preparation on initial and retry paths under the same
  lifecycle monitor. If an injected **pre-close** fault occurs, release only the
  exclusive active-settlement claim, retaining the exact batch and pin.
- Record `physicalTeardownAmbiguous=true` **before** the first physical
  channel close. Clear it only after both channel closes and the reservation
  drain finish normally.
- On failure after channel close begins, retain the ambiguous fence.
  A later stop explicitly rejects automatic retry and cannot publish the
  ActorRef's physical-finalization witness. Independent reconciliation of
  missed notifications is required; this change does not implement it.
- The existing terminal-batch retry logic remains authoritative for
  **post-preparation** receipt, drain, pending-operation and child-cancellation
  observer failures. A successfully completed batch still clears its pin
  fence only after all terminal observers finish.
- Added package-private-state, reflection-only test fault injections before
  the first close and after the first close; production leaves both null.

## Committed production regression sources (NOT EXECUTED)

`ActorPhysicalFinalizationPublicationTest` includes:

1. `failedPreSettlementTeardownRetainsBatchForSupervisorRetry`: an
   injected OOME before channel close must retain exact settlement ownership,
   clear exclusive active ownership, deny physical retirement and permit
   successful stop retry without re-snapshotting ledgers.
2. `ambiguousPartialChannelCloseCannotCertifyRetirement`: an OOME after
   first channel close must retain the code pin and deny a blind retry. The
   test then **independently inspects its empty channel fixture**, clears the
   test-only ambiguous marker and verifies that the actor can finish closing.
   This manual fixture repair is NOT a general production reconciliation API.

## Independent executed evidence

A separately authored source-free Java 21
`TerminalPreparationOracleV2.java` compiled with
`javac --release 21 -Xlint:all -Werror` and ran **629,000 / 629,000**
checks. It covered injected before-close errors, after-close ambiguity,
observer-release faults, one-shot completion and competing stop calls.
It is a protocol model: the private product `ActorRuntime`, JUnit,
JDK25, GraalVM and Native Image have **not** executed in this evidence.

## CI and source-transfer release hold

Source-org Actions have repeatedly reported failed jobs with
`steps:null`. This does not establish whether the cause is Actions minute
exhaustion or another runner-admission problem and provides no JVM assertion
verdict.

`.github/private-ci-approved-repositories.txt` on owning-source `main`
has no approved private mirror destination. Open security issues #962 and
#904 require approved numeric destination identity, independently validated
protected source provenance, exact Git-tree identity, and independent review.
No private source, blobs, files, branches or artifacts were transferred to a
different GitHub organization as part of this audit.

**Remaining P0:** approved exact-head JDK25 Maven/JUnit,
GraalVM Native Image, terminal-fault stress and unified code-image /
checkpoint / TaskScope / message / route transaction verification.
Retain PR #1367 as DRAFT.
