# GenServer pending-call Future arbitration

This host-internal slice composes the opaque correlation ledger with
`OresFuture`. It remains runtime plumbing, not yet the source-level
`GenServer.call_async` surface.

## One arbitration point

Every terminal path must first win the same `GenServerReplyLedger.Ticket`:

- valid reply -> exact-generation ticket claim -> Future success;
- deadline -> ticket retirement -> Future `TimeoutException`;
- consumer cancellation -> cancellation admission retires ticket -> Future cancel;
- exact target termination -> ticket retirement -> sanitized target failure;
- runtime/registry close -> atomically takes remaining tickets -> sanitized failure.

This ordering prevents these invalid states:

- a late reply reviving a timed-out, cancelled, crashed or restarted request;
- cancellation winning the Future after a reply already owns correlation;
- shutdown failing a call whose reply already won;
- a stale actor incarnation settling a newer call;
- a foreign registry settling another registry's handle.

## Predictable authority and identity

The runtime ledger uses a monotonically increasing **process-local opaque
correlation sequence**, not random UUID generation. Correlations are never
serialized or exposed as guest DATA. Runtime/actor/incarnation identity is the
authority fence.

`GenServerPendingCalls.ReplyCapability` is an opaque package-private
one-shot authority. It exposes no correlation or registry object and will only
settle a call when invoked with the exact target incarnation. It is sidecar
runtime state: it must never be serialized, frozen/copied as an ordinary
message field, logged, or treated as data.

Actor-originated registration also has a deterministic self-call gate:
same runtime + actor + incarnation is rejected **before correlation admission**.
A newer incarnation is a distinct target and is not rejected.

## Bounded crash and deadline indexing

Pending calls are indexed by exact target generation. Failing one actor
incarnation walks only the calls addressed to that incarnation, rather than
scanning every outstanding call in the VM.

Timed calls are indexed in a monotonic deadline tree. The registry owns no
timer thread and does not sleep. An existing VM/CONTROL timing substrate calls
`expireDue(nowNanos)`; expiration is deterministic by deadline then admission
order. The correctness clock accepts negative `System.nanoTime()` origins,
rejects rollback, rejects deadline arithmetic overflow, and linearizes clock
sampling with timed-call publication so an expiry tick cannot pass a call
between those two operations.

Reply, cancel, target failure, timeout and close all remove target/deadline
index entries when they win correlation, keeping auxiliary memory bounded by
live calls.

## Failure isolation

Registry/runtime shutdown never forwards the original host `Throwable` into
caller Futures. Pending calls receive a fresh cause-free,
suppression-disabled `GenServerCallClosedException` containing only the
bounded host exception **class name**.

Exact target termination is projected the same way through
`GenServerTargetTerminatedException`. Host messages, stack/cause graphs and
suppressed exceptions cannot become cross-actor data through either path.

The ledger uses insertion-preserving storage, so multi-call close settlement is
stable in admission order rather than inheriting hash-table iteration order.

## Payload-free observability

Host-only snapshots expose pending count, target bucket count, timed-call and
deadline-bucket counts, self-call rejections, deadline expirations, exact-target
failure settlements and correctness-clock regressions. Ledger pressure counters
remain separate: registered calls, successful reply/retire claims, rejected
stale/foreign/duplicate claims, capacity rejections, pending count and next
correlation. None of these diagnostics contains payloads, reply values,
exception text, capabilities or tickets.

## Still missing before guest call_async

- ActorRef mailbox integration and a typed Call sidecar/envelope admission path.
- Binding the deadline queue to the VM's existing bounded monotonic timer
  substrate; this slice deliberately creates no private timer threads.
- A source-visible, compiler-lowered ReplyCapability façade that resolves to
  the runtime-only authority without becoming actor DATA.
- Wiring supervisor CONTROL termination/restart receipts to `failTarget` for
  the exact generation.
- Source-level GenServer callback dispatch and state handling.
- LLVM/native parity and exact-head guest execution.

The Oreslang source protocol slice is tracked independently in PR #1213.

## Focused gate

```sh
mvn -q -Dtest=ActorReliabilityKernelTest,ActorReliabilityKernelConcurrencyTest,GenServerReplyLedgerHardeningTest,GenServerPendingCallsTest,GenServerReliabilityTelemetryTest test
```

The adversarial pending-call suite includes a repeated four-way
reply/timeout/cancel/target-crash race and asserts exactly one winner with no
target/deadline index residue.

Do not interpret a zero-step Actions failure as a runtime test failure.
