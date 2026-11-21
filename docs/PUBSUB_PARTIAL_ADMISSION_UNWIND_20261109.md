# AttoLang ActorGroup RELIABLE publication: exceptional unwind and cancellation hardening

**2026-11-09 · DRAFT · SOURCE INSPECTED · JAVA/JUNIT NOT EXECUTED · NOT CERTIFIED**

## Stacked provenance

This narrowly scoped source change builds on Java [#2724](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2724) at parent exact head `94e789c546b5a6ed797ada33445f92166ef32954`. Its ancestor branches remain #2718 (atomic pg detach/EventBus teardown), #2717 (membership-epoch fence), #2710 (registry callback lock), and #1700. #1230 provides the original separately stacked EventBus teardown implementation. No attempt was made to merge the source tree into current `main`, transfer it to another organization, or claim that host runtime code provides compiled AttoLang guest semantics.

## Audited source-level failure modes and patch

1. **All-recipient RELIABLE reservation phase:** capacity allocation occurs before any `Channel.writeAsync` call. The earlier code handled a normal false `reserveReliableEvent()` but not an exception thrown during the reservation loop. A thrown exception could leave earlier reservations outstanding and consume a topic sequence despite zero writes. The new guarded loop releases **every previously reserved** capacity slot, aggregates cleanup errors as suppressed, rolls back the prewrite sequence, and rethrows the primary exception.

2. **Partial write phase:** previously, if one `writeReliableReserved` call threw after earlier writes started, the catch cancelled pending writes then released unstarted capacity, but a cancellation hook's own exception could skip all remaining cleanups. The new catch attempts every pending cancellation and all unstarted reservations, preserving the original exception and suppressing cleanup failures. If a nonfatal `RuntimeException` occurs after one or more earlier write calls returned, the caller receives `EventPartialAdmissionException(topic, previouslyStartedWrites, intendedRecipients, cause)`. Completed channel writes cannot be rolled back; the exception deliberately warns clients **not to retry blindly without idempotency**. Fatal `Error` instances remain errors.

3. **Failed initial retain:** `writeReliableReserved` now retains inside its guarded scope. If `Event.retainDelivery()` itself fails, it releases the reserved subscriber capacity but does **not** decrement a delivery reference that it never obtained. Cleanup failures are suppressed behind the original exception.

4. **Sequence overflow:** under the topic delivery lock, `Long.MAX_VALUE` is a fail-closed exhaustion boundary; do not increment into a negative sequence. The outer frozen payload finalizer still runs when overflow is refused.

5. **Primary vs physical teardown failure:** `publishFrozen` now captures the original `RuntimeException` or `Error` from admission. If releasing its event reservation or draining stale subscriptions fails in the outer `finally`, that exception is suppressed onto the primary error, **not** substituted for it. All deferred subscription closes are attempted by the existing teardown helper.

## Three new host JUnit regressions (authored; not executed)

`ActorEventBusReliableUnwindTest`:
- `multiSubscriberCancelReleasesEveryPendingReliableWriter`: two subscribers each have capacity-one queues, one buffered event and one pending `RELIABLE` event. Cancelling the aggregate receipt must revoke both pending sends, free capacity for a replacement, and group close must return aggregate frozen payload bytes to zero without stopping either ordinary actor.
- `oneSubscriberClosingFailsFanoutReceiptAndRevokesOtherPendingSends`: closing one subscriber while a fanout is pending fails the combined completion and cancels other pending sends. After group close, both channels drain, all frozen event reservations disappear, and ordinary members remain alive.
- `sequenceOverflowFailsBeforeWriteWithoutRetainingFrozenPayload`: a reflected topic `AtomicLong` is set to `Long.MAX_VALUE-1`; one publication reaches the last valid sequence; the next is rejected without wrapping the sequence or retaining payload memory.

The existing source-local exact-head JDK25 workflow now selects this test class alongside all previous lifecycle, cancellation, and pg tests.

## Behavior *not* claimed

- Host `RELIABLE` partial-admission handling does **not** promise all-or-nothing writes once a send starts. A previously completed write can remain observable even if a later subscriber fails; the new exception reports that possibility, not a count of definitively consumed deliveries.
- Synchronous exception during `Channel.writeAsync` or `whenCompleteRuntime` registration *after* ownership transfer needs separate fault-injection/verification to establish exactly-once release under arbitrary host errors. ThreadDeath, OOM, and VM corruption are not independently guaranteed recoverable.
- Existing `OresFutures.all` and callback scheduling semantics must be verified with actual JDK25/JUnit under bounded, deterministic races. A zero-step GitHub job is neither a test failure nor a pass.
- Source-level sequence uniqueness is scoped to one `ActorEventBus` topic and local publication lock. No distributed total-order, consumer acknowledgement, cross-isolate identity, or backend parity is proven.
- Topic callbacks can still reenter code during synchronous future settlement; review all callback-bearing paths including `writeAsync` under the delivery lock before claiming complete lock freedom.
- Java/GraalVM, LLVM, Wasm, .NET NativeAOT, Cranelift and independent Forge compiled guests remain separately unverified.

## Security, CI and merge gates

The source organization's Actions runner admission is still tracked in [conformance issue #315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315). The private-source export hold, under [security #337](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/337), prohibits copying Git trees/blobs to another organization even if it successfully ran a source-free test. No cross-org source transfer, automated merge or branch-protection override is authorized.

Smallest source-local execution on approved exact-head runner:

```bash
mvn -B -ntp -Dtest=ActorEventBusReliableUnwindTest,ActorEventBusAdmissionRevocationTest,ActorEventBusShutdownStressTest,ProcessGroupAtomicDetachRaceTest,ActorEventBusTeardownLockTest,ProcessGroupRegistryMembershipEpochTest,ProcessGroupRegistryRetirementTest,ProcessGroupRegistryTest,ActorEventBusTest -DfailIfNoTests=true test
```

Record the **exact head and tree**, JDK version, runner ID, workflow/job/attempt, nonzero executed steps, complete Surefire named-test XML and failures. Then independently review #1219/#1229/#1230 and #1700/#2710/#2717/#2718/#2724 integration before merging or certifying compiled guest behavior. Keep the branch and PR in **DRAFT** until stepful exact-head execution and review are verified.
