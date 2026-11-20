# Functional pipelines: bounded compiled Java/GraalVM guest v1

**Status: canonical JVM guests actually executed locally on OpenJDK 21; GraalVM 25/Native Image and exact-head GitHub Actions remain unverified.** This is a restricted compiler entry point for scalar Atto functions, not the full actor/JVM/Graal frontend.

## Scope

The Java compiler at `src/compiler/pipeline/AttoFunctionalPipelineCompiler.java` accepts signed int64 functions, name-first `: int` and `-> int` returns, explicit `return` braces, single-expression `: int -> expression;` bodies, and up to four source-order immutable `val` bindings. It accepts normal direct named function calls, forward `|>`, and right-associative reverse `<|`, using the same checked call AST. Mixed direction requires explicit parentheses.

The generated `AttoFunctionalPipelineGuest.java` source is the actual guest implementation. The Java compiler does **not** evaluate guest expressions; javac compiles the resulting functions to JVM bytecode and GraalVM Native Image compiles them to a native executable. All guest arithmetic uses `Math.addExact`, `Math.subtractExact` and `Math.multiplyExact`. Unsupported division, booleans, mutable locals, dynamic callees, actors, async, I/O, imports, and ownership constructs are rejected. Source/type/call-graph checks precede output, and source/output paths have a bounded, no-symlink, no-overwrite admission policy.

## Observed local OpenJDK 21 execution

The compiler source Git blob `61f31581970076f88af694063e68e3e748c8d4b6` was reconstructed and hash-verified locally. `javac -Xlint:all -Werror` compiled it successfully on **OpenJDK 21.0.12.1**. The actual compiler processed the **four unchanged canonical .atto source blobs** from `atto-spec#121` and generated Java guest classes, which `javac` compiled and `java` executed as separate processes. Observed **process exit codes: 83, 83, 42, 42**, with empty stdout and stderr.

Compiled `AttoFunctionalPipelineGuest.class` SHA-256 hashes (in canonical order):

- 83 block / 83 arrow: `e1c2a6c53c1608d3d0a7115036f67381e8786a4ff16b0b08fcbf10123f5212ae` for each.
- 42 nested / 42 grouped: `e7069353619fc3f65c243696a4a021db2b2b615278e048f0f603d3952a79c431` for each.

The **four canonical negatives** and the compiler's **11-case extended negative matrix** were rejected with expected typed/syntax diagnostics, exit 2, and no guest output files. Signed i64 minimum source blob `52b2880b835af88f2a0376b9ae154adc23b24e34` compiled, and a separate Java observer invoked its **compiled** `f_main()`, observing `-9223372036854775808`. The negative underflow fixture blob `5c52861ff94b1771970cb4d57cb8be00a4443ef5` was rejected `E_LITERAL`/exit 2 with no generated class source.

This is **real local JVM guest execution**, not production Truffle/actor support, not the JDK25/GraalVM test, and not an admitted GitHub Actions result. All such CI/Native Image gates remain unverified.

## Real acceptance gates

Run `bash ci/compiler/verify-functional-pipelines.sh` under GraalVM JDK 25 with `ATTO_EXPECTED_HEAD_SHA` set to the exact checked-out commit.

The script verifies tracked source Git blobs and a clean tree, compiles the compiler with `javac -Xlint:all -Werror`, compiles all four independent `.atto` fixtures into Java guest binaries, executes JVM guests expecting **83/83/42/42**, and executes both exact **83** programs as actual GraalVM Native Image binaries with Epsilon GC. It checks deterministic Java generation, empty guest stdout and stderr, eleven negative diagnostics, and zero output artifacts for rejected sources. A fifth JVM guest executes signed `i64::MIN` via a value-observer that calls the compiled `f_main()` method; the opposite out-of-range negative magnitude is rejected without an output artifact.

Only **nonzero executed CI steps**, actual guest exit codes and source/tree/blob SHAs establish backend evidence. A failed zero-step GitHub Action, source inspection, an interpreter, or a host reference model does not count as parity. The independent production Java frontend and general TenantVM actor behavior remain separate gates.

Related: atto-spec #121, atto-source.rs #381, atto-source.llvm #54, atto-source.net #16 and atto-platform-conformance #105.
