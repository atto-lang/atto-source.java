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
  pre-header-cancelled and over-budget responses close without transmitting
  headers, and that valid buffered/streamed requests continue to work.
  Cancellation after headers commit instead aborts the active body stream.

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

## Response preflight and atomic exchange claim (2026-10-18)

An additional audit found that the initial Java adapter marked the HTTP
exchange committed before validating the combined response headers. A
rejected validation therefore consumed the exchange and could obscure whether
headers had reached the wire.

The hardened path is now:

1. `HttpResponseDraft.preflight(exchange)` validates metadata and the entire
   staged-plus-new header budget without consuming an affine response handle,
   setting headers, or initiating network I/O.
2. Under the **same State monitor** protecting terminal cancellation and header
   mutation, `NativeHttp.Exchange.respond(HttpResponseDraft)` checks the
   terminal and committed flags, preflights, claims the response exactly once,
   and marks the exchange committed with its status.
3. The asynchronous I/O worker calls `writeClaimedTo(...)`, which checks
   cancellation before emitting headers, streams through bounded chunks,
   and defers completion/release to the existing exchange state machine.

This creates one linearization point for ownership claim versus deadline/actor
cancellation and ensures **validation failures cannot mark a response
committed**. Once physical socket I/O begins, a racing cancellation may
terminate a partially emitted response; no stronger wire-level rollback is
claimed. The standalone writer's public-internal `writeTo` path uses the
same preflight before consuming its handle.

A new standalone real-HTTP regression tests an over-budget staged header map,
a rejected preflight, correction, and successful emission from the **same**
response instance. Its exact committed Git blobs were independently matched
against JDK21-compiled local source:
- `HttpResponseDraft.java`: `a60ddd6cb408664b1d0c1a36959651f5411def34`.
- `HttpResponsePreflightCheck.java`: `b595672e59315964d191bdd457243c226bbee796`.

A one-time `emissionStarted` CAS additionally prevents a second wire
emission through the trusted `writeClaimedTo` path, even if the caller
already holds a claimed response. The independent regression now asserts
that replay is rejected after a successful response.

Five consecutive local preflight regression runs passed. The test is
reproducible via `bash scripts/test-http-response-preflight.sh`.
**The full `NativeHttp`/Graal compilation and tests are not verified by this
standalone test**; the source repo's JDK25 CI still requires an admitted runner.
The repository's reviewed private-source mirror allowlist remains empty.

## Committed close-once regression and real-socket cancellation proof

The response body is now wrapped in `CloseOnceInputStream`, using an
`AtomicBoolean` to guarantee that the underlying producer's `close()` is
called at most once even when the asynchronous abort cleanup and normal
response writer both close the same stream. This is separate from the
one-time response handoff and one-time wire-emission guards.

Evidence pinned to exact committed Java blobs:

- `HttpResponseDraft.java`: `0fb24ab2d287e88c85127f2c84613b380a5efc92`.
- `HttpResponseCloseRaceCheck.java`: `bfb0404e5476e4681f00bd6950522181749f297c`.
- `HttpResponseAbortRaceWireCheck.java`: `198ca4aaeb8cd26483eb0d6f040baec90f02af59`.
- `HttpResponsePreflightCheck.java`: `b595672e59315964d191bdd457243c226bbee796`.
- `HttpResponseDraftStandaloneCheck.java`: `dbb2d15a2143ef4ada21614b4a5b507d5c735790`.

Run all four no-Maven tests together with
`bash scripts/test-http-response-hardening.sh` (Java 21+).
Five consecutive local passes completed **500 eight-carrier response-close
races**, **125 loopback HTTP cancellation/close races**, and all preflight
retry/regular HTTP wire checks. Against the unpatched source, the close-race
regression detected eight delegate close invocations; this establishes test
sensitivity rather than merely a happy-path assertion.

The close-once contention test is also part of `HttpResponseDraftTest`, so it
will run in normal Maven/JUnit CI when runners are available. The JUnit
addition itself has **not** yet executed in Maven; source-org JDK25 Actions
still fail before executing any job steps.

