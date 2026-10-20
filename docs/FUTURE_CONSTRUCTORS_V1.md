# User-created Future<T> constructors — v1 source contract

Status: **partially implemented source-language compiler slice, not yet verified
by real CI**. This branch implements `new Future<T>(provider)` where the
provider is a named non-actor class with `pub async run(): T`, and adds guest
acceptance/rejection test cases. This uses the existing scheduler-owned async
class method completion instead of inventing another Future runtime or nested
Future. The second `RunCallback<T>` source constructor is still **only
host-runtime staging**. Neither LLVM parity nor actual guest CI execution is
established. The `OresFutureConstructors.fromAsyncRun` host bridge remains
an independent reference adapter rather than the new source lowering target.

### Current source constructor admission (Java/Graal first slice)

The compiler statically requires exactly one explicit `T` and one nominal
class instance argument. That class must be non-actor and expose a public
non-abstract non-generic `async run(): T` method taking zero arguments.
The first slice is deliberately **stateless**: provider instance fields,
inheritance, and explicit constructors are rejected until the compiler proves
exclusive receiver movement/pinning across the Task boundary. The
method's return type must match the requested `Future<T>` parameter
**exactly**, after concrete generic substitutions. Ordinary assignment
compatibility is insufficient because the evaluator currently reuses the
provider method's `OresFuture<T>` without a value-mapping/coercion
continuation. Thus `async run(): int` cannot be wrapped as
`new Future<float>(...)` or `new Future<Any>(...)` in this first slice;
a future explicit result-map operation can admit such conversions safely.
Private, sync, suspends-only, actor, dynamic/unknown and incompatible run
providers are rejected. The evaluator invokes `run()` through its existing
async source Task path and returns the resulting `OresFuture` **without
starting a second Task or blocking a guest continuation**.

Example newly admitted by the compiler (still awaiting real CI proof):

```ores
define module app
  define pub class Calculate as
    pub async run(): int { return 5; }
  end
  pub async fnc main(): void {
    let Future<int> f = new Future<int>(new Calculate());
    let int answer = await f;
    stdio.println(answer);
    return;
  }
end
```

**Limits:** the generic `AsyncRun<T>` trait declaration/implementation contract
and `RunCallback<T>` guest resolver lowering are not yet admitted. Provider
shape is checked on a nominal class rather than blindly admitting arbitrary
structural callbacks; a future stage will bind the constructor strictly to
the generic trait and check that trait's declared conformance. Do not use a
host `Callback` or foreign thread as an unverified shortcut to guest
registration. In particular, `do task` lifetime ownership is not yet
integrated for this constructor family.

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

A further safety invariant is **terminal capability revocation**: externally
retained callback handles cannot keep the completed Future's completion
authority or buffered result payload alive. A terminal resolution, rejection,
consumer cancellation, or failed/invalid registrar revokes the staged handle,
clears its `OresFuture.Callback` target and any staged `T`, and runs the
returned detach action once when one exists. A first late callback after
revocation is ignored, but a second attempt is a programming error. This
revocation does not itself establish physical callback quiescence.

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

1. Finish generic `AsyncRun<T>`, `RunCallback<T>`, `Callback<T>`
   trait declarations and strict conformance in the actual type checker.
   The first source slice admits only a nominal public async `run()` provider.
   Keep future overload resolution nominal and reject ambiguous adapters.
2. Verify the new parser AST/checked evaluator path for `new Future<T>(run)`
   using named class implementations first. Finish compiler-enforced
   ownership, task-scope accounting, and cancellation propagation.
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

### Callback cleanup linearization audit — 2026-10-20

The host-only `fromCancellableRunCallback` now uses **one** terminal
`whenCompleteRuntime` subscriber for revocation and detach. The intermediate
`terminal` flag and a second cleanup branch were removed: the new Future
cannot be observed or consumer-cancelled until the factory returns;
registrar callbacks remain staged while `register` runs; the detach action
is installed *before* `staged.arm()`. Runtime waiter registration after a
synchronous terminal state immediately processes that state, so there is no
missing cleanup window.

