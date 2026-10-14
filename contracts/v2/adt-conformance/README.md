# ADT differential corpus v2

This directory is the **canonical shared input** for Java/GraalVM and
LLVM ADT conformance. `cases.toml` lists concrete Oreslang source fixtures,
the expected Java source diagnostic or guest stdout, and explicit per-backend
admission gates. Do not treat design-pending `contracts/v1/design` cases as
implemented compiler behavior.

## Current status

- `java = "required"`: Java is required to parse/typecheck every fixture.
  `kind = "run"` also requires *real GraalVM guest execution*, not only an AST.
- `llvm = "pending"`: source-level enum, nested ADT and ownership parity
  have not yet been verified in native code. This label must only become
  `required` after native constructor/tag/payload lowering, negative
  diagnostics, and owned drop/ABI mechanics have all been implemented.
- A `reject` entry must fail at compile/typecheck time and contain its
  declared diagnostic fragment; success at runtime is a failure of the test.
- `expected_stdout` comparison is exact after CRLF normalization. Changes
  to stdout must update both the fixture and manifest in the same review.

## Java/Graal verification

From `oreslang-source.java` at the *tested commit*:

```sh
mvn -B -ntp -Dores.runtime.carriers=native -Dtest=AdtConformanceCorpusTest test
mvn -B -ntp -Dores.runtime.carriers=native -Dtest=AlgebraicDataTypeMatchTest,ExclusiveAdtMatchSafetyTest,AdtFiniteMatchOracleTest,ExplicitDispatchStatementSyntaxTest,EnumScalarPayloadRangeHardeningTest,EnumOwnershipMatchTest,NestedEnumLocalValueTest test
mvn -B -ntp -Dores.runtime.carriers=native verify
```

The manifest now contains 21 cases, including bare and explicit-do ordered and exclusive suspendable
matches, terminal INT32_MAX enum tags, large integer guard boundaries, and unary unsigned underflow.

The JUnit test reads the TOML manifest and fixtures directly; file paths
are constrained to simple `.ores` filenames and duplicate case IDs fail.
Keep all fixtures committed. Never silently skip a source fixture or mark
a native backend `required` based only on source parsing.

## Java exact-head automated release gate

The dedicated `.github/workflows/adt-exact-head.yml` workflow is scoped to
ADT compiler/runtime changes. It records the exact Git commit and source tree,
runs all eight targeted JUnit classes (including the 4096-arm suspendable-match
trampoline regressions), uploads Surefire reports, then runs full `mvn verify`
on GraalVM 25. Its full suite job depends on targeted success.

GitHub Actions showing `failure` with **zero executed steps** is an
infrastructure/admission failure, not a failing compiler assertion. Check
the run's job steps and test reports before claiming a result. Rerunning a
failed job is not itself evidence of executable verification.

## LLVM parity plan

LLVM issue [#316](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/316)
tracks the native compiler implementation. Once lowering exists, the LLVM
harness should read this **same versioned manifest**, compile each fixture
with the native compiler, execute `run` fixtures, compare normalized output,
and reject `reject` fixtures before code generation. Vendor a SHA-pinned copy
only when necessary for a hermetic build; verify a manifest+fixture digest or
Git tree identity to prevent drift. Some local `u8` enum payloads are not
portable actor messages, and these restrictions must remain consistent.

## Release gate

A passing source-tree digest is only provenance. Actual compilation, positive
guest execution, and all negative cases must pass on the *same exact commit*.
If the GitHub Actions job fails at startup before running steps, record the
runner failure as **infrastructure blocked**, not as a successful or failed
compiler test. Do not merge the ADT feature solely on manifest existence.
