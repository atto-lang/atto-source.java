# HTTP response object — executable Java transport foundation

**2026-10-18: host adapter with a narrow transitional guest API; NOT unary HTTP actor support.**

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

This is a **trusted host-side class**, reachable only through the new narrow
Oreslang `http.response` facade and the trusted exchange handoff. It is not
actor-sendable, not a browser Fetch object, and not a guest-side Oreslang
`new HttpResponse` constructor.

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

## Guest-language bridge (2026-10-18, proposed integration tests)

The current Oreslang parser can already compile traditional
`define actor` + `receive(ActorMail<String>)` handlers. This branch now adds
a **narrow transitional guest API** using the existing HTTP exchange:

```ores
define actor Hello as
  receive(ActorMail<String> mail): void {
    val request = http.claim(mail.value);
    val response = http.response(201, "text/plain; charset=utf-8", "hello from Oreslang");
    await request.respond_response(response);
    self.end();
    return;
  }
end
```

- The typechecker recognizes `http.response(int, string, string): HttpResponse`,
  `new HttpResponse()`, `new HttpResponse(string)`, and
  `http.response_empty(int status): HttpResponse` for 204/205/304 and other
  bodyless statuses, and `HttpExchange.respond_response(HttpResponse): Future<void>`. These are
  compile-time **nominal** `HttpResponse` values backed by the private
  `HttpResponseDraft` Java transport class.
- The interpreter routes the guest response through the **same**
  `NativeHttp.Exchange.respond(HttpResponseDraft)` adapter and its
  generation-fenced exchange authority.
- `HttpResponseDraft` is rejected as an ordinary actor-mailbox sendable
  boundary type, so this does **not** allow sending HTTP response handles
  between actors. The resulting object remains an opaque, runtime-owned
  value, not the public final Oreslang `HttpResponse` type.
- `NativeHttpTest.guestResponseDraftIsTypedAndTransferredToRealHttp11Exchange`
  exercises source compilation and a real HTTP request for both shared and
  isolated actors. Its CI result is pending an authorized runner.

The minimal Fetch-like spelling also compiles through an existing handler:
```ores
val reply = new HttpResponse("hello from Oreslang");
await request.respond_response(reply);
```
A zero-argument constructor creates a bodyless 200 response; the one-argument
constructor creates UTF-8 text with default text/plain content type.
**The two-argument Fetch options initializer is explicitly rejected** until
its status/header semantics and frozen ownership are implemented.

This transitional adapter is intentionally **not** the final
`define unary http actor … run(HttpMessage): HttpResponse` grammar and
does not support guest-owned streamed bodies, arbitrary structured headers,
JSON helpers, or trailers. It must not be used as evidence that the marketing syntax is
shipping.

## October 18 hardening audit — exact-file local evidence

After PR #1399's guest bridge, a review uncovered two additional problems in
the trusted HTTP/1.1 response writer. Both were fixed and tested:

- **Cancellation precommit:** `HttpResponseDraft.writeTo` now checks the
  request's cancellation predicate *before* emitting any HTTP headers, and
  checks again before each potentially blocking stream read and before each
  write. A cancelled request cannot start a producer if already aborted.
- **Combined header quota:** a response's own frozen header list and the
  exchange's previously staged headers are validated together, before wire
  commitment. The aggregate cap is 100 fields / 32 KiB; individually legal
  header sets cannot bypass the cap by being concatenated.
- **Affine concurrency:** a 12-carrier race on one response is required to
  admit exactly one handoff. The contract also covers immutable body copies,
  multiple `Set-Cookie` fields, HEAD stream suppression, 204, forbidden
  framing headers, and CR/LF and NUL injection.
- **Cancellation during streaming:** an already-open producer is closed
  exactly once and not read once cancellation has been observed.
- **Socket regressions:** a real loopback HTTP/1.1 client confirms that
  cancelled and over-budget responses close without transmitting headers,
  and that valid buffered/streamed requests continue to work.

The committed Java source blob SHA is
`12795f73da01f5423a95756d72176c43277addba`; the committed standalone
wire checker SHA is `e62b4e00cd49a805aebf625bc14b921c1bcee4ed`.
Both hashes were verified against *the exact locally compiled files*. JDK21
`scripts/test-http-response-draft.sh` **passed five consecutive local runs**.
These are transport-only checks, not compiler, Java/Graal native-image, LLVM,
or true guest streaming conformance.

The source repository's checked-in private-CI destination allowlist still
contains **zero approved repositories**. Candidate private test organizations
checked on October 18 show zero-step runner failures; neither a funded runner
nor protected approval for cross-org source copying has been demonstrated.
No cross-repository export is authorized.

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
