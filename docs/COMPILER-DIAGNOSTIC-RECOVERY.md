# Compiler diagnostics recovery — Java/Graal draft

**Status: parser-only recovery implemented in draft; exact-head JUnit/GraalVM CI not executed.**

## Current opt-in frontend behavior

`oreslang --check file.ores` defaults to at most **5 independent syntax errors** from safely separated braced declarations. `--max-errors=1`, `=10`, and `=0` are supported; `0` removes the user-chosen count ceiling but retains a **1,000-primary-error hard cap**. Invalid/duplicate counts and using the flag without `--check` are usage errors.

The real parser reports a token-owned, 1-based location. The collector normalizes and bounds error text, orders by canonical source unit/location/code, deduplicates by compiler root identity **within a source unit**, and never claims a precise unseen-error count. The same checked source loader enforces existing read limits, import rules, source-unit identity, isolate checks, and no guest execution.

## Recovery safety rules

- The compiler recovers only to an independently closed **brace-delimited declaration**. Synchronization uses lexer tokens, not raw text, so braces in strings or comments do not change scope.
- Unbalanced braces, ambiguous braceless declaration boundaries, parsing outside a recoverable declaration, or the error/resource limit stop recovery and report that additional errors may exist.
- Partial recovered ASTs are **never** admitted into import linking, type/ownership/effect checking, Java compilation or guest execution.
- Semantic/type/ownership checking can now continue to **different independently compilable source units**, collecting at most one actual unpositioned semantic root failure per unit. Within-unit type/effect/ownership recovery remains fail-fast, and unresolved import/link errors may abort early. The unpositioned unit fallback is explicitly marked as such, never represented as a verified expression span. No algorithm splits a diagnostic string to fabricate independent errors.
- Existing non-`--check` compilation and runtime paths retain the previous fail-fast parser.
- The source-unit/diagnostic budget is bounded: max 1,000 errors, 8 related locations per error, 2,048 message characters per record, and bounded scanner resynchronization. The existing source loader's file/depth limits are still enforced.

## What this PR does not complete

This is **not** general Oreslang diagnostic parity or complete six-error semantic recovery. Grammar-aware resynchronization beyond isolated braced declarations, explicit poison AST nodes, within-unit type/effect/borrow sibling recovery, exact related source traces, LLVM parity, native-image check parity, and `build`-mode flag integration remain pending. Codes are provisional until the common contract #151's registry is approved.

## Acceptance checklist

- [ ] Run Maven/JUnit on exact source commit with actual nonempty job steps.
- [ ] Run real source `--check` for six same-file braced syntax errors (expected 5/6 for caps 5/10).
- [ ] Verify malformed braces and braceless functions halt conservatively.
- [ ] Confirm imported modules, quoted brace literals, deterministic order, invalid flags, true semantic errors, no side effects/cache corruption.
- [ ] Run full GraalVM/JIT/Native Image verification and confirm no guest execution.
- [ ] Complete independent frontend recovery for type/borrow/effects; then LLVM matching fixtures.
- [ ] Keep this PR draft until a qualified runner executes the tests. Source-org Actions currently have runner_id=0 and no steps.
- [ ] Cross-organization private-source mirroring remains unauthorized until a destination numeric ID and exact source snapshot are independently approved.
