# Actor Pub/Sub v1

Status: normative in-memory actor-runtime contract.

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

## Delivery contract

* RELIABLE is bounded asynchronous backpressure. Publication must never create an
  unbounded waiter list. Completion acknowledges delivery admission, not handler
  execution.
* LATEST has one unread slot per subscriber and coalesces an older unread value.
* LOSSY never waits and drops a new value when the subscriber's bounded queue is
  full.
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
