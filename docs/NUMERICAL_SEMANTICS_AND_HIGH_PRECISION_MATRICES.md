# Oreslang numerical semantics and high-precision matrix roadmap (draft v0)

Status: **design contract; not an implementation or a claim of parity** (2026-10-15).

## Audit snapshot on `main`

- Java evaluates real arithmetic using Java `double`; `int/int` division produces a floating result.
- The Java parser accepts floating literals using `Double.parseDouble`; Java `decimal` and `bigint` have recognized type names, **not yet proven arbitrary-precision runtime behavior**. Numeric expression evaluation and return shaping need a dedicated representation contract.
- Native LLVM source parsing rejects `/` rather than producing an incorrectly typed signed-i64 result; native floating point needs typed AST, LLVM lowering, and runtime parity.
- Binary64 is **not** a valid hidden implementation of exact decimal, big integers, or arbitrary-precision matrix arithmetic.

## Numeric domains (proposed standard-library public contract)

| API type | Representation | Expected behavior |
| --- | --- | --- |
| `f32` | IEEE-754 binary32 | round to 24-bit significand, including conversion and assignment |
| `f64`, `float` | IEEE-754 binary64 | 53-bit significand; unordered NaN in ordinary relational comparisons |
| `BigInt` | arbitrary-precision signed integer | no silent truncation or overflow; explicit size/operation quotas |
| `Decimal` | arbitrary-precision coefficient and base-10 exponent | exact add/sub/mul subject to resource limits; divide needs explicit finite precision/rounding or exact-division proof |
| `BigFloat` | arbitrary-precision binary floating point | caller selects binary precision and rounding; do not back with f64 |
| `BigRational` | normalized arbitrary-precision fraction | exact finite arithmetic; zero denominator rejected |
| `Complex<T>` | parameterized numeric pair | no premature conversion to binary64 |
| `Matrix<T>` | shape-checked dense matrix of element type T | explicit element-domain policy and storage ownership |

Names are proposals, subject to source-language grammar and generic type admission. Existing lowercase `decimal` and `bigint` should not be silently remapped in a patch: audit existing usages and migrate under an explicit compatibility plan.

### Rounding and conversion

- Immutable `DecimalContext(precision_digits, rounding_mode, exponent_bounds)` and `BigFloatContext(precision_bits, rounding_mode, exponent_bounds)`. Precision must be positive; contexts are explicit call arguments or lexical `with` scopes resolved into call arguments, **never process-global mutable state**.
- Specify `half_even`, `half_up`, `toward_zero`, `toward_positive`, and `toward_negative`; add `unnecessary` for checked exact conversions.
- Parse decimal **from text**, never via implicit `double`. Binary-float to decimal must distinguish exact binary-value conversion from shortest-roundtrippable decimal rendering.
- `Decimal` and `BigInt` prohibit implicit narrowing to f64/f32, i64, or each other where information can be lost. Conversions report inexact/overflow/underflow separately from capacity exhaustion.
- Floating relational predicates follow IEEE unordered NaN semantics; expose explicit `total_order` for sorting and `is_nan`, `is_infinite`, `is_finite`, `signbit`, `nextafter`, `ulp` in `std.math`.
- Preserve signed zero, normal/subnormal boundaries, overflow/underflow, exceptional division, and NaN behavior. FMA contraction and `fast_math` optimizations require explicit opt-in; ensure reproducibility tests across Java and LLVM.

### Matrix API (dense first)

```oreslang
// Proposed API notation, not yet executable Oreslang.
val ctx = DecimalContext(precision_digits: 80, rounding: #half_even);
val a: Matrix<Decimal> = Matrix.from_rows(..., ctx);
val b: Matrix<Decimal> = Matrix.from_rows(..., ctx);
val c: Matrix<Decimal> = Matrix.zeros(rows: a.rows, cols: b.cols, ctx);
Matrix.mul_into(rt borrow mut c, rt borrow a, rt borrow b, ctx);
val answer = Matrix.solve(rt borrow a, rt borrow rhs, ctx);
```

