# Persistent actor classes, generated factories, groups, and mailmen

Status: hardened design contract.

## 1. Keep three concepts separate

Oreslang has three related but distinct actor constructs:

1. **persistent actor classes** — long-lived state plus a typed public protocol;
2. **actor fnc/routine callables** — one-shot scheduled jobs;
3. **runtime/generated factories** — compiler/host construction descriptors used
   by supervisors, groups, catalogs, and hot loading.

A source `actor fnc` is not the hidden constructor ABI for a persistent actor
class.

## 2. Persistent actor classes

```ores
define actor Counter as
  let int value = 0;

  constructor(initial: int) {
    self.value = initial;
  }

  pub add(delta: int): void {
    self.value = self.value + delta;
    return;
  }

  pub current(): int {
    return self.value;
  }

  private normalized(delta: int): int {
    return delta;
  }
end
```

The public instance methods are the typed source protocol. Private methods are
direct self-calls while the actor owns its execution lease.

There is exactly one runtime mailbox. The compiler lowers the effective public
protocol to a hidden closed dispatcher/message ABI such as:

```text
Add(int, Reply<void>)
Current(Reply<int>)
```

The precise hidden representation is compiler/runtime-private.

A source method named `receive` is ordinary protocol syntax if declared; it
has no privileged runtime meaning.

## 3. ActorRef projection

Outside code receives `ActorRef<Counter>` or a compatible
`ActorRef<Interface>`:

```ores
val counter = spawn Counter(40);
await counter.add(2);
val answer = await counter.current();
```

Conceptually:

```text
Counter.add(int): void
ActorRef<Counter>.add(int): Future<void>

Counter.current(): int
ActorRef<Counter>.current(): Future<int>
```

Raw `ActorRef.send`, `receive`, and mailbox objects are runtime-private for
persistent actor classes. Protocol methods are not reified as bound callback
objects; write an explicit closure when callback capture is intended.

## 4. Protocol inheritance and interfaces

Actor inheritance must preserve actor execution/isolation kind.

Inherited public endpoints are part of the child protocol. A child may add
public endpoints, but may not narrow an inherited public endpoint to private.

Actors may implement interfaces and callers may narrow a concrete reference:

```ores
define interface CounterAPI
  fnc add(delta: int): void;
  fnc current(): int;
end

val ActorRef<CounterAPI> counter = spawn Counter(0);
```

This is the preferred hot-load abstraction boundary: implementation code may be
opaque provided it satisfies the interface/ABI, lifecycle/capability manifest,
and sandbox policy.

## 5. Boundary and effect rules

Every public actor method is a mailbox boundary.

Reject:

- method-level generic protocol methods;
- `mut` protocol parameters;
- borrows/references across the mailbox;
- futures, guards, spawn tickets, and mutable host objects across the boundary;
- actor objects transported by value;
- writable external shared state;
- public actor fields;
- public/generic/static/suspending constructors;
- imported effect-unknown helper calls from actor code.

Actor restrictions propagate transitively through ordinary local helper
functions/methods. Helper calls cannot escape the actor ownership/effect domain.

`ActorRef<Protocol>` is an explicit capability and may cross a boundary when
its protocol is valid.

## 6. Await inside protocol methods

`await` remains a hard scheduler boundary. A suspended protocol call retains
its mailbox envelope/accounting for the complete logical turn.

The runtime owns the reply Future. Synchronous completion settles it directly.
When compiler-lowered code resumes after `await`, the generated continuation
must complete the same runtime-owned protocol reply exactly once at the logical
method return point. Returning from a resumed protocol continuation without
reply completion is a fail-closed compiler/runtime invariant violation.

Cancelling the caller's observation Future does not rewind, interrupt, or undo
actor side effects.

## 7. One-shot actor callables

`actor fnc` / `actor routine` are one-shot actor-domain jobs. `spawn`
returns an `ActorSpawn<R>` with lifecycle/readiness/result handles. Do not use
these callables as persistent class behavior factories.

## 8. Generated runtime factories

