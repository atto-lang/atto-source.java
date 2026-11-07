# Publisher-scoped ActorGroup observation substrate (Java/GraalVM)

**Status:** Host/runtime implementation candidate. **Not** a source-language
`ObservableActor<E>` implementation, not a cross-backend parity claim and not
a guarantee of reliable processing acknowledgement.

**Security boundary:** `subscribePublisher` remains package-private in Java until a publisher-issued, nonforgeable observation grant is enforced. It is not an API exposed to untrusted guest source or general host callers.

This is the first backwards-compatible Java slice for the shared-language
`ObservableActor<E>` contract
([oreslang-common-source PR #200](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/200)).

## New host API

```java
ActorEventBus.Subscription<MyEvent> lease =
    group.events().subscribePublisher("changes", publisherRef);
```

Existing `subscribe("changes")` remains a topic-wide, single-subscription-per
observer compatibility API. `subscribePublisher` instead keys one lease by the
observer id plus the publisher's full `ActorAddress` (runtime UUID, actor UUID,
incarnation/generation, actor kind and transport). An observer can retain
independent leases to several publishers on a topic, or combine publisher-specific
and legacy topic-wide subscriptions. It must be a live SHARED actor that has
joined the same `ActorGroup`; the publisher must be a live member of the group.
Foreign, stopped and not-yet-joined publishers fail before allocation.

At publication, the bus partitions subscribers **before** reliable credit
reservation, channel writes, LATEST replacement and LOSSY drops. A system
publication has no actor publisher and therefore reaches only topic-wide
subscriptions. This is not implemented by filtering a shared channel after
fanout. Subscription removal on observer/publisher leave, publisher death,
group close and manual lease close releases unread retained payload ownership.

The publisher's address is rechecked while holding the topic's admission lock.
A publisher that leaves or terminates between initial lookup and registration
cannot leak an orphaned lease. Delivery-time checks also reject a stale
publisher incarnation, and `ActorRuntime.isAlive` now requires exact live
`ActorRef` object identity (not only a matching ActorId).

The existing event `sequence` is **per-topic**, not yet the new
`ObservableActor` per-publisher sequence contract. The existing Java
`ActorEventBus` still has only SHARED zero-copy subscriptions.
Its `Subscription<T>` uses Java generics and is not proof of Oreslang source
`Sendable` checking, event-schema identity, source visibility, or grant
authorization. In particular **an ActorRef is not an observation grant**.
`subscribePublisher` is callable only after explicit ActorGroup membership;
it is **not** the eventual source-level `observe_actor(grant)` operation.

## Required follow-on before source exposure

1. Add compiler-owned actor-only `ObservableActor<E: Sendable>` and
   `self.publish_event(E)`, with nominal event schema and real `Result` ABI.
2. Implement a nonforgeable publisher-granted observation capability scoped
   to an observer, publisher incarnation, event schema and permissions.
   Its grant mint and transfer must use the existing actor-capability controls.
3. Bind its admission to publisher-local event queues, per-publisher
   monotonically increasing sequence, and registration/close fencing.
4. Run observer hooks only on observer turns, never in the publisher or
   channel lock; runtime-owned hook notifications must be bounded and may
   not interfere with supervisor CONTROL delivery.
5. Preserve private/isolated actor restrictions until isolated-copy transport,
   per-domain quotas and deterministic release are proven.
6. Add actual guest `.ores` fixtures and exact-head Java JDK 25 /
   GraalVM AOT proof, then corresponding LLVM/.NET/Wasm/Forge contracts.
   LLVM must demonstrate zero GC and hard-real-time bounded publish work.

## Verification

Focused JUnit candidates:
- `ActorPublisherScopedObservationTest`: independent scoped+wildcard fanout,
  system publication exclusion, stale/foreign/not-joined publisher denial,
  retained quota, and publisher departure/stop cleanup.
- `ActorEventBusTest`: original bounded RELIABLE/LATEST/LOSSY behavior.

Workflow `actor-observable-publisher-scope.yml` verifies exact checked-out
commit/tree and runs both classes on GraalVM JDK 25. Pending execution does
not count as passing CI; source-org GitHub Actions may fail before any step.
