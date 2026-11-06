# AttoLang ownership Wave 1 — integration audit (2026-11-06)

**DRAFT — NOT EXECUTED. This is a compiler-source reconciliation, not proof of a sound borrow checker.**

## Base and provenance

- Source: `ores-truffle-oreslang/oreslang-source.java`, `main` at `1a7d55e21fd647f1ba56f3877ebe6d16a0b4326b`.
- Donor PRs: [#2197](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2197) for explicit/implicit projected moves and independent Share proof; [#2098](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2098) for transitive loan escape; [#1380](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1380) for call-wide receiver loans, reborrows and primitive-copy correctness.
- All three PRs remain independent drafts. This integration does **not** merge or modify their branches or the source `main`.

## Compiler reconciliation

- Applied 44 exact, unique source hunks from the three OwnershipChecker PR patches on the pinned base.
- Reconciled eight overlapping/shifted hunks without overwriting the current `main` actor-output handling, linked-layout registration, inferred field/index typing or conservative nominal Copy classifier.
- Enforced fail-closed projected move-only field and indexed consumption, including record/struct projections and indirect `rt take` source places.
- Required an independent Share proof, not just Copy, for `rt share`; rejected nested loans in `rt take` and `rt share`, plus owned-carrier escape paths.
- Preserved and extended receiver/projection loan tracking; imported class layouts are validated before they become available.
- Reconciled the one safe exception to transitive borrow-copy rejection: `rt copy` of an already-live borrow of a proven primitive scalar returns a detached owned scalar, not a borrowed wrapper. This exception requires no shadowed nominal/alias and keeps the borrow owner live; `rt copy` of a borrowed class/aggregate remains denied.
- Updated `TypeChecker` to recognize the same detached primitive copy result. The current `rt borrow` reborrow normalization in `main` was retained.

## Guest tests

Added/preserved exactly the donor regression sources:
- 41 authored JUnit cases: `PointerlessFncOwnershipTest` (#2197, retaining all nine base methods).
- 27 authored JUnit cases: `StaticBorrowEscapeBoundaryTest` (#2098).
- 17 authored JUnit cases: `ProjectedMethodReceiverLoanTest` (#1380).
- 4 authored JUnit cases: `PrimitiveBorrowCopyTest` (#1380).
- 4 authored JUnit cases: `SharedBorrowContractTest` (#1380) plus its shared TSV oracle.

Added a sixth JUnit test class, `AttoOwnershipWave1GuestTest` (2 `@Test` methods), with eight physical `.atto` sources under `src/test/resources/atto/ownership-wave1/`: four guest return-code positives (42), and four checker-semantic negatives that must parse before denial. The `copy-only-share` negative deliberately calls the Java ownership checker directly because the current nominal frontend has separate type-resolution constraints; it is not evidence of full linked compiler admission.

**95 authored JUnit test methods and eight `.atto` fixtures are NOT executed.** Their presence is not a pass. The new same-repo exact-head workflow must execute JDK25/GraalVM Maven targeted tests, then full project `verify`. A job with zero runner assignment / zero steps is **NOT_EXECUTED**.

## Independent review blockers

1. Prove compiler passes on exact PR commit/tree with real JUnit and guest execution. Reconcile any broken tests; do not modify expected diagnostics merely to mask a regression.
2. Verify all eight manual reconciliation points against both original PR changes, including type-checker copy lowering and transitive imported class checking.
3. Check actor self/output envelope, `critical-read`/`critical-write`, `await`, implicit scheduler cooperation, generators, delegates, imported classes, and unlinked dynamic receivers.
4. Confirm the merged code does not regress parent stages for [#1841](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1841) (canonical syntax), [#2487](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2487) (guarded mutable sharing), or [#2065](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2065) (actor message ownership).
5. Run [platform conformance #507](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/507) real `.atto` source tests with semantic diagnostics. Its diagnostic identifiers are candidate values, not yet implemented backend ABI.
6. Keep `main` unchanged until review + exact-head JUnit/Maven/Graal AOT and LLVM/Wasm/Forge/.NET parity work. No private source cross-organization mirror is authorized under current source security policy.

## Beyond Wave 1

Next typed MIR proof artifacts: `PlaceId`, `MovePath`, `LoanId`, `RegionId`, effect/suspension points, source-to-place provenance, and a verified drop schedule. A reference model alone is non-promoting: require real emitted code, Native Image and LLVM zero-GC proof, backend-specific actor/tenant transfer policy and exact-tree provenance.
