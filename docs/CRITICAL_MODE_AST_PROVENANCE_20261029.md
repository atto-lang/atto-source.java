# Bracket-mode compiler token provenance audit

The `critical[read]` / `critical[write]` grammar must create an
**unforgeable, AST-internal selector**, not an ordinary guest `String`.
A source expression `self.handle_state("critical:write", ...)` must
be rejected by the compiler even when called by an actor. String syntax
is not a capability or source-language mode declaration.

The parser constructs `Ast.CriticalStateModeExpr`, carrying the
closed `Ast.StateLoanMode` enum (`READ`, `WRITE`). Type checking accepts
this dedicated AST node only in the intrinsic, rejects malformed/extra
arguments, and grants a read-only record/borrow for `READ`. The evaluator
emits the Java enum to the *internal* runtime state-loan invocation; a
guest-supplied `String` cannot satisfy that mode check. Runtime read
views still reject writes; scalar-only read projections fail closed.

Two negative tests were added for the literal `"critical:read"` and
`"critical:write"` spoof attempts. The suite now registers **12**
critical-mode tests including one real guest execution assertion when
the Graal JUnit suite runs.

**Execution classification:** static AST/source audit only. This document
does not claim Maven, Graal, parser/type-checker or guest execution passed.
A source-organizational GitHub Actions job with `steps=null` is
`NOT_EXECUTED`, not a code test result.

Do not merge until a supported Maven + Graal toolchain runs
`-Dtest=ActorCriticalStateModeTest,ActorStateLoanTest,ActorSingleStateSchemaTest`
and emits actual JUnit results, with source and compiler SHAs recorded.
