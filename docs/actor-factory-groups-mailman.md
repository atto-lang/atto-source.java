# Actor factories, shared-actor groups, and mailmen

Status: design contract stacked on the resumable actor-event-loop work.

This document reconciles the actor callable/spawn work with the serialized shared-actor event-loop model.

## 1. Core distinction

Oreslang has persistent actors. An `actor fnc` / `actor routine` is the **factory/launch entrypoint** for one persistent actor; it is not an ordinary function and it is not itself a one-shot mailbox turn.

A spawn target MUST be declared with an actor execution-domain modifier:

- `actor fnc` / `actor routine` -> SHARED domain
- `isoactor fnc` / `isoactor routine` -> PRIVATE domain
- `untrusted actor fnc` / `untrusted actor routine` -> UNTRUSTED domain

The callable's declared return type MUST satisfy the compiler-owned protocol:

```ores
ActorBehavior<In, Out>
```

A concrete actor class may satisfy this protocol structurally.

The behavior has exactly one public mailbox ingress:

```ores
receive_message(In message): void
```

For shared actors, every other actor method and every mutable field is private to the behavior. The runtime never dispatches arbitrary public methods.

`Out` is the actor's typed outgoing-mail contract. The actor may emit zero or more `Out` values during a turn; these are appended to its ActorGroup outbox and are not returned from `receive_message`.

A no-output actor uses `ActorBehavior<In, void>`.

## 2. Example

```ores
define struct Increment
  int amount;
end

define struct CounterChanged
  int value;
end

shared actor Counter
  let int value;

  pub receive_message(Increment msg): void {
    self.value = self.value + msg.amount;
    emit CounterChanged(self.value);
    return;
  }
end

pub actor fnc counter(int initial): ActorBehavior<Increment, CounterChanged> {
  return new Counter(initial);
}

pub routine main(): void {
  val pending = spawn counter(10);

  // identity is synchronous after reservation/admission
  stdio.println(pending.id);

  // READY means the behavior exists and its mailbox endpoint is usable
  val counter_ref = await pending.ready;

  counter_ref.send(Increment(5));
  return;
}
```

The actor factory runs during STARTING with the child actor context installed. It may allocate/initialize actor-owned state, but it may not leak actor-owned mutable state, capture caller-owned mutable aliases, block a carrier, or perform an incomplete `await`.

## 3. Spawn contract

```text
RESERVED -> STARTING -> READY -> RUNNING -> TERMINATED
                  \-> FAILED_TO_START
```

`spawn actor_factory(args...)` performs only bounded synchronous launch work:

1. validate the spawn target is an actor factory;
2. validate/copy/freeze/transfer launch arguments for the target actor domain;
3. reserve ActorId and actor/group quotas;
4. create local control futures;
5. enqueue actor initialization;
6. return the launch ticket.

The actor factory executes later on the target actor dispatcher. READY is published only after:

- factory execution completes successfully;
- the returned value satisfies `ActorBehavior<In, Out>`;
- the behavior is rooted as actor-owned state;
- the single `receive_message(In): void` ingress is installed;
- group/mailman routing is attached.

Startup failure fails `ready` and `done`, tears down the actor, releases its group/generation/quota leases, and never publishes a usable ActorRef.

## 4. ActorSpawn and ActorRef

Persistent actors do not have a function-result future.

The source contract is:

```ores
ActorSpawn<In, Out> {
  id: ActorId;
  ready: Future<ActorRef<In>>;
  done: Future<ActorExit>;
}
```

`await spawn actor_factory(...)` is shorthand for awaiting `ready` and therefore returns `ActorRef<In>`.

```ores
ActorRef<In> {
  id: ActorId;
  send(In message): void;
  is_alive(): bool; // unavailable where sandbox policy forbids lifecycle inspection
}
```

`ActorSpawn` remains local control state and is not Sendable/shared-safe.

The one-shot `ActorSpawn<T>.result` semantics introduced by the earlier spawn draft are intentionally not the persistent-actor ABI. If Oreslang retains one-shot actor tasks, they must use a distinct task abstraction rather than overloading the persistent actor factory contract.

## 5. Shared actor mutation and nlex/ownership

"Shared actor" means shared-address-space scheduling/capability domain, not unrestricted shared mutation.

A shared actor turn may mutate only:

1. actor-owned state rooted in `self`;
2. fresh turn-local state;
3. explicit compiler/runtime synchronization or capability objects.

It may not mutate arbitrary caller/global/external mutable state.

Actor-owned mutable state is non-escaping. The ownership checker must reject:

- returning a mutable actor-state alias;
- sending a borrow/reference into a mailbox;
- storing actor-owned mutable state in global/singleton/external state;
- capturing actor-owned mutable state in an escaping closure;
- retaining a turn-scoped borrow across `await`.

