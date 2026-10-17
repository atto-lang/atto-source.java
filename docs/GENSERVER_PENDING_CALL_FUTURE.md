# GenServer pending-call Future arbitration

This host-internal slice composes the opaque correlation ledger from PR #1210
with `OresFuture`. It remains runtime plumbing, not a guest-visible GenServer
API.

## One arbitration point

Every terminal path must first win the same `GenServerReplyLedger.Ticket`:

- valid reply -> ticket claim -> Future success;
- deadline -> ticket retirement -> Future TimeoutException;
- consumer cancellation -> cancellation admission retires ticket -> Future cancel;
- runtime/registry close -> atomically takes remaining tickets -> Future failure.

This ordering prevents these invalid states:

- a late reply reviving a timed-out or cancelled request;
- cancellation winning the Future after a reply already owns correlation;
- shutdown failing a call whose reply already won;
- a stale actor incarnation settling a newer call;
- a foreign registry settling another registry's handle.

`GenServerPendingCalls` and its handles are package-private. No ticket,
correlation ID or completion authority is serialized or exposed as actor DATA.

## Still missing before guest call_async

- ActorRef mailbox integration and typed Call envelope admission.
- Caller identity and deterministic self-call refusal.
- Deadline scheduling via an explicit monotonic clock provider.
- Runtime-minted guest-safe ReplyCapability façade with no serialization.
- Supervisor CONTROL failure/restart settlement.
- Source-level GenServer callback dispatch and state handling.
- LLVM/native parity and exact-head guest execution.

The Oreslang source protocol slice is tracked independently in PR #1213.

## Focused gate

```sh
mvn -q -Dtest=GenServerPendingCallsTest test
```

Do not interpret a zero-step Actions failure as a runtime test failure.


## Predictable correlation and failure isolation

The runtime ledger uses a monotonically increasing **process-local opaque
correlation sequence**, not random UUID generation. Correlations are never
serialized or exposed as guest DATA. This keeps the hot path deterministic,
cheap and replay-auditable while actor incarnation remains the authority fence.

The ledger exposes host-only bounded pressure counters: registered calls,
successful reply/retire claims, rejected stale/foreign/duplicate claims, quota
rejections, pending count and next sequence. These are diagnostics, not
capabilities.

Registry/runtime shutdown never forwards the original host `Throwable` into
caller Futures. Pending calls receive a fresh cause-free,
suppression-disabled `GenServerCallClosedException` containing only the
bounded host exception **class name**. Host messages, stack/cause graphs and
suppressed exceptions cannot become cross-actor data through shutdown.

These properties complement the one-shot arbitration rule: determinism and
observability must not weaken isolation or let late replies revive retired
calls.
