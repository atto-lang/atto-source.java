# PR #576 semantic forward-port — cross-file RouteContract interop

Original head: `c6fa85b42e13d6eeb0ca1c288dea034c67fc668a`. Current-main baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

The historical branch replaced large portions of `OresLanguage`, `IncrementalCompiler`, `OresCompiler`, `LinkedProgramRunner` and `TypeChecker`. Those changes cannot be copied over modern linked-type admission and diagnostics without undoing newer compiler/security guarantees.

- Preserve all current-main compiler/import/type-checker and linked-guest execution paths unchanged.
- Restore the unique `SharedRouteContractInteropTest` as targeted release coverage of the pinned source Git blob, contract/interface ABI distinction, cross-file accepted module validation and execution, and missing/wrong public-method rejections.
- Remove **two historical tests** of `OresCompiler.linkVerifiedGuestParse/takeVerifiedGuestProgram` lease APIs; those APIs no longer exist under the current main contract. Do not restore the obsolete APIs merely to make the tests compile. The replacement linked metadata path must receive an independent security-focused regression in a future slice.
- Treat the source copy's Git SHA as a pinned *candidate*, not a production wire-authority decision; verify independently against its original repository before release.

**Blockers:** execute the exact-head `SharedRouteContractInteropTest` with Maven/Graal in the source org or an authorized private runner, verify the public contract SHA and Java/LLVM parity. No merge/SDK certification without that evidence.
