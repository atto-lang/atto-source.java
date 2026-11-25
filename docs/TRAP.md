# `trap`: Result-valued non-throwing callables

> **Normative contract:** [atto-spec trap-result-v1](https://github.com/atto-lang/atto-spec/blob/main/language/trap-result-v1.md) and [cross-backend reconciliation](https://github.com/atto-lang/atto-spec/blob/main/language/cross-backend-semantics-reconciliation-v1.md). `trap` and `trap!` yield `Result`, not `Option`. Ordinary throws preserve guest error identity; stronger control effects bypass the boundary.
>
> **Implementation status:** synchronous Java Result trapping is implemented in the admitted profile. Async trap callables and trapping an awaited Future remain fail-closed until the complete suspension/cleanup boundary is implemented and verified. The async sections below specify the target, not an enabled compiler feature.

This document defines the Oreslang contract for the `trap` callable modifier
and its interaction with `throw`, `raise`, `panic`, async/Future execution,
select, actors, and runtime cancellation.

The central rule is intentionally small:

> A call to a `trap` callable never exposes an ordinary `throw` to its caller.
> Success is `Ok(value)`; an uncaught ordinary throw is `Err(Error)`.

`raise`, `panic`, runtime cancellation, forced termination, and fatal host
failures are not ordinary throws and are not converted to `Err`.

## Surface syntax

```ores
pub trap fnc load_user(UserId id): User {
  return db_load(id);
}

pub async trap fnc fetch_user(UserId id): User {
  return await remote_load(id);
}
```

Instance methods use the same modifier contract:

```ores
define class Loader as
  pub trap load(UserId id): User {
    return self.lookup(id);
  }
end
```

`trap` is part of the callable type/effect contract and must survive imports,
function values, callbacks, generics, interface/trait slots, virtual dispatch,
tree shaking, JIT/AOT lowering, and interop metadata.

## Call result type

The declared return type remains the success type. A synchronous `trap fnc(): T` exposes `Result<T, Error>` to its caller. An ordinary return yields `Ok(value)`; an ordinary escaping throw yields `Err(error)`.

```ores
const user: Result<User, Error> = load_user(id);
```

If the successful value is already `Result<T,E>`, pass it through without nesting; widen the error type to admit the trapped error where necessary. Returning an existing Err is ordinary data, not an additional throw.

### Optional success and void

A callable returning `Option<User>` exposes `Result<Option<User>, Error>`. `Ok(None)` represents successful absence, `Ok(Some(user))` represents successful presence, and `Err(error)` represents an escaping ordinary throw. Do not flatten these states.

A trapped void callable exposes `Result<void, Error>`. A successful unit result is distinct from an error even when the internal successful payload is null/unit.

There is no two-slot trap tuple or compiler-owned TrapResult wrapper.

## Control-flow channels

Oreslang keeps three exceptional control effects distinct:

```text
throw  -> ordinary recoverable error
raise  -> deliberate non-trappable exceptional escape
panic  -> invariant/runtime non-trappable escape
```

The trap rule is:

```text
ordinary return -> Ok(value)
uncaught throw  -> Err(error)
raise           -> bypass trap
panic           -> bypass trap
cancellation    -> bypass trap
termination     -> bypass trap
```

A local explicit `try/catch` may consume a throw before it reaches the trap
boundary.

Returning an `Error` or another error-looking value is ordinary data. A returned Result passes through; only an escaping ordinary throw is newly converted into Err.

## Error identity

A trapped ordinary guest throw preserves the original nominal guest error identity and payload. A conforming non-Error thrown value may require a guest error envelope preserving its nominal identity and payload. Do not expose arbitrary host exception objects, cause chains or stacks. Unknown native failures and host-fatal control are not silently converted into guest errors.

## Async trap (target; not yet enabled)

```ores
pub async trap fnc fetch(): Payload {
  ...
}
```

The target call expression type is `Future<Result<Payload, Error>>`. A declared `Option<T>` success type yields `Future<Result<Option<T>, Error>>`; an existing Result is not wrapped again.

The boundary must span the entire computation, including execution resumed after every await. Success settles the Future with Ok; an escaping ordinary throw before or after suspension settles it with Err preserving guest identity. Raise, panic, cancellation and forced termination remain distinct control outcomes. `trap await f` requires the same suspension-spanning semantics.

Current Java main deliberately rejects async trap callables and trapping a Future where asynchronous failure capture is unsupported. Keep these gates until the current-main implementation and tests cover all completion paths. See [Java #579](https://github.com/atto-lang/atto-source.java/pull/579) for the historical implementation requiring migration, not authority for Option-based trapping.

## Future composition

Oreslang's Future is runtime-owned. Reactive chaining must preserve the same
scheduler rule as `await`: completion threads may settle a Future but may not
execute guest callbacks inline.

The language/runtime Future surface should support the same useful split used by
modern Vert.x APIs:

```text
map       : (T -> U)         -> Future<U>
compose   : (T -> Future<U>) -> Future<U>
flatMap   : alias of compose
onSuccess : (T -> void)      -> Future<T>
```

These callbacks execute as Ores scheduler/actor turns. They are not Java
`CompletableFuture` callbacks and do not inherit producer-thread affinity.

## Select interaction

Value-returning dynamic selection follows the Option/Future convention:

```text
select from cases     -> Option<SelectResult>
nb select from cases  -> Future<Option<SelectResult>>
try select from cases -> Option<SelectResult>
```

A blocking `select from` yields the current Ores continuation rather than
parking a carrier thread. When an arm wins, it resumes and returns
`Some(SelectResult)`.

`try select` returns `None` when no case is immediately ready and leaves no
waiter behind.

### Throws inside static select arms

A static branch-select is a control-flow construct:

```ores
select {
  case readch input: val value {
    ...
  }
}
```

Its selected arm is required to be non-throwing at the arm boundary.

- a throw handled by a local `try/catch` is allowed;
- a call to a `trap` function is allowed because the throw is converted to
  Result data before returning to the arm;
- an ordinary throw that can escape the selected arm is a compile-time error;
- `raise` bypasses select and continues to the nearest matching recover boundary;
- `panic` bypasses select and follows panic/recover/supervision policy;
- runtime cancellation/termination bypasses the arm and remains runtime control.

This prevents `select` from gaining a hidden exception channel while still
preserving explicit nonlocal control effects.

## Cleanup

`defer` / `finally` run during unwinding before a trap boundary produces
`Err(error)`.

Cleanup must not downgrade stronger control signals. The runtime preserves the
ordering:

```text
fatal/termination > panic > raise > throw > return
```

A cleanup throw may replace a normal return, and the nearest trap may then turn
that throw into `Err(error)`. A cleanup throw must not convert an in-flight
raise/panic/cancellation into `Err(error)`.

## Callable compatibility

`trap` is part of callable metadata.

These are distinct contracts:

```text
fnc(): T
trap fnc(): T
```

At the call site they expose `T` and the corresponding `Result` respectively.

V1 should require exact trap compatibility for overrides/interface slots and
function-value assignments unless an explicit safe adapter is defined.

## Actors and execution domains

A trap boundary is dynamic to one execution domain. It never implicitly crosses
an actor, isolate, process, or detached-task boundary.

An unrecovered `raise` or `panic` inside an actor follows actor supervision.
Untrusted actor resource cancellation/termination cannot be swallowed by
`trap`.

## Host/native failures

Only failures explicitly classified as ordinary Ores `throw` may become
`Err(error)`.

VM-fatal errors, OOM/stack corruption, process termination, scheduler
interruption/cancellation, and unknown native failures must not be blanket
converted by a broad `catch (Throwable)`.

## Required implementation work

1. Preserve trap metadata in AST, symbol and callable types, including both spellings.
2. Type synchronous trap calls as Result, without nesting an existing Result or flattening optional success.
3. Preserve ordinary guest error identity and successful void.
4. Keep async admission closed until the complete continuation, cleanup and Future settlement boundary is proven.
5. Type admitted async trap calls as Future of the corresponding Result, preserving error identity before and after await.
6. Preserve raise, panic, cancellation, termination and host-fatal bypass channels.
7. Enforce trap compatibility through function values, overrides, interfaces, imports and optimization.
8. Require actual compiled guest and AOT execution evidence for each admitted lane.

## Required tests

- ordinary success, trapped throw, returned Err pass-through, optional success (including None), and void;
- async success and throws both before and after suspension; pending and already-complete Future paths;
- distinct cancellation, raise and panic outcomes; cleanup exactly once;
- original error type/payload survives trapping; local catch runs before the outer trap;
- interface/override/function-value compatibility; select arm effect checking;
- Future callbacks execute on the correct logical scheduler/actor turn;
- unsupported async paths reject until real execution support exists.

## Core invariant

```text
throw  -> nearest catch, otherwise nearest trap -> Err(original error)
raise  -> bypass catch/trap/select -> nearest recover/supervision
panic  -> bypass catch/trap/select -> nearest recover/supervision
cancel -> runtime cancellation channel, never Err
```

That distinction is semantic and must not be erased by parsing, optimization,
interop, actor transport, or backend lowering.
