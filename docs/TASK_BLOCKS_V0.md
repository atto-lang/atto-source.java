# Oreslang lightweight Tasks: v0 draft frontend contract

**Status (2026-10-19):** Canonical `task {}` syntax is parsed. Draft PR #1484 adds only the immediate `await task { return <scalar>; }` path in a trusted public root `main`; the full-source Maven/JUnit/GraalVM execution gates have **not** been verified. All general, detached, nested and actor Tasks remain rejected. Most examples below specify intended future semantics, not executable behavior.

`task { ... }` is canonical. Legacy `nb { ... }` is parsed to the identical `TaskExpr`; nonblocking `nb readch` / `nb select` retain their separate meaning. Only the restricted immediate-await scalar form in trusted root `main` has an implementation draft; the general forms below remain unexecuted until their gates pass.

## Surface syntax

```oreslang
do task {
    let Data value = await fetchAsync();
    process(value);
}
continueWorking();

let Task<int> sum = task {
    let int a = await fetchAAsync();
    return a + 2;
};
let int answer = await sum;

let Task<void> write = task {
    await writeAsync();
};
await write;
```

- `do task { ... }` is a statement with **no result**. It schedules a Task but discards the handle.
- `task { ... }` is an expression producing `Task<T>`. Its result type comes from the body returns; `Task<void>` is valid.
- Bare `task { ... }` statements are rejected: explicitly use `do task` or bind the resulting handle.
- `await task` suspends only the current task until completion; `await` inside an `task` block suspends the child task, not its caller.
- Neither form is an actor or mailbox. `Task<T>` is an owned capability for a scheduled, single-completion computation; `Future<T>` remains an eventual-result abstraction.
- The Task's body has its own return target. `return` in the block never returns from its enclosing function.

## Argument-bearing Task continuations via an immediately invoked lambda

Reuse the normal pipe lambda with an immediate call; **no special new `task` parameter syntax** is needed.

```oreslang
let Task<int> task = (|int a, string b| -> task {
    let int result = await computeAsync(a, b);
    return result;
})(5, "xyz");

// Do other work. This line does not await the task.
let int result = await task;
```

Exactly equivalent explicit-return variant:

```oreslang
let Task<int> task = (|int a, string b| -> {
    return task {
        return await computeAsync(a, b);
    };
})(5, "xyz");
```

The outer ordinary lambda executes *synchronously* just long enough to evaluate its `task` expression; it returns the **single `Task<int>` handle**. Arguments are evaluated once in normal left-to-right call order and bound lexically. Task creation must transfer/copy/share the lambda parameters into a heap-owned Task capture environment before the lambda frame exits. No borrowed stack address may escape. A `return` inside `task` returns from the Task; `return task { ... };` returns a Task handle from the lambda.

**Type contract:**

- `task { return value; }` has type `Task<T>` (once compiler lowering is enabled).
- `(|T a| -> task { return a; })(value)` returns `Task<T>` directly.
- `await (|T a| -> task { return a; })(value)` returns `T` and intentionally suspends the caller; it is **not** fire-and-continue.
- `let Future<T> f = task.asFuture();` is the proposed explicit readout/observation conversion, with no extra task launch. Java host `OresTask<T>.asFuture()` is implemented in the separate runtime draft; source member/type support is still gated.
- `async |...| -> task { ... }` now has a distinct `AsyncLambdaExpr` AST. Its intended type is `Future<Task<T>>`, **never automatically flattened**. Source type checking still rejects execution pending async-lambda lowering.
- `async task |...| -> { return expression; }` has a distinct `TaskLambdaExpr` AST. Its **entire body** runs as one Task when invoked; its intended result is `Task<T>`, not `Future<T>` or `Future<Task<T>>`. This is a *single* semantic scheduling primitive (the `async task` modifier), not two nested async layers.
- `async task |...| -> expression` is also accepted as an expression-bodied Task lambda with an implicit Task-local return. Its `return` (explicit or implicit) never unwinds the caller.
- The `task` modifier is contextual after `async`; bare `task |...|` is not enabled in this draft, preserving existing identifier compatibility. Named `async task fnc` declarations are a separate future extension.
- A lambda with a braced body needs an explicit `return task { ... };`. It does not implicitly return its last expression.

