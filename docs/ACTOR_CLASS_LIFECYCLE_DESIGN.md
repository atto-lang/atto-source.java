# Actor class lifecycle and parent-facing output streams (design + implementation status)

**Status (2026-10-06): actor declarations plus `spawn Actor()`/`ActorRef<Actor>` static semantics, lexical-boundary enforcement, host-side actor ready/done Futures, and shared-code image registry; source-level spawn execution/output streaming are NOT lowered yet.**

The existing `ActorRuntime` already has `ActorRef.send/stop/cancel/forceCancel`,
bounded channel-backed mailboxes, actor identity, child supervision, and
`rt cooperate` continuation support. The Java `ActorMail<Out>` record is
**already used for ActorGroup Mailman outbox messages**. Do not silently
repurpose it as a class actor's inbound message.

## Target `main.ores` (proposed source API, not runnable yet)

```ores
// The compiler must eventually lower this class and its lifecycle hooks.
// This is a contract example and must NOT be treated as an executable fixture.
define actor Worker as
  on_start(): void {
    // Actor-local startup hook; actor constructors stay forbidden.
    return;
  }

  receive(ActorMail<String> mail): void {
    if mail.value != "start"; do
      return;
    fi

    self.send("hello");
    rt cooperate;
    self.send("world");
    rt cooperate;
    self.send("!");
    self.end();
    return;
  }
end

pub async routine main(): void {
  val workers = [spawn Worker(), spawn Worker(), spawn Worker()];
  await Future.all([workers[0].ready, workers[1].ready, workers[2].ready]);

  // Creating an actor does not automatically send a receive() message.
  for const worker of workers do
    worker.send("start");
  done

  // A parent may subscribe/merge all three bounded output streams. Each
  // worker's per-stream order is hello, world, !; cross-worker order is free.
  val streams = [workers[0].outputs, workers[1].outputs, workers[2].outputs];
  for await const output of merge(streams) do
    stdio.println(output.actor_id, output.sequence, output.value);
  done

  await Future.all([workers[0].done, workers[1].done, workers[2].done]);
  return;
}
```

This illustration uses proposed `spawn`, `ActorMail`, `outputs`, `ready`,
`done`, `merge`, and `for await` integrations. Some unrelated
syntax/runtime pieces may also need work before it can compile. In particular,
`return await w.ready` returns a readiness result, **not** `w` or a list
of output strings.

## Canonical API split

| Surface | Signature (conceptual) | Contract |
| --- | --- | --- |
| `spawn Worker()` | `ActorRef<Worker>` | Allocates actor identity; reserved on_start hook runs in actor execution context, not the caller |
| `w.id`, `w.kind` | `ActorId`, `ActorKind` | Immutable identity/diagnostics |
| `w.ready` | `Future<void>` | Completes once actor-local field initialization and reserved `on_start()` succeed; fails if startup fails/stops |
| `w.send(mail)` | `void` or explicit nonblocking variant | **Inbound**, serialized, bounded actor mailbox; reject capability/pointer/function payloads |
| `w.outputs` | `AsyncIterator<ActorOutput<T>>` or read-only `Channel<ActorOutput<T>>` | **Outbound**, bounded, async/backpressure-capable, parent-observable stream |
| `w.done` | `Future<void>` | Settles after actor finalization and isolate/carrier cleanup |
| `w.stop()`, `w.cancel()` | `void`, `bool` | Graceful stop vs structured cancellation (supervisor authority checks apply) |
| `w.kill()` | `bool` | Force-isolated cancellation only if revoker is configured; never thread interruption masquerading as isolation |
| `self.send(value)` | `Future<void>` or nonblocking admission acknowledgement | **Outbound** from actor to its output stream; NOT recursive delivery to own inbox |
| `self.end()` | `void` | Non-blocking graceful stop request; seals/drains the inbox immediately so no later `receive()` starts; current turn may finish, then finalization closes outputs after admitted values drain |
| `self.endWithCleanup(|| -> { ... })` | `void` | Same immediate inbox fence as `self.end()`, plus one synchronous zero-arg final cleanup action after the current turn unwinds and before outputs close / `done` settles |
| `receive(ActorMail<T>)` | `void` | Lifecycle handler invoked once per incoming envelope, serialized and actor-local |
| `rt cooperate` | `void` scheduling effect | Suspension/safepoint; no implicit publication or message delivery |

`self.send(value)`, `self.end()`, and `self.endWithCleanup(...)` are **non-overridable runtime actor operations**. Actor classes may not declare instance methods named `send`, `end`, or `endWithCleanup`, and source code does not use `super.send(...)` to reach transport.
This keeps quota checks, serialization, termination fences, and output-stream
ordering outside user-overridable dispatch.

`self.end()` is intentionally **not overloaded**. Cleanup uses the separate
`self.endWithCleanup(...)` name:

```ores
self.endWithCleanup(|| -> {
  // final synchronous actor-local cleanup
});
```

The end request itself does not block. As soon as either end API is called, the
actor mailbox is sealed and queued-but-not-started mail is discarded; no later
`receive()` invocation may begin. Code already executing in the current
`receive()` may continue until that turn returns. For `endWithCleanup`, the
cleanup then runs exactly once as the final guest action while actor-local state
is still available. Cleanup cannot be async/suspending; if it throws, `done`
rejects with the cleanup failure.

## Message identities and transport

- Source `ActorMail<T>` is now statically modeled with immutable
  `recipient: ActorId`, `sender: Option<ActorId>`, `sequence: int`, and
  `value: T`. Runtime delivery uses `ActorRuntime.ActorInboxMail<T>`, which
  is deliberately distinct from the existing
  `dev.oreslang.runtime.ActorMail` Group Mailman record.
