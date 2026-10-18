# Oreslang lightweight Tasks: v0 draft frontend contract

**Status (2026-10-18):** Parser/AST shape added in this branch. **Runtime execution is deliberately disabled** until capture ownership, task scopes, cancellation, cleanup and scheduling are implemented. These samples specify desired semantics, not verified executable behavior.

## Surface syntax

```oreslang
do nb {
    let Data value = await fetchAsync();
    process(value);
}
continueWorking();

let Task<int> sum = nb {
    let int a = await fetchAAsync();
    return a + 2;
};
let int answer = await sum;

let Task<void> write = nb {
    await writeAsync();
};
await write;
```

- `do nb { ... }` is a statement with **no result**. It schedules a Task but discards the handle.
- `nb { ... }` is an expression producing `Task<T>`. Its result type comes from the body returns; `Task<void>` is valid.
- Bare `nb { ... }` statements are rejected: explicitly use `do nb` or bind the resulting handle.
- `await task` suspends only the current task until completion; `await` inside an `nb` block suspends the child task, not its caller.
- Neither form is an actor or mailbox. `Task<T>` is an owned capability for a scheduled, single-completion computation; `Future<T>` remains an eventual-result abstraction.
- The Task's body has its own return target. `return` in the block never returns from its enclosing function.

## Argument-bearing Task continuations via an immediately invoked lambda

Reuse the normal pipe lambda with an immediate call; **no special new `nb` parameter syntax** is needed.

```oreslang
let Task<int> task = (|int a, string b| -> nb {
    let int result = await computeAsync(a, b);
    return result;
})(5, "xyz");

// Do other work. This line does not await the task.
let int result = await task;
```

Exactly equivalent explicit-return variant:

```oreslang
let Task<int> task = (|int a, string b| -> {
    return nb {
        return await computeAsync(a, b);
    };
})(5, "xyz");
```

The outer ordinary lambda executes *synchronously* just long enough to evaluate its `nb` expression; it returns the **single `Task<int>` handle**. Arguments are evaluated once in normal left-to-right call order and bound lexically. Task creation must transfer/copy/share the lambda parameters into a heap-owned Task capture environment before the lambda frame exits. No borrowed stack address may escape. A `return` inside `nb` returns from the Task; `return nb { ... };` returns a Task handle from the lambda.

**Type contract:**

- `nb { return value; }` has type `Task<T>` (once compiler lowering is enabled).
- `(|T a| -> nb { return a; })(value)` returns `Task<T>` directly.
- `await (|T a| -> nb { return a; })(value)` returns `T` and intentionally suspends the caller; it is **not** fire-and-continue.
- `let Future<T> f = task.asFuture();` is the proposed explicit readout/observation conversion, with no extra task launch. Java host `OresTask<T>.asFuture()` is implemented in the separate runtime draft; source member/type support is still gated.
- `async |...| -> nb { ... }` now has a distinct `AsyncLambdaExpr` AST. Its intended type is `Future<Task<T>>`, **never automatically flattened**. Source type checking still rejects execution pending async-lambda lowering.
- `async task |...| -> { return expression; }` has a distinct `TaskLambdaExpr` AST. Its **entire body** runs as one Task when invoked; its intended result is `Task<T>`, not `Future<T>` or `Future<Task<T>>`. This is a *single* semantic scheduling primitive (the `async task` modifier), not two nested async layers.
- `async task |...| -> expression` is also accepted as an expression-bodied Task lambda with an implicit Task-local return. Its `return` (explicit or implicit) never unwinds the caller.
- The `task` modifier is contextual after `async`; bare `task |...|` is not enabled in this draft, preserving existing identifier compatibility. Named `async task fnc` declarations are a separate future extension.
- A lambda with a braced body needs an explicit `return nb { ... };`. It does not implicitly return its last expression.

Do not permit Task type inference, expected-`Future<T>` coercion, mutable references to owner-local arguments, actor-private state access, or unmatched move-only ownership until the normal ownership/type passes prove them. Invocation arity and argument type checking continue to use ordinary callable rules; `nb` does not bypass them.

## Isolation and ownership prerequisites

Task captures are explicit ownership transfers/copies/authorized shares (`rt take`, `rt copy`, `rt share`), not escaping stack borrows or implicit actor-state access. Actor handlers may create Tasks, but the Task cannot inherit an actor's state lock or mutate its private heap without an owned-message/capability boundary. Neither `do nb` nor `nb` may bypass tenant isolation, trusted/untrusted execution domains, code-version fences, or permission checks.

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
