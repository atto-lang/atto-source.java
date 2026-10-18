# Source actor pipeline intrinsics — authenticated-turn phase 1

Status: **DRAFT / partial guest lowering**.

This phase connects a narrow set of sealed Oreslang `self.*` routing calls to
the existing correlated Java mailbox router without exposing a route frame,
router object, actor identity selector, or other bearer capability to guest
code.

## Enabled source calls

Only direct calls on lexical actor `self` are admitted, and only for SHARED
actors:

- `self.forward(value)`
- `self.forward_round_robin(value)`
- `self.forward_to(target, value)`
- `self.forward_by_key(key, value)`
- `self.backward(value)`
- `self.reply(value)`

The compiler checks selector arity/type and applies the existing actor
callable-boundary data/ownership validation to each outbound payload.

`backward` creates one authenticated reverse hop to the immediate predecessor.
`reply` creates a new one-shot return frame whose destination is index zero of
the immutable authenticated route path. It preserves the flow ID, receives a
new route ID, and accepts no actor-selected destination. Intermediate actors
are therefore not re-entered merely to relay a direct origin reply.

Aliases and method references do not acquire routing authority. For example,
`val alias = self; alias.forward(v)` remains rejected.

Each authenticated inbound route frame is **one-shot**. A successful
single-destination publication retires the source frame before guest code can
route again. A selector/admission failure before publication leaves the frame
live so code may handle the error or choose another valid route. Repeated
`self.forward*` calls therefore cannot emulate the still-disabled
`self.forward_all` surface or hide partial fan-out results.

## Scheduler-owned authority

A routed mailbox publication carries two VM-only metadata values together:

1. the immutable `ActorPipelineRouteSession.MailFrame`; and
2. the matching `PipelineTurnAuthority`.

Neither value is part of `ActorMail<T>`. Mailbox publication first requires
the authority to attest that the frame is live in that exact route session; a
frame from one router cannot be paired with another router's authority. The
recipient claims the pair only when the real mailbox envelope begins its DATA
turn. Normal completion,
suspended receive completion, failed continuation re-entry, STOP/drain and
discarded queued mail revoke or retire the metadata.

Every compiler bridge call first proves:

- current runtime affinity;
- an active user DATA receive;
- a live SHARED actor;
- a non-retired route frame;
- the actual actor ID matches the frame receiver; and
- the frame is live in the exact router/session carried by the same envelope.

An ordinary actor message, startup/cleanup code, host call, stale generation,
foreign actor, alias or fabricated target cannot manufacture this authority.

## Intentionally still fail-closed

`self.forward_all(value)` is not enabled. Fan-out is non-transactional and
must expose an Oreslang-level typed per-edge admission receipt vector rather
than hide partial success behind `void`.

Source-declared `transform pipe actor` / typed pipeline graph lowering also
remains unavailable. Phase 1 therefore proves the intrinsic/runtime authority
boundary for a supervisor-installed graph; it is not yet a complete
source-only pipeline feature.

The Java host reference still uses one generic payload type across forward and
reverse directions. Full source pipeline lowering must type forward and
backward ports separately and preserve Sendable/ownership rules.

## 2026-10-17 security / rejection hardening

The guest-intrinsic integration now reuses the final DATA-mailbox recipient
check from the preceding host routing audit: an immutable graph-instance to
**actual ActorId** binding is installed with the trusted session, and
`ActorRuntime.sendWithPipelineFrame` independently verifies the live
frame's bound recipient and SHARED destination kind **before mailbox
reservation or payload serialization**. Merely pairing a live frame and
`PipelineTurnAuthority` cannot send it to a different actor, and a
selector-only session cannot publish guest DATA.

A failed `backward` or direct `reply` mailbox admission no longer
silently destroys the source receive's reverse-routing right. The router
restores the source frame on a known prepublication `RuntimeException`;
this also handles the path in which rejection-side
`MessageEnvelope.close()` already retired the attempted child.
Allocated route IDs remain spent (never reused). Fatal `Error` is
not retryable and cannot be described as a safe rollback. An accepted
envelope is not automatically retried: scheduling failure after DATA
publication must remain distinguishable from admission failure.

The compiler now rejects direct `self.forward*`, `self.backward` and
`self.reply` calls in `on_start`: there cannot be a DATA mailbox turn
or verified return path during startup. Other helper-method invocations
still rely on the VM's runtime-authenticated receive guard. Tests cover
wrong-recipient, unbound-session, valid recipient, failed backward followed
by successful direct origin reply, and all enabled startup-hook negatives;
the standalone route session smoke also covers rollback after envelope
cleanup and spent route IDs.

These source changes/tests are committed, not independently validated
by a successful exact-head Maven/Native Image run. Both PR #1138 and
the alternate stacked compiler slice #1171 remain draft until their
overlapping source changes are reconciled against a tested base.

## Evidence required before promotion

1. Exact-head Maven/JUnit compilation and execution of
   `ActorPipelineRoutingAdmissionTest` and
   `ActorPipelineCorrelatedMailboxRouterTest`.
2. A positive compiled Oreslang guest test in which a host/supervisor-installed
   typed graph delivers a routed mail turn and guest `self.forward`,
   `self.backward` and `self.reply` traverse real mailboxes.
3. A negative compiled guest test proving ordinary `ActorRef.send` mail
   cannot call any routing intrinsic.
4. GraalVM Native Image coverage and cancellation/STOP tests.
5. LLVM parity before claiming cross-backend guest routing.
6. Typed source graph/port lowering and a per-edge `forward_all` receipt ABI
   before declaring the routing surface complete.

A zero-step GitHub Actions result is infrastructure evidence only and is not a
compiler or runtime test result.
