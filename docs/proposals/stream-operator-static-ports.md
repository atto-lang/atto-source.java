# StreamOperator<I,O>: typed actor port admission

Status: **compiler-only, fail-closed**. This branch is stacked on draft #542.
The actor stream runtime is not implemented. Even a type-correct `pipe`
expression is rejected until actual runtime links have bounded, awaitable
admission and lifecycle safety.

## Stream operators are actors

```ores
define actor Parse implements StreamOperator<String, int> as
  receive(ActorMail<String> mail): void {
    // Source processing, private mutable actor state.
    self.send(1);
    return;
  }
end

define actor Validate implements StreamOperator<int, bool> as
  receive(ActorMail<int> mail): void {
    self.send(true);
    return;
  }
end

pub fnc build(): ActorStream<String, bool> {
  return spawn Parse() pipe spawn Validate();
}
```

**The code above currently fails at the final `pipe` admission gate**, rather
than constructing a pretend graph. This is intentional until the runtime
has actual structured stage ownership, ready/done futures, and backpressure.

The reserved built-in `StreamOperator<I,O>` marker is legal only in an actor's
`implements` clause; it isn't a regular class, instantiable value or
userland interface that may override the runtime's contract. The type checker
validates:

- A non-void `I` exactly matches the actor's
  `receive(ActorMail<I>)` handler.
- Every `self.send(value)` in the actor body must be assignable to `O`.
- Both ports are actor-boundary-sendable owned data types; borrows, futures,
  channels, streams, actor capabilities and unresolved generic values are
  excluded by the existing transport checker.
- `void` input is reserved for a source actor (must not have a receive handler);
  `void` output is a sink (must not `self.send`).
- The `pipe` grammar resolves chain operands left-to-right, checks each edge
  with **exact** intermediate port type match (not implicit cast/coercion),
  and rejects missing contracts, void-to-value edges and mismatches before
  reaching the runtime implementation gate.
- `ActorStream<I,O>` is a two-argument **non-sendable capability type**.
  Guest code cannot construct this by ordinary `new` or transfer it as a
  data payload through an actor mailbox.

## Graph transport must exist before enabling the operator

The present source runtime's `ActorRef.send(M)` returns `void` and can
reject on mailbox saturation. Calling it from a naïve output-stream callback
would **not** provide awaitable backpressure and risks dropping the frame
or turning a transient full mailbox into a fatal stage failure.

Required actual runtime implementation:

1. A bounded asynchronous mailbox write **admission future**, or an equivalent
   one-shot reservation token with reliable cancellation and memory accounting.
   Never spin, poll or block a carrier to wait for a free mailbox slot.
2. A per-link event-driven relay with at most one outstanding upstream read
   and downstream write, using the existing ActorRuntime control dispatcher,
   **not a new scheduler or thread pool**. It must preserve source actor
   output envelopes until downstream admission completes.
3. Graph startup freezes/claims all stage endpoints **before** triggering
   `actor.ready` (which can initialize/run the actor). Failed admission must
   cancel or dispose any already spawned, owned actors.
4. Pipeline `ready`/`done` futures, cancel/close_and_drain, EOF vs error
   propagation, and bounded timeouts/partial failures.
5. Cross-isolate/untrusted message copy/serialization, generation fencing for
   hot upgrades, single-consumer links, explicit fanout/merge and deadlock
   analysis for cycles or multi-port stages.
6. Real JVM, native AOT/hybrid, isolate and slow-sink stress tests; the
   source repo currently has zero-step Actions failures, not executable passes.

Only after these are implemented should the compiler gate be removed.
Functional `|>` remains eager function application, with no actor graph
dispatch or overload.
