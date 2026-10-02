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

## Program load, link, and initialization barrier

Program startup is deliberately split into phases:

1. **LOAD / LINKED** — parse every reachable source unit, resolve its import edges, and
   register every module/class/function declaration. No user callable runs in this phase.
2. **INITIALIZING** — after the complete reachable graph is linked, execute lifecycle
   `init` hooks exactly once.
3. **READY** — only after every required init hook finishes successfully may `main`
   or any normal externally-entered callable run.
4. **FAILED** — if an init hook exits with an unrecovered failure, startup is aborted.
   `main` is not entered and ordinary calls remain unavailable.

Within one parsed program, all module declarations are indexed before the first init hook.
That means an init hook may call a helper from a module declared later in the source.

`init` is startup-owned. Source code cannot invoke `init()` or `module.init()`
directly. This preserves the exactly-once lifecycle guarantee.

An init lifecycle callable must be synchronous and deterministic at the call boundary:

- zero parameters;
- no generic parameters;
- `void` result;
- not `async`;
- not `actor` / `actor isolate`.

Both `fnc init()` and `routine init()` are valid.

### Circular imports

Import cycles are legal. Project startup does **not** topologically execute modules while
discovering them. Instead, the compiler/runtime contract is:

```text
discover reachable graph
        ↓
load + parse + link ALL reachable units
        ↓
initialization barrier
        ↓
run init hooks
        ↓
READY / main
```

The incremental compiler exposes this contract as a `StartupPlan` with:

- `loadOrder`: the complete set of reachable source units that must be linked first;
- `initializationUnitOrder`: deterministic dependency-first initialization traversal;
- `initHooks`: concrete unit/module lifecycle hooks in execution order.

A dependency back-edge encountered during initialization planning denotes an already-linked
cycle member and is not an error. Thus `a.ores -> b.ores -> a.ores` is supported rather
than rejected like a Go import cycle. Dependencies outside a cycle initialize before their
importers. Members inside a cycle use deterministic traversal order after the entire cycle is
already linked, so no init hook observes an only-partially-loaded module declaration graph.

## Actor failure isolation

Each actor owns an independent mailbox lifecycle and fail-stop boundary. An uncaught guest panic
or recoverable runtime exception terminates only that actor cell. The actor thread never throws
its failure onto the spawning/main thread, and sibling actors continue running.

`ActorRef.join()` waits for termination but completes normally even when the actor failed.
Failure is observable through `failed()` / `failure()`; escalation is therefore explicit
supervisor policy rather than an ambient exception leak. The current actor thread is recorded on
its ref so `self.join()` is rejected immediately; an actor can never deadlock by waiting for its
own termination.

Each source actor turn receives an implicit `self` handle in addition to its one mailbox-message
parameter. A mailbox delivery creates a fresh callable frame; captured actor-owned state persists,
while defer/recovery stacks are strictly per-turn.

Explicit `stop()` is graceful and ordered:

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

## Panic and recovery frames

Each callable invocation owns a recovery stack in addition to its defer stack.

`recover expression` evaluates `expression` immediately. The result must be an arity-1
callable and is retained on that callable's recovery frame. A normal callable return discards
unused recover handlers.

When a recoverable failure escapes the callable:

1. all deferred actions are drained first in LIFO order;
2. body/defer failures are combined using the existing primary/suppressed policy;
3. recover handlers are invoked LIFO with the pending failure value;
4. a handler that returns normally consumes the failure and supplies the callable result;
5. a handler that panics/throws replaces the pending failure and unwind continues to the next
   older recover handler;
6. if no recover handler consumes it, the failure propagates to the caller.

For `panic value`, handlers and guest `catch` blocks observe the panic payload rather than the
internal Java control object. Ordinary runtime exceptions are passed as exception values.

Scheduler/runtime cancellation is deliberately non-recoverable. Defers still run during
cancellation unwind, but neither guest `recover` nor lexical `catch` can swallow cancellation
and prevent actor/runtime shutdown.

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
