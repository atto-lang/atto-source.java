# Directional pipe endpoints — incremental compiler contract v1

Status: **DRAFT / internal Java compiler foundation only**. This does not
expose guest `HasInputChannel`/`HasOutputChannel` declarations, generic
external stream `pipe`, four actor ports, or multi-flow admission.

## Source-level target (NOT YET EXECUTABLE)

```oreslang
define interface HasInputChannel<T> as
    input: WriteChannel<T>;
end

define interface HasOutputChannel<T> as
    output: ReadChannel<T>;
end
```

The names above describe two *directional protocols*, not concrete Java
`ChannelRuntime.Channel` allocations. The endpoints are required properties,
not implicit getter calls: resolving a `pipe` graph must not execute arbitrary
user code, allocate a fresh endpoint, or observe a different endpoint on a
second lookup. Implementations used for automatic piping must expose stable,
fixed channel capabilities for these properties. `WriteChannel<T>` and `ReadChannel<T>`
are the intended source-level directional views already called out by the
stdio/channel roadmap; they are not yet public aliases on this branch. They
must be views over the existing bounded channel substrate, not independent
queues. A raw bidirectional `Channel<T>` must not be returned here because
that would let a caller regain the wrong direction. A source implements only
output; a sink implements only input; a transform implements both with
independently resolved types.

Once the directional views exist, `Channel<T>` itself can satisfy both
protocols intrinsically by exposing its write view as the default input
endpoint and its read view as the default output endpoint; it does not need
to fabricate wrapper objects on every lookup. `ReadableStream<T>` is output-only;
`WritableStream<T>` is input-only. `ActorRef<A>` receives compiler-synthesized
protocol conformance for its logical default ports; user-declared conformance
never grants actor route authority.

```oreslang
// Target, not currently executable:
inputFile.read_stream() pipe decoder pipe outputFile.write_stream();
actorA pipe actorB;                     // authenticated actor route
actorA.errors pipe errorSink;           // explicit side port
actorA.events pipe telemetry;           // trusted runtime event observation
```

## Current executable candidate compatibility

On the stacked Java ActorStream draft (#1145 -> #1329 -> #1333), both
`ActorUtils.pipe(A, B, ...)` and `A pipe B pipe ...` still:

- Require 2..16 SHARED typed ActorRefs in a non-actor root.
- Require one exactly equal, concrete, sendable `ActorMail<T>` domain.
- Build one `ActorStream<T>` with the existing authenticated route/frame
  authority, one root flow and physical `get_done()` barrier.
- Reject duplicate instance identities, untrusted/isolation-domain
  mismatches, invalid DATA admission and post-completion replay.
- Retain existing source-order evaluation and inline-spawn rejection cleanup.

`PipeEndpointContracts` is **compiler-internal**. It models `source`,
`sink`, `transform` and `authenticatedActor` with input/output types.
An actor-to-actor pair with exact equal domains chooses
`AUTHENTICATED_ACTOR`. Every other well-typed pair chooses
`BRIDGE_REQUIRED`, and `requireExecutable` rejects that transport until
bounded, cancellation-aware bridges have been implemented.

**No guest-supplied interface implementation is authentication.** The type
checker must derive real actor route authority from the compiler-owned actor
identity and the existing route plan. Any future interface dispatch must
not let user code mint actor CONTROL/MailFrame capabilities.

## Direction, cardinality and response semantics

`source pipe transform pipe sink` checks each adjacent output/input pair
separately; `transform` may accept `Bytes` and emit `DecodedMessage`.
The first implementation deliberately requires *exact* payload type equality
at each link, not implicit widening, structural coercion or framing.

A forward `output -> input` edge does **not** model or erase the existing
authenticated reverse response path. The current actor `self.backward` and
`self.reply` remain scheduler-owned with their own route correlation,
one-shot admission and physical DONE guarantees. A future bidirectional
actor transform must explicitly declare independent forward and backward
message domains.

`actor.send(msg)` remains an actor mailbox operation. Mailboxes are private,
authenticated scheduler queues, even if their storage uses channels.
`actor.input`, `actor.output`, `actor.errors`, and `actor.events` are
future *logical*, directional, channel-compatible port capabilities, not
automatic aliases of the raw mailbox. `actor.input` is the default
`HasInputChannel<T>` endpoint and `actor.output` is the default
`HasOutputChannel<T>` endpoint. `errors` and `events` are explicit output-only
side ports and are never implicitly connected by `actorA pipe actorB`.
Application errors and trusted runtime lifecycle events must remain separate.
Multi-reader `events` observation requires explicit reliable/latest/lossy
subscription semantics and quotas.

For an all-actor chain, the expression result remains the existing
`ActorStream<T>` and retains its authenticated reverse path and whole-route
`get_done()` barrier. Do not change that return type merely to introduce the
endpoint protocols. A future mixed source/transform/sink graph may receive a
separate graph/connection handle only when its cancellation, EOF, error and
physical completion contract is specified and implemented.

## Remaining implementation gates

1. Compiler-recognized source interfaces and built-in port types:
   `HasInputChannel<T>` and `HasOutputChannel<T>`. Reject shadowing,
   ambiguous default ports, unknown/borrowed/unresolved payload types and
   fabricated actor-route authority.
2. Whole-graph type resolution and publication: evaluate stage expressions
   once; check every directional link *before* DATA publication; track
   inline-created vs pre-existing actor ownership for rollback.
3. One bounded non-parking stream/channel adapter runtime, backed by
   actor-serialized admission, per-edge byte and waiter credits, cancellation,
   EOF, failure, reader leases, cleanup and transport-specific framing.
4. Actor side ports with stable logical identity across generation upgrades.
   Preserve in-flight generation leases and authenticated route epochs.
5. Java/LLVM shared source fixtures for typed source->transform->sink,
   actor->actor legacy forward/backward behavior, file/HTTP stream edges,
   input/output wrong direction, mismatched domains, unknown/borrowed data,
   subscription policy, stop/cancel/backpressure, and leak/retirement barriers.

Existing authored regression suites MUST remain gates:
`AuthenticatedActorStreamSourceTest`,
`ActorStreamPipeExecutionTest`,
`ActorPipelineRootAdmissionCreditTest`,
`ActorStreamPhysicalDoneBarrierTest`,
the reverse-admission fix (#1204), the inline-spawn rollback tests (#1333),
and LLVM directional pipeline fixtures (#244/#345).

**Provenance warning:** this branch is based on draft #1333, not
`main`, and source-org hosted CI may fail before steps. An exact-head
compiler/JUnit/Native Image run and cross-backend qualification are still
required. A funded private mirror may be used only after explicit
source-transfer authorization; runner capacity alone is insufficient.
