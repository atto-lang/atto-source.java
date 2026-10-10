# Request-scoped actor transport envelopes: HTTP, TCP, WebSocket

Status: **design contract** (2026-10-09). The source spellings in this document
are **proposed, not compiler-accepted syntax**. The currently executable Graal
surface is `define actor`/`define isoactor` with
`receive(ActorMail<String>)`, `http.claim(token)`, and the
`HttpExchange.start/write/finish` operations described in
[HTTP_SERVER.md](HTTP_SERVER.md). LLVM has no corresponding source HTTP actor
integration yet.

For per-instance configuration, the proposed constructor/spec model is
`val spec = new WebHandler(); spec.set_concurrency(128, 8);
val running = spawn spec;`. `new` creates an **inert ActorSpec**,
not a running actor; `spawn` executes constructor/startup only within
the actor's own domain. See [ActorSpawnSpec design](ACTOR_SPAWN_SPEC_DESIGN.md).
A regular actor spec instead uses `set_concurrency(6)`. Oreslang
`const` provides a readonly referent, so a mutating setter needs
`val`; `const spec = new Worker().with_concurrency(6)` is a proposed
immutable alternative. These forms are **not yet implemented**.

## Concurrency policy: one ordinary control, two network controls

**Public configuration under design (not executable Oreslang yet):**

```ores
@concurrency(messages = 16)
define actor OrdinaryWorker as
  receive(ActorMail<String> m): void { }
end

@concurrency(connections = 128, messages_per_connection = 8)
define http actor HttpWorker as
  receive_http(HttpMessage m): void { }
end

@concurrency(connections = 128, messages_per_connection = 4)
define tcp actor TcpWorker as
  receive_tcp(TcpMessage m): void { }
end

@concurrency(connections = 128, messages_per_connection = 4)
define ws actor WebSocketWorker as
  receive_ws(WsMessage m): void { }
end
```

Regular `actor`/`isoactor` declarations have **one** concurrency axis,
`messages`: number of concurrently **in-flight** `receive` handlers.
HTTP/TCP/WS actors have **exactly two** such axes: maximum live
**connections assigned to the actor**, and maximum **in-flight
`receive_*` message handler invocations per one connection**.
In-flight means admitted but not completed/cancelled, *including awaits*.
Per-connection counts are independent, so a slow client cannot consume
another connection's message permits. The product of the two axes is
an upper bound on in-flight network handler invocations; separate
host-wide resource/queue limits can lower effective admission.

Message counts are **not** TCP packets, bytes, HTTP body chunks
as transport framing, WebSocket fragments, or total lifetime messages.
The broker assigns a distinct, stable session identity and actor
incarnation to every admitted connection. HTTP keep-alive/HTTP/2/HTTP/3
connections may carry multiple request IDs and streams. Each delivered
application envelope has its own monotonically sequenced message
activation; many envelopes may refer to the same request. The broker
must preserve request/response identity and per-request order.
In HTTP/1.1 pipelining, response ordering still follows protocol
semantics even when application handlers overlap.

One actor-wide guest **execution** lease still allows only one carrier
to execute actor guest code at a time. This is a *fixed invariant*,
not an additional public concurrency setting. Awaiting yields that
carrier without consuming an extra thread, while the logical handler's
admission permit remains live. Interleaving suspended handlers and
scheduling fairness are **pending implementation**, not an established
feature of this PR.

The Java reference `ActorIngressConcurrency` gate now enforces
one- or two-axis host-side admission, scope/epoch fencing and
deterministic cleanup, with independent regression tests. **It is not
yet connected to NativeHttp or source actor dispatch.** In particular,
`http.listen(..., capacity, ...)` is a *listener-wide active exchange*
limit, not the actor's `connections` limit, and current JDK
`HttpExchange` does not expose enough connection identity for true
per-actor/per-connection admission.

## Scheduling and lifetime invariants

1. **Actor-wide one-writer lease.** Exactly one carrier at a time may execute
   actor-owned guest code, across any `receive*`, lifecycle handler,
   timer, callback, error handler, and continuation. A suspended operation
   does not occupy a carrier. Completion must re-enter via actor scheduler.
   No arbitrary transport callback may call actor guest code directly.
