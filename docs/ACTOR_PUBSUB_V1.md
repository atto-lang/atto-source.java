# Actor Pub/Sub backend v1 draft

Status: Java/Graal compiler/runtime draft, with a partial typed Result guest lowering slice. Portable Theater/Ensemble parity is **NOT_EXECUTED** and must be measured against the 11-backend 28-case `atto-platform-conformance#551` gate; this document must not redefine cross-backend core semantics.

Oreslang pub/sub is a capability of actors, not a new actor declaration kind. Do
not add `define publisher` or `define subscriber`. The source model is built
from `ActorGroup`, `Topic<T>`, `Subscription<T>`, and
`PublishReceipt`.

## Source model

An actor may belong to multiple ActorGroups at the same time. Membership is
cooperation/routing state only; it never grants lifecycle ownership, cancellation
authority, or access to another actor's state.

A publisher does not need to subscribe. A subscriber must explicitly subscribe
and must be an authorized member of the topic's scope. Leaving one group closes
only subscriptions owned by that group.

Current v1 source spelling:

```ores
val payments = actor.group("payments");
val completed = payments.topic<String>(
  "payment.completed",
  "reliable",
  64
);

// Root/supervisor authority explicitly admits an actor.
val audit = spawn AuditWorker();
await audit.get_ready();
payments.join(audit);

// Inside the already-admitted shared actor:
val actorPayments = actor.group("payments");
val subscription =
  actorPayments.topic<String>("payment.completed").subscribe();
val event = await subscription.next();
subscription.close();

// Publishing is independent of subscribing.
await completed.publish("p-1");
```

`ActorGroup` is intentionally not generic: one cooperation group can own many
independently typed topics. `Topic<T>` and `Subscription<T>` carry the payload
type. A group name is discovery only and never grants membership.

The exact parser/lowering spelling may evolve, but the semantic model above is
normative. No compiler may reinterpret group membership as supervision.

## Portable core versus backend extensions

The cross-backend portable-v1 core is the group-scoped **RELIABLE** path only:
atomic all-recipient admission, one topic sequence allocated only after full
admission succeeds, no implicit loss/coalescing/retry, and a nonblocking
`try_publish` admission result. Java's current host/runtime `publish` surface
and its completion future are implementation substrate, not proof that the
source-level portable `try_publish` contract has executed.

### Current nonblocking host substrate (NOT guest lowering)

`ActorEventBus.tryPublishReliable(name, value)` and the typed
`Topic<T>.tryPublishReliable(value)` use the runtime's existing authenticated
publisher admission. They return `Optional<PublishReceipt>`: present only
after full RELIABLE reservation preflight, empty only on bounded `Full`.
`tryPublishReliableSystem` is an explicitly supervisor-checked host path.
Non-RELIABLE policies, invalid/non-Sendable payloads, revoked memberships and
closed topics do **not** become silent empty results: they fail distinctly.
The host `tryPublishReliable` path now uses the topic's **physical queue
capacity**, not the asynchronous pending-writer quota. A capacity-one
subscriber with one unread delivery makes the next `tryPublishReliable`
return `Optional.empty()` *without* allocating a sequence or pending write.
An admitted receipt's completion future is already complete for its channel
admission, but is **not** an actor-handler acknowledgement. Existing
`publish()` retains bounded asynchronous pending-writer behavior. The
`sequenceUnsignedString()` representation covers the full unsigned u64
domain; signed `long` overflow at 2^63 is permitted, but reuse of zero
after 2^64-1 is not.

### Java source/compiler slice (IMPLEMENTED ON DRAFT; NOT CI-VERIFIED)

On the Java integration branch, `Topic<T>.try_publish(share! event)`
is parsed, type-checked as `Result<PublishReceipt, PublishError>` and routed
to the authenticated RELIABLE host admission primitive. `Ok` snapshots
the sequence and recipient admission counts; bounded Full becomes typed
`Err(PublishError{code="Full"})`. Non-RELIABLE policy, invalid payloads,
revoked membership and closed topics are still distinct hard failures, not
`Err(Full)`. The portable postfix `?` control-flow/cleanup path and
complete PublishError algebra remain separate unverified work.

The equivalent LLVM native substrate now has the V1 tagged Result ABI on
stacked PR #590, which distinguishes OK/Full from invalid/stale calls,
but **native compiler-generated Topic<T> lowering remains missing**.
The Java `.atto` guest JUnit cases and LLVM C ABI/CTest cases are authored
but do **not** have exact-head stepful CI execution. Neither backend can
claim guest portable parity until target execution and independently
authenticated conformance receipts pass.

`LATEST` and `LOSSY` remain useful Java runtime policies, but they are
**explicit backend extensions** outside portable-v1. They may not be used to
claim portable-core parity and must be negotiated/versioned separately.

## Delivery contract

* RELIABLE is the only portable-core candidate. The Java host substrate uses bounded reservation plus completion tracking; portable certification additionally requires the source-level nonblocking `try_publish` result and the shared vectors.
* LATEST is a Java extension: one unread slot per subscriber and coalescing of an older unread value.
* LOSSY is a Java extension: never wait and drop a new value when the subscriber's bounded queue is full.
* Delivery is at-most-once per accepted subscription publication.
* FIFO is required per publisher-to-subscription pair. There is no total order
  across topics or independent publishers.
* In-memory v1 has no durability or replay.

A `critical {}` body cannot suspend. If publication may suspend, it must occur
outside the critical section of a `suspends` routine.

## Lifecycle and identity

Membership and subscriptions are fenced by runtime identity plus actor
incarnation/generation. Actor termination removes all memberships and
subscriptions. A stale ActorRef or cached join capability cannot regain access
after retirement or generation change.

An actor may join A and B simultaneously. Leaving or closing A must not affect B.
A group that merely admits an actor cannot stop/cancel that actor.

## Isolation and tenancy

Cross-tenant publication is denied by default. Zero-copy delivery must not weaken
private/isolated actor memory rules. A backend that cannot safely deliver a
payload to an isolated subscriber must reject that subscription or use an
isolation-safe copy path.

## External transports

Redis or other brokers are adapters, not alternative language semantics.
Provider-specific durability, ordering, retries, duplication and replay must be
reported explicitly. An adapter must never silently claim the stronger
in-memory contract.

## Backend gate

Every backend consumes `contracts/v1/actor-pubsub-v1.json`. Unsupported guest
syntax must fail closed. Host-only registry tests do not establish source
compiler parity.
