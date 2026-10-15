# Postfix `?`: bounded Java scalar prototype

Status: **draft, unvalidated**; stacked on
[Java composition PR #1019](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1019).
Canonical broader contract:
[QUESTION_PROPAGATION_AND_REACTIVE_ERRORS_V1](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/elm-functional-contracts-20261015/docs/QUESTION_PROPAGATION_AND_REACTIVE_ERRORS_V1.md).

## V0 implementation

`value?` on `Option<T>` unpacks `Some` or early returns `None` from
the *nearest synchronous* Option-returning callable. `value?` on
`Result<T,E>` unpacks `Ok` or early returns exactly `Err(e)` from the
nearest synchronous Result-returning callable of the **same E**.

This is `return` control, not `throw`. `try/catch` does not catch
`Err`; ordinary `finally` and lexical `defer` still run.
`unwrap()` still panics. `trap` catches ordinary throws only and
is currently disallowed around a `?` operand in this prototype.
`raise`/panic/cancellation are not converted to value-level errors.

The lexer already tokenizes `?` for ternary. V0 only parses it as
*adjacent postfix* where the following token terminates an expression
or continues a postfix chain. `condition ? a : b` remains ternary.
Use parentheses around adjacent `?` before infix operators:
`(parse()?) + 1`.

## Explicit exclusions (not full v1)

- Only scalar/nested scalar sum payloads are admitted. No affine
  payloads, borrowed values, owned channel handles, or borrowed
  actor state in the sum boundary until move/drop laws are proven.
- No async, `suspends`, actor callable, detached select arm or generator.
  Full source-task integration for legacy implicit suspension must be
  audited before promoting this prototype. The current checker does
  not implement complete transitive suspension/effect verification.
- No implicit `throw`, `raise`, `panic`, `await`, `map_err`, or
  typed exception conversion. Use explicit `map_err<E>()` before `?`.
- General type inference, real tagged enums, Stream/Observable error
  frames, and LLVM lowering remain separate milestones.

## Review blocker

The static checker now additionally rejects `?` from an enclosing callable
when its body can reach a potentially suspending SourceTask path, including
ordinary source functions whose called functions contain `await`, blocking
channels, or selects. Callbacks/lambdas are not yet eligible for `?`.
This detection is conservative and must be checked against runtime scheduling
before ready/merge; unresolved imported dispatch remains a blocker.

The runtime uses its existing `ReturnSignal` for early sum return,
so static source-level admission **must prove** the surrounding
callable uses the non-suspending execution path. Awaitable source
task paths cannot be assumed to handle a direct ReturnSignal.
Test implicit suspension through indirect calls and nested lambdas.
Keep draft until this proof, cleanup and ownership tests, JDK 25 Maven
verification and exact-tree CI are green. No green test claim is made.

```sh
mvn -B -ntp -Dtest=QuestionPropagationLanguageTest,OptionResultCompositionLanguageTest,OptionResultSemanticsTest test
mvn -B -ntp verify
```
