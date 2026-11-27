# RFC: first-class actor stream pipelines in Oreslang

Status: **graph design / executable runtime not implemented**. Actor connection uses the **infix `pipe` keyword**. Eager functional `|>` belongs to independent draft compiler PR #542, not to this graph RFC; `ActorStream` does not execute yet. This RFC remains adjacent to the process-owned `Channel<T>` work in draft core PR #540.

## Decision

Do **not** invent a second actor-like class, mailbox scheduler, or independently running stream processor. A *stream operator* is a role of an ordinary actor with a typed, runtime-admitted input port and output port. The actor owns the state; `Channel<T>` supplies bounded backpressure and cancellation; `ActorStream<In,Out>` is a capability to a connected graph.

A normal `spawn Worker()` already returns `ActorRef<Worker>` immediately. `actor.ready` is a separate `Future<void>` initialization barrier; callers do not need `await spawn`. Current source actors still reject constructor arguments. Pipeline construction must not silently change that constraint.

### Proposed syntax (NOT yet accepted by the compiler)

```ores
// The StreamOperator interface/trait and its actor binding are proposed.
define actor Parse implements StreamOperator<String, Event> as
  // Private actor-local state, serialized at a chosen concurrency level.
  // handle(Event) and emission rules to be finalized by the actor ABI.
end

define actor Normalize implements StreamOperator<Event, Record> as
end

pub fnc connect_streams(): ActorStream<String, Record> {
  const parser = spawn Parse();         // returns ActorRef<Parse> immediately
  const normalize = spawn Normalize();  // returns ActorRef<Normalize>
  return parser pipe normalize;
}

// Pipeline handle is returned before actors are necessarily ready.
pub async routine main(): void {
  const pipeline = connect_streams();
  await pipeline.ready;                 // optional when a caller needs admission proof
  // ... write into pipeline.input, consume pipeline.output
  await pipeline.close_and_drain();
}
```

`pipe` is reserved **as an infix stream connection operator**, never as an overload of eager functional `|>`. Draft compiler PR #542 introduces parsing and fail-closed type checking for infix `pipe`, without claiming graph execution. Since userland already names local variables `pipe`, the spelling is contextual to infix position rather than globally forbidding those identifiers. The compiler must reject an ordinary `ActorRef<T>` that has no declared StreamOperator input/output contract.

### Proposed typing

- `ActorRef<A>` where `A : StreamOperator<I,M>` pipe `ActorRef<B>` where `B : StreamOperator<M,O>` gives `ActorStream<I,O>`.
- `ActorStream<I,M> pipe ActorRef<B : StreamOperator<M,O>>` gives `ActorStream<I,O>`, left associatively.
- `ActorStream<I,M> pipe ActorStream<M,O>` gives `ActorStream<I,O>` only if both endpoints are movable/exclusively attachable.
- Mismatched middle types `M != N` are a **compile-time error**. No implicit JSON/Any serialization or unsafe casts.
- A graph owns and disposes intermediate channel connections. A stage endpoint is consumed exactly once; reuse across pipelines requires an explicit supported `broadcast` / `merge` / `zip` operator.
- Actors may expose ordinary mailbox methods independently. Only an explicitly declared stream input/output is used by `pipe`.

### Startup, execution, flow control

1. `spawn` returns the actor ref without awaiting startup. No user-visible blocking occurs in construction.
2. `a pipe b` builds a typed graph, reserves both endpoint capabilities, and creates a **bounded** link; it returns an ActorStream handle synchronously.
3. Internal CONTROL scheduling begins stage initialization. `pipeline.ready : Future<void>` resolves once *all* stage ready barriers succeed and the edges are attached; if any stage fails or is cancelled, it fails and the graph cleans up.
4. A stage pulls its typed input through `await readch` and writes through `await nb writech`. Waiting releases the carrier via the existing suspension engine. No worker/thread is dedicated to every edge.
5. No unbounded implicit pending-write registry. The graph applies byte/message quotas and bounded outstanding write futures in addition to channel buffer capacity. Input type conformance and actor-domain transfer permission are checked *before enqueue*.
6. Per-stage serial state is the default. Parallelism/reordering is an explicit operator/actor configuration, not an accidental side effect of spawning two actors.
7. Channels follow single-consumer semantics; fan-out requires an explicit duplication/share contract, especially for move-only values.

### Errors, EOF, lifecycle

- Normal producer completion seals output after all accepted frames have been delivered; consumer drains remaining buffered frames and receives normal EOF.
- Failure closes the affected edge with a cause, and is **not** turned into ordinary EOF, matching `Channel.is_closed_normally()` and the raw `Observable.from_ch` adapter.
- Default supervision cancels descendants on a failed stage; restart/replay is explicitly configured, never silently claimed to offer exactly-once processing.
- `pipeline.cancel()` detaches registrations, closes graph-owned links, and stops owned stages. `pipeline.close_and_drain() : Future<void>` is an ordered terminal barrier and waits for accepted stage output to drain, subject to a bounded deadline.
- Cross-isolate or UNTRUSTED edges serialize/copy data rather than passing pointers or raw Channel/ActorRef capabilities. Generation fencing prevents output from a replaced actor incarnation from reaching the new graph generation.
- Process stdout/stderr may be a pipeline **sink** using the process-owned writable channels. Stdin may be a source via `stdio.in.stream()` or `Observable.from_ch(stdio.in)`; neither creates a second source of console bytes nor closes stdin on subscription cancellation.

### Grammar and implementation stages

1. Use contextual infix `pipe` for typed ActorStream graph composition. Draft #542 separately adds a distinct `VALUE_PIPE` token for eager `|>` calls; both operators parse left-associatively below logical expressions and above conditional/assignment. Test bitwise `|`, type unions, lambda delimiters, and pre-existing identifiers named `pipe`.
2. Specify source `StreamOperator<I,O>` declarations/trait conformance and actor-local port ownership. Add negative compiler tests for generic mismatch, missing stream contract, duplicate endpoints, wrong actor kind, crossing untrusted capability domains, recursive cycles, and unbounded fanout.
3. Introduce `ActorStream<I,O>` VM graph capabilities with typed ports, `ready`/`done` futures, cancellation and bounded drain. Integrate existing ActorRef lifecycle rather than creating a scheduler.
4. Build a real end-to-end test: spawn Parse and Normalize without await, connect with `pipe`, submit 10k records with slow consumer, assert values/order, max outstanding registrations, worker/carrier liveness, EOF/failure distinction, and no orphaned actors.
5. Validate JVM, native AOT/hybrid, isolate-mode and hot-reload generation switching with a downstream REST/CLI pipeline.

### Avoid premature semantic claims

The *current* compiler: accepts `spawn ActorType()`, but does not admit actor constructor parameters. ActorRef `ready`/ `done` are Futures; `send` is currently void and is not a stream flow-control acknowledgement. Draft #542 recognizes `|>` as eager function piping and recognizes contextual `pipe` while intentionally rejecting it in the type checker. A real typed `ActorStream` graph runtime remains separate work; nothing in this RFC makes it executable.
