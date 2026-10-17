# Actor reliability kernel v1

This branch adds internal reliability primitives. It is intentionally not a new guest API and does not claim that the full supervisor or GenServer stack is finished.

## Existing runtime strengths audited

The current Java actor substrate already has finite user-mailbox admission, reserved continuation headroom, separate private/shared/untrusted dispatcher bulkheads, a bounded watchdog-notice queue with a dropped-notice counter, structured actor-tree cancellation, untrusted execution quotas, and stable machine-readable send-error reasons. Those mechanisms should be preserved rather than replaced.

## Failure modes this slice addresses

- **Crash-loop amplification:** RestartBudget uses an explicit monotonic clock, a finite restart window, quarantine delay, and no sleeping or hidden scheduler ownership.
- **Synchronized retry storms:** BackoffPolicy is exponentially bounded and deterministically jittered from a stable salt. Replaying the same attempt/salt reproduces the same delay.
- **Mailbox latency collapse before full:** OverloadGate adds hysteretic DATA admission. CONTROL is never passed through the DATA shedding decision.
- **Opaque incident diagnosis:** the event ring is fixed-size, sequence-numbered and monotonic-time ordered. It records only stable enums, actor/runtime UUIDs, incarnation and numeric detail; no message payload, arbitrary metric label, or exception text is accepted.
- **Call correlation ambiguity:** GenServerReplyLedger now distinguishes CLOSED from CAPACITY admission while retaining one-shot, runtime/actor/incarnation-fenced reply tickets.
- **Call lifecycle visibility:** GenServerPendingCalls optionally records registration, terminal reply/timeout/cancel/close, rejected stale/duplicate replies and overload admission without owning completion authority.

## Rules

Telemetry is observational and never a substitute for correctness. Pending-call terminal state is still decided only by the one-shot ledger claim. A caller timeout or cancellation retires interest but does not revoke work already admitted by the server.

Retry remains opt-in. A future guest retry policy must require a finite retry budget plus an idempotency or deduplication contract. Mailbox admission is not processing acknowledgement and must never be presented as exactly-once delivery.

Supervisor integration must keep runtime CONTROL authenticated and bounded. Repeated child failure must eventually quarantine/escalate instead of consuming an unlimited restart loop.

## Evidence required before promotion

1. exact-source Java 21 compile and standalone kernel/ledger smokes;
2. JUnit saturation, restart-window, deterministic-backoff and reply/timeout/cancel race tests;
3. real guest-source GenServer execution with mailbox admission and serial state;
4. failure CONTROL wired to a real supervisor tree and restart-intensity exhaustion;
5. Java/LLVM differential source cases;
6. scheduler fairness, tail latency and allocation benchmarks under overload.

The standalone mirror tests prove only the copied exact blobs and their focused behavior. They do not prove the entire private repository, guest-source behavior, or LLVM parity.