2. **A request is not an actor turn.** Actor instances can own zero, one,
   or many live requests/connections. Requests have independent IDs,
   deadlines, cancellation and response state. Network actor
   `connections` and `messages_per_connection` bound admitted session
   and in-flight handler counts independently; they do **not** permit
   two guest code segments to execute at once in the same actor.
   FIFO ordering within requests and fair servicing across connection
   identities are separate guarantees. Current source receive
   suspension serializes logical receive turns and does **not** yet
   interleave multiple suspended HTTP handlers.
3. **Correlation is mandatory.** Every HTTP message, body chunk, writable
   response action, and completion carries `request_id` AND a non-reusable
   incarnation/generation. The receiving actor cannot acquire a global
   `req` or `res`, and must not store a mutable `current_response` field.
   Messages for request A may never control request B's response.
4. **Two independent lifetimes.** A fresh actor spawned by
   `http.handler(...).dispatch(exchange)` is *dedicated* to that exchange:
   its death/deadline cancels the request, and admission is held until
   response closure AND actor finalization. A persistent actor receiving
   `exchange.move_to(existing_actor)` is *reusable*: request abort/deadline
   must not cancel the actor or its other requests, and HTTP listener
   admission is released per finished exchange, even while actor lives.
   Actor death revokes all unfinished requests belonging to it.
5. **Bounded output with transport-owned framing.** Headers commit once;
   response write/flush/finish are ordered for a given request, return
   futures, and honor per-request byte and buffer quotas. Slow readers
   backpressure the writer without parking actor carriers. Reject
   write-after-finish, stale generations, double finish, wrong-owner writes,
   and attempts to mutate committed headers. Cancellation closes/aborts
   only the relevant request or stream.
6. **Capabilities are not serializable pointers.** An HTTP request is a
   logical exchange, not the client socket FD. HTTP keep-alive, TLS, and
   HTTP/2 multiplexing remain owned by the transport broker. A transferred
   generation-bound capability grants access only to one request. Untrusted
   actors receive a bounded **serialized** request snapshot/chunk/event and
   can emit bounded response commands to a supervisor-owned broker. No JVM
   object, OS FD, shared heap pointer, TLS secret, or raw socket handle
   crosses the untrusted actor boundary. Untrusted execution still requires
   a tested sandbox and resource quotas; a copied payload alone is not
   isolation.
7. **Non-HTTP actors remain unchanged.** Ordinary `ActorMail<T>` is
   independent of transport-specific envelopes. A `http actor` can receive
   zero or many HTTP envelopes but no implicit HTTP authority exists in a
   generic `actor`.

## Proposed source API (not yet parsed)

The transport qualifier narrows the mailbox contract rather than creating a
new scheduler. Prefer explicit `HttpMessage`, `TcpMessage`, and `WsMessage`
types to one ambiguous `Message` with optional request/connection fields.

```ores
define http actor WebHandler as
  receive_http(HttpMessage m): void {
    // Each envelope belongs to one request, even if this actor serves 1000.
    val req = m.get_request();
    val res = m.get_response();
    val request_id = m.request_id;

    await res.start(200, "text/plain");
    await res.write("chunk A");
    await res.write("chunk B");
    await res.finish();
  }
end
```

`HttpMessage` is a typed envelope with `request_id`,
`exchange_generation`, `event_sequence`, a safe request view, and
a revocable response sink. A later body-chunk or disconnect event for the
same request must use the same identity pair and must not overwrite other
requests. Exactly one writer owns a response at a time; an actor can retain
multiple response sinks by request ID. Source retention and await lowering
must preserve sink ownership/identity and release it deterministically.

```ores
define tcp actor TcpHandler as
  receive_tcp(TcpMessage m): void {
    val conn = m.get_tcp_conn();
    // m.kind distinguishes connected, data, half-close, closed, error.
    // Data is delivered to receive_tcp again, never as a free-floating
    // conn.on("message", callback) invoked outside actor scheduling.
    if m.kind == TcpEvent.data; do
      await conn.write(m.bytes);
    fi
  }
end

define ws actor WsHandler as
  receive_ws(WsMessage m): void {
    val conn = m.get_ws_conn();
    // m.kind distinguishes open, text, binary, ping, pong, close, error.
    if m.kind == WsEvent.text; do
      await conn.send_text(m.text);
    fi
  }
end
```

