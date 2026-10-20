# Experimental AST static-safety warnings (Java/Graal)

Status: prototype and opt-in. Not a security scanner, proof of bounded resources, taint solver, static-facts v0 exporter, or exhaustive analysis. It does not change normal build admission.

Call OresCompiler.analyzeStaticSafety(source) to type/ownership check Oreslang source and then receive immutable diagnostic records. Parser-only analysis is for fixture coverage and cannot be used for security admission.

## Current rule IDs

- ORES-TERM-002: unconditional source loops with no apparent exit; potential rather than proven nontermination.
- ORES-AMP-001: possible recursive actor creation via spawn ActorType(...) and a statically recognized receive/on_message handler.
- ORES-AMP-004: callable SCCs under uniquely resolved same-module direct calls.
- ORES-AMP-005: actor creation inside source loops without demonstrated total bound.
- ORES-SEC-010: candidate host execution/FFI call requires independent authority and data-flow validation; not a proven vulnerability.
- ORES-ANALYSIS-001: unresolved target, nominal overload, or handler. Never claim completeness.

## Limitations

AST nodes currently lack adequate source locations and proof metadata for production diagnostics. Direct call resolution omits many method/dynamic calls; task, mailbox, supervisor and arbitrary callback propagation are not covered. Spawn is not equivalent to delivery to receive: the actor edges represent a potential event path, not synchronous execution. No ranking function, actor admission resource bound, taint flow, sanitizer proof, runtime capability certificate, or full LLVM parity is implemented.

Warnings are deterministically sorted and traverse AST constructs without source text heuristics or reflection. The node traversal has a finite budget, but deeply nested source is not yet fully stack-safe. SCC witness lists are component members, not verified directed-cycle paths.

Full source semantics must be checked by the Java/Graal compiler, and real native host effects still require trusted runtime/OS enforcement. A parser-only fixture is not guest compilation/execution evidence.

Unit tests reside at src/test/java/dev/oreslang/compiler/StaticSafetyAnalyzerTest.java. Run in the real project Maven JUnit suite, record exact commit and executed CI steps. Zero-step GitHub Actions failures are not successful tests.

Shared reference contract: https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/106
