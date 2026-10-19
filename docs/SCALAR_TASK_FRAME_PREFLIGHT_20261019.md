# Scalar Task execution preflight (stacked on #1475)

**Status: DRAFT — host-scheduled scalar frame ONLY. Guest `nb` remains rejected.**
Parent PRs: #1411 → #1466 → #1475. This branch intentionally does not
relax either rejection in `TypeChecker`; it changes neither parser grammar
nor actor memory rules.

## New executable unit

`OresScalarTaskFrame` is a package-private, single-turn
`OresScheduler.Task<Object>` frame for compiler-selected, pure scalar
operations: return captured scalar, return constant, and checked `int`
addition. It accepts exact Java `Long`, `Double`, `Boolean` and `String`
values only. Scalars are snapshotted before scheduling (strings are copied).
An array of values is owned by the frame, not the parent's mutable variable
environment. It cannot retain a guest closure or evaluate arbitrary guest
functions. It rejects null, mutable aggregates, host objects, native handles,
Task/Future handles, and non-scalar identity types even when a permissive
external trait callback exists. Capture capacity is bounded to 16 values.
Repeated frame entry fails. Checked addition reports overflow.

The real host `OresTaskScope.startCompilerFrame` retains frame ownership until
`getDone()` physical exit; source-level callers cannot access this package-
private entry point. Host JUnit covers scalar copy-on-spawn, awaited result,
integer addition, rejection of nested Task/Future and mutable/native-like
objects, replay, overflow, and release-on-failure.

This is a **backend frame foundation**, not a guest interpreter path.
It is not proof that Oreslang source-level captures resolve to these host
values, nor that the type checker can safely emit a Task. In particular,
`AsyncRuntime.await` is a blocking host boundary; guest `await Task<T>`
must lower to suspension and resume on `OresScheduler`, not park an
actor/Task carrier.

## Trait policy reconciliation

For Task boundary admission, the canonical policy remains:

- Owned `Send` controls move of independently owned worker values;
  `Sync` controls shared read borrows. Neither grants actor message
  transport, lifetime, or authority over an actor's private arena.
- `Channel`, `Task`, `Future`, `ActorRef`, `Arc`, `Weak`, `Shared`,
  `RwLock`, `Mutex` and all guard/region/native capabilities are
  **denied**, regardless of payload or nominal callback, until the
  compiler has a trusted, resolved runtime identity and a documented
  ownership ABI. #1467's independent Channel Send rule must not be
  integrated into Task admission without reconciliation.
- `void` is a return effect, not a moveable runtime value. Source
  `SendSyncTraits` now agrees with resolved `ThreadSafetyTraits`.
- The `SendSyncTraits(program, trustedIntrinsicNames)` overload is still
  a trusted-compiler API, not an authenticated runtime witness. Production
  Task admission must never fill it from source-name matching or user
  callbacks; replace the string set with a compiler-issued intrinsic identity
  witness before enabling builtin collection or wrapper transfer.

## Remaining executable guest vertical slice

1. Under TypeChecker, authenticate trusted actor-free context and recognize
   only `nb { return literal; }` or `nb { return copiedScalar; }` initially.
   Derive a canonical source/resolved scalar proof, require copy semantics and
   reject all effects/calls, loops, borrows, mutable captures and nested Tasks.
   The code emitted must reference immutable captured values and never Env.
2. Compile that verified Task AST into this scalar frame and into an opaque
   context-owned code-generation/scope pin; submit on the context's runtime-
   owned scheduler. Do not allow a host user to pass arbitrary closures.
3. Add a typed Task return and guest `await` continuation state, mapping
   completion to an `OresScheduler.Await` step. Ensure actor carriers never
   block and nested actors/Tasks stay rejected. Add one real positive
   `.ores` full compiler + guest execution test and negative tests for
   native/actor-private/borrowed captures and moved-owned-aggregate reuse.
4. Keep scoped region loans a *different* gate. Require compiler-minted
   allocation/generation/owner/field permissions, enforce runtime pins and
   exact-once release after real Task `getDone()` on cancel/fail/success.
   First executable region test: two disjoint writers on one source allocation,
   blocking parent overlap until both are physically quiescent.
5. Enable anything beyond this subset only after exact-tree JDK21 Maven/JUnit,
   source guest, cancellation stress, GraalVM/native, and LLVM parity checks.

## Verification ledger

- Local environment has JDK 21 but no Maven and no authenticated private
  checkout. Do **not** claim that this branch's JUnit tests ran locally.
- GitHub Actions for #1475 at `ec0a52e` previously failed before steps.
  A rerun of exact-head `fast-ci` and `concurrency-contract` was requested
  on 2026-10-19; the new branch's jobs must be inspected separately.
- Do not mirror private Oreslang code to another organization without
  approval of the documented private-source transfer security policy.
- Remain DRAFT and fail closed; neither successful isolated host execution
  nor static trait proofs are sufficient to merge or enable guest execution.