**Still open:** end-to-end actor death, request deadline vs actual wire
commitment, stream supplier blocking/failure, guest source parsing/typing,
Graal Native Image, and LLVM parity. The candidate private GraalVM JDK25
runner `opto-sync-test/go-java-validation` has succeeded on source-free
canaries but cannot receive private source without the protected-main
security approval tracked in issues #1368 and #1409. No cross-org source
mirror has been performed.

## Stream supplier failure before HTTP response commitment

The trust-boundary audit caught a wire-level success misreport: the adapter
previously called `HttpExchange.sendResponseHeaders(200, ...)` before invoking
the lazy `InputStream` supplier. An immediately failing producer could therefore
produce **HTTP 200 with an empty body** instead of aborting before response
commitment. A newly authored real HTTP/1.1 regression reproduced this exact
failure against the old source.

The adapter now **opens and registers the response stream before emitting
headers**. Supplier-open failure closes the transport without sending a 200
response; cancellation is rechecked after producer registration and before
header emission. HEAD and bodyless responses do not invoke suppliers.
The buffer/chunk write loop, close-once body wrapper, and previously verified
preflight/affine state machine are unchanged.

- Exact current `HttpResponseDraft.java` Git blob:
  `0bbaaddb59f427404683d21a01ce0d2f046c89ac`.
- Exact `HttpResponseSupplierFailureCheck.java` blob:
  `243c8ce8100e6bf6f04591cd656b9329dadd520f`.
- `scripts/test-http-response-hardening.sh` now compiles and executes
  the supplier-failure check, concurrent close, real-socket cancellation,
  preflight retry and ordinary HTTP/1.1 wire check.
- **Three consecutive local JDK21 runs of all five tests passed.**
  The supplier failure check allows transparent client-side retry attempts
  but rejects any successful HTTP response when every producer-open fails.

