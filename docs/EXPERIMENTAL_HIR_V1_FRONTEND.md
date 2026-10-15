# Experimental source → common HIR v1 (Java)

**Non-production research path.** `ExperimentalHirV1Exporter` uses the
real Java Oreslang parser **and** `OresCompiler.parseAndTypeCheck` (type and
ownership admission), then exports the tiny shared
`ores-hir/1.0.0-experimental` envelope. The normal Truffle/Graal compiler
and runtime remain unchanged.

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
  return count * 43;
}
pub fnc main(): int { return counter_response(1); }
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
