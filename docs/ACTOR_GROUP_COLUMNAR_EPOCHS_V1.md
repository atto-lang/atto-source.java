# ActorGroup columnar epochs — host runtime vertical slice

Status: **experimental Java host API only**. This change is not an Oreslang
guest syntax feature, not a full ECS region implementation, not a shared SoA
view, and not LLVM parity.

## Why

ActorGroup memberships are cooperation relationships, **not actor lifecycle
ownership** or implicit shared-memory capabilities. An actor can be in several
groups while its mutable heap remains private. A group's per-actor mailbox
transports must remain bounded and isolation checked.

The narrow first slice aggregates one *primitive row* per selected actor into
a field-major (`double[field][actor]`) result. This avoids scattering the
aggregator's data across individually boxed result objects while continuing
to use normal ActorRef.send to request work. Collection involves a single
OresFuture rather than one per-member result future.

## Java host API

```java
ActorGroupColumnarEpoch epoch = group.requestDoubleEpoch(
    recipients,                       // List<ActorRef<List<Long>>>, live members
    id -> List.of(id, 16L),         // [epochId, dtMillis], supported payload
    List.of("x", "y", "energy"));

// In each recipient actor's mailbox handler, after its own private update:
group.publishDoubleEpoch(message.get(0), x, y, energy);

// Await outside the guest dispatcher, or through Oreslang's scheduler:
ActorGroupColumnarEpoch.Snapshot finished = epoch.completion().join();
double[] xColumn = finished.column("x");  // defensive copy
```

A manager can also begin a manually coordinated epoch over the current group
membership with `group.beginDoubleEpoch(fields)`. Its caller is responsible
for issuing requests, passing the epoch ID, and handling unresponsive members.

All selected actor IDs are captured once and sorted deterministically.
Publishing from a nonmember, host without actor context, duplicate publisher,
stale epoch, or actor absent from the selected recipient set fails closed.
Only the actor's own runtime execution context can publish its row. Private
actors publish scalar copies; this API does not expose their heap pointers.

## Completion and failure semantics

- Exactly one contribution per selected actor, one aggregate completion future.
- Empty selected sets return an immediately completed, zero-row snapshot.
- One active epoch per group; completion or failure permits a later epoch.
  Aborting an incomplete epoch drops its column buffers promptly.
- Any selected member leaving, terminating, or group closure aborts the epoch.
- The collected snapshot has immutable member/field metadata; `column(...)`
  returns a defensive copy. Mutating the returned array cannot change it.
- The result is field-major only **within one group result buffer**. It does
  not stitch together separately-owned actor-private SoA data by reference.
- Results are bound to the epoch ID, not the current group member count.
- Dispatch through `ActorRef.send` is **not atomic fanout**. If one send
  rejects, the returned epoch has a failed Future, and previously accepted
  actor messages are not retracted; actors must make commands idempotent as
  required. Fatal JVM Errors may still propagate immediately.
- Aggregate completion proves each actor called `publishDoubleEpoch`, not
  that all effects were transactionally committed or that a remote message
  was durably delivered.
- This experimental API provides no built-in deadline; a still-alive actor
  that never publishes can leave the Future pending. Host callers should use
  their own timeout and close/terminate the group if appropriate.

## Bounds / eventual work

- At most 16 double fields; at most 16 MiB of raw column array per epoch.
  This is a fixed admission cap, not yet integration with OresVM's aggregate
  actor-memory accounting or a global retained-snapshot budget.
- No new thread, pool, actor per row, or unbounded queued callback per result.
- ActorRef mailbox admission and isolation still apply independently. Use
  transport-admitted immutable scalar/list/map messages; arbitrary Java records
  are not Sendable in the current host transport.
- No signed capability exported for zero-copy actor heap access.
- Future work: group-owned memory reservations; cancellation/timeout policy;
  typed fixed-width primitive columns and runtime-native AoSoA shards;
  differential perf benchmarks; source-level exposure; scoped, generation-
  checked Region execution permits; Java Native Image and LLVM parity.
- No benchmark speedups are claimed by this first slice.

Regression tests: `ActorGroupColumnarEpochTest`; existing
`ActorGroupMultiMembershipTest` and `ActorEventBusTest` should remain green.
A passing standalone collector test must not substitute for exact-head Maven
and guest-source tests. In particular, a zero-step GitHub Actions failure
does not establish a compilation result.
