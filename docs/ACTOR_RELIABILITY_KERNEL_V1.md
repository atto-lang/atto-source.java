# Actor reliability kernel v1

**Status:** current Java/Graal runtime hardening. This document separates
implemented invariants from roadmap items; it is not a claim that every
cross-backend actor feature is complete.

The goal is stronger than copying Erlang/OTP, Akka/Actix, or another actor
runtime. Oreslang should make overload, failure, lifecycle and authority
semantics explicit enough that fault containment and incident reconstruction
remain predictable under saturation.

## Invariants implemented on current main plus this slice

1. **Bounded admission.** User mailboxes have explicit item and memory limits.
   Full admission fails synchronously; no unbounded queue or unbounded producer
   waiter is hidden behind `send`.
2. **Control headroom is not user capacity.** Runtime continuations have
   separately reserved bounded slots. STOP/cancellation seals admission
   out-of-band and cannot depend on a free user mailbox slot.
3. **Capabilities are incarnation fenced.** Actor references carry runtime
   identity plus actor generation. Carrier thread identity is never actor
   identity.
4. **Structured failure containment.** Actor failure is fail-stop for that
   actor/subtree; independent actors and the root scheduler do not inherit an
   arbitrary sibling exception.
5. **Cancellation is control flow.** Guest catch code cannot turn structured
   cancellation into success; cancellation is observed at scheduler boundaries
   and force cancellation requires a host isolation revoker.
6. **Stuck-turn observation is independent.** A daemon watchdog can request
   cooperation even if actor carriers are saturated. It cannot execute guest
   callbacks, interrupt a carrier, or claim that a request equals suspension.
7. **Restart storms are bounded.** `SupervisorRestartWindow` uses a fixed
   timestamp ring and an injected monotonic time sample. Denials do not consume
   budget. Clock rollback rejects. Retry delay is explicit.
8. **Backoff is replayable.** Exponential backoff saturates rather than
   overflowing. Optional jitter is derived from a stable numeric key rather
   than process-global randomness, making an incident reproducible.
9. **Observability does not expose actor heaps.** `ActorHealthSnapshot` and
   `RuntimeHealthSnapshot` contain structural counters/flags only: no
   messages, guest state, exception text, frames, thread references or live
   capabilities. Actor code cannot call these host/supervisor APIs. A small
   terminal snapshot survives ActorCell retirement on the ActorRef for
   post-mortem inspection; it retains no actor state.
10. **Lifecycle and failure are orthogonal.** Health reports a stable lifecycle
    phase (STARTING/IDLE/RUNNING/SUSPENDED/STOPPING/FINALIZING/TERMINATED) and
    a separate terminal class (NONE/CANCELLED/QUOTA_EXCEEDED/FAILURE), avoiding
    ambiguous string parsing.
11. **Hot diagnostics stay bounded.** Core reliability events are fixed IDs
    plus one numeric value in the preallocated `CoreLog` recorder. Logging is
    off by default and does not format guest values on the event path.

## Actor-system pitfalls Oreslang must continue to reject

- unbounded mailboxes or unbounded dead-letter queues;
- selective-receive mailbox scans with workload-dependent latency;
- supervisor failure notices sharing saturated user-mail capacity;
- restart loops without an intensity window and explicit retry delay;
- wall clock for restart correctness;
- random retry behavior that cannot be replayed;
- executing supervisor policy inline on the failing child's carrier;
- user-forgeable CONTROL/STOP/failure messages;
- treating message enqueue as message completion;
- transparent remote references that hide partial failure and serialization;
- hidden shared mutable state, process dictionaries or ambient global registries;
- silent message drop, silent retry or retry-until-success defaults;
- blocking a scheduler carrier while waiting for actor/Future/channel progress;
- thread ID, pool lane or CPU placement being treated as actor identity;
- logging message bodies or exception text in always-on runtime telemetry;
- conflating actor fault isolation with hostile-code security isolation.

## Next release gates

The following remain explicit gates rather than assumed features:

- integrate the bounded authenticated supervisor CONTROL lane from the
  supervision work onto current main, with a reserved runtime carrier budget;
- integrate priority user mail only with bounded starvation
  (URGENT/NORMAL/BULK) while preserving task-owning continuation precedence;
- connect GenServer one-shot reply tickets to ActorRef mailbox admission,
  self-call refusal, deadline/cancellation and supervisor failure settlement;
- enable actor execution `max > 1` only after per-message execution frames,
  state-loan/critical isolation, cleanup ownership and CONTROL ordering have
  compiler/runtime proofs. Configuration alone never grants unsafe parallelism;
- add causal message/reply/parent/restart correlation to trace contracts without
  placing guest payloads in the core recorder;
- define bounded dead-letter policy for remote/routed transport separately from
  local mailbox failure;
- prove shutdown/restart/STOP races, restart-window arithmetic and priority
  scheduling with finite models plus adversarial stress;
- require Java and LLVM guest parity before removing native fail-closed gates.

## Operational model

An operator should be able to answer these questions without pausing or
inspecting an actor heap: Is it alive? Which incarnation? Is admission sealed?
How many user/control messages are queued? How many sends have been rejected?
Is a turn running? Is a source receive suspended? Has the watchdog requested
cooperation? How much mailbox memory is reserved? Is cleanup active? Was the
terminal outcome cancellation, quota exhaustion, failure, or graceful stop?
What is the runtime aggregate memory/actor/watchdog state? The same bounded
structural terminal record remains available after the live ActorCell is gone.

Those observations are diagnostics, not synchronization barriers. Correctness
must never depend on a snapshot remaining current after it is returned.
