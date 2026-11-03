# PR #405 semantic reconciliation — static select

Historical head: `b0ca90611f22b006c18ab5a114c6a97513ad7139`. Current-main baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

## Resolution choices

- Preserve current main's parser/type/ownership lowering for `do select { ... }`, `do nb select { ... }`, and its explicit rejection of `cb select` and `do select from cases`. Do **not** restore the historical parser/AST implementations.
- Preserve the current dynamic selection return contract and the `nb cb writech` callback path. Keep the newer source-flow treatment of arm-local returns, nested lambda returns, and defer/finally.
- All eight historical PR test paths already exist in current main. The upstream tests have since evolved; preserve those current tests rather than replacing them with old snapshots.
- The historical branch's source and test changes are retained in the first-parent Git history. This merge resolution deliberately uses the modern main tree rather than a wholesale historical file overwrite.

## Verification / release gate

Git conflict resolution is **not** execution certification. Current source Actions may fail before steps. Require exact-head Maven/JUnit tests for static and dynamic select, ownership, return/finally, diagnostics and actor cancellation before merging. Review this reconciliation before treating historical #405 as independently releasable.
