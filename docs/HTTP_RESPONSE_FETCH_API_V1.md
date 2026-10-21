# Fetch-style `HttpResponse` for unary HTTP actors — v1 target

**Status: design and conformance target, 2026-10-18. NOT compiler-verified or implemented on either Java/Graal or LLVM.**
This is a transport-specific specialization of
[`ACTOR_UNARY_STREAM_TRANSPORT_API_V1.md`](ACTOR_UNARY_STREAM_TRANSPORT_API_V1.md),
not a replacement for the shipping `http.claim` / `HttpExchange.respond` interface.
Do not update a public feature-verification bit until the exact-source compiler and real HTTP tests pass.

## Authority and deliberate deviations

The familiar surface follows the WHATWG [Fetch Response interface](https://fetch.spec.whatwg.org/#response-class):
`new Response(body, init)`, default status 200, `status`, `statusText`, `headers`,
`ok`, and a read-only body. [Streams](https://streams.spec.whatwg.org/)
supplies backpressure semantics. HTTP message framing and trailers follow
[RFC 9110 §§6.5 and 6.6](https://www.rfc-editor.org/rfc/rfc9110.html),
[RFC 9112](https://www.rfc-editor.org/rfc/rfc9112.html) for HTTP/1.1,
and the relevant HTTP/2 and HTTP/3 specifications.

This is **Fetch-inspired**, not a claim that all of browser `Response` is implemented.
`HttpResponse` is a server response builder/affine result, **not** a browser response.
`trailerNames` and `trailers` are **Oreslang extensions**; the standard
`ResponseInit` does not have these properties. Browser Fetch callers generally
cannot inspect received trailers. There is no implicit writable
`response.stream` or `get_response()` on a unary actor.

## Canonical source shapes

```ores
define unary http actor HelloHttp as
  run(HttpMessage request): HttpResponse {
    return new HttpResponse("hello from Oreslang", {
      status: 200,
      headers: {
        "Content-Type": "text/plain; charset=utf-8"
      }
    });
  }
end
```

```ores
define unary http actor Download as
  run(HttpMessage request): HttpResponse {
    const (body, digestReady) = checksumDownload();

    return new HttpResponse(body, {
      status: 200,
      headers: {
        "Content-Type": "application/octet-stream"
      },
      trailerNames: ["Content-Digest"],
      trailers: digestReady
    });
  }
end
```

`checksumDownload()` above is a **hypothetical, request-supervised** producer returning
an owned readable byte stream and `Future<HttpHeaders>`. It is not a built-in.
The stream remains a live response operation *after* the handler returns.
Unsupervised background tasks, escaped actor state loans and raw socket handles
are not permitted.

### API shape and constructor arity

| Name | v1 contract |
| --- | --- |
| `new HttpResponse()` | Empty 200, no body |
| `new HttpResponse(body)` | One owned string, byte buffer, or readable byte stream |
| `new HttpResponse(body, init)` | Body and `HttpResponseInit` |
| `HttpResponse.empty(init)` | Bodyless response with custom status/headers |
| `HttpResponse.json(data, init?)` | Encode JSON using a checked codec and default JSON content type |
| `HttpResponse.redirect(url, status?)` | Validate redirect status and Location |
| `response.status`, `statusText`, `ok`, `body` | Read-only after construction |
| `response.headers` | `HttpHeaders` with Fetch-like `get/set/append/has/delete` until handoff |
| `init.trailerNames` | Optional declared field-name list, immutable at handoff |
| `init.trailers` | Optional `Future<HttpHeaders>` fulfilled after body EOF |

No constructor overloads may have the same effective user argument count: that
is an Oreslang-wide overload rule. The explicit `empty(init)` factory avoids
a one-argument ambiguity between body and options; absent bodies use an
overload/factory, **not `null`**, since Oreslang has option-safe data.
`ResponseInit`-like options are a statically known structural shape, not
a dynamic module/class instance.

`HttpResponseInit` has `status: int = 200`, `statusText: String = ""`,
`headers: HttpHeadersInit` (optional), and the two Oreslang-only trailer members.
String bodies gain `text/plain; charset=utf-8` if Content-Type is absent.
The exact byte-buffer/stream types and JSON codec must be finalized against
the standard library's static layouts before compiling this syntax.

## Single ownership and commit state machine

```text
NEW -> HANDED_OFF -> HEADERS_COMMITTED -> BODY_ACTIVE -> TRAILERS -> COMPLETE
                   \                                  /
                    ----------------------------------
                         (bodyless/HEAD or no trailers)

At any nonterminal phase: cancellation/timeout/disconnect/failure -> ABORTED
```

1. `HttpMessage` is a request-scoped, runtime-minted, non-Sendable capability.
   Only a transport-bound unary actor may return `HttpResponse` as a special
   **result handoff**. The result cannot be stored as actor state, sent as ordinary
   `ActorMail`, serialized to `ActorRef.request`, cloned across actors, or returned
   from a non-HTTP unary handler.
2. After one successful `run`, the trusted adapter **consumes** exactly one
   `HttpResponse` and the one owned body stream. It revokes guest mutation of
   headers/trailer declarations and any stale response handle.
3. The actor handler frame may finish immediately. The request's **logical
   response lease**, admission credit, trace, cancellation token, stream producer
   and exchange must remain live until completion or abort. Finalizing the
   handler is not evidence that bytes were sent. One terminal outcome must
   settle the exchange/completion exactly once.
4. Transport-side pulls and writes respect bounded byte/item credit, propagate
   demand to the stream producer, and never allocate an unbounded implicit
   buffer. No blocking actor carrier for socket I/O. Suspendable work uses
   compiler-owned continuation/lifetime management, not detached guest futures.
5. At successful body EOF, await the bounded trailer future (if present),
   validate the declared names, emit allowed trailing fields, and complete.
   Trailer timeout/failure after headers commit **aborts/resets**; it cannot
   send a second HTTP response. Cancellation cancels both body and trailer
   future and releases every lease exactly once.
6. Status, headers and body source are no longer mutable by user code after
   handoff; `response.body` is never a writable transport sink. Status/headers
   commit happens before the first body byte, possibly after `run` returns.
   A separate STREAM HTTP actor may retain its existing explicit writer API;
   that writer must never coexist with a unary-return response for a request.

## HTTP protocol checks and security

- Status is an integer in **200–599** for ordinary constructed responses;
  1xx (including 101 upgrades) is a separate protocol mechanism. `ok` means
  status 200–299. `statusText` is a validated reason phrase and has no
  status-line effect on HTTP/2 or HTTP/3.
- A constructed response with a **non-absent** body is rejected for 204, 205
  or 304. HEAD suppresses outbound body and trailer bytes while preserving
  the semantics of selected response metadata; it cancels/unwinds its body
  source without sending it. No conflicting framing headers.
- Reject malformed header field names, embedded CR/LF/NUL, invalid values,
  and multiple different-case spellings that bypass checks. Header operations
  are case-insensitive. Preserve multiple Set-Cookie fields without
  invalid comma folding. Enforce counts and total header/trailer size quotas.
- The transport owns `Content-Length`, `Transfer-Encoding`, `Connection`,
  HTTP/2 pseudo-headers, `Trailer` advertisement/framing, and protocol-specific
  hop-by-hop fields. Guest-supplied framing/control headers fail closed.
  A known body length may be emitted only when the adapter validates it.
- A trailer future requires **prior `trailerNames`**. At handoff validate each
  name against the same field-name and forbidden-field policy; at completion
  reject fields not declared and prohibited fields (framing, routing,
  authentication, Content-Length, pseudo-headers). For HTTP/1.1 use chunked
  framing and advertise declared names before commitment; for HTTP/2/3 emit
  a final trailer header block only if the backend actually supports it.
  Never claim HTTP/2/3 parity merely because the Java JDK HTTP/1.1
  server can write chunked bodies.
- HTTP intermediaries or clients may discard trailers; they may not carry
  metadata essential to processing the body. A request that disallows trailers
  follows an explicit fail-closed/omit policy chosen *before* headers commit,
  never silently drops a promised essential integrity guarantee.
- On handler failure **before** headers commit, the trusted error mapper can
  emit an appropriate single error response. After headers commit, fail by
  aborting/resetting the stream; do not attempt to rewrite status or headers.
- Ownership/generation checks apply across cancel, actor restart, connection
  reuse, pooled actors and untrusted actor relays. No raw socket/FD or host
  stream objects cross the guest boundary. Enforce deadlines, ingress limits
  and explicit output quotas.

## Relationship to scalar unary returns

A unary HTTP actor returning a non-`HttpResponse` `R` may continue to use the
separately specified typed JSON codec. This does **not** imply that a returned
`HttpResponse` is JSON-serialized. `HttpResponse` is an exact nominal
transport result recognized by the compiler/adapter. The previously proposed
`HttpReply<T>` can remain an opt-in data/codec abstraction, but it must not
become a second writer or override the explicit Fetch-style response.

## Cross-backend acceptance gates

1. Parser + typechecker: recognize the exact `HttpResponse` result only in
   `define unary http actor`; reject escape/send/state storage, invalid
   constructor arity, bodyless mismatches, false trailer declarations,
   callback/state-loan escapes, and unsupported async/transport modifiers.
2. Java/Graal: adapt `HttpMessage` and the response handoff to the existing
   owner-authenticated `HttpExchange`, preserve the legacy `http.claim`
   adapter, and prove byte streaming without buffering. Native Image AOT
   and hybrid are separate tested targets.
3. LLVM: implement the same actor-source syntax, ownership transitions,
   bounded HTTP response streaming/trailer framing, and cancellation/EOF.
   The native C host response writer alone is not guest-language parity.
4. Run identical **real HTTP** positive and negative tests: text, JSON,
   201/204/205/304, HEAD, redirect, no-body validation, duplicate headers,
   Set-Cookie, CRLF, overlength fields, backpressure under slow clients,
   binary multi-chunk, late exceptions, cancel, panic, trailer completion,
   trailer mismatch, trailer timeout, connection reuse, actor restart,
   concurrent requests, shutdown, and HTTP/1.1 framing assertions.
5. Run source compilation on both backends, JDK 25 tests, Graal AOT/hybrid,
   LLVM sanitizer runs, and exact-tree CI provenance. Until these pass,
   examples and fixtures remain **design-only**.

Contract vectors and a small reference checker live in
`contracts/v1/http-response-fetch/`; they test the **reference metadata rules**,
not compiler or network-runtime behavior.


## Security audit and hardened v1 rules (2026-10-18)

1. **Actual body type:** one constructor argument `HttpBody` is an explicit,
   compiler-known sum/trait over owned UTF-8 text, owned byte buffers and owned
   readable byte streams; it is not a family of same-arity overloads distinguished
   only by parameter type. A stream can be constructed only from a request-owned,
   cancellation-aware producer. Neither class nor module types may be dynamically
   created. `HttpResponse` remains nominal and affine.
2. **Null-body semantics:** the *presence* of a body, even a zero-length text
   string or zero-length stream, is prohibited for 204, 205 and 304, matching
   Fetch's null-body status validation. `HttpResponse.empty(init)` does not
   create a body. For HEAD, constructing a response with a body may be valid but
   the transport must never read/send that body; it promptly cancels/settles the
   producer and does not await a phantom EOF/trailer. HEAD trailer declarations
   are rejected in v1.
3. **Trailer compatibility is explicit:** a trailer future is permitted only
   with a nonempty, unique case-insensitive `trailerNames` set and a readable
   streaming body. For HTTP/1.1, require a trailer-capable transport and a client
   `TE: trailers` opt-in; otherwise *reject before headers commit* under the
   v1 fail-closed policy. HTTP/2 and HTTP/3 additionally require actual frame
   writer support. Neither the `Trailer` nor `Transfer-Encoding` header can
   be set by user code. Declaring trailers does not guarantee an intermediary
   or browser will expose them to the ultimate consumer.
4. **Permitted trailing fields:** RFC 9110 §6.5.1 forbids fields whose
   definitions do not permit trailer use. The default v1 registry is
   `Content-Digest`, `Repr-Digest`, and `Server-Timing`; any extension
   requires an audited registry change. In particular `Location`,
   `Content-Type`, routing, authentication, connection and framing fields
   may **not** appear in trailers. A trailer future resolving an undeclared,
   duplicate, or invalid field aborts the already-committed response.
   `Content-Digest` follows RFC 9530 Structured Fields (e.g.,
   `sha-256=:base64digest:`), not an ad-hoc hexadecimal header. The digest
   example is *best-effort metadata*, not a promised end-to-end integrity check.
5. **Header policy:** field names are ASCII RFC 9110 tokens; values are
   HTTP ByteString-compatible with no CR/LF/NUL, other C0 controls or DEL.
   Reject non-ByteString Unicode scalars, bounded counts/bytes, and
   case-insensitive prohibited names. Transport owns `Content-Length`,
   `Transfer-Encoding`, `Connection`, `TE`, `Trailer`, `Upgrade`,
   and HTTP/2 pseudo-headers. Preserve individual `Set-Cookie` occurrences
   without comma coalescing. A field-name casing trick cannot bypass policy.
6. **Handoff linearization:** `run` returning a response transfers a single
   immutable metadata snapshot and affine stream lease; exactly one of
   `COMPLETE` or `ABORTED` publishes. Callback/continuation races
   (disconnect, panic, deadline, EOF, trailer-future resolution, pooled actor
   replacement) use a generation-checked compare-and-set terminal barrier.
   In-flight native writes may finish cleanup, but never publish a second
   response or retain an unbounded buffer after terminal publication.
7. **Replay determinism:** fixed random seeds and exact-tree SHA manifests
   must accompany evidence. Python reference metadata/lifecycle tests are
   **design-policy tests only** and cannot upgrade Java/Graal or LLVM case
   status. Actual HTTP wire framing, async body backpressure, and actor
   ownership require executable Oreslang and live socket tests on each backend.

Primary normative references:
[WHATWG Fetch](https://fetch.spec.whatwg.org/#response-class),
[WHATWG Streams](https://streams.spec.whatwg.org/),
[RFC 9110 §6.5](https://www.rfc-editor.org/rfc/rfc9110#section-6.5),
[RFC 9112](https://www.rfc-editor.org/rfc/rfc9112),
[RFC 9530](https://www.rfc-editor.org/rfc/rfc9530).
