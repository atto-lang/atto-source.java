# Consistent actor construction, per-instance concurrency and spawn syntax

Status: **partially implemented (immediate-spawn syntax), otherwise a
language proposal / acceptance plan (2026-10-09)**. Java/Graal source
supports `spawn Worker()`, `spawn new Worker()` and
`spawn (new Worker())` for actors without constructor arguments.
All three forms compile to the same internal spawn operation. Oreslang *ordinary classes* already use `new Class(...)` and
`constructor(...)`, but actor constructors, `new Actor(...)`,
`ActorSpec.set_concurrency`, and `spawn spec` **are not implemented**.
LLVM has no matching source implementation either.

## Decision: an actor is a specialized class declaration, not an ordinary instance

An actor declaration participates in the *class declaration/constructor*
syntax family but never exposes a caller-owned instance of actor state.
For regular classes, `new C(args)` constructs a normal `C` immediately.
For actors, `new Worker(args)` creates an **inert**, linear
`ActorSpec<Worker>` (constructor arguments plus spawn options). There is
**no actor execution, actor ID, mailbox or actor heap** until `spawn`.

An actor's `constructor(...)`, when eventually supported, executes
**inside the newly spawned actor** in its isolation domain, before
`on_start()` and before `ready` resolves. It cannot run under the
caller as a side effect of building the spec. It must be synchronous
(no `await`, external network calls or actor reentrancy); async setup
belongs in `on_start()`. Its parameters are copied/frozen/moved under
actor-boundary policy at spawn and runtime-enforced no-live-borrow rules.
An actor without an explicit constructor retains zero-argument spawn
and actor-owned field initializers. Constructors do not grant a
capability or bypass isolation/actor lexical dependency restrictions.

## Proposed code

```ores
define actor Worker as
  let int initial_count = 0;

  constructor(int initial_count) {
    self.initial_count = initial_count;
  }

  on_start(): void {
    // Runs after actor-side constructor under actor ownership.
  }

  receive(ActorMail<string> message): void {
    // ...
  }
end

pub routine main(): void {
  // A spec, NOT an actor instance. No actor code has executed yet.
  val w = new Worker(42);
  w.set_concurrency(6);             // Regular actors: ONE parameter.
  val spawned = spawn w;            // Consumes w, returns ActorRef<Worker>.
  // spawned.send("hello");         // Send after ready if required.

  // Concise canonical equivalent; existing syntax remains valid:
  val other = spawn Worker();
}
```

Regular `actor` and `isoactor`: `spec.set_concurrency(messages)`.
HTTP/TCP/WS actor specs: `spec.set_concurrency(connections,
messages_per_connection)` **only**. Wrong arity or protocol mismatches are
type errors, not silently ignored. This avoids inventing a third
network-actor control. The one-writer actor guest-code execution lease
is fixed and never a setter parameter.

```ores
define http actor HttpWorker as
  receive_http(HttpMessage message): void {
    val response = message.get_response();
    // ...
  }
end

pub routine http_main(): void {
  val spec = new HttpWorker();
  spec.set_concurrency(128, 8);      // Up to 128 sessions, 8 in-flight
                                     // handler activations per session.
  val running = spawn spec;          // ActorRef<HttpWorker>.
}
```

All examples except existing `spawn Worker()` are **unimplemented syntax**.
Network connection identity and handler limits must be enforced by the
transport broker; an HTTP request/stream ID is **not** a connection ID.

## Binding mutability and alternative builder

Oreslang defines `const` as a fixed, **readonly referent**; calling a
mutating setter on `const w` must be rejected. Use `val` (fixed,
mutable referent) for the explicitly mutating configuration form:

```ores
val w = new Worker();
w.set_concurrency(6);
val worker = spawn w;
```

For a readonly binding, offer a **functional consuming** builder rather
than weakening `const` semantics:

```ores
const w = new Worker().with_concurrency(6);
val worker = spawn w;
```

`with_concurrency(...)` consumes its original temporary/spec and returns
a **new** `ActorSpec<Worker>` value; it does not mutate the receiver and
does not duplicate one-shot spawn authority. `set_concurrency(...)` returns
`void` and mutates only an unspawned, uniquely owned spec. Both are
runtime builtins on `ActorSpec`, not overridable actor instance methods
or arbitrary user-defined `Worker.set_concurrency`. This distinction
prevents actor guest code from setting host admission policy.

## Spawn rules, syntax and compatibility

| Form | Proposed result | Notes |
| --- | --- | --- |
| `new Class(x)` | `Class` | Existing eager ordinary class constructor, unchanged |
| `new Worker(x)` | `ActorSpec<Worker>` | New: inert, constructor args validated and stored |
| `spawn spec` | `ActorRef<Worker>` | New: atomically consume a compatible actor spec |
| `spawn Worker()` | `ActorRef<Worker>` | **Executable** zero-argument shorthand |
| `spawn new Worker()` | `ActorRef<Worker>` | **Executable** zero-argument alias |
| `spawn (new Worker())` | `ActorRef<Worker>` | **Executable** zero-argument alias |
| `spawn Worker(x)` | `ActorRef<Worker>` | Proposed shorthand when actor constructor exists |
| `spawn new Worker(x)` | `ActorRef<Worker>` | Syntax accepted but constructor args are **currently rejected** |
| `spawn spec` twice | error | One-shot spec; make another for a second actor |
| `spec.set_concurrency(...)` after spawn | error | Revoked/consumed spec |
| `running.set_concurrency(...)` | error | ActorRef cannot mutate running admission synchronously |

