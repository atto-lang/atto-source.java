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