Prefer `send_text`/`send_binary` to `write` for WebSocket frames.
A TCP connection ID + incarnation is distinct from an HTTP request ID;
a WebSocket upgrade transfers ownership of the **protocol session**, not
an alias for a live HTTP response. Each message and write capability must
be scoped to its connection/session and generation.

## Current Graal runtime surface

Today, a supervisor can explicitly reuse an actor while preserving
per-request identity:

```ores
define actor Handler as
  receive(ActorMail<String> mail): void {
    if mail.value == "stop"; do
      self.end();
      return;
    fi
    val exchange = http.claim(mail.value);
    await exchange.start(200, "text/plain");
    await exchange.write(exchange.request_id);
    await exchange.finish();
  }
end
```

Supervisor:
```ores
val worker = spawn Handler();
await worker.ready;
val exchange = await server.accept();
val finished = exchange.completion;
worker.send(exchange.move_to(worker)); // single-use token for this request
await finished; // finishes without needing reusable worker.done
```

There is no `HttpMessage` constructed in this path yet. It transports
a single-use claim token through the ordinary data mailbox and uses a
generation-bound `HttpExchange` that is accessible only inside its
owning actor's runtime domain. This is logical authority transfer, **not**
an OS FD handoff or protected isolate process boundary.

## Hardening / acceptance matrix

| Scenario | Required property |
| --- | --- |
| Concurrency axes | Generic actor admits <= N in-flight handlers; transport actor admits <= C connections and <= M in-flight handlers on each |
| Rejected admission | Does not consume an event sequence or connection ID; connection-local backpressure cannot starve other connections |
| Completion/cancel/connection close | Releases only the affected message permit, or all permits for the closed connection, without affecting other sessions |
| Two requests on one persistent actor | Distinct request IDs, no reply mixup, admission reclaimed independently |
| Deadline/abort of request A | Actor and request B remain alive; A's authority is revoked |
| Dedicated handler deadline | Only its dedicated actor is cancelled, admission drains on finalization |
| Wrong actor / stale owner / duplicate claim | Rejected without leaking body, response, or transport handle |
| Two events per request, interleaved with another request | A/B/A messages preserve their own response sinks and per-request order |
| Stream vs socket close race | At most one terminal transition; no write after revoke or double close |
| Slow network consumer | Bounded memory and backpressure, no carrier parked |
| Untrusted payload and command injection | Length-prefixed bounded codec; no pointer/FD/handle deserialization |
| Actor crash/hot reload | Generation fence rejects delayed old events and stale writes |
| TCP half-close and WebSocket fragment/close | Transport emits typed events; actor stays single-writer |
| HTTP/2/TLS multiple logical streams | Broker retains connection and TLS state |
| LLVM parity | Compiler diagnostics and backend tests before claiming native support |

### Follow-on implementation gates

- Implement parser/typechecker recognition of the `@concurrency` annotation with
  **one** regular vs **two** transport parameters; reject invalid mixes,
  nonpositive limits and overflow. Enforce per-connection session limits
  in broker adapters using `ActorIngressConcurrency`, then wire in-flight
  handler permit release to actor cancellation and continuation finalization.
- Implement compiler/typechecker recognition of `http/tcp/ws actor` and
  `receive_http/receive_tcp/receive_ws` and reject mixed/ambiguous
  declarations; add parser and lowering conformance cases in both backends.
- Introduce runtime-owned immutable envelopes and request-scoped response
  capabilities, with explicit incarnation, expiry, terminal fencing, quotas
  and deterministic per-request cleanup. Do not rely on ambient actor fields.
- Interleave suspended HTTP operations via actor scheduler/continuation
  queues only after proving the single-writer lease, fairness, reentrancy
  invariants, and cancellation behavior under adversarial scheduling.
- Implement a brokered copy/serialize/command channel and a real sandbox for
  untrusted workers before enabling untrusted HTTP/TCP/WS actors.
- Build native TCP and WebSocket transport adapters and run end-to-end
  streaming/close-race stress suites on Java/Graal and LLVM separately.
