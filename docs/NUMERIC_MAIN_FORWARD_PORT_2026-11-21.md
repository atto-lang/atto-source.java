# Numerical P0 — minimal forward-port on current Atto Java main

**Status: DRAFT / NOT GUEST EXECUTED / NOT FULL-AOT VERIFIED.**

This integration branch starts from the current **Atto Java main**, not from the stale full numeric PR [#1329](https://github.com/atto-lang/atto-source.java/pull/1329). #1329 was based on an older compiler and is currently non-mergeable. Its large evaluator/type-checker edits would overwrite newer main implementation, especially the already implemented `Result`-based `trap` and suspension handling. This branch deliberately forwards only the *checked i64* semantics and guest numerical error identity. It is an **overlap-reconciliation PR, not a second bignum/decimal implementation**.

## Changes scoped to this forward-port

- TypeChecker: `int / int` has `int/i64` type, not implicit `f64`.
- Evaluator: checked signed `+`, `-`, `*`, unary minus; integer `/` truncates toward zero, `%` has the dividend sign; `MIN/-1` is overflow, while `MIN%-1 = 0`.
- Errors: `ArithmeticOverflowError` (ABI **65537**) and `DivisionByZeroError` (ABI **65538**) are distinct ordinary guest errors. `is type Error` and typed numeric error tests can recognize them; `trap` uses **main's existing** `trapOk`/`trapErr` implementation, never a replaced Option-era implementation.
- Constant rewriting: negative signed minimum cannot silently wrap, and exact integral comparison is not coerced through binary64.
- Reuses the `NumericErrorAbi` and registry blobs from #1329 rather than inventing new codes.
- Stages JUnit/Graal guest tests for quotient/remainder signs and boundaries, nominal Error catches, `Result` trap, normal arithmetic, IEEE float division and ABI identities.

## Existing work to reconcile — no blind cherry-pick

- [#1329](https://github.com/atto-lang/atto-source.java/pull/1329): retain its pure-source bounded BigIntValue/DecimalValue reference algorithms and broader numeric API work for a **separate, reviewed rebase**. Current-main's more recent trap/type machinery must win.
- [#748](https://github.com/atto-lang/atto-source.java/pull/748): decimal literal, exact arithmetic and matrix work; **reject default DECIMAL128 division** unless explicitly selected by caller.
- [#742](https://github.com/atto-lang/atto-source.java/pull/742): IEEE/math reference work, which must not silently become claimed guest primitive support.
- [atto-spec #209](https://github.com/atto-lang/atto-spec/pull/209): target typed arithmetic error and full guest AOT contract.
- [conformance #563](https://github.com/atto-lang/atto-platform-conformance/pull/563): 51 mathematical goldens plus 7 Git-blob-pinned `.atto` guest candidates; all backend results remain `not_executed`.

## Known verification/security gaps

1. GitHub Actions source organization is runner-admission blocked; any job with `steps=[]` is **NOT_EXECUTED**. No Maven/JUnit, guest interpreter, native image, AOT or actor CONTROL test has passed for this branch.
2. The source repository is private, and the live `.github/private-ci-approved-repositories.txt` has no active approved alternate-org carrier. [Issue #65](https://github.com/atto-lang/atto-source.java/issues/65) documents the export denial. **Do not mirror or add tokens/secrets** without independently approved exact source/destination and effective source control.
3. Existing nonnumerical `trap` failure classification and actor supervisor escalation must be audited against chapter 07 before broader semantic parity claims. This forward-port does not replace main's asynchronous control machinery.
4. Source-level tests and reference goldens do not satisfy guest AOT or parity. Require a stepful JDK 25 Maven/JUnit run on exact head/tree and real full guest Native Image execution (no runtime compilation), exact binary hash and actual negative cases.

**Merge hold:** pending stepful exact-current-head CI, source security gate, all Ready-cycle governance, stale overlapping PR reconciliation and real compiled-guest evidence. No automatic merge or release.

## IEEE ordered comparison hardening (2026-11-21)

The current-main evaluator previously applied Java `Double.compare` to mixed or binary-float ordering, making NaN spuriously ordered, treating `-0.0` as less than `+0.0`, and potentially converting 64-bit integers beyond 2^53 to inaccurate doubles.

- New `dev.oreslang.types.NumericValueOrdering` computes exact order for already-admitted `Byte/Short/Integer/Long`, `Float/Double`, `BigInteger` and `BigDecimal` carriers; either-NaN comparison is unordered (represented by `null`), and **all** guest `< <= > >=` predicates return false for unordered operands. Signed zeros have equal ordering.
- Nonfinite binary values are distinguished from finite big integers; mixed finite comparisons use exact decimal reconstruction of the binary float's represented value, rather than conversion of the integer to binary64 or `BigDecimal.valueOf(double)`'s shortest display string. Unknown `Number` carriers fail closed.
- Candidate host JUnit: `NumericValueOrderingTest`. Candidate actual guest JUnit: `NumericGuestOrderingV1Test`, including NaN, -0, infinity and the 2^53 boundary. The conformance repo's numeric guest manifest pins three companion positive `.atto` fixtures; this is source identity, not runtime evidence.
- Independently authored standalone Java 21 version of the ordering helper compiled and passed **15 host-only assertions**, including fail-closed NaN/infinity cases for unknown host Number carriers in the local environment. This is **not** a build/test of the committed Java/Graal compiler, Maven suite, exact source blob or native image.
- Older Java [#742](https://github.com/atto-lang/atto-source.java/pull/742) includes overlapping IEEE behavior but carries older evaluator/type-checker changes. Reconcile behavior by independent review; never merge it wholesale over the current-main Result/trap implementation.

**Numerical precision remains deliberately incomplete:** the helper does not establish guest `bigint`, `decimal`, `f32` or sized/unsigned primitive runtime types. Unsupported primitive admission and full-AOT backend evidence are separate unresolved gates.
