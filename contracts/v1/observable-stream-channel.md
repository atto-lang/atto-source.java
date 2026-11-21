# Observable, Stream and Channel — cross-backend contract v0 (2026-10-11)

**Status: target contract, not an implemented cross-backend ABI.** Keep the
document byte-identical in `oreslang-source.java` and `oreslang-source.llvm`.
Do not promote this version to a supported feature until executable guest
conformance and real transport integration pass on both backends.

## Separation of responsibilities

| Primitive | Meaning | Ownership | Backpressure |
|---|---|---|---|
| `Channel<T>` | Typed point-to-point transfer, optionally buffered | Domain-local sender/receiver capabilities | Bounded slots; zero capacity is rendezvous |
| `ReadableStream<T>` | One logical asynchronous sequence with an exclusive reader | Reader lease owned by actor/execution context | Consumer demand; at most bounded in-flight chunks/bytes |
| `WritableStream<T>` | Asynchronous sink | Single writer or explicitly shared synchronized sink | Awaited admission and flush barrier are different |
| `DuplexStream<TIn,TOut>` | Paired read/write directions with separate half-close | Owning connection/session actor | Both directions independently bounded |
| `Observable<T>` | Subscription factory for asynchronous event observation | Each subscription owns its cursor and quota | Explicit RELIABLE / LATEST / LOSSY policy; never implicit unbounded multicast |
| `Subscription<T>` | Cancellation-capable reader of one observable | Non-sendable actor-local capability | Demand/credit accounting per subscription |

The language/runtime owns these abstractions. Java `InputStream`,
`Flow.Publisher`, host `CompletableFuture`, raw file descriptors, C++ pointers,
and socket handles must not appear in a guest type, actor envelope or ABI.
Host adapters translate to typed Oreslang values with explicit capabilities.
Generic channels and subscriptions are **not sendable to other actors**; only
typed data envelopes pass through `ActorRef` mailbox transport.

`Observable.from_channel(channel)` creates a **single-consumer** subscription
unless an explicit broadcast policy is requested. Creating multiple independent
readers must never silently duplicate/steal a channel's messages.
`channel.stream()` is a single reader of the same underlying channel;
releasing an exclusive reader must preserve the cursor without racing a pending
read. `Observable<T>` multicast requires per-subscriber bounded credits,
delivery policy and independent cancellation. A channel/stream conversion may
not invent unbounded buffering or a background guest thread.

## Mailboxes and serial actors

The actor mailbox transports typed `Envelope<Message>` using a private
`Channel` substrate. Envelope admission also validates incarnation,
sender capability, payload ownership/freeze, byte/message budget, sequence,
cancellation, and supervisor state. The **mailbox layer**, not ordinary
`Channel<T>`, owns logical handler-processing credit.

- Ordinary `Channel<T>(capacity=0)`: a writer completes admission once a
  reader commits the rendezvous; this does **not** mean the reader has
  finished processing.
- **Serial actor**: `max_active_lifetimes=1`, `queued_user_messages=0`,
  and a **single gate shared by HTTP requests and actor messages**. A
  one-credit delivery reservation is held from handler admission through
  `await`/suspension, `defer`, `finally`, `recover`, state-loan release,
  stream/response finalization and post-handler acknowledgement.
- The implementation may use an unbuffered channel plus a separate lifetime
  reservation, or a capacity-one acknowledged port. These are implementation
  alternatives; **a raw zero-capacity rendezvous channel alone is incorrect**.
- Public `send` is an admission attempt, not a processing acknowledgement.
  Future `send_async` must suspend the producer without parking an OS
  carrier and without retaining an unbounded waiter. `send_and_wait` / request
  reply requires explicit reply or completion protocol.
- An unavailable serial receiver must not make guest `send` block an OS
  thread. `try_send` fails immediately; awaitable admission uses bounded
  sender waiters, fair arbitration, cancellation and deadlines. Sending to
  the same serial actor while holding its lease cannot suspend awaiting
  itself: fail with a structured reentrancy/deadlock diagnostic.