Additional staged JUnit cases cover synchronous and asynchronous
**producer-side cancellation**, duplicate settlement after producer cancel,
and a registrar that returns without settling. The host constructor suite now
contains **21** JUnit cases; these have **not executed** against this private
repository's Maven/JDK25 build.

A source-free isolated Java 21 surrogate Future model ran six behavioral
checks and **5,000** cancellation-versus-resolution races successfully.
The copied callback state machine and wrapper wiring were exercised but the
surrogate is **not** the actual `OresFuture`, source compiler, GraalVM or
LLVM. In particular, this local result cannot qualify the PR for merge.
The focused exact-head source Actions run #39473077546 still failed with
**zero executed steps**. Cross-org mirroring remains deny-by-default until
main protection and a reviewed destination/pinned contract are confirmed.


### Recursive source-type admission audit — 2026-10-20

The first source constructor slice must reject **dynamically erased**
`Future<T>` payloads. Checking only the internal `Unknown.INSTANCE`
sentinel is insufficient: the type resolver can represent source `Any` and
`Unknown` as `Named` types, including in nested `Option<Any>`,
`Array<Unknown>`, records, unions, or function signatures. The dedicated
`containsUnresolvedFutureConstructorType` guard now recurses through
all those shapes, rejects unresolved `Generic` parameters, and is applied
to the producer's type arguments as well as its resolved `run()` result and
the requested `Future<T>` type. This strict rule applies **only to this
incomplete first-stage constructor** and does not globally ban legitimate
generic declarations or existing Future consumption. A follow-up compiler
proof must allow explicitly bounded/specialized generic constructors rather
than relaxing it by type erasure.

There are now **14 guest Future-constructor tests** (including nominal
`Any`, nested `Option<Any>`, `Unknown`, and erased provider-generic
rejection) and **21 host JUnit tests** staged in the PR. The type-gate
recursion was independently syntax-compiled on **OpenJDK 21** in a
source-free surrogate harness with 14 deterministic positive/negative
cases passed. This tests the standalone predicate against synthetic type
nodes, **not** the actual Oreslang type checker, parser or evaluator.

An existing private source-free runner probe in
`sonus-auris-test/clients-api-contract` was rerun (Actions run
`39362748484`, attempt 3) and completed successfully with a JDK25
GraalVM toolchain. That repository is **not** an authorized source mirror.
At the audited exact PR head, the source's Future-constructor workflow still
failed with zero executed steps. The source `main` branch reports
`protected: false`, and the protected destination allowlist contains no
approved entries. Do not copy private source to that carrier until reviewed
origin and destination contracts allow it.


### 2026-10-20 follow-up audit: evaluator parity and intrinsic identity

Both the suspension-aware `evalSuspendable(NewExpr)` path and the direct
synchronous `eval(NewExpr)` path must funnel source
`new Future<T>(producer)` through the same checked `instantiateEvaluated`
intrinsic. Before this audit, the direct path attempted normal class
construction and failed with `unknown class Future`. The draft now contains
the direct-path fix and a synchronous `main` regression test.

`Future` is sealed against locally defined classes and type aliases; both
the type checker and evaluator fail closed if a linked class collides with
the intrinsic name. The guest regression set also exercises `Future<void>`
completion and its zero-payload await.

These tests have been **committed but not executed on the real Java/GraalVM
compiler**. The dedicated PR-head workflow continues to fail with zero
steps and no assigned runner. A private cross-org copy is not an acceptable
substitute: the protected source-origin and reviewed carrier allowlist remain
unsatisfied. A passing synthetic host-adapter harness, if available, is not
a substitute for exact-head guest tests.



### Sealed Future type namespace — 2026-10-20

