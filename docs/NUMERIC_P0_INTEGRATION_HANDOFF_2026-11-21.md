# AttoLang numerical P0 implementation handoff

Status: IMPLEMENTED ON JAVA PR BRANCH, NOT GUEST-EXECUTION-VERIFIED, NOT MERGE READY (2026-11-21).

## Canonical branch and semantic scope

Continue [Atto Java PR #1329](https://github.com/atto-lang/atto-source.java/pull/1329); do not start a duplicate numeric runtime. The authoritative proposed source contract is [atto-spec #209](https://github.com/atto-lang/atto-spec/pull/209), with [conformance #563](https://github.com/atto-lang/atto-platform-conformance/pull/563). It stages 51 independent mathematical goldens and four pinned `.atto` guest candidates, not a proven compiler pass.

New Java int/i64 division stage:
- int/int has int type, signed division truncates toward zero (e.g. -7/3 == -2).
- % has the dividend's sign; MIN % -1 == 0.
- MIN / -1 is guest ArithmeticOverflowError, code 65537; never JVM MIN wrap.
- int / 0 and int % 0 are guest DivisionByZeroError, code 65538; never host ArithmeticException or IEEE floating fallback.
- float/f64 operands still use binary64 division; no implicit int/int promotion to floating point.
- TypeChecker and evaluator agree. Tests exercise named function arguments rather than only folded constants.

These are *branch claims* until the exact Java source head passes JUnit/guest execution and full guest AOT checks.

## Existing overlapping PR disposition — do not merge independently

| Existing PR | Reuse | Blocker / disposition |
| --- | --- | --- |
| [#1329](https://github.com/atto-lang/atto-source.java/pull/1329) | Primary checked-i64 evaluator, fail-closed primitive admission, pure source `BigIntValue` / `DecimalValue` references, numeric error ABI | Preserve as the integration target, reconcile the conflicts below, test on current source tree |
| [#748](https://github.com/atto-lang/atto-source.java/pull/748) | Decimal-text literal parser, bounded decimal operations, tested matrix algorithms, overflow/resource preflight | Its default DECIMAL128/HALF_EVEN division violates v1 exact-until-context semantics; port only semantics-preserving parts with new guest tests |
| [#742](https://github.com/atto-lang/atto-source.java/pull/742) | IEEE NaN/zero comparisons, source-precision property cases, BigInt/Decimal host kernels, DecimalMatrix as oracle | Host Number/BigDecimal and matrix references are *not* guest-level `bigint`/`decimal` implementations; avoid overlapping TypeChecker/Evaluator replacement |

### Strictly remaining semantic blockers

1. **Overflow effect conflict:** chapter 17 prescribes an actor panic while #1329 currently throws a catchable nominal ArithmeticOverflowError; both cannot claim parity. Before integration, owner must settle ordinary catch/trap vs uncatchable supervisor panic, define non-actor root exit and pin effect tests. New DivisionByZeroError has the same ordinary-error routing on Java and is not yet a final language-wide decision.
2. Distinct `bigint`, `decimal`, sized-integer and `f32` runtime representations remain unsupported; do not alias them to i64 or float. Distinguish bounded *reference class* arithmetic from a first-class primitive.
3. The source branch has been migrated from legacy Oreslang; tests currently use the language's supported legacy spelling. The canonical `.atto` fixture must be compiled separately with a real AttoLang compiler; file acceptance is insufficient.
4. Every compiler must reject unsupported features before artifact emission. Java implementation does not certify LLVM, Rust/Wasm, Cranelift, Forge, .NET NativeAOT, JavaScript or HDL parity.

## Verification and private CI

- Independent local **host-only** Java 21 reference verified 100,121 deterministic boundary and seeded random signed quotient/remainder comparisons against `BigInteger.divide()/remainder()`. This validates the *mathematical operation*, **not this source tree or Graal guest code**.
- A local Node 22 source-only check rehashed 4/4 conformance guest candidate Git blobs, and a reconstructed integrity test ran 2/2. No `.atto` program was executed.
- The source repository's latest `ci` and `fast-ci` jobs for earlier exact head had **zero steps**, so they are NOT_EXECUTED, not failing math assertions.
- This repository is **private**. Its `.github/private-ci-approved-repositories.txt` contains no active approved destination and issue [#65](https://github.com/atto-lang/atto-source.java/issues/65) records a deny-by-default export gate. No alternate-org private source transfer may occur until the security owner independently approves the exact source/destination identities and effective protected source-side control. Runner capacity does not supply export permission.
- Required: original source commit/tree/blob proof; exact native JUnit and guest execution; Java/GraalVM native image/full **guest AOT**; negative no-artifact checks; overlapping-PR reconciliation; independent required approval and security gate.

**Merge status:** blocked on real tests, effect decision, private CI runner admission and overlapping/conflicting PR reconciliation. Do not auto-promote merely on a green host-reference test.
