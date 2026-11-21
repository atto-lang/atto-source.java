# Directional pipe endpoints — incremental compiler contract v1

Status: **DRAFT / Java directional-channel + compiler endpoint slice**.
`ReadChannel<T>`, `WriteChannel<T>`, stable `Channel<T>.input/output`
views, and compiler-recognized `HasInputChannel<T>` /
`HasOutputChannel<T>` conformance are implemented on this branch. Generic
non-actor `pipe` transport, external stream adapters, the four ActorRef ports,
and multi-flow admission remain fail-closed.

## Source-level endpoint contract

```oreslang
define interface HasInputChannel<T> as
    input: WriteChannel<T>;
end

define interface HasOutputChannel<T> as
    output: ReadChannel<T>;
end
```

The underlying Java host API must preserve the same capability boundary.
`ChannelRuntime.ReadChannel<T>` and `WriteChannel<T>` do **not** publicly
expose their backing `Channel<T>`: doing so would allow a caller holding
only a readable endpoint to write/close it (or a writable endpoint to
read/close it). `ChannelRuntime.ReadCase<T>.channel()` exposes only
`ReadChannel<T>`; `WriteCase<T>.channel()` exposes only
`WriteChannel<T>`. The trusted select coordinator retains private
access to the same backing channel and atomic arbitration lock. This
protects the normal Java API capability boundary, not privileged JVM
reflection in a trusted host process.

The source evaluator (`OresEvalRootNode`) is in a **different Java
package**, so it must not depend on private `backingChannel()` methods.
Guest `readch`, `writech`, and static `select` lower through explicit
`ReadChannel<T>` / `WriteChannel<T>` views (a full `Channel<T>`
is projected to its cached `output` / `input` view). Suspended,
nonblocking, immediate, and synchronous paths use these same directional
operations. There is no new queue or privileged unwrap in guest lowering.

**Java host compatibility notice:** `ReadCase.channel()` and
`WriteCase.channel()` change their Java return types from `Channel<T>`
to directional views. This is intentionally **source/binary incompatible**
with host integrations that directly called the old record component
getter. Migrate such code to `ReadChannel<T>` and
`WriteChannel<T>`; never restore the unrestricted getter as a
compatibility alias. Existing `ChannelRuntime.read(Channel<T>)` and
`write(Channel<T>, T)` factory calls remain supported.


The names above describe two *directional protocols*, not concrete Java
`ChannelRuntime.Channel` allocations. The endpoints are required properties,
not implicit getter calls: resolving a `pipe` graph must not execute arbitrary
user code, allocate a fresh endpoint, or observe a different endpoint on a
second lookup. Implementations used for automatic piping must expose stable,
fixed channel capabilities for these properties. `WriteChannel<T>` and
`ReadChannel<T>` are compiler-recognized source types on this branch and
runtime zero-copy views over the existing bounded `Channel<T>` substrate,
not independent queues. A raw bidirectional `Channel<T>` must not be returned here because
that would let a caller regain the wrong direction. A source implements only
output; a sink implements only input; a transform implements both with
independently resolved types.

`Channel<T>` satisfies both directional roles intrinsically: `.input`
returns one cached `WriteChannel<T>` view and `.output` returns one cached
`ReadChannel<T>` view. Both retain the exact same backing channel,
admission quota, buffer and select coordinator. The views cannot regain the
opposite direction or close the backing channel. `ReadableStream<T>` is output-only;
`WritableStream<T>` is input-only. `ActorRef<A>` receives compiler-synthesized
protocol conformance for its logical default ports; user-declared conformance
never grants actor route authority.

```oreslang
// Directional channel views execute on this branch:
writech channel.input, value;
const next = readch channel.output;

// Generic mixed pipe topology is type-checked but transport remains gated:
inputFile.read_stream() pipe decoder pipe outputFile.write_stream();

// Existing authenticated actor route remains the executable actor path:
actorA pipe actorB;

// Future ActorRef side ports:
actorA.errors pipe errorSink;
actorA.events pipe telemetry;
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

`PipeEndpointContracts` is **compiler-internal**. The type checker now
recognizes the reserved `HasInputChannel<T>` / `HasOutputChannel<T>`
protocol names, validates exact public stable `input`/`output` fields on
ordinary implementing classes, rejects rebindable endpoint slots, and models
`source`, `sink`, `transform` and `authenticatedActor` with
input/output types.
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
The checker validates the complete graph ABI before transport gating, so an
unimplemented early bridge cannot hide a later type mismatch. The first
implementation deliberately requires *exact* payload type equality at each
link, not implicit widening, structural coercion or framing.

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

## Implemented slice

1. Reserved compiler-recognized `HasInputChannel<T>` and
   `HasOutputChannel<T>` protocol types, including ordinary-class
   conformance and protocol-typed function parameters.
2. Stable directional `ReadChannel<T>` / `WriteChannel<T>` source types
   backed by one `Channel<T>`; `readch`, `writech`, static select and
   `SelectCase` enforce direction in both the checker and evaluator.
3. `Channel<T>.input` / `.output` return cached zero-copy directional
   views; async-task argument handoff preserves the exact direction.
4. Whole mixed-graph endpoint/type validation before the executable transport
   gate. All-actor graphs continue through the existing authenticated
   `ActorStream<T>` path.
5. Endpoint capabilities are rejected from actor mailbox/shared-data
   boundaries, and actor classes cannot self-assert authenticated pipe
   authority by implementing the protocols.

## Remaining implementation gates

1. One bounded non-parking non-actor pipe adapter/runtime with explicit
   ownership, cancellation, EOF/error propagation, per-edge element/byte and
   waiter credits, reader/writer leases, cleanup and transport-specific framing.
2. Define the mixed graph lifecycle/result handle before enabling non-actor
   `pipe` execution; do not infer it from the single-flow `ActorStream<T>`.
3. ActorRef `input/output/errors/events` logical ports with stable identity
   across generation upgrades, preserving in-flight generation leases and
   authenticated route epochs.
4. Readable/Writable/DuplexStream and HTTP/file/TCP/WS endpoint conformance
   over the same directional protocol without duplicate buffering systems.
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