- Internal runtime continuation/control envelopes use separately bounded,
  reserved headroom and never enter the user message channel. Only the owning
  actor's serialized lane may resume guest continuations.
- `receive()` for the next user message is callable only once the prior
  user handler has *logically* completed. Suspended handlers retain the lease
  even when a physical carrier was returned to its pool.
- Cancellation/stop seals admission and atomically revokes or settles waiting
  requests. Stale incarnation/generation tickets, double completion and
  post-stop resume must be rejected. Physical state deallocation requires
  quiescence of active carriers, loans and transport I/O.

### Awaitable actor-mailbox admission linearization

Awaitable actor send is a bounded mailbox-admission operation, not a retry
loop and not a second message queue.

- `send(message)` remains fail-fast when the USER lane is full.
- `send_async(message)` may suspend the logical producer, but the entire
  pre-publication cohort is finitely bounded before payload traversal/copy.
- Payload ownership/freeze/copy and retained-byte accounting are established
  before a producer may remain pending.
- At the source ownership layer, an affine mailbox payload transfers at the
  `send(...)` / `send_async(...)` call boundary, not at a later `await`.
  Copy payloads remain usable by the sender. Borrowed references and lexical
  guards are never mailbox payloads. A future recoverable-send API may return
  ownership explicitly on rejection; ordinary send does not silently restore a
  statically transferred affine value.
- `await actor.send_async(...)` is a compiler-lowered scheduler suspension:
  evaluate/register the send, return an `OresScheduler.await` step, unwind the
  current carrier, and resume the saved logical continuation after admission.
  It must never call a blocking Future bridge, poll/retry for space, or keep an
  OS/JVM carrier parked.
- The full observation, waiter registration, cancellation and USER-credit
  release share one admission linearization domain; check-then-register lost
  wakeups are invalid.
- USER credit transfers directly to one FIFO pending producer and stays charged
  during handoff, so a racing synchronous sender cannot steal it.
- Producer cancellation or producer-actor shutdown may withdraw the operation
  only before credit transfer. After transfer, admission is irrevocable with
  respect to producer cancellation.
- Graceful target shutdown must seal new producer admission, wait for every
  pre-publication loan that predates the seal to either publish or retire, and
  only then capture its admitted-mail drain watermark. A transferred credit
  must not disappear between graceful sealing and watermark capture.
- Abrupt target stop/failure may revoke unpublished work, but must return or
  transfer every retained credit and payload reservation exactly once.
- Future settlement and wakeup occur outside mailbox/accounting/lifecycle
  locks because callbacks may re-enter actor APIs.
- Pending/transferred producer state is a physical transport loan: actor DONE,
  arena/heap release and code-generation reclamation remain fenced until it
  publishes an envelope or releases all retained resources.
- A serial actor cannot await capacity in its own mailbox when only that same
  logical lifetime can release the credit; fail with a structured
  would-deadlock diagnostic.
- Completion acknowledges mailbox admission only, not handler completion,
  network flush, peer receipt or physical quiescence.

These are observable semantics. Backends may use different scheduler/credit
mechanisms, but they may not substitute OS-thread blocking, polling/yield retry
loops, unbounded waiter retention, or post-cancellation phantom delivery.

A long-lived WebSocket/TCP stream can hold a serial actor's only lease
indefinitely. The recommended design is a **connection actor** per stream
with independent bounded queues; a serial coordination actor handles
short messages/commands. Do not bypass the gate by dispatching WebSocket
events on an uncontrolled host callback thread.

## Unified network streams

Network transport types are **adapters**, not unrelated stream systems:

- HTTP request body: `ReadableStream<Bytes>`; response body:
  `WritableStream<Bytes>`. Request/response metadata and headers remain
  typed HTTP values, outside the raw byte stream. An HTTP/2 stream has its
  own flow-control and cancellation state independent of sibling streams.
- TCP: `DuplexStream<Bytes, Bytes>` with ordered bytes and independent
  read/write half-close. TCP has **no message boundaries**; framing belongs
  to an explicit codec (`lines`, length-prefix, etc.).
