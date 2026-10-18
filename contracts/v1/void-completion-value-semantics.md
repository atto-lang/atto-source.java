# Void completion, optional presence, and asynchronous selection (v1)

**Status:** normative type/value boundary; Java/GraalVM partial implementation.
Cross-backend behavioral parity and value-producing static-select lowering are NOT certified.

## The distinct contracts

| Form | Meaning | Value can be stored? |
| --- | --- | --- |
| `void` | successful completion with **no** user-language value | **No** |
| `Future<void>` | observable pending/success/failure/cancellation of a valueless operation | **Yes** |
| `Option<void>` | `Some` presence tag (no payload), versus `None`: absence or a recoverable trap result | **Yes** |
| `Result<void,E>` | `Ok()` versus `Err(E)` | **Yes** |
| `Future<Option<T>>` | asynchronous operation returning an optional result | **Yes** |

`void` is **not** an implicit `None`, a magic `undefined`, `null`, a
zero-sized user variable, or a missing-result sentinel for nonblocking
operations. Internal host/JVM `null` or zero-payload tagged representations
must not escape as guest `undefined`. User code must not bind an expression
of type `void` to a value slot, even when the declared type is `void`.

`Option<void>` is retained intentionally: existing `trap fnc ...: void`
code returns a `Some` presence tag upon success and `None` for an ordinary recoverable
failure. Changing it to `void` or an automatic `None` would destroy a
meaningful success/failure distinction. The same holds for
`Result<void,E>`; its `Ok()` carries no payload.
The tag name `Some` here describes its runtime variant; this contract does
not introduce an unverified zero-argument source `Some()` constructor.

`Unit` remains a **separate, unimplemented first-class type proposal**.
If it is introduced, it must have a concrete sole inhabitant and
well-defined lowering. No implicit `void`-to-`Unit`, `Option<Unit>`, or
`undefined` conversion is admitted by this contract. This avoids promising
zero-tuple literals, names, or ABI symbols that no production backend defines.

## Canonical source examples

```ores
fnc process(int message): void { stdio.stdout.write(message); return; }
async fnc complete(): void { return; }

pub routine main(): void {
  const Future<void> pending = complete();
  await pending;                  // valid statement: observes terminal state

  // Both are invalid: void is a completion, not a storable value.
  // const void result = await pending;
  // const result = await pending;
  return;
}
```

```ores
trap fnc admitted(): void { return; }
fnc success(): Result<void, String> { return Ok(); }

fnc check(): void {
  const Option<void> admission = admitted(); // Some presence tag on success
  const Result<void, String> outcome = success();
  const bool present = admission.is_some();
  const bool succeeded = outcome.is_ok();
  return;
}
```

## Static select expressions: intended source contract, not a shipped claim

```ores
const Future<void> b = nb select first {
  when readch inbox: int message -> { process(message); }
  when await pending: int result -> { process(result); }
};
await b;
```

`nb select first { ... }` must **register exactly one selection and return a
tracked Future<void>** for void arms. It never returns an ordinary `void` or
`Option<void>`. The future settles only after the selected arm completes,
or fails/cancels with a distinct terminal state. The unselected arms retire
atomically and release their registrations. Unobserved failures cannot
silently disappear as detached callbacks.

```ores
await nb select first {
  when readch inbox: int message -> { process(message); }
  when await pending: int result -> { process(result); }
};
```

This immediately-awaited form must have the same observable result,
ordering, failure, cancellation and actor-domain safety as awaiting the
named Future. The compiler MAY elide a materialized Future if all
observational and ownership guarantees remain intact. Bare `void` cannot
be assigned; `const void b = await nb select ...` must be rejected.

`do select first { ... }` remains procedural, and `await do select ...`
is invalid; do not rewrite it into a future operation. Blocking
`do select` arms may return from their enclosing callable, whereas arms
of a nonblocking expression cannot return from an already-continued caller.

### Current implementation reality

Java main has a statement `Ast.SelectStmt`, including legacy detached
`nb select`, and a separate runtime-case-collection
`Ast.DynamicSelectExpr`. The proposed `Future<T>`-producing **static**
select expression does not currently have end-to-end AST/typechecker/
continuation/runtime lowering. Fail closed rather than miscompile the
expression as a detached statement or accidentally consume a channel.
This document does not certify a `Future<void>`-producing static select.
See `contracts/v1/static-select-result-and-return.md` for the select
result/return and atomic-admission contract.

## Conformance requirements

1. Bare void in named locals, inferred locals, parameters, class and module
   fields and destructured value slots is a compile-time type error.
2. Awaiting `Future<void>` is valid as a statement but never creates an
   inhabitant of `void` or a user-visible `undefined`.
3. `Future<void>`, `Option<void>`, `Result<void,E>` are distinct,
   never implicitly interconverted; `Some()`, `None`, `Ok()`,
   `Err(E)` remain distinguishable.
4. A pending future is not `None`, a cancelled future is not successful
   void, and failed `nb select` is not successful `Some()`.
5. `await nb select first` and `nb select first` + later `await`
   have observationally equivalent cancellation/error/cleanup behavior.
6. No actor turn is executed directly by host reactor/notification threads.
   Retire registrations and captured resources exactly once on winning,
   cancellation, actor STOP, error, and shutdown.
7. LLVM/.NET/WASM/Forge and formatter must not claim support for a syntax
   until their own parser, typing, runtime and source-level tests pass.
8. A host-only unit test or an Actions run with zero executed steps is not
   evidence of compiled guest-program conformance.