- `Matrix<T>` owns a typed row-major buffer and shape (checked `rows * cols`, stride math, offsets, indexing); `MatrixView<T>` is a non-owning borrowed `offset/strides/shape` view. Track borrower lifetime, parent arena epoch, and actor heap owner; disallow view use after `rt take`, `rt unref`, actor transfer, or arena retirement.
- `rt borrow` allows immutable strided views; `rt borrow mut` requires an exclusive loan for write, and an output overlapping an input requires a documented alias-safe algorithm or rejection. `rt take` transfers unique backing storage; `rt copy` deep-copies; `rt share` requires immutable, actor-safe storage/capabilities.
- `add_into`, `transpose_view`, `mul_into`, `dot_into`, and `solve_into` use caller-owned output/workspace. They can avoid **container** allocations, but arbitrary-precision limb growth may still allocate unless explicit preallocated limb arenas and scratch bounds are used. No zero-allocation promise without an allocation-counting test.
- Include dimension, precision, singularity, finite-value, capacity, and allocation-budget errors via Oreslang's typed throw/raise contracts.
- Matmul: deterministic scalar reference implementation first with checked dimensions and explicit rounding schedule. Then cache-blocking, SIMD, parallel ActorGroup execution with defined reduction order, and optional GPU kernels. CPU/GPU affinity must not silently change mathematical semantics.
- Decompositions: LU with pivoting and `solve`; Householder QR for least squares; Cholesky for positive-definite matrices. Include condition estimates and residuals; do not use naïve matrix inversion as the default solve algorithm.
- For decimal matmul, specify whether accumulator rounds on every fused step or uses an extended-precision exact accumulator rounded once; backends must agree on the chosen policy. Expose a separate fast/nonreproducible mode where appropriate.

## Admission / tests

1. IEEE-754 cross-backend golden vectors: bitwise `f32/f64` results, `NaN`, infinities, signed zero, subnormals, ulps, ordered and total comparisons, parse/format round trips, and fma optimization boundaries.
2. `BigInt`/decimal property tests against Python `decimal` and Java `BigInteger`/`BigDecimal` using independently generated expected values; fuzz signed magnitudes, scales, contexts, inexact divide, over-limit input, and hostile exponents.
3. Matrix conformance fixtures: dimension mismatch, zero dimensions, rectangular products, aliasing, transposed/sliced views, actor ownership, exact integer and decimal reference matrices, Hilbert/Vandermonde ill-conditioning, rank-deficient systems, singularity, zero pivots, and near-degenerate QR.
4. Run Java and LLVM against the **same** versioned vectors. Require exact equality for BigInt and contracted decimal; define acceptable ULP and residual envelopes for floating and decomposition algorithms. Record determinism and allocation counters.
5. CI gates: no runtime use of binary64 for exact numeric types; no unchecked `rows * cols` allocation; no leaking writable views across actor boundaries; no unexpected implicit f64 conversion.

## Reference matrix kernel now staged in Java

`src/main/java/dev/oreslang/math/DecimalMatrix.java` is a **host-internal**, row-major
`BigDecimal` reference kernel, not the proposed Oreslang guest `Matrix<Decimal>`.
It parses source decimal strings directly, accepts explicit `MathContext`, uses
exact products and exact inner-loop accumulation followed by one final per-cell
rounding, checks dimensions/indices, imposes dimension/cell/work/precision/scale
budgets, and rejects aliased output. `multiplyInto` stages outputs before
publishing them so budget errors cannot leave a partially updated matrix.
Its numerical tests reside in `src/test/java/dev/oreslang/DecimalMatrixReferenceTest.java`.

