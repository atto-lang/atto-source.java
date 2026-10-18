# HTTP response object — executable Java transport foundation

**2026-10-18: host-side implementation, NOT guest-language support.**

This branch provides `HttpResponseDraft` (trusted Java transport value), a single-use
`NativeHttp.Exchange.respond(HttpResponseDraft)` adapter, and real HTTP/1.1 wire tests.
It is the first executable part of
[`HTTP_RESPONSE_FETCH_API_V1.md`](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1387),
not evidence that `define unary http actor … run(HttpMessage): HttpResponse`
currently parses, typechecks or runs. No LLVM support is claimed.

## Runtime behavior actually implemented

- Immutable status and validated, frozen response headers; each `Set-Cookie` is
  emitted separately. Status must be 200–599. Framing, hop-by-hop and
  pseudo-header names are not guest-modifiable.
- `empty`, `text` (default UTF-8 content type), `binary`, and lazily opened
  `InputStream` response bodies. Byte buffers are copied defensively.
- Presence of any body, including zero-length text/binary/stream, is rejected for
  204, 205 and 304. HEAD emits no body and does not open a stream producer.
- Exactly one successful response claim and at most one opening of a body.
  The trusted adapter writes chunks of 8192 bytes on the existing virtual HTTP
  I/O worker. A slow client applies ordinary socket backpressure.
- `Exchange.respond(HttpResponseDraft)` retains existing ownership checks,
  cancellation/deadline tracking and actor/exchange admission cleanup.
  An active stream reference is closed when exchange cancellation occurs.
- The existing `Exchange.respond(status, type, body)` / `start/write/finish`
  interfaces remain available. Legacy `set_header` now enforces matching
  response-value and aggregate response-header bounds.

This is a **trusted host-side class**, not an actor-sendable class, not a browser
Fetch object, and not a guest-side Oreslang `new HttpResponse` constructor.

**Trailers:** not implemented in the JDK HTTP/1.1 adapter; explicit trailer
creation rejects, rather than claiming that a `Future<HttpHeaders>` is emitted.
HTTP/2 and HTTP/3 are not supported by this adapter.

**Remaining limitations:** client-disconnect cancellation, malicious or stuck
stream suppliers, large-output quotas, terminal races and exact once-only
admission cleanup require actor-runtime integration and dedicated live-socket
fault-injection tests; the standalone writer is not a replacement for those tests.

## Reproducible no-Maven local test

```sh
bash scripts/test-http-response-draft.sh
```

Requires JDK 21+; compiles the actual Java source and a standalone wire
check, sends HTTP requests to a loopback server, and rejects incorrect
status/header/body, HEAD, Set-Cookie, streaming and ownership behavior.

The corresponding JUnit tests are:
- `HttpResponseDraftTest` — immutable metadata, invalid headers, body presence,
  single handoff and lazy stream ownership.
- `HttpResponseDraftWireTest` — actual Java HTTP/1.1 response framing.

Run full verification with JDK25 and the existing Maven/Graal CI when a
trusted private runner is available. A Java 21 standalone pass is **not**
full Maven, Graal Native Image or guest-source conformance.

## Next implementation gates

1. Establish a private, authorized CI runner; the source repository's checked-in
   cross-repository destination allowlist is intentionally empty. **Do not**
   mirror private source to another organization without prior review/approval.
2. Implement a guest-visible immutable `HttpResponse` and `HttpMessage` in the
   type system and Graal execution nodes. Lower unary `run` return through the
   existing generation-bound exchange rather than exposing a socket or JDK
   `InputStream` to actors. Preserve the legacy handler migration path.
3. Supervise the body producer under the request's logical lifetime after
   `run` returns. Prove backpressure, deadlines, cancellation, actor death
   and race-safe response commitment under full real HTTP integration.
4. Add explicit protocol-supported trailers and then LLVM parity. Promote
   any marketing example only after its exact source runs on both backends.