`spawn Worker(args)` should lower to `spawn (new Worker(args))` without
breaking the existing parser's accepted `spawn Worker()` behavior. The
parser needs a distinct operand path for `spawn identifier` (a typed
`ActorSpec`) versus `spawn TypeName(args)` (type construction), including
correct handling of imports and qualified names. Do not resolve names by
heuristically treating any expression as a class.

`ActorSpec` must be a linear/affine, domain-local value: it must not
be copied, serialized into actor messages, persisted, or sent to
untrusted code. It can be moved once through `spawn`. Aliasing or
sharing across tasks must not permit configuration races or double-spawn.
A consumed spec is dead regardless of success or failure of the spawn
transaction; a failed spawn cannot leak actor state, IDs, or capacity.
Constructor argument snapshots are validated by a sendability/type
checker and by runtime admission at the isolation boundary.

## Configuration defaults, limits, and dynamic behavior

`@concurrency(messages = N)` and
`@concurrency(connections = C, messages_per_connection = M)` on an actor
**declaration** establish default policy values only. `set_concurrency`
or `with_concurrency` on an `ActorSpec` **override per-instance
defaults before spawn**. If no values are supplied, use runtime-defined
defaults, currently unspecified (do not accidentally assume 1 vs N
as a deployed promise). Every spawn must validate the effective
policy against supervisor/tenant/process ceilings; invalid,
unsupported, overflowed or over-budget policies fail closed without
silently reducing limits or upgrading capabilities.

Changing a **running** actor's concurrency is a distinct future
supervisor operation and is **not** `ActorRef.set_concurrency`.
If required, use a future capability-gated asynchronous
`reconfigure_concurrency` operation with versioned CAS, pending
admission pause, drain/deny behavior when lowering existing limits,
and atomic broker agreement for connection/per-session quotas. An
actor-side constructor cannot set connection limits: it executes
*after* the spawn reservation must have been validated.

"In flight" counts an admitted logical receive-handler invocation
until completion, cancellation, or actor/connection close, including
await suspension. It does not imply parallel guest execution. The
actor-wide execution lease remains one even when message concurrency
is greater than one. The existing source scheduler serializes
suspended handlers; configurations that promise logical interleaving
above existing support must be explicitly feature-gated rather than
claiming the new capability is already delivered.

## Immediately implemented grammar and its boundaries

The Java/Graal parser accepts these zero-argument source spellings:
`spawn Worker()`, `spawn new Worker()`, and
`spawn (new Worker())`. Their ASTs compare equal and use the existing
`RuntimeCallExpr("spawn", [NewExpr(Worker, [])])` lowering. Existing
typechecking and runtime actor ownership remain identical; non-actor
targets and constructor/startup parameters still fail closed. The
regression suite `ActorSpawnNewSyntaxTest` covers AST equality,
typechecking, startup/ready/mailbox/done behavior, invalid nesting,
non-actor rejection and unsupported actor constructor arguments.

This does **not** implement `new Worker()` by itself: outside an
immediate `spawn`, actor construction remains prohibited until
`ActorSpec`, its ownership typing and isolation enforcement exist.

## Implementation and acceptance gates

1. Parser/AST: add actor constructor declarations, actor-typed `new`
   specs, and `spawn expr`, preserving `spawn Actor()` grammar.
   Preserve source compatibility for actor field initializers and
   `on_start()`.
2. Static checking: distinguish `Class`, `ActorSpec<A>` and
   `ActorRef<A>`; enforce constructor arity/visibility, typed
   `set_concurrency` overloads, `const`/mutable receiver rules,
   one-shot moves, and no actor spec escape or actor-state access
   from the caller.
3. Runtime: allocate no actor/heap on `new Actor()`. On spawn,
   reserve validated capacity and isolation budget transactionally,
   copy/move arguments, allocate the actor, initialize fields,
   run constructor and `on_start` on the actor lease, and publish
   `ready` only after those succeed. On failure revoke all permits.
4. Scheduler/broker: wire `ActorIngressConcurrency` through typed
   per-connection ingress; release permits on every terminal path,
   including constructor failure and actor cancellation. Implement
   suspended receive interleaving before promising >1.
5. Tests (both backends separately): parse and typecheck all forms;
   reject `const spec.set_concurrency`, network setter with one
   parameter, stale/double spawn, escaped spec, bad constructor
   args, actor-method calls on a spec, post-spawn mutation, mixed
   declaration/instance limits, and quota escalation. Validate that
   constructor runs once and only in actor context; actor `ready`
   waits for startup, and no actor identity exists during spec
   configuration. Stress concurrent spec consumption and admission
   atomicity.

The HIR/MIR contract belongs in `oreslang-common-source`; Java/Graal
and LLVM remain independent implementations until they converge on
that contract.