- WebSocket: `DuplexStream<WsFrame, WsFrame>`, preserving opcode, frame
  boundaries, close handshake and ping/pong. Messages spanning frames need
  bounded reassembly. Binary/text validation happens at the adapter.
- Server-sent events and other push interfaces:
  `Observable<Event>` as a *view* of a single stream or explicitly bounded
  broadcast, not a second transport queue.
- `pipe`, `map`, `filter`, `tee`, `merge`, `select` and composition
  execute as Oreslang continuations with declared ownership effects.
  `tee` is not atomic fanout unless its contract expressly provides it.

Every transport advertises message/frame limits, byte limits, deadlines,
read/write directions and capabilities. Backpressure must propagate through
the whole graph to the OS adapter; OS write **admission** is not flush,
remote receipt, or peer application processing.

## Terminal ordering, fairness and trust

1. A stream/channel completes with exactly one terminal outcome:
   clean EOF, error, or cancellation. Already committed channel buffer
   entries are drained before normal close; error causes are preserved.
2. Cancelled pending reads/writes/select losers detach atomically; a late
   completion must not resurrect delivery, execute a guest callback on an
   I/O thread, or refund the same credit twice.
3. Readable streams have exactly one active reader lease; no release during
   an outstanding read. A new reader cannot use a stale lease.
4. Bounded byte quotas include queued and *checked-out/unacknowledged* payloads;
   credit returns only after handler / device / transport ownership fences.
   Pending producer registrations also need finite quotas.
5. FAIR select rotates deterministically; PRIORITY and RANDOM require
   explicit selection. A full waiter quota may reject a pending registration,
   **but cannot prevent an immediately ready rendezvous from committing**.
6. Untrusted actors cannot receive a privileged socket/file capability or
   raw channel. Trusted isolated actors receive data copies or sealed,
   explicitly allowed handles, not host references or cross-actor shared
   mutable queues. Close and capability revocation are incarnation-fenced.
7. Source-level methods must not depend on Java-specific host classes or
   expose native pointers. A backend's lack of an implemented intrinsic is
   a compile-time unsupported-feature diagnostic, not silent emulation.

## Executable conformance gates (required before release)

- Channel: cap=0 rendezvous, cap>0 FIFO, multi-select fairness, close/EOF,
  loser preservation, pending registration quotas, cancellation/close races,
  immediate progress while quota saturated.
- Actor: serial mixed HTTP/message FIFO, one active lease across suspended
  cleanup, no pending user queue, try-send overload, self-send prevention,
  stop/restart fencing, no carrier parking and exactly-once credit return.
- Streams: exclusive reader handoff, no double-delivery or subscriber stealing,
  bounded multicast policy, cancellation during producer registration,
  no guest continuation on host I/O completion thread.
- Transports: HTTP body cancellation and streaming response close, TCP
  half-close and byte framing, WebSocket fragment/close race, burst
  backpressure and process shutdown, all within per-actor and per-runtime
  memory quotas.
- Execute the **same public `.ores` fixtures** on Graal and LLVM, with
  identical success traces or intentionally shared diagnostics. Build the
  real guest compilation paths; native unit tests alone are insufficient.

## Current integration boundaries

Java main has `ChannelRuntime` and actor-channel mailboxes but no shared
serial HTTP/message gate wired through the full transport lifecycle on main.
Stream/Observable and stdio changes are spread across draft PRs #210, #300,
#467, #538, #540, #656; reconcile their single-reader, subscriber, stdio
and actor-lifetime semantics before merging.

LLVM main has `BoundedActorPort` and `NativeActorMailbox`, not source-level
`Channel<T>` / `Observable<T>` / network-stream parity. The native
`NativeActorMailbox` with capacity 1 is a **nonqueueing try-admission gate**
that immediately rejects an overloaded entrant; higher capacities can wait
on `pthread_cond_wait` and must not be used as a guest carrier-suspension
implementation. The native serial lifetime draft is PR #195.