Do not permit Task type inference, expected-`Future<T>` coercion, mutable references to owner-local arguments, actor-private state access, or unmatched move-only ownership until the normal ownership/type passes prove them. Invocation arity and argument type checking continue to use ordinary callable rules; `task` does not bypass them.

## Isolation and ownership prerequisites

Task captures are explicit ownership transfers/copies/authorized shares (`rt take`, `rt copy`, `rt share`), not escaping stack borrows or implicit actor-state access. Actor handlers may create Tasks, but the Task cannot inherit an actor's state lock or mutate its private heap without an owned-message/capability boundary. Neither `do task` nor `task` may bypass tenant isolation, trusted/untrusted execution domains, code-version fences, or permission checks.

A Task must have an owning task scope/actor, cancellation token, bounded admission, full cleanup before terminal completion, and observable error supervision even when the handle is discarded. Owner shutdown cancels/drains Tasks. No detached immortal tasks.

Awaited results support exactly-once ownership transfer for move-only values; multi-await for read-only/copy-safe values needs an explicit well-defined policy. `await Task<void>` is a completion barrier with no payload.

## Scheduler/lowering prerequisites

Use existing Oreslang scheduling domains and the continuation/safepoint machinery; do not create an OS thread per Task as a language guarantee. The current Java `AsyncRuntime` uses virtual-thread compatibility carriers. A future continuation-frame M:N implementation must preserve semantics without blocking actor worker threads or permitting uncooperative CPU loops. CPU-bound work uses `rt cooperate` and admission quotas; truly blocking FFI uses dedicated blocking executors.

Compiler stages must provide: (1) Task AST and expression typing; (2) lexical capture and escape checks; (3) nested `await` continuation frames; (4) completion/error/cancellation publication and defer/finally ordering; (5) Task type constructor and await typing; (6) actor-owned lifetime and shutdown; (7) native parity and exact-head conformance.

## Compatibility

Existing `nb select` returns its existing result kind and remains distinct from a Task block. Existing `do nb select`, `nb readch`, `nb writech` and `nb cb writech` are unchanged. Proposals for `nb cpu { }`/`nb gpu { }` currently use `Future<T>`; don't quietly retype them without an explicit migration. Bare `nb` is never a synonym for `defer`.

## Acceptance tests before enabling runtime

1. `do nb` continues without waiting, has no result and owns its errors.
2. `let Task<T> t = nb { ... }; await t` produces `T` with no result lost.
3. `Task<void>` is awaitable.
4. Cancellation/shutdown and `defer`/finally run exactly once.
5. Move-only captures/results, escaping borrows, actor-state access, tenant barriers and stale hot-reload generations fail correctly.
6. Bounded scheduler work under load, race tests, and real GraalVM+LLVM executable parity on exact PR heads.

Until those gates are met, type checking rejects the new AST with a clear diagnostic instead of silently running it as a synchronous block.


## Whole-body task lambda, explicit vs nested async

```oreslang
// One scheduled Task<int> (no nested Future).
let Task<int> job = (async task |int a, string b| -> {
    return compute(a, b);
})(5, "xyz");

// Equivalent Task intent without new syntax.
let Task<int> job2 = (|int a, string b| -> nb {
    return compute(a, b);
})(5, "xyz");

// Entire lambda body is a single Task; expression body is shorthand.
let Task<int> job3 = (async task |int a, string b| -> compute(a, b))(5, "xyz");

// The outer await JOINs the Task, returning int.
let int answer = await (async task |int a, string b| -> {
    return compute(a, b);
})(5, "xyz");

// The distinct ordinary async lambda returns Future<Task<int>>.
let Task<int> nested = await (async |int a, string b| -> nb {
    return compute(a, b);
})(5, "xyz");
let int answer2 = await nested;
```

