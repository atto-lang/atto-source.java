# AttoLang actor failure → supervisor CONTROL execution contract

**Status:** Proposed integration contract only; **not executed or implemented by
this PR**. An inbox claim is host bookkeeping, not a guest handler invocation.

## Lifecycle and trust boundaries

1. On first terminal failure, seal the source actor and detach a bounded
   immutable causal value. Never call guest Throwable methods, copy guest
   exceptions to peers, or treat a Java carrier stack as a guest source stack.
2. Admit one immutable failure receipt per actor ID + incarnation generation to
   its *direct* managed supervisor. Associate the supervisor route during
   trusted worker ownership admission; keep that route pinned through group
   closure until physical retirement. Wrong supervisor/ancestor cannot claim.
3. Admission, claim, scheduling, handler execution and acknowledgement are
   independent statuses. Do not infer one from the previous one.
4. The VM-only CONTROL envelope must bind
   `(supervisorId, actorId, actorGeneration, receiptSequence, claimAttempt,
   policyRevision)` to trusted runtime metadata, **not** guest-supplied IDs.
5. A queued/claimed receipt consumes bounded quota until an authenticated,
   attempt-fenced acknowledgement. A full inbox applies a nonblocking
   finalization-admission fence; no scheduler carrier waits on capacity.
6. After an actual source-compiled `.atto` supervisor policy handler has
   run under a pinned policy revision, the runtime validates incarnation,
   sequence, claim attempt, direct supervisor and outcome before acknowledging.
   A lost coordinator claim may be replayed, but an old claim attempt or
   retired code revision cannot acknowledge current work.
7. Exactly-once *effects* need an idempotency journal/transaction keyed by
   terminal actor generation, not just an exactly-once queue insertion.
   Do not claim this before guest handler execution and replay tests.
8. Final actor arena reclaim requires independent physical quiescence and
   outstanding loan, mailbox, cancellation and cleanup receipts; neither
   host inbox acknowledgement nor admission alone authorizes reclaim.
9. Shutdown/hot upgrade must preserve generation fencing and distinguish
   deferred, retried, canceled, quarantined, scheduled and executed receipts.

## Required compiler-backed positive and negative fixtures

Positive: one failed actor is delivered to exactly its direct supervisor,
source policy executes once logically, cancellation/handler retry leaves no
double side effects, and physical actor arena retires only after all receipts.

Negative: unauthorized ancestor/foreign supervisor; replayed receipt sequence;
stale actor generation; stale claim attempt; wrong policy revision; group
closure and worker readoption during failure; inbox saturation plus
cancellation; repeated close/restart; malformed/unbounded causal snapshots;
unexpected allocation failure while admitting/requeuing; guest exception,
arena, or mutable object escaping into a public Future or peer actor.

For every backend (Java/GraalVM, LLVM, .NET NativeAOT, Wasm, Forge), record the
exact guest fixture, expected output or rejection, actual compiler/guest
execution, job ID/attempt and nonzero steps. A standalone Java host probe or
source-free runner canary is **not** guest execution evidence.

## Existing source-only bridge

`SupervisorFailureInbox` is a bounded, host-owned, monitor-guarded ledger.
Its admission and requeue operations stage their new map/deque indices and
commit atomically, rather than relying on rollback under allocation failure.
Its `pollHost` and `acknowledgeHost` are diagnostic APIs only. Do not use
these methods as a guest CONTROL authority or claim a handler executed.

The private-source CI carrier must be independently authorized under
`.github/private-ci-approved-repositories.txt` before exact-tree export.
Review request: https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2555.
