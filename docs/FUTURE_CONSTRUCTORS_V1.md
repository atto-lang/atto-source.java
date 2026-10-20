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

## Audited host callback registration and cleanup

The Java reference adapter provides three distinct entry points:

- `fromRunCallback(operation)` is the **host-only immediate registrar**.
  It may run the registrar synchronously on the caller's stack. It is only
  safe when that caller is a trusted host boundary or already owns the
  authorized guest turn. It is not general foreign-thread guest dispatch.
- `fromRunCallbackOnCurrentTask(operation)` refuses registration unless
  `OresScheduler.requireCurrent()` succeeds. This is a stricter lowering seam
  for guest Tasks, not a substitute for actor turn or root context checks.
- `fromCancellableRunCallback(operation)` takes a registrar returning a
  runtime-only `Runnable` detach action. On success, failure or cancellation
  it invokes that detach action **once**. It handles completion during
  registration, completion after registration, cancellation races and
  the case in which registration fails before returning its detach action.

The cancellable variant stages early/synchronous callback signals until
`register()` returns a non-null detach action. Thus registration failure,
including a null detach action after `cb.resolve(value)`, rejects the future
rather than publishing an uncleanupable early success. This stricter,
transactional registration admission applies to the cancellable variant;
the original immediate `fromRunCallback` retains callback-first settlement
semantics.

A detach callback is trusted runtime code only: **no guest execution, no
blocking and no throws**. A registrar throwing after partial listener
installation MUST itself roll back the partial installation. Missing or null
detach actions are invalid registration. Physical callback quiescence is
a separate lifecycle barrier: calling detach alone is not evidence that a
racing foreign callback or owned Task has stopped.

The host adapters do not presently provide an `AsyncRunCallback<T>` entry
point for a source-level `async run(Callback<T>)`. Such lowering will require
a tracked child `Future<void>` for the async registrar so a throwing/raising
registration task rejects a still-pending result without swallowing its
failure, while a successful registrar return leaves the result pending.
A `do task` spawned by the registrar must have a scope/quiescence owner.

### Validation and CI admission status (2026-10-20)

- Java PR #1676 source head initially `2977c04bb42ed9a8490c05e3f83d275ecb0ef003`.
- The GitHub-hosted `fast-ci` run 39435718937 concluded failure,
  but its test job had **zero steps**, no runner and no actual Maven execution;
  this is an **infrastructure / admission failure**, not a test result.
- The original `ci` workflow's four jobs likewise had zero steps on
  that source head. Do not call any of these red runs compiler failures.
- The destination allowlist in
  `.github/private-ci-approved-repositories.txt` contains **no approved
  destinations** (commented candidates do not count).
- GitHub reported source repository `main` `protected: false`.
  Therefore, under `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`,
  **no cross-organization private source mirror is authorized**. Merely
  finding an organization with free Actions minutes cannot override this.
- A local **isolated adapter compile** with JDK 21 and minimal Future/scheduler
  test doubles passed eight checks and 1,000 callback completion/cancellation
  races after disabling an environment-specific full-tier JVM JIT hang. This
  verifies the copied adapter's Java 21 syntax and focused cleanup algorithm
  only; it does NOT execute the repository's real `OresFuture`, JUnit tests,
  Truffle guest compiler or LLVM backend.
- The local environment may permit only isolated Java 21 verification.
  Successful local adapter compilation or a synthetic test double cannot
  replace exact-tree JDK 25, full Maven, Graal native, or LLVM guest evidence.

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