All code above illustrates intended **post-lowering semantics**, not current executable source. With an ordinary `async` lambda, the first await resolves only the outer Future and cannot implicitly join the Task. The Task lambda has just one completion, cancellation identity, execution scope and scheduler admission. Parameter expressions are evaluated exactly once in ordinary left-to-right order, and their ownership-safe capture into the Task must finish before caller/lambda stack frames exit; escaping borrows are prohibited. Do not allow a Task lambda to capture actor-private mutable pointers.

A Task lambda whose body itself returns `Task<U>` would produce `Task<Task<U>>` rather than silently flatten. Similarly `async |...| -> nb {...}` produces `Future<Task<U>>`. Plain and Task lambdas still use the established block-return requirement: braced bodies require explicit `return` for non-void values; expression bodies return their expression result.

The Java frontend recognizes the AST forms but **both fail closed at type checking** until Task/async lambda type inference, closure capture, nonparking suspension and source execution are integrated and tested across GraalVM and LLVM.

## Canonical Task syntax and cooperative scheduling (follow-up)

Use `task { ... }` for a **Task-producing expression**; `do task { ... }` when the caller deliberately discards the Task handle. The previous `nb { ... }` and `do nb { ... }` remain source-compatible aliases with the identical AST. Both launch immediately. Only `await` suspends the current Task to join another Task. When the awaited completion is pending and compiler continuation frames exist, the scheduler releases the physical worker; the language NEVER interprets a keyword as "switch OS threads now."

`nb task { ... }` is a valid redundant spelling of the very same Task launch, not a second scheduling/cancellation tier. A bare `nb task { ... };` statement is rejected just like a bare `task { ... };` statement; bind the handle or use `do task { ... };`. `task` remains a contextual identifier when followed by a task block; an ordinary `task()` callable is unchanged.

**Single-layer whole-body Task lambda:**

```oreslang
let int result = await (
    async task |int a, string b| -> compute(a, b)
)(5, "xyz");
```

**Real nested Task (no flattening):**

```oreslang
let Task<Task<int>> outer = (async task |int a, string b| -> task {
    return compute(a, b);
})(5, "xyz");
let Task<int> inner = await outer;
let int result = await inner;
```

The second example starts a *separate child Task* inside an already scheduled Task, producing `Task<Task<int>>`. If that extra scheduling boundary is not intentional, omit the inner `task {}`.

## Task ownership and cancellation audit

The Java ownership checker now treats `task {}`, `nb {}`, `do task {}` and `do nb {}` as independently escaping closures at their launch site. It collects free names transitively through nested lambdas and Task blocks, and moves affine values at capture time, not at first resumption. Copyable immutable values remain available to the caller.

It rejects borrowed captures, by-value borrowed arguments to immediately invoked `async task` and `async` lambdas, escaping actor self, captured MutexGuards, Task launch from active protected critical sections, and ordinary borrows held over `await`. Task execution does not inherit an enclosing constructor's partial-init privilege or actor state lock. Task lambda arguments must be owned/copy-safe before the caller stack frame exits.

The type checker also recognizes the intended `await Task<T> -> T` rule, without flattening `Task<Task<T>>`. **These are static admission improvements, not enabled Task lowering.** Existing `OresScheduler` supports nonparking resumable `Await` steps, while the Java runtime Task handle draft adds tests for a single carrier progressing other work during a wait. Actual Oreslang source-frame transformation, actor-owned Task admission, physical quiescence before resource reclamation, and exact-head GraalVM/LLVM runtime parity are still release blockers. Do not remove the TypeChecker fail-closed gate based on these tests alone.


## Task and `suspends` are independent effects (October 18, 2026)

