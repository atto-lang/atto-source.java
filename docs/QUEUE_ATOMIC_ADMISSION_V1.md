# Queue atomic authenticated admission — Java reference slice

Status: **host/runtime draft; not a guest API or production queue release**.

## Admission linearization

The Java runtime exposes a host-only
`ActorRuntime.admitCurrentQueueCommand(transaction, producerSlot, expectedGeneration)`.
It accepts no sender ID or payload argument. It reads both from the active
runtime-owned mailbox envelope for the current queue actor turn.

The lock order is:

1. queue actor `lifecycleLock`;
2. that actor's `QueueCommandAuthority` monitor;
3. the `QueueAdmissionTransaction` monitor.

Parent grant, parent revoke and authenticated producer admission use the
same lifecycle/authority ordering. A producer command therefore either
reserves admission before revocation or observes the revoked grant. Mailbox
sequence floors also prevent pre-grant queued commands from gaining authority
retroactively.

The transaction ledger binds on first successful admission to one exact queue
actor ID. A second queue actor cannot reuse the same ledger.

Ingress and completion storage use private bounded `ChannelRuntime.Channel`
instances as their sole FIFO buffers; this adds no second pending-writer list or
parallel scheduler. The transaction ledger owns reservation accounting, while
the channel primitive owns bounded FIFO storage.

## Reservation and completion

Each successful admission reserves, before publishing its delivery:

- one outstanding ingress unit (pending or in-flight); and
- one bounded completion slot.

Delivery is FIFO. A delivery moves from pending to in-flight, then exactly one
completion can be published. A foreign, still-pending or already-completed
delivery cannot complete. Completion capacity is released only when its
completion record is drained. Closing the ledger rejects new admission while
allowing already-committed work to drain.

The runtime transaction does not accept or invoke guest callbacks. The payload
is sourced from the active mailbox envelope, which has already crossed the
ActorRuntime transport-freezing boundary.

## Tests authored

`QueueAdmissionTransactionTest` covers:
- authenticated producer admission through an actual actor turn;
- ingress saturation;
- producer revocation before a subsequent command;
- completion-capacity backpressure independent of ingress capacity;
- exactly-once completion and rejection of foreign delivery handles;
- stale expected generation rejection without resource reservation;
- rejection of ledger reuse across queue actor incarnations; and
- close, committed FIFO drain and terminal drained state; and
- FIFO behavior of the ChannelRuntime-backed ingress and completion channels.

The existing `QueueGrantAdmissionFenceTest` and
`QueueGrantRevocationFenceTest` cover pre-grant admission and revocation of
already-admitted-but-not-yet-dispatched mail.

## Explicit limits

This is a **host-side admission ledger**, not end-to-end queue completion.
It does not yet:
- publish completions to an Oreslang actor output channel or remote consumer;
- implement guest-level queue handles or typed source lowering;
- implement cancellation tickets or durable replay storage;
- provide a generation-advance/restart API (a retired queue ledger is closed;
  a replacement actor must get a fresh ledger);
- integrate with the existing BoundedQueue/ChannelRuntime as the sole storage;
- prove native-image reachability or any LLVM/Wasm/Cranelift/.NET behavior.

Do not enable the demo's default-deny queue. The next integration step is to
connect this admission ledger to one bounded runtime-owned queue storage and
its reserved completion sink, then verify actual guest producer-to-consumer
delivery. All new tests remain authored until exact-head JDK25/GraalVM
execution passes. Zero-step GitHub failures are `NOT_EXECUTED`.