Limit: a malicious or permanently blocking supplier that never returns from
its factory cannot be interrupted solely through a returned stream handle.
True bounded supplier acquisition requires an additional deadline-aware
producer task/interruptible operation contract; the current HTTP deadline can
close the socket but cannot guarantee cancellation of arbitrary host code.
Full JDK25 GraalVM/Maven and guest actor conformance remain unverified because
the source org's Actions jobs have zero executed steps. The private exact-tree
candidate requires protected-main approval (#1368, #1409); no source export
has been performed.

## Bounded body-supplier startup (October 18)

In production, the per-listener `NativeHttp.Server` now instantiates a
`HttpResponseBodyOpenGate` with `min(32, admissionCapacity)` permits.
Only *streaming* response suppliers consume permits; buffered/text responses
continue opening directly on the I/O worker and are not blocked by a hung
stream-supplier pool.

The trusted `HttpResponseDraft.writeClaimedTo` accepts an injected,
checked-`IOException` body opener. For actor HTTP exchanges, the injected
opener executes each stream supplier on a separate Java virtual thread and
waits only for the **remaining request deadline**. The transport writer polls
cancellation at no more than 10 ms intervals. If cancellation/deadline wins,
it abandons the result and interrupts the opener thread. Should the supplier
ignore interrupts and return later, the returned input stream is closed.
The supplier's gate permit is **not returned until the supplier actually
exits**: timing out the HTTP request never admits unlimited replacement
host tasks. This is a bounded-exposure contract, not guaranteed forced
termination of arbitrary uncooperative Java code.

Verified exact Git blob identities (Java 21 source-only smoke tests):

- `HttpResponseBodyOpenGate.java`: `c39d962e99d6d7735d65f58009d1fe5212d817ea`
- `HttpResponseDraft.java`: `1a488b2e0f3d6260cf05672007dd61cd9cb9aba6`
- `HttpResponseBodyOpenGateCheck.java`: `aac6dca701b62bdd9944b3b26aab6f63960c95a1`
- `HttpResponseBodyOpenWireCheck.java`: `7d6674cffdbdcc241d14a480e4255decb71fff4e`
- `scripts/test-http-response-hardening.sh`: `b7dbd3f13104f1aa3570c36bff54503ca1dd91e1`

Three complete local executions passed all seven standalone test groups.
The new tests covered 90 deliberately noncooperative timed-out suppliers,
bounded overload, 300 close contention races, 75 HTTP response cancellation
races, actual HTTP socket timeout and late close, ordinary buffered response
availability, supplier failure, header preflight retry, and HTTP framing.
The deadline/overload test is also invoked by JUnit
`HttpResponseDraftTest.blockingHostSuppliersRespectBoundedOpenerLifetime`,
but **full Maven/JDK25 has not yet run**.

Open limits: an uncooperative supplier can occupy one of a *bounded* number
of opener virtual threads indefinitely, and native resources acquired inside
host code are outside what the guest compiler can guarantee. A separate
production policy must restrict which trusted host suppliers are allowed.
Do not call this proof of unary HTTP actors or LLVM parity. The source org's
Actions jobs still fail before tests; the destination allowlist is empty,
with protected-main security review issues #1368 and #1409 outstanding.
No cross-organization source transfer was made.

## Executor-dispatch deadline accounting audit (October 18)

A new negative-control test identified an additional deadline bypass:
`HttpResponseBodyOpenGate.open()` previously recorded its start clock
**after** `Executor.execute()` returned. Even with a request's remaining
budget of 25 ms, an executor that spent 100 ms accepting the task could
allow the stream to be returned successfully.

The start clock now precedes executor dispatch, so dispatch/queue delay
consumes the **same** bounded startup budget as the supplier operation.
The response remains uncommitted on deadline failure. A dedicated regression
also checks abandonment before a queued task begins, execution rejection,
a supplier throwing during acquisition, and associated permit cleanup.

**Negative control:** prior `HttpResponseBodyOpenGate.java` blob
`c39d962e99d6d7735d65f58009d1fe5212d817ea`
failed `dispatch delay incorrectly reset body-opening deadline`.

**Committed exact-file positive evidence:**

- Patched gate blob: `c37c33a545929465c5dcd94bf44f3e68d9b948d9`
- New regression blob: `4acd5e12f2c36a4f398cd76af5a6d28d82b4e764`
- Full standalone runner blob: `e61d402e537b1807234febc64fb6235f2e1adf63`

Three consecutive Java21 executions passed **all eight** standalone
regression groups with matching Git blobs. An additional JUnit wrapper
`HttpResponseOpenSchedulingJUnitTest` was added for future Maven runs.
The JUnit wrapper itself has **not yet run in Maven**.

The gate's executor must be an asynchronously dispatching executor;
a caller-provided inline executor can itself block before returning from
`execute()`. Production `NativeHttp` uses a virtual-thread-per-task
executor. This hardening fixes deadline **accounting** for dispatch delay,
not forcibly terminating arbitrary blocking host code.

Full compiler/Native Image and guest actor integration remain blocked by
the source-org CI runner failure (zero test steps). The protected-main
approval policy still has no authorized cross-org private-source mirror
destination (#1368, #1409). No private source was exported.

## Reserved versus wire-start versus headers-sent state (October 18, 2026)

An additional cancellation audit separated three concepts that were previously
represented by one `committed` flag on `NativeHttp.State`:

1. `committed`: logical one-time actor response reservation (before opening
   the body supplier and before any physical HTTP/1.1 write).
2. `wireStarted`: a single attempt to emit response headers has been
   authorized under the *same synchronized State monitor* used by
   `State.abort()`. Cancellation before this point denies success headers;
   once this point is reached the server **must not synthesize another HTTP
   status**, even if the socket write has not returned.
3. `headersSent`: `HttpExchange.sendResponseHeaders` returned successfully.
   This is a transport API observation, not proof a remote peer received bytes.

The response writer now accepts two trusted callbacks:
`beginHeaders` (one-shot, checked before sending) and `headersSent` (called
after a successful send). The old standalone writer overload remains for
source-only host checks. Both `Exchange.respond(HttpResponseDraft)` and the
older `respondBuffer`/`start` paths use the State-owned callbacks, so all
three production response paths serialize cancellation against their first
header emission. Blocking network I/O is never performed under the State
ownership monitor. Abort does not generate another status after
`wireStarted` becomes true; request completion still records an aborted
response separately from a successfully completed response.

Real HTTP/1.1 loopback regression
`HttpResponseWireCommitCheck` exercises cancellation during a deliberately
blocked stream opener before any headers and during a blocked body read after
HTTP 200 headers are sent. It also checks exactly-once stream close and
records the two distinct state outcomes. Five focused runs passed, as did
three consecutive runs of the complete **nine-group** standalone Java21
hardening suite. Across the three complete runs the new test exercised
24 preheader cancellations and 24 postheader aborts; the earlier bounded
producer, stream-close, preflight and HTTP socket checks remained green.
The real HTTP test is also invoked by
`HttpResponseWireCommitJUnitTest`, which must run with future Maven CI.

Exact tested Git blob receipts:

- `HttpResponseDraft.java`: `70ef2ab508399634fe829f21e9a52325515bfa3e`
- `HttpResponseWireCommitCheck.java`: `9eed3553c110a226cba29cfc6e4606067001d5f6`
- `scripts/test-http-response-hardening.sh`: `27acc9af98b902d28d7333f1d47ea290593b8df6`

**Scope limitation:** the standalone test uses a synchronized test fixture to
model the native State monitor; it does not execute the whole guest actor
runtime, nor prove JDK25 Maven / Graal Native Image or LLVM behavior.
Actual `NativeHttp.State` was changed in source, but complete integration
validation remains a release gate. Source-org Actions currently fail before
running steps. The approved private mirror list is empty and the protected
branch security prerequisite remains unmet (#1368, #1409). No source was
exported across organizations.

## Production lifecycle guard and shutdown rejection recovery

The current NativeHttp.State now uses the same
`HttpResponseWirePhase` logic exercised by the independent concurrency
regression. The phase remains protected by the State ownership monitor.
An error-status response cannot race with a separate first successful-header
attempt. A header send that threw still counts as an irreversible wire
attempt: transport cleanup may close the socket but cannot synthesize
another status. Header-send success is recorded separately from the attempt.

Also, the old `State.finish()` silently returned when cancellation or a
deadline had already set `terminal`. This could complete the pending
`respond_response`/finish Future successfully for an aborted exchange.
It now throws an `IOException` on that path, which the existing operation
runner propagates as a failed Future. This is a correctness change requiring
the full NativeHttp guest integration suite on the admitted JDK25 runner.

Shutdown had another fail-open resource-accounting bug: if the I/O executor
rejected the abort's transport-close task, the old catch called `release()`
without setting `transportClosed`. Since `release()` explicitly checks
`transportClosed`, request admission could remain permanently occupied.
`HttpResponseCleanupDispatcher` now schedules cleanup on the normal I/O
executor, with an independent virtual-thread fallback on rejection.
Its once-only task guard also handles an executor that invokes a task and
then throws rejection. Both abort-time producer close and socket close use
this dispatcher, and Server.shutdown() uses it so a rejected drain task
does not strand the shutdown Future.

Standalone, exact-blob Java21 verification (3 complete runs):

- `HttpResponseWirePhase.java` blob
  `68348214d6c34a08f58dab89bc4d1852c5f2303c`.
- `HttpResponseWirePhaseCheck.java` blob
  `905b5d711d8ca33ac2ab7ac743e8bcb416c08271`.
  1,000 competing abort/header attempts each run, 3,000 total.
- `HttpResponseCleanupDispatcher.java` blob
  `eed5519d815855603cefc241b34dc5f2f4ba6ece`.
- `HttpResponseCleanupDispatcherCheck.java` blob
  `31653a7fd7bf224a8e4b52510e2642cc233c3054`.
  300 rejected cleanup tasks each run, 900 total, including normal
  dispatch, rejection, and once-only fallback semantics.

Both checks are committed into the standalone script
`scripts/test-http-response-hardening.sh` and have JUnit entry points
`HttpResponseWirePhaseJUnitTest` and
`HttpResponseCleanupDispatcherJUnitTest`.

These tests execute **the helper components used by NativeHttp.State**;
they are not a full NativeHttp or GraalVM integration run. The changed
NativeHttp.java file still requires real JDK25 Maven/JUnit/native-image and
guest-language regression execution before merging. Main remains
unprotected and the approved external private-source CI list is empty;
no private source has been mirrored to another organization.

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