- `suspends fnc foo(...): T` may yield **the caller's current Task**. Calling `foo` does not launch another Task or wrap `T` in `Future<T>`. Its caller must be declared `suspends` or `async`; the special root `main` runs on an existing VM-owned Task and remains permitted to `await`, matching existing Oreslang programs.
- `task { ... }` and `nb { ... }` each launch a **new Task<T>** whose body is intrinsically suspension-capable. Their creation does not suspend the parent; an explicit `await` joins one Task and yields `T`. `do task { ... }` remains supervised even when the handle is discarded.
- `async task |...| -> body` schedules the whole body as one `Task<T>`. It is suspension-capable even though it needs no redundant `suspends` modifier. `async |...| -> body` instead returns `Future<T>`, and a returned inner Task is still an explicit `Future<Task<T>>`.
- `suspends |...| -> body` (contextual alias `suspend |...|`) denotes a **caller-executed suspendable lambda**, not a new Task. Parser/AST now retain `SuspendsLambdaExpr` separately. This source form remains type-checker gated until suspension-effect function types, nonparking call-frame lowering and lifetime proofs are implemented.
- Plain `|...| -> body` is synchronous even when nested inside an `async` or `suspends` declaration; it cannot inherit the enclosing function's permission to `await` or invoke a `suspends` function. The type checker saves/restores `currentMaySuspend` and `currentAsyncCallable` on lambda boundaries.
- `for await` is permitted where suspension is allowed (including `suspends` callables), without requiring the separate `async` Future-producing declaration effect.
- `async suspends`, `suspends task |...|`, and `async task suspends |...|` do **not** create new scheduler modes. They are rejected rather than implicitly flattening Tasks or diluting the effect contract.

```oreslang
suspends fnc fetch_blocking(int key): int {
    // May suspend the current Task; does not create another Task.
    return key + 1;
}

pub fnc main(): void {
    // Illustrative *future* source support: one owned Task, not a Future.
    let Task<int> job = (async task |int key| -> {
        return fetch_blocking(key);
    })(5);

    // Root main may await because it is already a VM-owned Task.
    let int result = await job;
    return;
}
```

The `async task` example is **not executable yet**. Its Task-owned capture frame must be materialized before the parent stack returns. `await` and `suspends` are suspension boundaries: no ordinary live lexical borrow, MutexGuard or actor-private pointer may outlive such a boundary. A `suspends` call executed *inside* a Task never receives implicit actor permissions or ownership of the caller's heap.

The Java parser and type checker now preserve the effect boundaries for existing synchronous/async/suspends callables, while `task {}`, `async task`, `async` lambda and `suspends` lambda source execution remain gated. LLVM continues to fail closed for unsupported native lambda/Task lowering. Runnable GraalVM/LLVM parity requires exact-head executable tests.

## Tasks and the suspends callable effect — continuation contract

`suspends fnc f(...): R` and `suspends |...| -> R` do **not** schedule
or allocate another `Task<R>` or `Future<R>`. They run on the invoking
execution unit, with one logical continuation and its *current* Task's
cancellation/ownership domain. The short `suspend` spelling remains a
contextual declaration/lambda alias, not a new launch kind.

A `task { ... }` / `nb task { ... }` / `nb { ... }` expression, by
contrast, launches **one** independently owned child `Task<R>`; the
body begins with its own suspension effect. `do task { ... }` discards
only the caller's handle, never the scope's ownership. Ordinary callers
may launch a Task without becoming `suspends`; only an actual `await`
or direct call to a `suspends` callable requires a suspension-enabled
caller. Awaiting `Task<R>` produces exactly `R` (including `void`).

```oreslang
suspends fnc load(): int { return await fetch_async(); }

fnc start(): Task<int> {
    return task {
        // The call may suspend this Task; it does not create another one.
        return load();
    };
}

suspends fnc consume(): int {
    let Task<int> child = start();
    return await child;
}
```

The source samples illustrate the intended **post-lowering** contract; full
Task source typing and execution remain disabled. A plain synchronous lambda
must not borrow a surrounding `suspends` function's effect, while a
`suspends` lambda explicitly carries that effect in its callable type.
The latter still fails closed because typed suspension-callable re-entry and
owned capture lowering are pending. `async task |...|` creates a Task on
invocation; `suspends |...|` does not. `async suspends` and
`suspends task { ... }` are invalid; they cannot silently create a
second scheduling layer or erase a cancellation boundary.

