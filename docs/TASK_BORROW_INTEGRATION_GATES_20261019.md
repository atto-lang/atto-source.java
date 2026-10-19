# Task borrow safety — integrated compiler/runtime gate (2026-10-19)

**Status: DRAFT, source execution DISABLED, no real JUnit/GraalVM test pass.** This is an exact Git-tree integration of previously separate review stacks, not a new production concurrency permission.

## Branch composition and ancestry

- **Task frontend + runtime first parent:** Java PR #1411 at `f589c6a42c20fd0606f66aa94bc28f4ed6666111`. This preserves AST/grammar/parser, `OwnershipChecker`, `TypeChecker`, real host TaskScope/cancellation implementation and existing regression suites.
- **Send/Sync parent:** Java PR #1439 at `e33540578acc1dc665b21f9d977ddfbabbaff153`. Exact SHA of its additive files, ECS planner, thread-safety checker, test suite and workflow were added to the integration tree. Nine uniquely matching `TypeChecker` patch hunks from this updated branch were applied to the original Task/suspends checker (not a wholesale replacement).
- **Loan/certified-layout parent:** Java PR #1453 at `a3ef9da2445090015e50d2e2f6469c230a423ca7`, itself based on PR #1448 and earlier #1439. The *latest* PR #1448 loan-verifier/test blobs at `ff86a401c9f2f5b57cd885776cbf07260a1115d0` were selected, preserving newer hardening not present in the older #1453 ancestry.
- No private Git tree was mirrored to another organization. The connector created a multi-parent Git commit inside the original source repository.

## New integrated OwnershipChecker behavior

At `checkTaskBody`, before `checkLambda` mutates parent ownership state, compute the free-capture set, normalize every captured `VarState` to its `ownershipRoot` (including refinement/alias roots), and use `TaskCaptureRootAudit` to reject duplicate root capture when either reference is affine/move-only or writable.

The gate compares **object identity**, never names or value equality. Copyable read-only captures of the same root remain valid. Capture count is bounded (4,096). Nine focused JUnit tests cover duplicate affine/exclusive capture, read-only compatibility, value-equal but identity-distinct allocations and untrusted input limits.

This does **not** yet convert arbitrary user-authored Task expressions into verified runtime layout claims. It does not issue `TaskAccessVerifier.Ticket` for source Task bodies, track interprocedural/FFI read/write effects, establish TaskScope lifetime bounds, or authorize zero-copy shared memory. The existing source Task execution gate stays closed.

## Required validation, in order

1. **Build exact integration head:** compile with JDK 21 + pinned repo Maven toolchain, run all selected `concurrency-contract` tests, then full Java/Graal JVM and native/suspends suites. Do not infer success from static source checks or zero-step failed Actions jobs.
2. **Source-proof correctness:** extend ownership-region root IDs through all binding forms, reborrow and field projections; generate per-Task READ/WRITE effects from resolved AST/call graphs; reject unknown alias/foreign/FFI effects. Make actual proven allocation provenance and physical backend layout mandatory before granting array/field partitions.
3. **TaskScope join semantics:** scheduler-corroborated physical quiescence must be observed after child body, suspension resumption, defer/finally, FFI/device pins and code-generation lease retire. Cancellation intent and future result resolution alone must never release loans.
4. **Real executable parallel proof:** positive program with two physically disjoint tasks, negative source programs for overlapping writers, transitive alias overlap, source escape, cross actor/tenant isolation, ABA/generation reuse, hot reload and cancellation racing with parent access. Validate with stress/randomized tests.
5. **LLVM parity:** implement the same auto-traits, owned root identities, continuation semantics, compiler diagnostics, runtime fences and race tests before enabling the language feature on either backend.

## CI limitations and privacy

The existing Java source organization's inspected Actions failures ran **zero job steps and had no assigned runner**. This is not Java compiler evidence and does not prove a budget cause. Do not bypass private-source restrictions by transferring code to a different org without documented security authorization. An approved local/organization runner with the correct permissions is needed for exact-head tests.

## Release gate

Keep integration PR in DRAFT, its dependencies unmerged, and source Task zero-copy parallel borrowing disabled until an executed test record on the exact integrated commit proves both compiler diagnostics and runtime quiescence.
