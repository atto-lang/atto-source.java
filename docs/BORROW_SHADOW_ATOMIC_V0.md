# Java same-pass borrow observer — fail-closed publication V0

Stacked on Java borrow shadow PR #2740. This patch does not replace `OwnershipChecker` or introduce an additional AST pass.

The opt-in `checkWithBorrowShadowV0` collects up to **65,536** diagnostic events while the **existing** semantic ownership pass checks the entire program. Only after every declaration validates does it deliver them to the caller-provided sink. A later invalid function no longer leaks earlier tentative events; overflow raises an error and publishes none. A sink exception still aborts before `Ast.CheckedTaskCaptures.publish`, although previously published external sink side effects are not rolled back. The ordinary `check()` path has no shadow buffer allocation.

Events are partial, name-based and not canonical typed owner/loan CFG facts. This is a regression-hardening step, not an authenticated Rust MIR bridge or GraalVM AOT/guest proof. Verify exact source head on private runner with `BorrowSamePassShadowV0Test,GuardedMatchOwnershipFlowTest`, then independently prove source-to-typed-MIR identity and backend guest outcomes before parity claims.
