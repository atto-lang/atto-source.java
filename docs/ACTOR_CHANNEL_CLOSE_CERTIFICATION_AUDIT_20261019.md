# Channel close certification and actor terminal retry audit — October 19, 2026

**Release status: DRAFT / BLOCKED**. This document explicitly records a newly discovered remaining defect in the committed narrow channel-close recovery. **Do not merge or treat the source JUnit as green.**

## Initial issue and implemented narrow recovery

ActorRuntime deliberately quarantines an actor after a potentially partial Channel.close: the channel may have committed closed=true and select outcomes but fail before final notification delivery. A later close can return as a no-op, so clearing terminalSettlementPending could release executable code prematurely.

This branch added:
- `ChannelRuntime.Channel.closeSettledWithoutSelectorsForTeardown()`: a local receipt published only if the first close observed zero current selectors and normal `pumpLocked`/completion-return.
- `ActorRuntime.TerminalSettlementBatch.mailboxReservationDrainStarted`: irreversible fence before draining channel reservations. A partial reservation drain is **not** automatically replayed.
- On a previously ambiguous teardown, ActorRuntime retries only when the first mailbox close and any already-closed continuation channel return conservative close receipts and no reservation drain began.
- Source JUnit `ActorPhysicalFinalizationPublicationTest` covers the selector-free successful-first-close injection recovery and a pending-select quarantine case; `ChannelCloseTeardownReceiptTest` checks basic close witness behavior.

## Newly found missing historical notification authority

**The current implementation is still NOT a complete or sound certificate.** An async `select` may *commit* on a channel and unregister its CaseRegistration, yet the selection's Future observer may remain blocked or its publication deferred behind Future-waiter attachment. The channel's `registrations.isEmpty()` at close time is therefore insufficient to prove that **previously committed** selection callbacks have fully drained.

`ChannelHistoricalDeferredCloseTest.previouslyUnregisteredSelectCanStillHaveAnInFlightCallback` was committed as an intentionally **red** regression: it holds the selected read's completion observer open on another thread after its registration disappears, invokes `close()`, and asserts that the narrow certificate is NOT published. **This is expected to fail against the currently committed ChannelRuntime and intentionally blocks PR promotion.** It is not evidence that the defect was fixed.

### Proposed fail-closed fix (not yet committed)

A minimally conservative correction is to add `asynchronousSelectEverAdmitted`, guarded by the Channel coordinator, mark it BEFORE each asynchronous select start for all read/write channel arms, and deny the narrow `closeSettledWithoutSelectorsForTeardown()` receipt if that historical bit was ever set. Ordinary immediate-only transient `tryRead/tryWrite` probes need not disqualify a channel. This loses some automatic recovery opportunities but cannot falsely certify a historically asynchronous deferred callback.

A local unified diff is available at `/mnt/data/oreslang_channel_close_audit/channel_async_history_guard.patch`, pinned to channel source blob `ab5b52079ed56bdca6015e1231eec9973135743a`. Attempts to update the full private ChannelRuntime source through the GitHub connector were blocked by tool safety checks. Do not claim the proposed fix is installed. Maintainers must apply/review the patch or provide a correctly authorized source-edit workflow before rerunning exact-head tests.

Long-term, replace the conservative history bit with a bounded per-channel or per-selection deferred-publication receipt ledger, committing every notification owner before unregistering it and releasing receipts only after all relevant Future notification drains.

## Actual executed evidence

An **independent, source-free Java 21** model was compiled with `javac --release 21 -Xlint:all -Werror` and executed: **326,000 assertions passed** across 20,000 serial teardown sets and 2,000 two-platform-thread stop races. This model covers selector-free first-close recovery, no selector at the time of close, callback failure, and repeat stop. **It does not model the newly discovered historical deferred-callback counterexample and cannot validate its missing production fix.** It is not production Oreslang JUnit, JDK25, GraalVM or Native Image evidence.

## Source CI and confidentiality

Source-org Actions jobs have repeatedly failed with **zero executed steps**. This is not a test verdict or demonstrated minutes exhaustion. Source `main` private destination allowlist has zero entries, and security/CI issues `oreslang-platform-conformance` #22/#77/#69 and source #962 remain open. No private source was mirrored to another organization; funded external Actions minutes do not override confidential source-export requirements.

**Next gate:** apply/review the historical-async-source fix, run the new in-flight-observer regression and all actor terminal/future/physical receipt suites on the exact tree under authorized JDK25 Maven/GraalVM CI, and keep PR #1367 Draft until all pass.
