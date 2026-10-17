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
