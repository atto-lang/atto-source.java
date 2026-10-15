# Oreslang source formatting: Java/GraalVM integration

The canonical formatter lives in the private
[`ores-truffle-oreslang/oreslang-format`](https://github.com/ores-truffle-oreslang/oreslang-format)
repository. Its `oresfmt` binary formats `.ores` source. There is no second
formatter implementation in Java.

## Read-only repository audit

Install or build the **same reviewed formatter revision** outside this repo.
From the Java repository root:

```sh
python3 -m unittest discover -s scripts -p 'test_oresfmt_corpus_audit.py'
python3 scripts/audit-oresfmt-corpus.py --formatter oresfmt examples contracts/v1/fixtures
# Only enable once the corpus has been reviewed and intentionally migrated:
python3 scripts/audit-oresfmt-corpus.py --formatter oresfmt --strict examples
```

The audit runs `oresfmt --stdout <file>` for each non-symlink `.ores` file,
compares bytes without ever writing a source file, and reports changed versus
rejected fixtures. Rejected parser/formatter fixtures can be intentionally
invalid. `--strict` is opt-in and is **not** currently a default CI gate.
A JSON inventory can be written with `--json <report-file>`.

## Initialization-safe unused imports

Java `ImportGraph.resolveDependencies()` includes **all source imports** in
the initialization graph. A named import can be unreferenced but required to
execute a target module's `init()` hook, including transitively and in cycles.
`CircularImportInitializationTest` now has a regression for an unreferenced
named import that still executes its target's init hook.

For this reason the formatter preserves imports by default, even if their
names are lexically unused. After reviewing direct and transitive effects,
mark an import with an exact `// oresfmt: pure-import` directive to permit
commenting when unused. `// oresfmt: keep-import` always takes precedence.
A purity marker is a human assertion, not compiler-proven effect analysis.

## Integration gates before enforcing --check

1. Audit real examples and fixture families; separate intentionally invalid
   fixtures from sources the formatter is expected to handle.
2. Verify `oresfmt` idempotence, source parse/typecheck, import initialization
   order, and before/after guest behavior using the Java runtime.
3. Check Java and LLVM's independently implemented syntax feature matrices.
   LLVM currently has **no Java-style import parser parity**.
4. Pin an exact formatter revision and only then add narrowly scoped CI
   `--check` gates. Do not blindly rewrite existing examples or test fixtures.

The audit script is a formatter-compatibility inventory, **not** proof that
the resulting program compiles or retains semantics.