`Future` is a compiler intrinsic, not a user-defined nominal class.
The constructor is checked and lowered on both the direct/synchronous and
suspendable expression paths. The declaration pass must therefore reject
*all user-defined type declarations* with this name: **classes, type
aliases, and interfaces/traits**. Merely rejecting classes is insufficient:
an interface named `Future` could otherwise occupy a nominal type namespace
while the source compiler continues to interpret `Future<T>` as its intrinsic.
The latest tests assert the **specific sealed-intrinsic diagnostic** for
class, alias and interface shadowing; unrelated parser errors are not
acceptable substitutes for this contract.

The interface/trait declaration gate is staged on PR #1676. A source-free
JDK21 syntax/probe cannot validate Oreslang's complete parser or GraalVM
runtime; these cases still require real exact-head guest JUnit execution.


### MutexGuard admission and exact-head CI scope (2026-10-20)

`new Future<T>(provider)` immediately starts an async source method,
even though expression evaluation returns a Future without awaiting it.
Consequently the OwnershipChecker now rejects that constructor when a
`MutexGuard` is live or execution is inside a protected
`with_lock`/`recover` critical section. Starting a new Task under an
unreleased guard is not equivalent to constructing a passive value. A guest
regression asserts this precise ownership diagnostic.

The focused GitHub Actions workflow now watches the ownership checker,
scheduler, Future, async runtime, and TaskScope source files as well as its
guest and host test suites, not only the original type checker/evaluator
files. This avoids missing regression runs for ownership-only changes.

**CI evidence boundary:** the exact-head GitHub-hosted Actions jobs in
the source repository continue failing with no assigned runner and zero
steps. Source-free Actions jobs in the two examined private carrier
repositories exhibit the same zero-step failure. The latter observation
does not prove why jobs fail or establish available Actions minutes; it
does indicate those repositories have not yet demonstrated working
capacity. Private source transfer is still denied by the unprotected
origin main and empty approved-destination allowlist. Full JDK25
GraalVM/Maven/JUnit execution and LLVM parity remain unverified.



### Malformed external rejection cleanup — 2026-10-20

The cancellable host callback adapter now treats an external
`callback.reject(null)` as a **terminal rejection with a
`NullPointerException` cause**, rather than throwing immediately out
of the producer callback while leaving its Future pending indefinitely.
This closes the missing-detach path for an invalid rejection issued
*after* registration. The same rule applies to callbacks invoked
synchronously inside `register`: terminal delivery is staged until
the registrar returns a valid detach hook, after which it fails and
detaches once. A subsequent settlement attempt is still a duplicate.

Two staged host JUnit cases cover asynchronous and synchronous invalid
rejection. The host suite now contains **23** tests and the guest
Future-constructor suite **20** tests. A Java 21 **isolated surrogate**
with a copied `StagedCallback` state machine passed null rejection,
terminal reference clearing, duplicate rejection and 5,000 concurrent
arm/revoke/settle races; these are **not** Maven/JUnit results from the
actual Oreslang runtime.

This host-runtime hardening does not magically make external
registrations physically quiescent: detach action semantics remain a
separate trusted runtime contract, and the untyped host-only
`RunCallback` overload has no external detach hook.


### Host RunCallback null-rejection normalization — 2026-10-20

The synchronous host-only `fromRunCallback` and scheduler-admitted
`fromRunCallbackOnCurrentTask` now normalize a malformed
`callback.reject(null)` into a **terminal failure** with a
`NullPointerException` cause. Their original pass-through callbacks
would instead throw before settling the returned `OresFuture` when a
retained external producer delivered that invalid signal later.
A small adapter delegates `resolve`, `cancel` and `isDone` to the
existing `OresFuture.Callback`, retaining the original single-claim
and late-cancellation arbitration. The separate
`fromCancellableRunCallback` staging/cleanup contract is unchanged.

Three additional host JUnit cases cover late foreign null rejection,
synchronous null rejection during registration, and rejection inside
an admitted scheduler turn. The **host suite now stages 26 JUnit tests**
and the guest suite stages **20 JUnit tests**. In a **source-free Java 21
surrogate**, three terminal-state checks and 2,000 concurrent settle/
cancel races passed; this model is not the actual Oreslang runtime or
GraalVM/LLVM/Maven integration, so exact-head CI remains mandatory.
