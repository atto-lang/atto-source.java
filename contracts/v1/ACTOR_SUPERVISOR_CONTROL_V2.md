# Actor Supervisor CONTROL v2

Status: runtime contract under review.

This contract defines Oreslang's terminal-failure control plane. It is intentionally
separate from ordinary actor mail and from source continuations.

## Required invariants

1. **No CONTROL starvation by user mail.** A terminal child failure MUST NOT
   require an ordinary mailbox slot. The runtime reserves one child-generation
   slot before child admission and one root-fallback slot before actor admission.
2. **No silent loss.** Once a terminal notice exists it remains queued or
   in-flight until acknowledged, retried, or transferred to the root supervisor.
   Overwrite/drop-on-overflow is forbidden.
3. **Generation fencing.** Reserve/publish/ack/retry are bound to the exact actor
   generation. Stale or duplicate publication fails closed.
4. **Parent-carrier execution only.** A child failure callback executes only on
   the parent's serialized actor execution lease. The failed child's carrier
   MUST NOT directly invoke parent guest code.
5. **Cause isolation.** CONTROL carries immutable runtime-owned metadata only.
   Raw Throwable/exception graphs, mutable guest objects, stack objects, and
   user message strings MUST NOT cross actor isolation through supervision.
6. **Root fallback.** If a parent is stopping, failed, fenced, or fails while a
   child notice is in flight, the notice transfers to the independently
   pre-reserved root slot before the parent can finalize.
7. **Backpressure instead of dead letters.** If undrained root CONTROL consumes
   all reserved generations, new actor admission fails. The runtime MUST NOT
   discard old terminal notices to admit new actors.
8. **Priority with bounded fairness.** CONTROL has priority over continuations
   and user mail. To avoid an infinite failure storm permanently starving a
   healthy actor, after 64 consecutive CONTROL callbacks one runnable ordinary
   turn may execute before CONTROL priority resumes. A suspended source receive
   keeps its state loan and therefore admits its continuation before any
   supervisor callback that would touch actor state.
9. **Single-flight handling.** At most one supervisor CONTROL callback may be in
   flight per parent lane. A retry retains the same immutable notice. Root
   polling returns a settlement lease: dequeue is never acknowledgement.
   Durable handling must explicitly `ack`; transient failure must `retry`.
   Dropping/closing an unsettled root lease retries by default, so host failure
   after dequeue cannot erase a terminal event.
10. **Deterministic observability.** Every notice has a monotonic runtime-local
    sequence and stable failure class. Host-only metrics expose reserved,
    pending, in-flight, published, acknowledged, retried, transferred,
    reservation-rejected, duplicate-publication-rejected, and fairness-yield
    counts.
11. **Graceful termination is not failure.** A child that stops without a
    terminal cause retires both parent and root reservations without publishing
    a failure event.
12. **Default policy is fail-closed escalation.** A parent without an explicit
    child-failure handler fails with a cause-free runtime wrapper. The original
    child exception remains local to trusted host diagnostics.
13. **No per-actor supervisor thread.** CONTROL shares the existing actor carrier
    system and serialized logical lane. Reservation is metadata, not a thread.
14. **Bounded memory.** CONTROL capacity is bounded by runtime actor admission.
    Failure storms cannot grow an unbounded side queue.

## Why this differs from common actor-runtime failure modes

- A full ordinary mailbox cannot hide a child failure behind user traffic.
- Dead-letter/drop behavior is not accepted for terminal supervision.
- System messages are not executed concurrently with actor state.
- Priority traffic cannot grow without a bound and cannot starve ordinary work
  forever.
- Failure metadata does not smuggle mutable exception graphs across actors.
- Parent shutdown cannot erase an already-published child failure.
- A supervisor that stops draining failures eventually applies admission
  backpressure rather than silently degrading observability.

## Cross-backend parity

Java and LLVM implementations MUST preserve the same externally observable
invariants even if their scheduler internals differ. Backend-specific queue,
pthread, JVM, Graal isolate, or native carrier identities are not language-level
actor identity.

The shared conformance suite should eventually include:

- saturated user mailbox + child terminal failure;
- generation replay/duplicate publication;
- parent failure while child notice is in flight;
- root-sink backpressure and recovery;
- 70-child failure storm with the 64:1 priority/fairness boundary;
- structured cancellation classification;
- supervisor callback execution on the parent logical lane;
- source suspension/continuation interaction;
- stable metrics and monotonic notice sequence;
- Java/LLVM parity for the same event trace.
