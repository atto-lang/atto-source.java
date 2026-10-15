# Postfix `?`: bounded Java scalar prototype

Status: **draft, unvalidated**; stacked on
[Java composition PR #1019](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1019).
Canonical broader contract:
[QUESTION_PROPAGATION_AND_REACTIVE_ERRORS_V1](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/elm-functional-contracts-20261015/docs/QUESTION_PROPAGATION_AND_REACTIVE_ERRORS_V1.md).

## V0 synchronous plus bounded async SourceTask implementation

`value?` on `Option<T>` unpacks `Some` or early returns `None` from
the *nearest* Option-returning callable admitted by the checker. `value?` on
`Result<T,E>` unpacks `Ok` or early returns exactly `Err(e)` from the
nearest Result-returning callable of the **same E**.

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

The checker rejects `?` inside `finally` and `defer` cleanup
expressions. Cleanup must not introduce an implicit early return that can
replace the pending result or conceal an earlier failure; this restriction
applies equally to synchronous and async callables. Ordinary errors
raised by cleanup still follow the runtime's explicit failure-precedence
rules, not `?` value propagation.

## Explicit exclusions (not full v1)

- Only scalar/nested scalar sum payloads are admitted. No affine
  payloads, borrowed values, owned channel handles, or borrowed
  actor state in the sum boundary until move/drop laws are proven.
- Ordinary standalone `async fnc` v1 is **restricted to the direct initializer** of a
  binding, for example `val int x = (await result_future)?;`. A failed
  sum becomes `SourceFlow.RETURN`, not a thrown exception, and is
  carried through `finally` and lexical `defer`.
  Async postfix `?` inside conditions, call arguments, return expressions,
  nested operators, callbacks and detached continuations is **rejected**
  pending verified continuation-local early-return lowering.
- No async methods, `suspends`-only callables, actor callables,
  detached select arms or generators.
  Full transitive scheduling/effect verification is still a merge gate.
- No implicit `throw`, `raise`, `panic`, `await`, `map_err`, or
  typed exception conversion. Use explicit `map_err<E>()` before `?`.
- General type inference, real tagged enums, Stream/Observable error
  frames, and LLVM lowering remain separate milestones.

## SourceTask block-cleanup ownership

The resumable statement walker now reports a `SourceFlow` to its
enclosing `runBlock`. Only `runBlock` performs lexical defer execution
and mutex-guard release for that block. This avoids double cleanup on
normal completion, direct return, `?` early return, break/continue, and
exceptional completion.

Nested `try/finally` and `defer` tests exercise both `None` and
`Some` after `await`; exact-once resource cleanup and cancellation
must still be verified on hosted JDK 25 runs. No passing test claim is
made for this branch.

## Review blocker

For **synchronous** callables, the checker rejects `?` when the callable
body can reach a SourceTask path, including ordinary source functions
whose callees contain `await`, blocking channels or selects.
For **async** callables, the direct binding-initializer subset above
uses a checked `SourceFlow.RETURN` after the await. Callbacks/lambdas
are not eligible for `?`.
This detection is conservative and must be checked against runtime scheduling
before ready/merge; unresolved imported dispatch remains a blocker.

The runtime's synchronous `ReturnSignal` is converted at the
SourceTask statement boundary into `SourceFlow.RETURN`. The async
unary evaluator also forwards a pending signal to that same boundary.
This is not an ordinary `SourceFlow.THROW`; `try/catch` must not
intercept an `Err` or `None` propagated by `?`.
Verify both suspended and already-settled Future paths, nested cleanup,
and exact single terminal delivery. Imported/dynamic call effects and
callback boundaries remain review blockers.
Keep draft until this proof, cleanup and ownership tests, JDK 25 Maven
verification and exact-tree CI are green. No green test claim is made.

```sh
mvn -B -ntp -Dtest=QuestionPropagationLanguageTest,OptionResultCompositionLanguageTest,OptionResultSemanticsTest test
mvn -B -ntp verify
```
