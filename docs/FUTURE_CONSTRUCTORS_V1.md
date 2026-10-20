# User-created Future<T> constructors — v1 source contract

Status: **staged proposal plus host-side reference adapters**. The adapters
`OresFutureConstructors.fromAsyncRun` and `fromRunCallback` exist in this
branch, but **the Oreslang compiler does not yet admit or lower the source
`new Future<T>(...)` syntax shown below**. The Java adapters are neither an
LLVM implementation nor evidence of guest-level conformance.

## Three source-level creation paths

1. `AsyncRun<T>`: return `T` from an async body; the constructor observes
   its underlying `Future<T>` and settles the newly-created future with its
   value, failure, or cancellation. Do not expose `Future<Future<T>>`.
2. `RunCallback<T>`: register work and explicitly settle via a one-shot
   `Callback<T>`. Returning `void` from `run` **does not** settle.
3. `Deferred<T>` (future stage): explicitly construct a separate resolver
   capability and read-only `Future<T>` handle, without starting work.
   This is *proposed*, not implemented.

All three produce the same `Future<T>` accepted by `await`, `select`,
future combinators and generic `HasFuture<T>.get_future()` projection once
those generic trait bindings are admitted.

### Named-class Oreslang examples (target syntax, not current compiler support)

```ores
define pub trait AsyncRun<T> as
    async run(): T;
end

define pub trait RunCallback<T> as
    run(Callback<T> cb): void;
end

define pub class Calculate as
    implements AsyncRun<int> as

    async run(): int {
        let int x = await foo();
        return x * 8;
    }
end

let Future<int> f = new Future<int>(new Calculate());
let int answer = await f;
```

```ores
define pub class RegisterWork as
    implements RunCallback<int> as

    run(Callback<int> cb): void {
        do task {
            let int x = await foo();
            cb.resolve(x * 8);
        };
    }
end

let Future<int> f = new Future<int>(new RegisterWork());
let int answer = await f;
```

An `async run(Callback<T>)` implementation may also be supported later.
Its return value is **not** the result of the callback-style future; an
unhandled async-runner error fails the future if it is still pending, while
normal `async run` completion leaves it pending. This requires compiler
effect tracking, joining / adoption of child tasks, and error supervision.

A `return x * 8` inside a `do task { ... }` body returns from the **child
Task**, not from its enclosing callback Future. It MUST call
`cb.resolve(x * 8)` or arrange explicit callback settlement.

Anonymous inline implementations such as
`new Future<int>(new AsyncRun<int>() { ... })` are optional syntax sugar:
they require anonymous trait-object support, which is not implied by this
contract. Named implementations and generic specialization are the base API.

## Resolution and safety invariants

- **Immediate start, no implicit actor:** construction evaluates the provider
  and starts/attaches work in the current authorized Task/actor/root execution
  domain. The factory itself does not spawn an actor, thread, or isolate.
  Java's temporary virtual-thread compatibility bridge is not source semantics.
- **Exactly one terminal winner:** `Pending -> Resolved(T) | Failed(Error) |
  Cancelled(reason)`. A duplicate callback settlement is a programming
  error. A callback arriving after consumer cancellation may be discarded,
  but cannot resurrect a cancelled future. The underlying Java callback
  primitive already implements this arbitration.
- **Error propagation:** `AsyncRun<T>` return resolves to `T`; thrown/
  raised unhandled failures fail the future under the language effect policy.
  A `RunCallback<T>` registrar throw fails only a still-pending future;
  a previously resolved callback wins.
- **No reentrant guest continuation:** a foreign producer callback may
  atomically complete the future, but guest code may resume only when its
  authorized scheduler dispatches a continuation after the suspending turn
  has unwound. No user callback execution on I/O, JNI, timer or producer
  completion threads.
- **Cancellation != teardown:** an observable cancelled Future is not proof
  its backing task/callback registration has physically quiesced. The
  constructor's producer must supply a revocable registration, cancellation
  propagation and an independent quiescence gate where needed. Cancellation
  of a derived future must not cancel shared producers without ownership.
- **Structured lifetime:** `do task` started from a callback registrar is
  owned by a constructor-associated scope; returning from `run` does not
  silently orphan it or end the scope. Cancellation and shutdown must release
  callback handles and join owned children. Dropping a Future handle does not
  confer detach authority.
- **Capabilities/borrows:** the callback is a narrow, single-shot resolution
  capability. It cannot grant actor heap access, escape trust domains, leak
  borrowed references across `await`, or copy non-shareable mutable data.
  Values crossing async/actor domains obey existing ownership and Send rules.
- **Generic correctness:** `AsyncRun<T>`, `RunCallback<T>`,
  `Callback<T>` and `Future<T>` retain concrete `T` and trait bounds;
  no fallback to `Unknown`, erasure, or wrong-arity nested future unwrapping.
- **Core/alloc/std boundary:** bare-metal `core` may define the type/trait
  contract; any scheduler, heap-backed resolver, or host callback adapter
  is optional runtime/alloc territory rather than a mandatory OS dependency.

## Backend state and staged gates

### Present in the Java host runtime

- `AsyncRuntime.submit(Task<T>)`: scheduled operation -> `OresFuture<T>`,
  with its existing cancellation hook and carrier tracking.
- `OresFuture.fromCallback(registrar)`: callback adapter with atomic,
  deterministic single-shot completion and late-callback cancellation rules.
- This branch adds **only** named host-facing adapters and focused JUnit
  coverage to make the two intended constructor lowering targets explicit.

### Still required before marking Oreslang syntax implemented

1. Introduce generic `AsyncRun<T>`, `RunCallback<T>`, `Callback<T>`
   definitions and constructor overloads in the actual type checker. Keep
   overload resolution nominal and reject ambiguous adapters.
2. Add parser/HIR support for `new Future<T>(run)` using named class
   implementations first; type-check async effects, ownership and cancellation.
3. Lower the `AsyncRun<T>` path through runtime scheduler-owned continuation
   dispatch. For callback runs, ensure registrar execution on an authorized
   turn and explicitly associate spawned `do task` children with a lifetime
   owner, including resolver revocation.
4. Add source-language test fixtures for sync callback settle, pending after
   `run` return, error before/after callback resolve, duplicate settlement,
   cancellation/late settlement, scheduled await resumption, generic types,
   non-Send captures and shutdown quiescence. Exercise deterministic races.
5. Implement and verify equivalent LLVM lowering. Until its continuation
   backend is ready, reject unsupported constructions at compile time;
   never claim parity from a native Future metadata gate alone.
6. Run exact-head JVM guest tests, Java/native AOT where available, LLVM
   source conformance and relevant race/sanitizer gates before merge.
   Do not mirror private source into unapproved or public CI repositories.

## Optional Deferred<T> stage

A future stage may expose:

```ores
let Deferred<int> d = Future<int>.deferred();
let Future<int> f = d.future();
d.resolve(40);             // one-shot capability owned by producer
let int result = await f;
```

The resolver must not be extractable from `Future<T>` itself; completing
a Future is an authority separate from merely awaiting it.