The kernel **allocates temporary BigDecimals and a destination staging array**,
is not guest-importable through the Oreslang standard library, does not yet
implement borrowed views or actor-local arenas, and has no LLVM implementation.
It is an executable Java-side reference for later guest and cross-backend work,
not proof that arbitrary-precision matrix support is complete.

### Exact scalar runtime boundary

`src/main/java/dev/oreslang/math/ExactScalarMath.java` now handles Java
`BigInteger`/`BigDecimal` carriers without accidental conversion to binary64.
The evaluator dispatches these carriers through this helper for arithmetic and
unary negation; implicit mixing with binary float or complex is rejected.
BigInteger division requires an explicitly selected result domain, while
nonterminating BigDecimal division requires an explicit positive-precision
`MathContext` through `ExactScalarMath.divide`. Scalar precision and scale
budgets apply. Host-level regressions live in `ExactScalarMathTest.java`.

**Limitations:** Guest-source `bigint`/`decimal`/ `f32` type checking and
construction still need parity work. Recognizing a type name is not evidence
that guest arithmetic uses the exact host implementation. Thus the helper is
an exact *runtime carrier boundary*, not a claim of completed source-level
arbitrary precision.

### Resource and CI admission updates

`DecimalMatrix` now bounds total significant digits per matrix in addition
to individual per-cell quotas, checks output shape/work before allocating
results, and checks staged output aggregate size before publication.
JUnit regressions include aggregate input/output exhaustion and preallocation
shape-check precedence.

A source-free capacity probe succeeded with three actually executed steps in
private `cliptown/cliptown-e2e`, but [security admission issue #962]
(https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
explicitly **does not authorize** private Oreslang source transfer. Neither
private source nor Git object bytes were transferred to that carrier.
This is runner availability evidence, not Java/LLVM Oreslang test verification.

## Known unresolved numerical risks (2026-10-15)

- The staged Java complex-division algorithm avoids denominator squares that overflow or underflow, and power-of-two scaling preserves common extreme/zero cases. **It is not a correctly-rounded complex division algorithm.** An independent deterministic 1,500-case, wide-exponent comparison against a 100-decimal-digit oracle identified 18 cases with unacceptable relative errors for small, representable result components. Most involve extreme exponent separation and loss of a small component. This is a *development finding*, not a conformance result. A more robust complex division implementation or a dedicated high-precision fallback is required.
- Running the isolated Java 21 microharness and Python rational-oracle arithmetic checks does **not** exercise the full Oreslang parser/runtime or either production Actions workflow.
- The reference vector datasets in `contracts/v1/numeric-precision/cases.json` are accompanied by `tests/test_numeric_precision_vectors.py`, which independently checks fixture consistency. They are not yet wired to compiled Java/LLVM guest execution; CI must not claim end-to-end parity from these data-only checks.
- Cross-org GitHub Actions test carriers are subject to the fail-closed `docs/PRIVATE_CI_MIRRORS.md` admission policy; a successful run in a public repo does not authorize transfer of the private source.

## Implementation order

- Stage 0: fix Java IEEE comparisons/literal admission and add regression tests (in this PR). Java complex division has a first power-of-two-scaling hardening pass; precision certification remains outstanding.
- Stage 1: native `f32/f64` AST/type checking, LLVM IR arithmetic, strict compare semantics, conversion/printing, and cross-backend vectors.
- Stage 2: owned `BigInt` limbs and exact/rounded `Decimal` with explicit contexts and arithmetic budgets in core/std-lib; JVM and LLVM parity.
- Stage 3: `Matrix<T>` + `MatrixView<T>` with borrow/ownership enforcement, dense arithmetic and deterministic matmul; then LU, QR, and condition/residual reporting.
- Stage 4: `BigFloat` and `BigRational`; optimized CPU kernels and optional GPU accelerators behind conformance checks.

Do not claim production-ready high-precision arithmetic/matrices until tests cover both compiled backends and the APIs are reachable from guest Oreslang source.
