# Postfix `?` propagation — Java compiler admission RFC

**Status (2026-10-15): an initial executable Java synchronous `Option`/`Result` local-carrier slice has been implemented in draft #1017 but remains UNVERIFIED by an executed CI job. Typed-throw, methods, lambdas, suspension, actors and backend parity remain unadmitted.**
Canonical public Oreslang names use snake_case. Shared normative contract:
[common-source PR #36](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/36).
Related typed throwable/raise/panic RFC:
[common-source PR #35](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/35).

## Deterministic routing

- `Result<T,E>?` in a `Result<U,E>` callable unwraps Ok or immediately
  **returns** Err (never throws, and never passes through `catch`).
- `Option<T>?` in an `Option<U>` callable unwraps Some or immediately
  returns None. No synthesized throwable.
- `Result<T,E>?` in a non-Result callable *explicitly declared*
  `throws E` unwraps Ok or emits a genuine typed ordinary `throw E`,
  eligible for local `try/catch`. This route is **not admitted** until
  the `throws` effect AST, sound call graph, Error bound, and guest unwind
  runtime are verified. Ordinary Result return routing takes precedence if
  both conditions apply; an incompatible returned Result is a type error.
- `raise`, `panic`, cancellation and host fatal are NEVER caught or
  rewritten by `?`. In actors, panic still escalates as typed terminal
  CONTROL to supervisor. `raise` bypasses catch/trap, and only a direct
  same-invocation deferred recover can consume it.
- `?` runs operand exactly once; later chain nodes and callbacks do not
  execute after an Err/None. `finally` and LIFO `defer` run exactly once
  on early-return and throw paths, with existing failure-precedence/suppressed
  cleanup contract. No duplicate moves, borrow escape or dropped results.
- `(await future)?` may be admitted later when the Future yields
  `Result`/`Option` and frame/unwind ownership is proven. Applying
  `?` directly to Future, Stream, Observable, or Channel is invalid.

## Current implementation proof boundary

`PostfixQuestionRoutes` is a closed reference route policy object; the first guest-language slice additionally uses `Ast.PropagateExpr` parsed at completed-expression boundaries, checked against the enclosing synchronous fnc's exact return type, consumed once by `OwnershipChecker` from a named local/parameter, and lowered in the evaluator through a `ReturnSignal` that preserves `finally`/`defer`. `PostfixQuestionExecutionTest` adds guest-language regression cases for success, early Err/None, try/finally, LIFO cleanup and moves. Those tests are **committed but not verified passing**. The policy helper is not yet directly integrated into type checking for typed `throws E` routes. Direct chained calls such as `parse_port(text)?` are intentionally rejected by the ownership checker: bind their Result to a named local first.

## Integration checklist before exposing the token as valid source

1. Parser precedence: ensure suffix `?` coexists with compact ternary,
   `as?` cast, call chains and `(await ...)?`, with no lexical guessing.
2. Typed HIR with an explicit `FUNCTION_RETURN_ERR`, `FUNCTION_RETURN_NONE`
   or `THROW_TYPED` control edge, never conflated with match-expression
   `ARM_RESULT` or arbitrary `break`/select arm return.
3. Type-check exact nominal Error identity, `Error` derivation for the
   throw route, return context of innermost callable/lambda, imported
   effect metadata and unresolved generics.
4. Path-sensitive affine ownership: move one payload exactly once; drop only
   the live tagged variant, run all prior cleanup; reject borrowed error
   payloads that escape owners. Do not combine error propagation with
   a call-tail optimization that bypasses cleanup.
5. Runtime: a real typed throw is catchable, but Err return is not.
   Existing `trap` handles ordinary throws only; raise/panic propagate.
   Cross-await continuation and actor supervisor CONTROL must be tested,
   not inferred from synchronous host exceptions.
6. Exact guest-source positive/negative conformance tests (including
   chained `?`, nested try/defer/finally, callback/lambda boundaries,
   panic/raise/cancel, plus unsupported-family negatives), Java JVM/Native
   Image and native LLVM comparison, and formatter idempotence.

Until these are proven, keep all unsupported postfix `?` forms fail-closed; do not mark this draft PR ready before exact-branch-head guest execution, runtime cleanup tests, and ownership negatives pass.
