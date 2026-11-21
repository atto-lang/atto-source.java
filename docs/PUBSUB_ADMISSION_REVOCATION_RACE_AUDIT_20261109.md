# AttoLang Pub/Sub — RELIABLE admission and membership-revocation hardening

**2026-11-09 · DRAFT · Source-authored but Java/JUnit NOT_EXECUTED · NOT_CERTIFIED**

## Exact dependency

This is a focused draft stacked directly on [Java #2718](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2718), parent head `2ed976899d4f00b91a7c8888021ba65d7df4d629`, itself stacked on #2717/#2710/#1700. Do not merge into `main` without semantic reconciliation against #1230 and its lifecycle dependencies #1229/#1219, and independently reviewed CI execution.

The local `ActorGroup` event bus is not the standalone portable cross-backend Pub/Sub reference broker. It uses runtime-owned channels, with `RELIABLE`, `LATEST`, and `LOSSY` policies and `PublishReceipt.completion()` meaning the host writes have been admitted, **not that consumers processed messages**. ActorGroup membership, topic subscription, process monitoring, and supervision remain separate authorities.

## Concrete source-level defect fixed

Before this branch, `ActorEventBus.publishFrozen()` assigned `topic.sequence.incrementAndGet()` before checking bounded `RELIABLE` delivery reservations for all active subscribers. If `reserveReliableEvent()` refused the publication (backlog exhausted or a logically closed subscriber), the method unwound the successfully reserved slots but **left the topic sequence incremented**. No event had been delivered or admitted for that sequence, so the next successful event would contain a sequence gap.

The narrow patch calls `topic.sequence.decrementAndGet()` on that **pre-write, all-recipient reservation-refusal path only**, while still holding `topic.deliveryLock`. Every topic sequence allocation in this implementation is serialized on that lock. The original outer `finally` still releases the freshly frozen payload reservation, and earlier reserved subscribers receive `releaseReliableEvent()` before throwing `EventBackpressureException`.

**Limits:** Exceptions or shutdown races *after some reliable writes start* are different partial-admission/cancellation semantics and are not rolled back here. This is an alignment fix for the **failed pre-admission** case, not proof of general all-recipient atomicity or cross-backend parity. Publication may overlap an in-progress group leave; a successful event is not necessarily a post-revocation event unless the leave **completed before admission began**.

## Regression sources authored (5 JUnit test methods)

`ActorEventBusAdmissionRevocationTest` (4):
1. `rejectedReliableAdmissionDoesNotConsumeSequenceOrPayloadBudget`: capacity-one subscriber, one buffered and one pending RELIABLE event, reject the third publication, free one outstanding slot, then prove delivered sequence values are 1, 2, 3 (without a gap) and all retained frozen memory is released after consumption.
2. `directGroupLeaveRejoinDetachesOldSubscriptionBeforeNewAdmission`: direct ActorGroup (not `pg`) leave rejects publishing/subscribing, drains an unread payload, then rejoin yields an independent subscription. The actor remains alive throughout.
3. `groupShutdownFailsBufferedAndPendingReliableWritesWithoutKillingMembers`: host group close must fail pending reliable admission and release both buffered and backpressured payloads; group close does not own ordinary actor lifecycle.
4. `actorPublicationIsRejectedAfterConcurrentHostGroupShutdown`: enqueue a bounded batch of actor publication turns while host closes the group, then a sentinel after `group.close()` returns must fail admission. Overlapping earlier publication successes/failures may legally linearize on either side.

`ActorEventBusShutdownStressTest` (1):
5. `repeatedConcurrentPublisherAndGroupShutdownHaveNoResidualReservations`: 12 bounded rounds. Two host threads race 32 `LOSSY` publications against manager group close using a rendezvous barrier and reproducible seeded scheduler yields. Accepted versus rejected attempts both count; after concurrent completion no new admission, no retained aggregate payload bytes, and ordinary subscriber actor still alive. Iteration failures include round context.

The branch's **source-local** exact-head JDK 25 GitHub Actions workflow selects these two classes plus the six inherited `pg`/EventBus tests. It uses no cross-org checkout and no dependency cache.

## Test evidence classes / fail-closed interpretation

- **Source inspected:** yes (GitHub connector exact-file blobs, branch and code changed).
- **Java compile:** **NOT_EXECUTED** (no matching exact-head nonzero-step build receipt).
- **New JUnit methods:** **AUTHORED, NOT_EXECUTED**, even if a GitHub workflow reports `failure` without job steps.
- **Portable conformance:** historical isolated V8 source-free vector smoke passed 26/26 plus two grant-close regressions on conformance #631, but that is not current Java, Node CI, compiled AttoLang guest, or native/AOT execution.
- **Guest backends:** Java/GraalVM, LLVM, Wasm, .NET, Cranelift and independent Forge remain **NOT_CERTIFIED**.

Source-org GitHub Actions admission is currently blocked by [issue #315](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/315) (payment/budget admission; exact cause needs billing-admin inspection). A source-free, no-checkout alternate-org canary has previously executed successfully, but source-transfer incident hold [#337](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/337) prohibits copying private source trees/blobs to a different organization. **Do not try cross-org mirroring for these Java tests.**

## Release gates

1. On approved same-source-org exact-head execution, run:
```bash
mvn -B -ntp -Dtest=ActorEventBusAdmissionRevocationTest,ActorEventBusShutdownStressTest,ProcessGroupAtomicDetachRaceTest,ActorEventBusTeardownLockTest,ProcessGroupRegistryMembershipEpochTest,ProcessGroupRegistryRetirementTest,ProcessGroupRegistryTest,ActorEventBusTest -DfailIfNoTests=true test
```
2. Collect commit SHA/tree, run/job/attempt, runner ID, actual named steps, JDK/GraalVM version, Surefire test counts, stdout and failures. Report timeout as actual failure **only if** the test executed.
3. Test direct host `ActorGroup.join` versus actor leave, topic publication versus member retirement, subscription close vs waiter, group delete and registry close under quota pressure. Extend bounded source tests where actual failures expose gaps; do not infer concurrency correctness from static ordering alone.
4. Complete the independent parent-stack merge audit and compiler-generated guest Pub/Sub conformance on each backend. Keep **DRAFT** and uphold private-source export holds until security and release gates pass.
