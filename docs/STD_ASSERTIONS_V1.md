# Atto standard assertion modules (Java-owned source slice)

Two independent .atto modules are staged; no shared cross-backend code is required.

- stdlib/std/assert.atto: canonical std.assert design from atto-spec PR #114. Methods true, false, equal, not_equal and fail must throw a typed guest AssertionError extends Error; checks are never optimized away.
- stdlib/std/test_assert.atto: nonthrowing, test-runner-friendly assertions returning the existing ores.std/testing TestOutcome. A test can report a failure without stopping the suite. Includes overflow-safe inclusive integer-range checks.

The first equality operand is expected and the second actual. Static Eq compatibility, one left-to-right evaluation of each argument, no truthiness conversion, no mixed-type numeric coercion, and safe bounded diagnostics are requirements.

Legacy stdlib/assert.atto (Assert methods returning bool/rt fail) and stdlib/testing.ores (existing actual-first helpers) remain unchanged for compatibility. Do not alias the legacy API to std.assert.

## Honest execution boundary

Closed source registry additions and Maven resource packaging are present. This is not proof that pkg "std.assert" resolves; the Java compiler still needs static package binding, permitted keyword member exports (true/false), generic Eq type checking, checked Error effect lowering, and typed catch/reporting in atto test. The assertion source and authored guest fixtures are NOT_EXECUTED, not guest passes.

Run a SOURCE-ONLY lexical guard: node --test tests/assertions_source_contract.test.mjs. Passing it verifies expected text, not compiler execution. Real guest pass and failure tests under interpreter/AOT/optimization and actor-isolation tests remain mandatory.

Related work to reconcile: atto-spec#114, atto-source.rs#401, atto-source.java#1365 and #1394 (native test harness), atto-common-source#90 (eight-module ledger), atto-source.llvm#305/#317/#605 (independent assertion and testing candidates).

Do not export private source to any CI carrier absent independent explicit authorization, source export guard, target owner/reader/retention approval, and per-blob identity verification.
