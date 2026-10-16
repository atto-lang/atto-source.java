# Numeric precision contract (draft, Java backend only)

Last audited: 2026-10-15. This document distinguishes implemented behavior from future language/backend contracts. Do **not** treat it as evidence of LLVM parity.

## Domains

- `int`: currently a signed 64-bit runtime value. Addition, subtraction and multiplication still use Java wrapping arithmetic. This requires a separate checked-vs-wrapping language decision and Java/LLVM parity tests.
- `float`, `f64` (and currently `f32`): Java's binary64 `double`. Unsuffixed fractional/exponent literals are binary64. Overflowed literals fail parsing instead of silently becoming infinity; ordinary runtime arithmetic may produce NaN/infinity.
- `decimal`: exact base-ten `java.math.BigDecimal` in Java guest execution. Literal syntax: `0.1d`, `123d`, `1.234e20d`. Source mantissa is parsed directly, never via `double`. Decimal literals are capped at 16,384 source characters, with absolute scale capped at 100,000.
- `complex`: currently binary floating-point components; exact decimal cannot implicitly convert to complex.

Exact decimal and binary floating-point operands cannot be implicitly combined in binary expressions. Integer-to-decimal widening is exact; callable parameters and declared decimal returns are canonicalized to BigDecimal when an integer enters them.

## Arithmetic and comparison

- Decimal `+`, `-`, `*`, `%` use BigDecimal exact arithmetic (no silent rounding).
- Decimal `/` uses deterministic `MathContext.DECIMAL128`: **34 significant digits, HALF_EVEN rounding**. For explicit higher precision use `std/decimal.divide(left,right,precision,rounding)` with 1..4096 significant digits; `round` and `sqrt` take the same context. These are Java VM intrinsics wrapped in source-first Oreslang, **not yet cross-backend parity**.
- Decimal operation results are capped at 1,000,000 significant digits and absolute scale 200,000 for resource safety. More refined pre-allocation admission and actor-local budgets are still required.
- Numeric equality compares the exact numeric values of finite numbers and ignores BigDecimal representational scale; +0 == -0; NaN is unequal to everything; infinities compare equal only to the same signed infinity.
- Ordering compares exact BigDecimal and binary float numerical values without silently rounding large integers to binary64. Any relational comparison with NaN is false; signed zeros compare equal; infinity orders above/below finite values.
- Binary64 arithmetic remains host `double`, including runtime NaN/infinity on zero division.
- `f32` is **not yet** a distinct IEEE binary32 value domain. Native floating-point and decimal lowering are **not yet implemented** in `oreslang-source.llvm`.

## Native-first standard library

- `std/decimal`: exact decimal sum, integer nonnegative power, Horner polynomial evaluation; explicit per-call `divide`, `round`, and `sqrt` via closed `DecimalOps` VM kernel. Precision is 1..4096 significant digits and rounding is one of UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN, UNNECESSARY; there is no ambient mutable rounding context.
- `std/matrix`: row-major `Array<decimal>` dot products, element-wise addition, transpose, and matrix multiply, exact trace and identity construction, and Frobenius norm with a caller-supplied decimal sqrt precision context.
- Matrix shape functions return `Result<..., String>` rather than silently truncating incompatible inputs. Current reference implementation bounds each matrix dimension to 128 (dot element budget 16,384) so products/offsets fit signed 64-bit.
- Matrix accumulation is exact under decimal addition/multiplication, not a compensated binary64 approximation.
- These are Oreslang source modules, whitelisted by `CoreLibrarySources`, not Java interop.
- This is a **reference matrix library**, not LAPACK, BLAS, GPU acceleration or arbitrary-scale sparse algebra. LU/QR/Cholesky/SVD, eigensolvers, condition-number estimates and high-performance kernels require additional work. Frobenius norm is supported with explicitly rounded square root.

## Validation requirements before merge

1. Run `mvn -B -ntp -Dtest=HighPrecisionDecimalTest,DivisionTypeSoundnessTest,NativeCoreLibraryTest test` on GraalVM 25/JDK 25.
2. Run source-first stdlib boundary audit, full Maven `verify`, native-image JIT/AOT and actor-isolation tests, and LLVM parity/fail-closed tests.
3. Add differential property tests against independent arbitrary-precision reference arithmetic for 16, 34, 50, 100, 256 and 1,000 digits; include ties-to-even, division-by-zero, underflow/overflow, signed zero, infinity and NaN.
4. Validate decimal serialization and network/messaging wire schema (coefficient, sign, scale, canonical binary encoding, bounds), actor copy/share/ownership, and ARM64/x86 parity before admitting decimals as native cross-actor payloads.
5. Add resource-bounded adversarial tests for extreme scales, large operands, exponents, 128×128 matrices and cancellation.
6. Only claim formal guarantees once numeric semantics are modeled in an executable specification (e.g. SMT bit-vector models for binary floats and refinement properties for decimal/matrix kernels), with checked artifacts and exact Git tree SHA.

## Known blockers

CI may be unavailable if GitHub Actions minutes/billing are exhausted. Failure before the first build step is **not** a compiler-test failure and must not be called a pass. Do not mirror this private repository across organizations unless the destination is explicitly approved by immutable GitHub repository ID, the exact Git tree is proven identical, and the source-privacy policy permits it.
