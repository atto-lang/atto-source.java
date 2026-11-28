# Actor–supervisor mail over Channels: implementation boundary (Java V1)

Status: **runtime-substrate audit + regression tests; no source-level or cross-backend certification**.
The assertions here describe Java `main` at the audit point (2026-11-10); the
canonical behavioral contract remains `contracts/v1/observable-stream-channel.md`.
Do not confuse the existence of a `ChannelRuntime.Channel` with an end-to-end
supervisor CONTROL protocol.

## What exists

| Path | Transport and accounting | Full-path behavior | Authority |
| --- | --- | --- | --- |
| Actor USER DATA | Private `ChannelRuntime.Channel<MessageEnvelope>` with exactly `policy.maxMailboxMessages()` slots; separately charged bytes/sequence | `send` fails fast; host `sendAsync` retains at most 256 producer admissions and returns an `OresFuture` that completes on **admission**, not processing | Authenticated `ActorRef` + sender/incarnation/payload checks |
| Actor continuations | Independent, bounded `ChannelRuntime.Channel<MessageEnvelope>`, 1,024 internal slots | Do not borrow USER slot capacity | Runtime-minted continuation receipts |
| Actor SourceTask cleanup | Separate bounded runtime Channel, 1,024 internal slots | STOP may retain authenticated lexical-unwind work | Runtime-minted SourceTask receipts |
| Actor output | Bounded `ChannelRuntime.Channel<ActorOutputEnvelope>` | Output admission is not delivery/flush/durability | Actor generation + byte budget |
| Host/supervisor lifecycle STOP or cancel | `ActorControlHandle` calls the privileged ActorRuntime lifecycle method; **not** an ordinary USER Channel send | Seals admissions and detaches pending DATA producer waiters without requiring a USER slot | Supervisor-context guard; descendant/lifecycle fencing |
| Child failure to supervisor | **Separate draft work, not implied by the continuation Channel** | Lossless reserved terminal CONTROL, strict priority/fairness and ACK/retry are not declared production-ready here | Must be runtime-minted and generation-fenced |

A full mailbox has two deliberate outcomes: synchronous `send` fails with
`MAILBOX_FULL`; `sendAsync` suspends only the logical producer via a Future
when there is bounded admission budget. It **must never park an actor carrier**
or allocate unbounded pending waiters. When a USER credit is released it must
be transferred FIFO and irrevocably to exactly one pending producer, with
cancel-before-transfer and post-transfer semantics defined by the shared
Channel/mailbox contract.

## Do not conflate these capabilities

1. `Channel<T>` is the guest-facing communication primitive in its own
   execution/admission domain. A raw `Channel`, its read-only/write-only endpoint views, `SelectSet` or control handle
   cannot be placed in an `ActorMail` payload to mint new authority.
2. `ActorMailbox<T>` is the internal Channel-backed envelope router with
   sender authority, incarnation, freeze/copy, byte budgets, sequence numbers,
   and a serial logical handler-lifetime lease. `Channel(0)` alone is **not**
   serial actor processing backpressure: rendezvous admission does not imply
   completed handling.
3. Supervisor **application DATA mail** may use an ordinary actor mailbox
   (when a supervisor application-service actor is expressly installed).
   Privileged child failure, STOP, restart, fencing and hot-upgrade commands
   must **not** compete for the application's USER mailbox credits.
4. `ActorControlHandle.cancel()` currently proves a privileged host lifecycle
   operation exists; it does **not** prove that every child failure has a
   reserved, lossless, fair supervisor CONTROL delivery queue.

## This PR's focused Java regression gate

`ActorSupervisorMailChannelBoundaryTest` covers:

- Full one-message USER queue + suspended producer + host supervisor cancel:
  waiters settle without waiting for a USER credit; a physically held handler
  still joins before its old state can be considered retired; an unrelated
  sibling remains usable afterward.
- Full `Channel` capabilities and both read-only/write-only directional views
  are rejected as direct/nested ActorMail payloads, and rejected payloads
  cannot consume legitimate mailbox credit.
- Neither a full `Channel` nor either directional endpoint view can escape
  validation by entering `sendAsync` while
  the USER mailbox is full. A later valid producer still observes ordinary
  finite backpressure and cancellation.

Production hardening explicitly recognizes `ChannelRuntime.ReadChannel` and
`ChannelRuntime.WriteChannel` alongside the full Channel in all three actor
transport/share rejection checks. This tightens an already fail-closed unknown
host-object path with type-specific denials and regression diagnostics.

These are **host JUnit tests** and may require a runner with JDK25/Maven. The
host fixture intentionally holds one physical callback with a latch to force
DATA saturation. That is not an actor-liveness or no-blocking-carrier proof.
Until exact-head steps and JUnit reports execute, mark this gate
`AUTHORED_NOT_EXECUTED`.

## Still required to ship supervisor mail

- Integrate a **single authoritative** bounded, lossless terminal supervisor
  CONTROL delivery implementation. Reconcile overlapping Java supervisor
  CONTROL drafts (including #1223 and #1320) rather than introducing an
  unrelated queue. Reserve failure delivery capacity before child publication;
  deny new child admission when that capacity is exhausted.
- Prove priority over saturated USER mail, bounded fairness during failure
  storms, ACK/retry, duplicate suppression, generation/epoch fencing,
  descendant STOP ordering, cancellation and physical quiescence.
- Expose typed `.atto` send/admission without exposing the raw private
  mailbox Channel or granting guest code supervisor CONTROL authority.
  Guest `await` must suspend logical execution and preserve the serial lease.
- Execute actual compiled `.atto` guest fixtures plus exact-head JDK25 JUnit,
  GraalVM/native-image, LLVM, .NET NativeAOT, Wasm and Forge negative/positive
  parity fixtures. A zero-step Actions run is `NOT_EXECUTED`, not evidence.
- Do not copy private source into a different GitHub organization merely to
  obtain Actions minutes without a separately reviewed destination,
  authorization and exact-tree verification.

Related work: Java #1978 (merged USER backpressure), #1981/#1983 (draft
admission-domain composition), #1223/#1320 (draft supervisor CONTROL),
#2297 (design-only pipeline DATA versus supervisor CONTROL), and #2785
(draft supervisor failure DATA-admission linearization).