The Java/runtime `BehaviorFactory` layer is kernel construction plumbing, not
source actor syntax.

For a persistent source actor class the generated construction path:

1. reserves ActorId/domain/group quotas;
2. validates/transports constructor arguments;
3. initializes actor-owned state under the target actor context;
4. installs the hidden typed protocol dispatcher;
5. publishes READY only after initialization succeeds.

The SHARED reference evaluator lowers through
`spawnSourceSharedProtocolActor`. PRIVATE and UNTRUSTED source actor classes
require isolation-aware OresVM lowering.

## 9. ActorFactoryCatalog

`ActorFactoryCatalog` is immutable and generation-scoped.

A descriptor key is path-oriented:

```text
workers/worker.ores::Worker
```

`Descriptor.inputType` denotes the compiler-generated hidden protocol-envelope
ABI, not a privileged source `receive(In)` parameter. `outputType` denotes
the group emitted-output contract where applicable.

The ABI digest covers at least:

- actor kind;
- constructor signature;
- complete public protocol shape;
- implemented protocol interfaces;
- generated message/wire tags;
- relevant capability contract.

Private helper reordering must not affect the ABI digest.

## 10. ActorGroup and ActorMailman

An `ActorGroup<Out>` owns actor registry/policy, bounded routing queues,
quotas, and one logical serialized `ActorMailman<Out>` on the control-plane
pool.

Workers never scan all actor mailboxes. A mailbox transition makes one actor
runnable in its actor-domain scheduler queue.

Actors may emit bounded group output:

```text
actor turn -> emit Out -> group outbox -> ActorMailman
```

Routing back into an actor uses that actor's typed protocol and therefore
re-enters its one mailbox.

## 11. Group authority

Supervisor/root code may hold `ActorGroupRef<Out>`.

Trusted actors may receive only an opaque `ActorGroupHandle<Out>` containing
authenticated identity/domain/generation capability data—not runtime,
scheduler, registry, queue, or mailman implementation objects.

UNTRUSTED actors never receive spawn-capable group authority.

## 12. Scheduling

All actor kinds preserve:

- one active execution lease per actor;
- separate bounded carrier pools by actor domain;
- no mailbox scanning;
- bounded turns/reductions/fuel;
- await as a hard scheduler boundary;
- completion/timer threads enqueueing only;
- suspended logical-turn state rooted until completion.

## 13. Group configuration and supervision

A positive group `min_actors` requires a generation-pinned factory key so a
supervisor can restore the floor.

Recommended restart strategies:

- `one_for_one`
- `one_for_all`
- `rest_for_one`

Restart intensity is bounded by `max_restarts` within `restart_window`.

Dynamic overrides are narrowing-only: they may not widen capacities,
capabilities, restart intensity, or execution domain.

## 14. Tree shaking and hot reload

Actor declarations do not self-register and imports do not execute guest code.

Build roots include actor classes referenced by:

- executable reachability;
- static group factory keys;
- dynamic group templates;
- explicit hot-load entry metadata.

Each hot-load generation receives an immutable catalog. Existing actors remain
pinned to their birth generation until drained/terminated.

## 15. Enforcement checklist

Compiler/type system:

- persistent actors are classes; actor callables are one-shot;
- at least one effective public protocol endpoint exists;
- inherited endpoints count;
- public protocol visibility cannot narrow;
- ActorRef concrete-to-interface narrowing is checked;
- protocol calls return `Future<T>`;
- protocol methods are not first-class bound values;
- raw send/receive/mailbox is not a source persistent-actor API;
- all constructor/protocol boundary types are validated;
- actor restrictions propagate through helper call graphs.

Runtime:

- one mailbox and one execution lease per actor;
- typed protocol calls use runtime-private request/reply envelope metadata;
- only user argument/reply graphs cross transport validation;
- reply completion authority is runtime-owned;
- raw messages fail closed against typed protocol dispatchers;
- suspended protocol replies complete exactly once after continuation return;
- queue/memory/fuel/lifetime reservations release exactly once;
- actor termination reclaims private resources and generation leases.
