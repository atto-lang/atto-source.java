# `trap!` and `trap` Result boundaries

This document defines the Oreslang contract for the `trap` callable modifier
and its interaction with `throw`, `raise`, `panic`, async/Future execution,
select, actors, and runtime cancellation.

The central rule is intentionally small:

> A call to a `trap` callable never exposes an ordinary `throw` to its caller.
> Success is `Ok(value)`. An escaping ordinary throw produces `Err(error)`.

`raise`, `panic`, runtime cancellation, forced termination, and fatal host
failures bypass the ordinary throw boundary.

## Java draft coverage

This branch implements the Result boundary for top-level and module callable declarations, function values and expression `trap!` or `trap`. A synchronous callable whose body suspends keeps the same trap boundary when its task resumes. Explicit async trap declarations, generators, actor callables and method modifiers remain gated in this branch.

The `throw!`, `raise!` and `panic!` forms admit owned values with the nominal
`Throwable`, `Raisable` and `Panicable` markers, respectively. The unadorned
spellings are aliases. The built-in `Error` supports all three effects. A
typed guest throw preserves its `Error` value. For a custom nominal payload,
`Err(error)` retains that value in `error.payload`, and ordinary catch
receives the original payload. Explicit recovery exposes the original raise
payload in `signal.payload`. A panic bypasses ordinary catch, trap and
recovery. Linked std/error subclass resolution and guest supervisor delivery
remain separate integration work.

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

The declared return type remains the success type. A trapped callable returning
`T` has call type `Result<T, Error>`. A successful `Option<T>` remains an
Option inside `Ok`. A successful void call produces `Ok(void)`.

A value of type `Result<T, E>` passes through without another Result layer.
Its original `Ok` or `Err` payload remains intact. The compiler admits the
additional `Error` variant in the error type when needed for a trapped throw.

An ordinary error handled by a lexical catch never reaches the trap. Raise,
panic, cancellation and termination use their existing control channels.

## Async trap

For:

```ores
pub async trap fnc fetch(): Payload {
  ...
}
```

the call expression type is:

```text
Future<Result<Payload, Error>>
```

The trap boundary spans the complete asynchronous computation, including code
resumed after every `await`.

- success settles the Future with `Ok(payload)`;
- an ordinary uncaught throw before or after suspension settles the Future
  with `Err(error)`;
- `raise` and `panic` bypass trap and retain their distinct control identity;
- runtime cancellation stays cancellation and bypasses trap;
- forced actor/isolate/sandbox termination stays a supervision/termination
  outcome and bypasses trap.

Awaiting an async trap Future re-emits any non-trappable raise/panic/cancellation
according to the normal runtime rules.

A trap callable whose declared success type is already `Option<T>` therefore
returns `Future<Result<Option<T>, Error>>` when async.

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
that throw into `Err(error)`. An in-flight raise, panic or cancellation
retains its control channel.

## Callable compatibility

`trap` is part of callable metadata.

These are distinct contracts:

```text
fnc(): T
trap fnc(): T
```

At the call site they expose `T` and `Result<T, Error>` respectively.

V1 should require exact trap compatibility for overrides/interface slots and
function-value assignments unless an explicit safe adapter is defined.

## Actors and execution domains

A trap boundary is dynamic to one execution domain. It never implicitly crosses
an actor, isolate, process, or detached-task boundary.

An unrecovered `raise` or `panic` inside an actor follows actor supervision.
Untrusted actor resource cancellation/termination cannot be swallowed by
`trap`.

## Host/native failures

Only failures explicitly classified as ordinary guest errors may become
`Err(error)`. Mapped runtime failures use an inert compiler-owned Error.

VM-fatal errors, OOM/stack corruption, process termination, scheduler
interruption/cancellation, and unknown native failures must not be blanket
converted by a broad `catch (Throwable)`.

## Required implementation work

1. reserve and parse `trap` on supported callables;
2. store trap metadata in AST/symbol/callable types;
3. type synchronous trap calls as `Result<T, Error>`;
4. preserve Option payloads and pass through existing Results;
5. support compiler-produced `Result<void, Error>`;
6. lower ordinary uncaught throw at the boundary to `Err(error)`;
7. lower successful return to `Ok(value)`;
8. type async trap calls as `Future<Result<T, Error>>`;
9. preserve raise/panic/cancellation/termination as distinct bypass channels;
10. enforce trap compatibility across function values/interfaces/overrides/imports;
11. reject escaping ordinary throw from static select arms;
12. preserve the contract through optimization, actors, JIT/AOT, and interop.

## Required tests

Compile/runtime coverage must include:

- trap success -> `Ok(value)`;
- trapped ordinary throw -> `Err(error)`;
- `trap fnc(): Option<T>` -> `Result<Option<T>, Error>`;
- trapped void -> `Result<void, Error>`;
- async trap success -> `Future<Result<T, Error>>`;
- throw after await -> Future settles with `Err(error)`;
- raise/panic bypass sync and async trap;
- cancellation bypasses trap;
- local try/catch inside trap handles throw before the boundary;
- returned Error/Result values remain data;
- interface/override/function-value trap compatibility;
- static select arm rejects an escaping ordinary throw;
- static select arm permits locally caught throw and handled trap Result;
- raise/panic in a select arm bypass select;
- dynamic select returns `Option<SelectResult>`;
- `nb select` returns `Future<Option<SelectResult>>`;
- callbacks used by Future.map/compose/onSuccess execute only on an Ores scheduler
  or actor turn.

## Core invariant

```text
throw  -> nearest catch, otherwise nearest trap -> Err(error)
raise  -> bypass catch/trap/select -> nearest recover/supervision
panic  -> bypass catch/trap/select -> nearest recover/supervision
cancel -> runtime cancellation channel
```

That distinction is semantic and must not be erased by parsing, optimization,
interop, actor transport, or backend lowering.
