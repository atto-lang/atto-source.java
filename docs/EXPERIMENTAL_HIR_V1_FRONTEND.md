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


### Checked i64 admission (audit update)

**Do not equate the experimental checked-i64 IR contract with the production
integer runtime's wraparound behavior.** The actual Java/GraalVM and LLVM
backends currently perform ordinary signed 64-bit addition/subtraction/
multiplication without overflow traps. Both experimental exporters therefore
run a conservative interval proof over each function's *entire* signed-i64
parameter domain. A possible overflow, including `count * 43`, is rejected
before exporting `checked-i64.v1`. Literals and genuinely safe operations
such as `count * 1` and `40 + 3` remain admissible.

The test fixture `fixtures/ir-v1/checked-safe-counter.ores` is intended for
source-to-HIR parity. A passing guest result on this fixture is a **smoke test**
and not evidence that production runtimes trap overflow. Remove this
restriction only after both backends implement verified checked arithmetic,
or introduce a separate explicitly negotiated wrapping-i64 IR contract.
