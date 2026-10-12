# Actor declarations, spawn, and producer lifetime

`define actor Worker as ... end` declares a shared actor. `define isolated actor
Worker as ... end` declares a trusted actor with confined state, without a
separate Graal isolate. `define untrusted actor Worker as ... end` declares an
untrusted actor requiring a separately sandboxed tenant isolate. All three
support `as { ... }` and `{ ... }` bodies. The existing `isoactor` spelling is
also accepted.

An actor class is a persistent mailbox protocol. Only public instance methods
are callable through its `ActorRef`; those calls return Futures. Fields, private
methods, constructor re-entry, and method extraction are inaccessible through
the reference. `id`, `is_alive`, and `mailbox` cannot be protocol endpoint names.
A user-defined `send` or `receive` method is a typed protocol endpoint, not raw
mailbox access. Actor constructors and actor functions require `spawn`.

An `actor fnc` creates one actor per invocation. It may return owned data, a
`Stream<T>`, or an `Observable<T>`. The source spelling is independent of the
class spelling; no additional `persistent` modifier is required for a producer.

```ores
actor fnc produce(): Stream<int> {
  return Stream.from_values([10, 20, 30]);
}
pub routine main() => void {
  val producer = spawn produce();
  val values = await producer.result;
  val subscription = values.subscribe();
  let item = await subscription.next();
  for (; item.is_next(); ) {
    stdio.println(item.value);
    item = await subscription.next();
  }
  await producer.done;
  return;
}
```

The runtime wraps returned reactive values in an actor-owned result capability:

* `ready` means the actor initialized; `result` hands off the data or stream.
* `done` means the actor has finalized, including producer lifetime and mailbox
  teardown. Returning a stream does not complete `done`.
* Subscribing, pulling, source cleanup, and item conversion execute as serialized
  continuations of the original actor. A pending pull does not retain a carrier.
  External producers settle Futures and enqueue work; they do not run source
  cleanup hooks on their producer threads.
* One pending pull is allowed per subscription. Admission uses bounded actor
  continuation and subscription queues. Items pass runtime sendability and
  private-transport size checks; mutable host objects and control handles cannot
  escape as items.
* Stream permits one subscription. Observable permits multiple concurrent
  subscriptions. The actor retires after the last admitted subscription ends
  or is cancelled. A retired result cannot be subscribed again; it is not a
  replayable capability that silently creates a new producer actor.
* `values.close()` retires even a producer that has never been subscribed.
  Callers that abandon a result must close it or cancel their subscriptions.
* Terminal completion and errors finish the subscription; producer errors also
  fail `done`. Cancelling a subscription or its outstanding pull releases it.
  Stopping the producer cancels pending output Futures. Forced VM shutdown
  cancels pending waits and reclaims the actor without requiring guest hooks.

This work executes shared and trusted confined actor classes in the reference
interpreter. Untrusted source spawning remains rejected until tenant-isolate
lowering transports the class/code and protocol capabilities into the sandbox.
The host runtime already admits multiple untrusted actors in one runtime marked
as a spawned isolate; it does not require one isolate per actor. That shared
runtime is a tenant boundary, not an isolation boundary between tenants.
Actual Graal isolate execution is not established by ordinary JVM tests.

Reactive replies from persistent actor-class methods are currently rejected:
they need a separate owner-aware protocol reply adapter. Unary protocol replies
remain supported. Actor-owned reactive handles cannot be re-exported as another
actor's result or sent as ordinary mailbox data.

`Observable.from_values` and `Stream.from_values` are the first reference
interpreter bridge. The intended explicit `std/rx` library linker and removal
of unused RX code from AOT applications remain unproven. This branch also still
needs convergence with the channel/select compiler stack before those combined
source programs can be claimed as tested.
