# PR #433 semantic reconciliation — selection readiness and reusable plans

Historical PR head: `04ea99f0f9bcc146dd7083dcd066a2f3cd8fd32b`; baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

Retain modern `ChannelRuntime.SelectSet`, `SelectPlan` and `DynamicSelectPlan`, fair-selection cursors, actor cancellation and parser typed-selection infrastructure. Do not replace current files with the historical implementation of `await`, timeout/cancellation-token arms or match result ADTs.

The historical runtime suite additionally defines `ChannelRuntime.await(future)`, `timeout(nanos)`, `cancelled(token)`, `SelectOperation.AWAIT/TIMEOUT/CANCELLED`, none of which are currently present together in the target main runtime. **Do not port those tests verbatim as compile-breaking assertions**. The `SelectReadinessSyntaxTest` likewise requires new frontend support and remains deferred.

Ported the original `reusableSelectPlanPreservesFairnessAcrossGenerations` property into an independent current-runtime JUnit oracle, asserting exact retained values and alternation across ready channels. The original implementation and tests remain in the historical first-parent ancestry for incremental proper lowering of heterogeneous readiness.

**Release blocker:** fully implement and verify Future/timeout/cancelled readiness, typed payload Option and per-generation cancellation before claiming the complete feature. Run the exact-head `SelectPlanForwardPortTest` and broader channel/select suite; this commit only resolves historical Git conflicts, not feature parity.