Cancellation of an owned Task cancels its suspended continuation and detaches
its pending `await` registration; cancelling a waiter on a shared producer
does NOT automatically cancel the producer. Only the Task's physical exit
barrier may authorize reclamation of its continuation frame and owned resources.
A `suspends` call must preserve the same cancellation cause across lexical
`defer`/`finally` cleanup and must not catch normal cancellation as an
ordinary `throw` or convert it to a successful result. Uninterruptible FFI
requires separate bounded-domain shutdown semantics.

Implementation gates: actor-affine TaskScope admission, typed suspends
callables and effect propagation across indirect calls, source-level
continuation lowering, exactly-once cleanup, cancellation wakeup and
generation fences, source-native/WASM parity, and actual exact-head tests.


## Audit: suspend-effect laundering and borrow containment (2026-10-18)

The same suspension effect must be preserved across **all** callable-value paths; merely checking direct `suspends fnc` calls is insufficient.

Hardened the Java type checker so a plain synchronous callback passed against an expected `Fnc<...>` contract, or invoked under `Mutex.with_lock`, cannot inherit an outer `async` / `suspends` declaration's permission. Save both `currentMaySuspend` and `currentAsyncCallable`, clear them for callback analysis, and restore them in `finally`. Existing direct `suspends` calls remain legal in genuinely suspendable callables and VM-owned root `main`.

Until effectful function-value signatures are implemented, **fail closed** on first-class `suspends` function references, static methods and their contextual typed callback coercions; they must not silently become ordinary `Fnc<...>`. Public module namespace shapes also omit a bare/raw function-valued member for a suspended function, while retaining its arity-qualified contract member for module conformance and leaving *direct qualified calls* unaffected. A future `SuspendsFnc<...>` type must explicitly carry and enforce the suspend effect across indirect calls before this restriction is relaxed.

The ownership checker now rejects live nested borrows (`Tuple<&T>`, `Result<&T,E>`, and other recursively parameterized borrow-bearing types) across any scheduler suspension, in addition to root-level borrow states, guard-held critical regions and outstanding mutable/immutable loans. Actor `self` keeps only its preexisting mailbox-affine exception; no user borrow or guard gains that privilege.

Added `SuspendsCallbackIsolationTest` regression coverage for typed and mutex callback effects, state restoration, borrow-bearing tuple suspension, global/static/module function value erasure and preservation of legal direct calls. Updated a legacy Mutex callback negative test to recognize either a guard-specific or a stronger synchronous-callback effect diagnostic. Conformance PR #105 adds corresponding parser-accepted / semantic-rejected fixtures.

**Test status:** structural source checks passed 16/16 and the new conformance-fixture checks passed 12/12. No JUnit/Maven/LLVM compiler execution is claimed. The source PR GitHub Actions jobs still failed with zero steps. Two explicitly source-free probes were rerun in private alternative carriers:
- `gha-indie-worker/gha-indie-worker-docs` [run 39059223685](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/39059223685), attempt 3: failed with **zero steps**.
- `3FA-app/threefa-monorepo` [run 38617654037](https://github.com/3FA-app/threefa-monorepo/actions/runs/38617654037), attempt 2: failed with **zero steps**.

Neither carrier established usable GitHub Actions minutes/runner capacity. **No private code was transferred across organizations.** If an authorized, private and funded carrier becomes available, mirror only the reviewed exact Git source tree/manifest and execute `mvn -B -ntp -Dtest=SuspendsCallbackIsolationTest,SuspendsTaskEffectContractTest,SuspendsContractLanguageTest,TaskOwnershipBoundaryTest,MutexLanguageTest test` at its recorded exact commit. Avoid secrets and ensure temporary refs are cleaned up.

The guest `task {}`, `async task` and first-class `suspends` lambda execution gates must remain closed pending real suspension-safe lowering, ownership and cancellation tests.