The existing `nlex` model should be reused for actor factories/actor-owned lexical state where applicable, but actor isolation is a stronger semantic rule and must not depend solely on surface `nlex` spelling.

## 6. Event loop and scheduling

No scheduler or mailman dynamically selects across every actor mailbox.

Incoming path:

```text
send(message)
  -> actor.inbox.push(message)
  -> CAS IDLE -> QUEUED
  -> ActorGroup/domain runnable queue.push(actor_ref)
```

A carrier obtains an actor execution lease and runs a bounded quantum. At most one carrier executes guest code for one actor at a time.

`await`, timers, and I/O completion use the existing continuation path. Completion threads enqueue wakeups only; they never execute actor code. A suspended logical mailbox turn remains serialized until its continuation completes.

Generator `yield` is unrelated and must never become an actor scheduling primitive.

## 7. ActorGroup and mailman

Every persistent actor belongs to exactly one `ActorGroup<Out>`. A default shared group exists for spawns that do not name a group.

Conceptually:

```text
ActorGroup<Out>
  actor registry
  runnable scheduling state
  bounded MPSC outbox<ActorMail<Out>>
  one logical mailman
  supervisor/lifecycle policy
  quotas
```

Actors never expose one outbox per actor to a giant `select`. They append outgoing mail to the group's bounded MPSC outbox.

```text
Actor A --\
Actor B ----> group.outbox ---> logical mailman
Actor C --/
```

The mailman is one logical serialized consumer, not one permanently dedicated OS thread. It may migrate across carriers exactly like an actor.

A future implementation may partition mailman work by an explicit routing key, but parallel routing must be opt-in because it weakens total ordering.

## 8. Outgoing envelope

The group outbox stores envelopes:

```ores
ActorMail<Out> {
  actor: ActorId;
  group: ActorGroupId;
  correlation_id: Option<CorrelationId>;
  message: Out;
}
```

Trace/span id, request id, deadline, sequence, or other runtime metadata may be attached without becoming guest-mutable actor state.

The mailman/supervisor routes with ordinary Oreslang pattern matching:

```ores
switch mail.message {
  CounterChanged(value) -> {
    // route/persist/reply/etc.
  }
  _ -> {
    // group policy
  }
}
```

The actor itself does not receive ambient authority merely because the mailman can perform a side effect.

## 9. Supervisor vs mailman

Keep the concepts distinct even if the first implementation shares machinery.

Mailman:
- consumes ordinary actor output;
- routes replies/events;
- preserves configured ordering.

Supervisor:
- owns lifecycle;
- restart/escalation policy;
- generation leases;
- actor/group shutdown;
- startup/failure handling.

An unrecovered actor `raise`/`panic` goes to supervision policy, not through ordinary outgoing mail.

## 10. Compatibility with the current PR stack

This design is intentionally aligned with the current actor work:

- the single-carrier execution lease and three isolated SHARED/PRIVATE/UNTRUSTED dispatcher domains remain unchanged;
- resumable actor continuations, timers, and next-tick wakeups remain unchanged;
- generator `yield` remains separate from actor suspension;
- actor generation leases must cover factory initialization plus the full actor lifetime;
- global/singleton refs and shared-mutable capabilities remain forbidden from untrusted actors;
- actor-owned state follows ownership/borrow escape rules;
- actor HTTP/native transport ownership remains capability-based and does not turn shared actors into arbitrary socket owners.

The main semantic correction is to the earlier one-shot spawn draft: a persistent actor factory returns an `ActorBehavior<In, Out>`, and `spawn` returns a launch/control ticket rather than treating the actor callable's application return value as the actor's result.

## 11. Compiler/runtime enforcement checklist

Compiler:

- reject direct calls to actor factories;
- reject `spawn` of non-actor callables;
- require actor factory return type to satisfy `ActorBehavior<In, Out>`;
- require exactly one public `receive_message(In): void` on a concrete behavior;
- reject additional public shared-actor methods/fields;
- derive `ActorSpawn<In, Out>` from the factory return type;
- type `await spawn` as `ActorRef<In>`;
- type `ActorRef.send` against `In`;
- type `emit` against `Out`;
- reject actor-owned mutable alias escape and forbidden external mutation;
- preserve actor-domain/capability checks transitively.

Runtime:

- execute factories only after ActorId reservation on the target dispatcher;
- publish READY only after successful behavior construction/validation;
- root returned behavior exclusively in the actor cell;
- enqueue one actor ref per runnable transition, never scan mailboxes;
- enforce one active execution lease per actor;
- bound mailbox, continuation, timer, and group-outbox admission;
- route emitted mail through the actor's group outbox;
- reclaim group/actor/generation/resources exactly once on failure/termination.
