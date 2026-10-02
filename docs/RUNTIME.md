# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
9. `self` cannot be rebound.

## Deployment matrix

| Profile | Host | Guest execution | Hot reload |
| --- | --- | --- | --- |
| JIT | JVM/GraalVM | interpreter -> Truffle JIT | fresh source generation |
| AOT | Native Image | precompiled interpreter | fresh source generation, no executable-code load |
| HYBRID | Native Image | interpreter -> guest JIT where supported | fresh source generation |

iOS is treated as AOT-only by the execution-profile validator. Android may use AOT or another profile where platform policy allows it.

## Why hot reload is source/IR based

Native Image is fundamentally closed-world for Java classes. Oreslang therefore does not make hot reload depend on dynamically linking new Java/native code. The runtime/interpreter is part of the shipped artifact; newly downloaded Oreslang source (and later a stable serialized Ores IR) is treated as untrusted data, validated, then executed in a new generation.

That makes the mechanism consistent across Windows, macOS, Linux, Android, and AOT-only targets. Platform-specific native dynamic linking can remain an optional trusted-host optimization, never a semantic dependency.

## Capability ownership

Capabilities belong to a launch policy, not to source code. Source may eventually declare required capabilities for diagnostics, but declarations will never grant them.

The strict production direction is:
- parent supervisor owns maximum authority;
- child isolate/actor receives an equal-or-smaller capability set;
- no child may escalate its own policy;
- cross-actor values must pass sendability/freezing rules;
- hot-loaded code gets a new generation and new policy admission.

## Actor failure isolation

Each actor owns an independent mailbox lifecycle and fail-stop boundary. An uncaught guest panic
or recoverable runtime exception terminates only that actor cell. The actor thread never throws
its failure onto the spawning/main thread, and sibling actors continue running.

`ActorRef.join()` waits for termination but completes normally even when the actor failed.
Failure is observable through `failed()` / `failure()` and the language-level `failure_trace`; escalation is therefore explicit
supervisor policy rather than an ambient exception leak. The current actor thread is recorded on
its ref so `self.join()` is rejected immediately; an actor can never deadlock by waiting for its
own termination.

Each source actor turn receives an implicit `self` handle in addition to its one mailbox-message
parameter. A mailbox delivery creates a fresh callable frame; captured actor-owned state persists,
while defer/recovery stacks are strictly per-turn.

`stop;` and `ActorRef.stop()` intentionally have different semantics.

The language statement `stop;` is structured actor-tree termination. The current actor
immediately stops accepting new messages, recursively requests graceful/FIFO shutdown of
its descendants, prevents stopping descendants from spawning new children, runs lexical
`finally` and callable defers, bypasses guest catch/recover, waits for descendants to
terminate, and never marks the current actor failed. The current actor abandons its own
queued messages; child actors drain already accepted work.

Outside a statically known actor body, `stop;` is admitted only in a branch guarded by
`actor.in_actor()` / `actor.inActor()`. Runtime admission checks the current actor
execution again, so root/supervisor code cannot stop itself accidentally.

Explicit `ActorRef.stop()` / `self.stop()` is graceful and ordered:

1. messages accepted before stop remain FIFO;
2. the actor stops accepting new messages atomically with the stop request;
3. queued messages drain before termination;
4. runtime/context shutdown has a separate force-stop path for cancellation.

This prevents the common race where a message accepted after a stop sentinel would otherwise be
silently stranded.

The current Java backend has two actor kinds:

- `SHARED`: actor-owned mutable captures are allowed under ownership/move rules;
- `ISOLATE`: mutable, borrowed, and move-only outer captures are rejected; state must cross the
  mailbox boundary.

Both kinds already have independent failure containment. The Java interpreter still lives in one
JVM process; `ISOLATE` is also the backend contract for mapping that actor to a stronger
Graal/native isolate. The interpreter does not claim physical address-space separation when the
host has not supplied an isolate backend.

## Throw, panic, raise, and recovery frames

The runtime keeps distinct guest failure channels:

- `throw value` is catch-oriented. Lexical `try/catch` may consume it; callable `recover` does not.
- `panic value` is recover-oriented. Callable `recover` may consume it; lexical `try/catch` does not.
- `raise;` re-propagates the exact failure currently handled by catch/recover and preserves its logical trace.

Each callable invocation owns a recovery stack in addition to its defer stack.

`recover expression` evaluates `expression` immediately. The result must be an arity-1
callable and is retained on that callable's recovery frame. A normal callable return discards
unused recover handlers.

When a recoverable failure escapes the callable:

1. all deferred actions are drained first in LIFO order;
2. body/defer failures are combined using the existing primary/suppressed policy;
3. recover handlers are invoked LIFO with the pending failure value;
4. a handler that returns normally consumes the failure and supplies the callable result;
5. a handler that creates a new panic replaces the pending panic and unwind continues to the next
   older recover handler;
6. `raise;` preserves the pending failure unchanged;
7. if no recover handler consumes a panic/runtime failure, it propagates to the caller.

`throw` bypasses this recovery frame entirely. Guest catch/recover handlers observe the guest
payload for explicit throw/panic signals rather than internal Java control objects. Ordinary
runtime exceptions are passed as exception values.

Scheduler/runtime cancellation and actor-stop control are deliberately non-recoverable.
Defers still run during unwind, but neither guest `recover` nor lexical `catch` can swallow
those control signals and prevent actor/runtime shutdown.

## Logical stack traces

Oreslang diagnostics use a logical call trace rather than treating the JVM carrier-thread
stack as language truth. Frames record the Ores callable/boundary kind, callable name,
current actor id when present, and carrier thread for debugging.

Actor sends capture the sender logical context into the mailbox envelope. The receiving actor
installs that context before executing the turn, then adds its own actor/callable frames. This
preserves causality across actor and JVM-thread boundaries and puts actor ids directly in
failure traces.

`OresTrace.captureBoundary(...)` / `install(...)` and the provided wrapper helpers are the
runtime convergence point for async tasks, virtual threads, CompletionStage callbacks, and
event-loop dispatchers. Async lowering must capture before scheduling and restore before
resuming guest code; it must not reconstruct traces from Java thread stacks after the fact.

`@NoStackTrace` on a callable suppresses that callable's logical frame in normal mode. Host
configuration controls the policy with `ORES_STACK_TRACE`:

- `auto` (default): respect `@NoStackTrace`;
- `full` / `on`: force logical frames even for annotated callables;
- `off` / `none`: disable logical stack-trace capture process-wide.

This variable is host/runtime configuration and does not grant guest environment access.

## Receiver implementation

Method code is stored once per class declaration. Direct calls dispatch to that definition with the receiver as an implicit immutable argument. Only first-class method extraction allocates a bound method pair. This provides Go-like receiver safety without allocating a closure for every instance or every direct method invocation.

## Defer frames

Each callable invocation owns a private LIFO defer frame. Lexical blocks reuse the
current callable frame; they never create or drain a defer stack.

At a `defer expression` statement, the runtime evaluates `expression` immediately.
The resulting value must be callable and is moved into the frame. At callable exit,
the frame invokes each registered callable with zero arguments in LIFO order. Moving the
callable into the frame means a named move-only closure cannot also be used afterward.

All registered actions are attempted even if one fails. A body failure remains the
primary failure and defer failures are suppressed onto it. With no body failure, the
first defer failure in LIFO execution order is primary and subsequent failures are
suppressed.
