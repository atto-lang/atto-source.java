# Oreslang OTP: one upgrade publication authority

Status: engineering decision / audit gates, NOT live hot-reload implementation.

## Decision

Select the **single generation + mailbox + immutable route publication**
candidate `ActorUpgradeAtomicPublicationV1` from draft #1432 as the
integration baseline. Keep `ActorRoutePublicationV1` an inactive
alternative and do NOT expose two runtime publishers, separate DATA dispatch
paths or multiple supervisor CONTROL schedulers.

Rationale:
- `ActorUpgradeAtomicPublicationV1.publish` holds one coordinator lock and
  calls `ActorUpgradePreflightV1.commitWithAuthoritativeRoute` under both
  generation and mailbox authority locks. It advances the same epoch in
  generation, mailbox and the single Route record.
- The alternative `ActorRoutePublicationV1` also has a single logical
  route record but creates a fresh task-generation gate with epoch 1 at
  every publication while the logical route epoch grows. That is an
  independently valid model but requires explicit epoch mapping for every
  suspended Task, caller, foreign receipt and teardown path. Having both
  models runnable would create two incompatible sources of truth.
- This selection is conditional on end-to-end tests. If its immutable
  physical delivery reservation proves unsafe, revisit the selection
  through a reviewed contract change, not a second active API.

## Required integration transaction

1. The existing #1223 serialized priority CONTROL system remains the only
   actor scheduler. Fatal child notices use pre-reserved no-drop slots.
   Upgrade commands use their separate bounded subquota (#1476).
2. Mint supervisor authority in trusted runtime CONTROL, binding runtime ID,
   logical ActorRef ID, incarnation, current route epoch and compiler-
   authenticated candidate image digest. A raw host-supplied number or
   hash string is not a cryptographic/compiler admission receipt.
3. PREPARE_UPGRADE is a request only. The CONTROL coordinator (not an actor
   callback) stages `ActorUpgradeAtomicPublicationV1` and
   `ActorUpgradePreflightV1` under that authority.
4. Freeze outgoing DATA dispatch at a recorded old-route watermark, not
   incoming bounded immutable DATA admission. Preserve queued FIFO and all
   queued+dispatched-but-unsettled credits.
5. Quiesce and authenticate: all old physical mailbox deliveries, actor
   turns, suspended Task frames, state loans/borrows, pending reply writers,
   timers, FFI/DMA and code pins must be retired. An interrupted or expired
   wait is NOT evidence of quiescence.
6. Stage one compiler-checked canonical state image, new physical actor
   and exact module/wire/capability ABI. New code has no externally visible
   side effects until publication or a proven compensation mechanism.
7. Commit through one `ActorUpgradeAtomicPublicationV1.publish` call,
   with no callbacks or allocation inside the authoritative publication
   critical section. Route, image, state and epoch switch together.
   Retire old executable image only after physical quiescence.
8. After a verified commit receipt, send COMMIT_NOTICE through priority
   CONTROL. The command ACK only confirms receipt, NEVER publication.
   On precommit error, ABORT + rollback old generation/mailbox. On
   postcommit error, quarantine and perform a NEW versioned upgrade;
   do not rewind delivered I/O or replay ambiguous physical sends.
9. Stable logical ActorRef remains separate from physical incarnation.
   GenServer reply tickets are trusted per-envelope sidecars and are
   single-winner. New incarnation cannot settle old-generation tickets.

## Invariants to prove before enabling COMMIT_NOTICE

- Exactly one logical route owner for DATA; bypassing the publisher is
  forbidden. `Delivery` must represent one authenticated dispatch.
- All generation/mailbox/route epochs agree before and after commit.
  Old or foreign `Staged`, `Delivery`, `Lease` and ActorRef fail closed.
- `pollDispatch` returns a physical-delivery reservation, NOT proof that
  transport accepted the send. A physical submit with ambiguous outcome
  requires quarantine, no requeue/retry or premature credit refund.
- Completion receipts must be minted by the runtime's physical actor
  execution context, not reconstructed from a guest sequence number.
- Actor crash/parent crash during any stage settles terminal failure on
  the *existing* reserved supervisor lane; an upgrade command is never
  relabeled as a failure notice or ACK.
- 64 CONTROL notices yield fairly to a healthy ordinary turn, except
  where a suspended source continuation holds actor-state ownership.
- No untrusted code migration into trusted memory/identity. SHARED
  source-actor integration is first; PRIVATE and UNTRUSTED require
  separate isolation and physical-ownership proofs.

## Branch dependency order

- #1223 (priority CONTROL) -> #1476 (bounded upgrade CONTROL);
- merged #1372 (host generation gate) -> #1367 (logical DATA proxy) ->
  #1432 (joint preflight/publisher).
- Do NOT merge the full draft #1432 on top of #1476 mechanically: #1432
  currently conflicts with its base and owns independent Task/suspends
  compiler changes. Extract/verify the one publication dependency
  without overwriting active ActorRuntime or releasing denied guest APIs.
- #1216 GenServer source policies/reply ledger remain pending real
  ActorRef-envelope integration. LLVM needs independently proven parity.

## Test gates

1. Source-owned exact-head JDK25/Graal Maven/JUnit; tests MUST execute,
   with nonempty test reports and artifact receipts.
2. Deterministic dequeue-vs-stop and stop-vs-ACK CONTROL races,
   128+ command admission/stop races, full DATA mailbox, 70-child burst.
3. Positive SHARED source-actor code-only upgrade with bounded immutable
   queued DATA and proven physical task/continuation quiescence.
4. Negative: stale/foreign authority, forged compiler receipt, ABA,
   old reply token, unknown delivery, nonzero pin, failed state restore,
   parent failure during stage, abort before commit, failure after
   publication and unload quarantine.
5. LLVM AOT-to-AOT parity including no mandatory JIT or GC. No claim
   of production-grade OTP parity from Java-only/host-only tests.

CI policy: cross-organization private-source mirroring is disallowed
until the source repo's approved-destination policy is reviewed and
merged. Do not substitute a source-free oracle or zero-step workflow
for exact-source end-to-end evidence.
