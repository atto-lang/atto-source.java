# Functional pipelines: bounded compiled Java/GraalVM guest v1

**Status: implementation and execution tests authored; exact-head guest execution not yet verified.** This is a restricted compiler entry point for scalar Atto functions, not the full actor/JVM/Graal frontend.

## Scope

The Java compiler at `src/compiler/pipeline/AttoFunctionalPipelineCompiler.java` accepts signed int64 functions, name-first `: int` and `-> int` returns, explicit `return` braces, single-expression `: int -> expression;` bodies, and up to four source-order immutable `val` bindings. It accepts normal direct named function calls, forward `|>`, and right-associative reverse `<|`, using the same checked call AST. Mixed direction requires explicit parentheses.

The generated `AttoFunctionalPipelineGuest.java` source is the actual guest implementation. The Java compiler does **not** evaluate guest expressions; javac compiles the resulting functions to JVM bytecode and GraalVM Native Image compiles them to a native executable. All guest arithmetic uses `Math.addExact`, `Math.subtractExact` and `Math.multiplyExact`. Unsupported division, booleans, mutable locals, dynamic callees, actors, async, I/O, imports, and ownership constructs are rejected. Source/type/call-graph checks precede output, and source/output paths have a bounded, no-symlink, no-overwrite admission policy.

## Real acceptance gates

Run `bash ci/compiler/verify-functional-pipelines.sh` under GraalVM JDK 25 with `ATTO_EXPECTED_HEAD_SHA` set to the exact checked-out commit.

The script verifies tracked source Git blobs and a clean tree, compiles the compiler with `javac -Xlint:all -Werror`, compiles all four independent `.atto` fixtures into Java guest binaries, executes JVM guests expecting **83/83/42/42**, and executes both exact **83** programs as actual GraalVM Native Image binaries with Epsilon GC. It checks deterministic Java generation, empty guest stdout and stderr, ten negative diagnostics, and zero output artifacts for rejected sources.

Only **nonzero executed CI steps**, actual guest exit codes and source/tree/blob SHAs establish backend evidence. A failed zero-step GitHub Action, source inspection, an interpreter, or a host reference model does not count as parity. The independent production Java frontend and general TenantVM actor behavior remain separate gates.

Related: atto-spec #121, atto-source.rs #381, atto-source.llvm #54, atto-source.net #16 and atto-platform-conformance #105.
