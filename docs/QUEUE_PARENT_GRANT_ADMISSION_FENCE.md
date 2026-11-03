# Queue direct-parent grants and admission-sequence fencing

Status: **draft Java host/runtime + source-guest integration; not a passed guest release gate**.

The existing queue authority proposal uses the admitted runtime ActorMail
envelope as the source of sender identity and the actual structured parent
as supervisor. This extension adds ActorRef-based parent-issued delegation.

## Guest API

`queue.grant_producer(slot, producer_ref)` requires an int slot and a
real `ActorRef<T>` producer. `queue.revoke_producer(slot)` accepts an
int slot. Neither call accepts an ActorId, a string producer ID, or a
guest-constructed capability. The runtime requires the caller to be the
queue actor's **live direct spawning parent** and both actor references
to belong to the same ActorRuntime. Only slots 0 and 1 are supported.

Grant, revoke and SEND/CANCEL authorization synchronize on the per-queue
`QueueCommandAuthority` monitor, not on a global lock.

## Admission-fence invariant

A grant MUST NOT upgrade a producer message that was admitted to the queue
before the grant was issued. Without this rule a producer could flood an
untrusted message, later be granted authority, and have buffered earlier
commands accepted retroactively at dispatch.

At grant time the runtime takes the queue's `lifecycleLock` (the lock
that serializes user mailbox sequence admission), captures
`nextUserMessageSequence`, and stores it with the producer slot grant.
The queue authorizes SEND/CANCEL only when both the runtime-provided
sender matches the currently live grant **and**
`activeMail.sequence >= producerAdmissionFloor[slot]`.

This grant fence applies independently on each new grant, including
re-grants after revocation. No guest-provided epoch or request ID is
used as evidence of the admission sequence. Internal actor mailbox
sequence overflow already fails closed at admission.

## Tests

- `QueueRuntimeAuthorityTest`: direct parent grant and revocation,
  outsider and host-main denial, authorized producer vs outsider.
- `QueueParentGrantTypingTest`: valid ActorRef typing, forged producer
  string rejection, invalid slot types, runtime rejection of host main.
- `QueueGrantAdmissionFenceTest`: bounded, controlled host-only
  concurrency regression; pre-grant buffered producer mail stays denied
  and post-grant producer mail is permitted.
- The earlier `QueueGuestAuthorityBridgeTest` protects the
  `self.queue_authorized` source intrinsic and method shadowing.

**Execution:** none of the above new Java/JUnit tests have passed CI.
The active source organization continues to report zero-step workflow
failures. A separate private carrier passed source-free JDK25 and
C++17/sanitizer tests, but the exact private Git tree transfer was
stopped by a safety restriction. No Java source guest or native queue
test was run on that carrier.

## Gates before enabling the secure queue

Authorization is currently a boolean check and is NOT atomic with a
subsequent user-state write. The default `TypedQueue` MUST remain
denying operations until authorization+state mutation, request-ID
ownership, bounded reply reservation/journal, direct producer
notifications, shutdown/ACK sequencing, cancellation, and restart
fencing have been proven on the exact target compiler and backend.
Five-backend parity cannot be inferred from Java host tests.

## Revocation of already-admitted producer mail (November 3, 2026)

QueueGrantRevocationFenceTest adds a deterministic mailbox-hold schedule: the parent grants slot 0, the queue actor is held inside one turn, the producer submits a command behind that turn, the parent revokes the grant, and the queue actor is released. The queued command must be rejected when it is dispatched, and a second command submitted after revocation must also be rejected.

This verifies dispatch-time authority revalidation for queued mail. It does not make authorization and a later queue-state mutation atomic, nor does it prove reserved reply publication, exactly-once delivery, durable receipt, or source-guest message delivery. The test is host-runtime only and must execute on the exact PR head before it is counted as evidence.