- Introduce `ActorOutput<T>(actor_id, sequence, value)` for output. The
  output sender is the *emitting* actor, not its parent.
- Send values through the same strict graph walker/serializer/copy rules
  used by mailbox admission. Function closures, raw object pointers,
  borrowed/transient aliases, mutable cross-actor references, and live
  domain-local channels are not permitted on actor mailboxes/output.
- Backpressure must never block an actor carrier or retain unbounded
  `Future`/waiter queues. Define finite pending writer/read quotas,
  cancellation behavior, buffer accounting, and terminal EOF/error results.
- Output readers must not gain permission to read actor-owned heap objects.
  Freezing or copy-across-isolation is mandatory.
- Per actor/mailbox and per output stream ordering is FIFO. Ordering *between*
  workers is nondeterministic. A merged stream must preserve each producer's
  sequence, not promise a global order.
- Parent cancellation/failure should terminate structured children and close
  output streams. `done` observes the existing carrier-exit finalization
  barrier rather than just a return from `receive`.

## Lexical scope / actor isolation boundary

Ordinary modules and ordinary classes retain normal source-unit lexical
visibility. They may refer to sibling functions, modules, classes, interfaces,
and type aliases in the same file without artificial imports.

Actor classes are the exception. A define actor, shared actor, isoactor, or
untrusted actor is a hard executable dependency boundary. Actor fields,
signatures, methods, lifecycle hooks, and lambdas may resolve only:

- actor-owned fields/methods and inherited actor members;
- explicit imports from another source unit;
- language/compiler/runtime intrinsics admitted by the actor's capability policy;
- parameters and locals introduced inside the actor.

An actor cannot reach a sibling file-level helper, module, class, actor, interface,
or type alias merely because it appears in the same source file. Move reusable
actor dependencies to an importable source unit and import them explicitly.
Same-file import escape hatches such as @self, ., or normalized aliases that
resolve back to the current physical file are rejected.

The type checker and evaluator enforce the same boundary, and declaration origin
survives async/nlex/lambda lowering so closures cannot launder ambient file
access. Capability checks remain a separate layer: importing code never grants a
private or untrusted actor authority that its execution policy forbids.

## Implementation sequencing / acceptance gates

1. **This branch:** parse `define actor ... as ... end`; enforce actor-only
   lexical dependency boundaries while preserving ordinary same-file class/module
   visibility; reject self-import escape hatches; and add regression tests.
2. **Parser/type checker now recognize `spawn Worker()`** as a distinct intrinsic and type it as `ActorRef<Worker>`; `new Worker()` and constructor-style spawn arguments remain rejected. `ready`/`done`, mailbox `send`, actor-local `self.send`/`self.end`, and the reserved non-public `on_start(): void` shape are statically checked. **Execution lowering is still pending**; a startup hook must execute on the actor's own execution domain before `ready` settles.
3. **Host substrate implemented in this branch:** `ActorRef.ready()` and
   `ActorRef.done()` expose runtime-only completion Futures. `ready()` schedules
   actor initialization even with no first message; stop-before-start and
   initializer failure settle the Future exceptionally. Before source exposure,
   still verify force-cancel and parent/child termination races with CI.
4. Implement inbox dispatch and bounded parent outbox with serialization,
   per-worker sequence counters, EOF and failure forwarding; lift `self.send`
   into asynchronous nonblocking flow control.
5. Expose parent-side `outputs` and async-generator/channel adapters;
   interoperate with `select`/`nb select`, `Future.all`, and stream merge.
6. Test actual source-level `main.ores` end-to-end with 3+ workers,
   `rt cooperate` interleavings, output order, overflow/backpressure,
   cancellation, actor startup/on_start failures, object/pointer/closure rejection,
   private/shared/untrusted policies, and module/import isolation.
7. Only after green tests for the *exact* head SHA consider merging. If the
   source org cannot allocate a GitHub Actions runner, mirror the **exact Git
   tree** into a funded test org's temporary branch, verify blob/tree identity,
   and run Actions locally to that repo without cross-org secrets.

The source-level `async || -> { ... }` lambda spelling is the required slim-arrow
form. Parser and runtime support for async RHS lambdas and the full worker-stream
example are separate gates; do not silently substitute fat arrows.

```ores
// Proposed syntax; not executable until async RHS lambda/spawn lowering.
const create = async || -> {
  const worker = spawn Worker();
  await worker.ready;
  return worker;
};
```

Returning `await worker.ready` would return the readiness result (void), not the
actor reference. For a runnable acceptance fixture target, see
`docs/proposals/actor-worker-streaming/main.ores` (design-only today).

`SharedCodeImageStore` and `OresContext` now hold one immutable `Ast.Program`
reference per linked source unit for the same OresVM, and `Evaluator.link()`
publishes it once. Existing linked-import lookup returns the shared evaluator.
This is a real same-process JVM code-image reference invariant, not proof that
Graal native/JIT machine-code pages are shared among independent Truffle engines
or OS processes. Actor state and mailbox payloads stay outside the code store. Static-select
fairness is also actor-local: the shared AST supplies only the immutable select
site identity, while each ActorCell owns its mutable cursor. This is the model
to follow for any future inline cache or scheduler metadata—shared code may
reference actor-local runtime state, but must not become a writable state bag
shared by isolated actors.

Do not mark this milestone as executing source-level `spawn Worker()` or streamed worker output. The source parser/type checker now admit and validate `spawn Worker()`, `w.ready`/`w.done`, typed `ActorMail<T>`/`receive`, `ActorRef.send`, `self.send`, and `self.end`, but evaluator lowering into `ActorRuntime` remains a separate acceptance gate.
