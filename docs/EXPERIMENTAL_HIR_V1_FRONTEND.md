# Experimental source → common HIR v1 (Java)

**Non-production research path.** `ExperimentalHirV1Exporter` uses the
real Java Oreslang parser **and** `OresCompiler.parseAndTypeCheck` (type and
ownership admission), then exports the tiny shared
`ores-hir/1.0.0-experimental` envelope. Unmarked Truffle/Graal source preserves existing runtime arithmetic; a strict
source-unit checked-i64 opt-in is available for host-only experiments.

The admitted surface is top-level, nongeneric synchronous `fnc` declarations,
typed `int` or `bool` parameters/returns, one direct return expression,
integer/boolean literals, locals referenced by name, `+`, `-`, `*`,
`<`, `==`, and direct calls with positional arguments. Unsupported
source, effects, actor modes, imports, modules, mutable statements, arbitrary
host types, or generic instantiations **fail closed**.

The mapping of Java-backend `int` to *experimental* `i64` is **not**
a certified production language-ABI decision. The IR reference interpreter
traps signed overflow; actual backend arithmetic behavior still requires
independent execution tests.

Example source:

```oreslang
pub fnc counter_response(int count): int {
  return count;
}
pub fnc main(): int { return counter_response(43); }
```

Run after building Java classes on a compatible JDK:

```sh
java -cp target/classes dev.oreslang.compiler.ExperimentalHirV1Exporter example.ores > java-hir.json
```

The class is also callable via `fromSource(String)`; it refuses raw
unverified AST injection through a public export method. The source and JSON
output are individually bounded to 1 MiB. It does **not** imply that the
common-source verifier has run; the external parity harness must validate
the document before using it.

JUnit tests: `src/test/java/dev/oreslang/ExperimentalHirV1ExporterTest.java`.
Hosted exact-head tests are needed before merge; a missing runner is not success.

### Checked-i64 source-unit execution (new opt-in)

The experimental **host-only** checked integer mode is selected by the
exact first source line (LF terminator):

```oreslang
// oreslang:checked-i64-v1
pub fnc scale(int count): int { return count * 43; }
pub fnc main(): int { return scale(1); }
```

Marked source executes signed-64-bit addition, subtraction, multiplication
and unary negation with overflow detection. Java/GraalVM uses
`Math.*Exact`; LLVM uses `llvm.sadd/ssub/smul.with.overflow.i64`, with
checked negation lowered as zero-minus-operand. LLVM's experimental host
failure path prints `ORES_I64_OVERFLOW` and exits with status 86; the
GraalVM guest raises a failure carrying the same diagnostic marker. This
is **not yet a unified guest `Error`/supervisor ABI**.

Unmarked source units preserve ordinary runtime arithmetic, and their HIR
exporters continue to reject expressions such as `count * 43` unless a
conservative whole-domain interval proof establishes overflow freedom.
Marked source can emit the same expression as `checked-i64.v1` only because
its runtime now actually detects overflow. This opt-in does not enable
actor-wide, embedded, Wasm or GraalVM native-image AOT checked arithmetic.

The shared [experimental checked-i64 execution contract](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/experimental-hir-mir-v1-20261014/docs/EXPERIMENTAL_CHECKED_I64_V1.md)
defines the precise source directive, tested boundaries and remaining
production error-policy limitations. Real compiler tests include safe
arithmetic, runtime overflow, legacy wrapping, identical frontend HIR and
reference MIR checks.
