# Java PR #430 — static class namespace contracts: conceptual reconciliation

Historical PR head `baf0a2a3028973cebfbe5384b83b261b89a56029`, current Java main `d16793fd4209504bb8fd85f959d01370ba37fa77`.

Current main has substantial newer parser, type checker, Task capture, actor separation, ABI and incremental compiler changes. It does **not** have the historical dedicated static contract AST/namespace/field ABI implemented as a complete, independently tested feature. Therefore do not replace its newer AST/Parser/TypeChecker or revive old class static-state layout from this historical branch.

Semantic contract preserved in review: `define static contract` is distinct from dedicated instance/module `define contract`, and `implements static X` must never be mistaken for `implements X`. Static fields must be excluded from instance object layout, visible/mutable according to their declared binding kinds, and checked in incremental ABI. The original 12 historical `StaticClassContractTest` cases remain available in first-parent Git ancestry but are **not** forwarded as passing assertions until actual lowering lands.

Adds a focused admission test that static contract syntax currently fails closed rather than silently becoming an instance/module contract, alongside a control test for ordinary `define contract`. This enforces today's behavior; when static-contract implementation is ready, replace the negative test with the original positive/negative conformance matrix as part of the same reviewed feature PR.

Release gates: exact-head Maven/JUnit, semantic static-vs-instance contract checker, static field ABI/layout, runtime storage and cross-backend validation; no status or guest parity claim is made from this Git conflict resolution alone.
