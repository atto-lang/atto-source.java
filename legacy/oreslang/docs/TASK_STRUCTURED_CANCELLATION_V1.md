# Task cancellation and structured ownership — v1 proposal

Status: staged implementation; cancellation-tree primitive only. **The proposed source syntax and scheduler wiring do not work yet.**

## Concepts

- `Task<T>`: independently scheduled continuation in an actor/root execution domain, not an actor, mailbox or isolate.
- `Future<T>`: completion observation; not necessarily a spawned Task or ownership authority.
- `TaskScope`: lifetime owner of child tasks, associated with a parent task, actor or CONTROL/root scope.
- `TaskCancellation`: host/runtime cancellation-request authority. A request is NOT physical task teardown.
- Cancellation link: directional propagation, no lifetime ownership transfer.
- Monitor: completion/failure observation, no termination authority.

## Proposed syntax

```ores
let Task<int> a = task { return compute(); };
let Task<int> b = nb task { return compute(); };
do task { await publish_async(); }
let Task<int> c = nb { return compute(); };
let int value = await a;
let TaskOutcome<int> outcome = await b.get_done();
```

All forms remain scoped, supervised and cancellable. `do task` discards only the handle, never the task owner's cleanup or failure responsibility. `nb` does not detach, allocate a new thread, or relax actor isolation. `nb task` is reserved for an explicitly nonparking effect contract once checker/lowering is implemented; until then the syntax must not be admitted as a working feature.

`task { nb { ... } }` creates a nested Task and does not flatten Task<Task<T>> silently. The earlier `async task |args| -> nb { ... }` form is a single async task-body expression; callers still await exactly once to get T.

## Cancellation and ownership target

```ores
let Task<int> job = task { return expensive(); };
job.cancel(#no_longer_needed);    // initiation only, idempotent
let TaskOutcome<int> result = await job.get_done(); // final cleanup/physical quiescence
```

`TaskOutcome<T>`: Success(T), Failed(Error), Panicked(Error), Cancelled(CancelReason). A parent owns its descendants unless they are atomically adopted into another authorized scope. Child cancellation does not cancel parent/siblings by default; child failure is surfaced to the scope or supervisor. Dropping a handle does not detach execution; a detached lifetime is an explicit adoption into a longer-lived supervisor.

A normal scope exit joins unfinished children; cancelled scope exit cancels and joins them. Exactly one terminal outcome wins, and `get_done` must not settle before scheduler-confirmed quiescence. Existing `OresFuture.cancel()` alone does not guarantee teardown for a running carrier. Cancelled awaits must unlink dependency registrations, channels/select must obey exactly-once arbitration, and the user Task handle must not grant lateral actor termination rights.

**Cancellation links** are directional additional edges; `unlink()` removes future propagation only. Ownership transfer is `adopt()`, requiring both old and new scope authorization and cycle prevention. Erlang-like bidirectional failure links remain a separate facility. Ordinary cancellation is not a panic.

Compiler checkpoint insertion can improve responsiveness in source loops only at proven safe points. Arbitrary FFI/native blocking calls cannot be forcibly interrupted reliably. Truffle/JVM carrier threads must not be abruptly killed.

## Stage 1 implementation in this PR

`src/main/java/dev/oreslang/runtime/TaskCancellation.java` introduces:
- A runtime-only single-owner cancellation tree with first-reason-wins.
- Atomic subtree cancellation flags before any runtime callbacks.
- Revoker hooks before observation hooks, outside graph lock.
- Idempotent cancellation, detachable callback subscription, one-way cancellation links.
- Atomic ownership transfer and cycle/reparent rejection.
- Tests for propagation, detached links, callback order, cancelled-child inheritance, transfer, and transfer-versus-cancel races.

The class **does not** yet create tasks, hook into `OresScheduler.TaskRunner`, implement `get_done`, or expose source-language `Task<T>`. Hooks must remain trusted, nonblocking host callbacks. The global graph lock is a correctness-first v1 mechanism and should be evaluated for multi-tenant scalability before widespread use.

## Acceptance gates for language release

1. AST/lexer/parser + static ownership/effect checks for task, nb task, do task and nb blocks.
2. Bind runtime token revocation to scheduler cancellation, with safe initial enqueue and quiescence-aware TaskHandle/TaskScope.
3. Actor/root cancellation lineage, capability checks, failure-supervisor policy, no orphaned tasks.
4. Await/select/channel/async I/O cleanup; cancellation-vs-completion and shutdown races.
5. Explicit tests for FFI, CPU-only loops, nested task scope, owner adoption, hot reload and no double terminal notification.
6. Java/Graal, LLVM and shared source conformance run against actual compiler backends, not synthetic-only models.

## Compatibility note

`docs/channels-select-and-actor-cancellation.md` currently says only actors are independently concurrent. This proposal supersedes that statement **for independently scheduled Task work** after language integration, not for actor identity, isolation, mailboxes, or supervision. Until implementation lands, retain existing source-language admission behavior.
