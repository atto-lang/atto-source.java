# AttoLang scoped telemetry spans and runtime activation (experimental)

**Status: Java/Graal feature-branch implementation in progress.** The Java
parser now admits `using telemetry.span(...) as span { ... }` and lowers it
into an ordinary Atto-local binding with `try { ... } finally { span.end(); }`.
Corresponding JUnit real-guest execution tests have been authored for normal
fallthrough, nested scopes, return, failure and suspension/await. **They have
not yet been executed on the exact branch head.** Do not claim production
effect-safe semantics, native OTLP export or full backend parity.

## Source syntax

```atto
import module telemetry from "ores.std/telemetry";

pub fnc checkout(Order order): Result<void, Error> {
  using telemetry.span("checkout") as span {
    span.attribute("order.type", order.kind);
    return process_order(order);
  }
}
```

The parser recognizes the scoped expression as an ordinary `telemetry.span(...)`
call. A minimal native `stdlib/telemetry.atto` module **now exists** and is
registered at `ores.std/telemetry` (with legacy `std/telemetry` alias).
It supplies non-recording `span()` and `span(name)` factories, a
`Span.is_recording()` query and idempotent `Span.end()` method.
The source fixture and `NativeTelemetryScopeGuestTest` that compile and run
this module have been authored but **NOT EXECUTED**.

The true recording `span.attribute` API, trusted `TelemetrySpanScope` type,
automatic logical active-context activation and OTLP exporter are still
**NOT IMPLEMENTED**. Separate `UsingTelemetrySpanScopeTest` cases use a small
user-defined guest `Telemetry` class to exercise lexical cleanup semantics,
not OTel export.

The parser must not reinterpret `using resource.borrow() as view` as a span
scope: borrowed views retain their distinct AST node and fail-closed lifetime
checks. It also retains ordinary function calls named `using(...)` and
signature-level `using` effect clauses.

## Semantic requirements before production

- `using` evaluates the span factory once, binds its result for one lexical
  scope, then calls cleanup at most once even when exiting by return, throw,
  raise, panic, cancellation or suspended task teardown. Current lowering
  inherits the implemented `try/finally` behavior for the cases in the test.
- **Do not infer effects away:** calling a user-defined `end()` may throw or
  suspend. The future type checker must require a trusted
  `TelemetrySpanScope` cleanup protocol with non-suspending, no-throw,
  non-blocking completion so telemetry errors never mask business exceptions
  or panic CONTROL messages. Current AST lowering has not proved that.
- Creating a `Span` and activating it in logical context are distinct
  operations. The `using` construct should activate context on entry and
  restore the previous context on exit; the existing `Span.end()` desugaring
  does not yet implement that activation.
- A live mutable span remains actor-owned. Only immutable trace context
  crosses `await`, channel and actor envelopes, with incarnation and tenant
  checks. No host thread-local identity serves as semantic authority.
- If instrumentation is disabled, auto-generated call sites must avoid
  evaluating expensive attributes, allocation, clocks or output; manual
  explicit `telemetry.span()` still executes its user-written expression.

## JIT reconfiguration: allowed subset, not magical new call sites

A new internal `TraceProbeEpoch` uses a **per-domain Truffle Assumption**.
Trusted configuration replaces a versioned policy and invalidates the old
assumption; optimized call targets depending on it may deoptimize and then
recompile to observe the new mode. This mechanism does **not** rewrite
bytecode, monkey patch guest objects, attach Java agents or create new
compiler sites.

The policy tests cover OFF default, `BOUNDARIES`/`CALLS`/`FULL` coverage
ceilings, independent domain policies, sealed profiles and no-op updates.
These are **host policy unit tests**, not a full Truffle optimizing-JIT or real
compiler instrumentation proof. The gate is now **wired to the checked callable dispatcher**, which records
numeric call admission durations in a fixed-size, per-isolate
`AutoTraceRecorder`. It does **not** yet trace async *source function completion*,
export OTLP, or provide a trusted, externally accessible runtime
reconfiguration service. The numeric recording gate is controlled by trusted
`--ores-telemetry=calls` at startup. An explicitly requested
`--ores-telemetry-receipt=stderr` emits a **cold shutdown-only diagnostic
receipt**, never synchronous guest hot-path output. Its site IDs are an
internal provisional hash and **not** cross-backend stable public IDs.

A trace-capable JIT/AOT build can change the **recording mode** without
redeployment only for probes which were compiled in. If we want to
*introduce* new entry probes after startup, we must retain validated typed
source/MIR and compile new versions through a governed JIT with
versioned dispatch and quiescence. Java/Graal JVM may support this in
principle, but it is not implemented here. In sealed Graal Native Image,
LLVM AOT, .NET Native AOT and freestanding Wasm without such a JIT, new
uncompiled sites **require new code**; precompiled dormant probes can still
be enabled.

## Immediate actual guest gates

1. Run `UsingTelemetrySpanScopeTest` with the exact compiler source branch.
   Confirm its emitted `try/finally` cleanup and no borrow regression.
2. Run `TraceProbeEpochTest` with JDK 25, including deopt/reoptimization
   instrumentation (current unit tests only verify the policy results).
3. Preserve all previous `NativeTraceStdlibTest` execution checks and run
   real `.atto` tests under the optimizing Graal runtime, not interpreter
   fallback.
4. Extend the native non-recording `ores.std/telemetry` module into a
   typechecked `TelemetrySpanScope` recording implementation, plus runtime
   context capture/restore; then run real HTTP/actor/HTTP tracing with an
   external OTLP receiver. Until then the syntax alone is not distributed tracing.
5. Repeat the same guest on LLVM; its currently narrower native compiler
   needs separate parser, ownership, unwind and actual nonblocking HTTP work
   before matching claims can be made.

Related contracts:
[shared zero-code tracing](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/docs/AUTO_INSTRUMENTATION_AND_DISTRIBUTED_TRACING_V1.md)
and [shared v1 instrumentation ABI](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/atto-instrumentation-contract-v1-20261112/contracts/v1/instrumentation-api-v1.json).


## Additional Java/Graal implementation slice: immutable context and HTTP

- `TraceParent` is a strict **W3C v00-only**, lowercase-hex,
  nonzero 16-byte trace ID / 8-byte span ID value. Roots/children use
  `SecureRandom`, preserve the sampling flag and reject malformed headers.
  Parsing does not presently support future W3C versions or `tracestate`.
- `TaskContext.pushTraceParent()` stores the immutable parent inside the
  task's already heap-retained logical context chain. `SourceTask` saves and
  restores this same chain across suspend/resume; OS/JVM thread-locals are
  caches for the current turn, not the authority.
- `ActorRuntime.MessageEnvelope` now includes a private immutable
  `TraceParent` field *outside* the user message graph. Both synchronous
  send and asynchronous mailbox admission capture the sender's value **before
  any asynchronous waiter callback**. USER dispatch installs the captured
  context, then restores its carrier's prior state on normal/error exit.
  Propagation is disabled by default and excluded for untrusted recipients.
  This has not been proven on the exact Java compiler branch.
- `OutboundHttpRequest.request()` captures the parent at guest admission,
  after the existing network permission check, and adds a new valid child (or parentless root)
  `traceparent` HTTP header to an immutable list handed to the physical I/O
  worker. It rejects duplicate user-supplied tracing headers and respects the
  established 100-header/32768-byte request limits.
- `NativeHttp.Server.receive` now extracts a strictly validated incoming
  `traceparent` once during HTTP admission, stores an immutable child/root
  context on the internal exchange state, and `Handler.dispatch` installs
  it around actor-mailbox admission. The sender's previous logical context
  is restored on both success and failure. Duplicate/malformed traceparent
  is never adopted as the parent. A sampled semantic HTTP SERVER span
  now starts at admission and completes at physical exchange retirement.
  This does **not** yet export an OTLP span.
- `SemanticTraceBuffer` now stages sampled SERVER, actor dispatch-turn
  INTERNAL, and outbound CLIENT spans with explicit W3C parents, numeric
  names, status and Unix nanosecond timestamps. Completion is idempotent,
  and the client span ends only when physical asynchronous I/O settles.
  This is a bounded, nonblocking **host staging** data structure, not an
  OTLP exporter. A suspended Atto source Task's full lifecycle still needs
  an independently correct completion span.
- `stdlib/telemetry.atto` now contains a native `CompletedSpan` value
  and an actor-owned bounded `SpanBatch` with overflow accounting. The
  guest batch test is authored but NOT_EXECUTED. This class is **not yet a
  collector actor**; no runtime→Atto handoff or OTLP export exists.
- **Still missing:** native Atto collector actor, the source-owned
  permission-checked OTLP/HTTP POST exporter and runtime-to-guest span
  delivery/draining, safe
  `tracestate` handling, actor-tenant trust policy, compiler-generated full
  function body probes, and actual Java/LLVM executed guest trace receipts.
  The Java HTTP frontend still relies on a host transport rather than the
  proposed native `std/http` implementation.

Tests authored on this branch:
`AutomaticCallProbeGuestTest`, `AutoTraceRecorderTest`,
`TraceParentTest`, `TraceTaskContextTest`,
`ActorTraceContextPropagationTest`, and
`OutboundHttpTraceInjectionTest`,
`NativeHttpInboundTraceContextTest`, and
`HostOnlyTracePathCompositionTest` (host-only W3C → real JVM actor mailbox →
outbound header). None has an exact-head nonzero-step passing CI receipt. **Do not present a source-level match as runtime pass.**


## Local exact-blob smoke receipts (not full backend CI)

- Exact GitHub blob `38315cf30146d587fff795aeb5538793f8a765a1`
  (`TraceParent.java`) compiled with local `javac --release 21` and
  passed a standalone 2,000-root/child W3C parse/format/random-ID smoke.
  **This is not JDK 25/Graal compiled guest evidence.**
- Exact Java blob SHAs `410c17cc51b140a6835b344460d191617b0f4c86`
  (`TraceProbeEpoch`), `b9b5c25bd169614fb4b4d84d5ab4a148b456aac1`
  (`AutoTraceRecorder`), and
  `6f5a391bc6a4060a6421648274add60d8f6e4673`
  (`SemanticTraceBuffer`) compiled locally using JDK 21 and **minimal
  Truffle API stubs** (actual deopt/reopt NOT tested). A standalone
  concurrent recording smoke passed: one SERVER→ACTOR→CLIENT parent chain,
  800 concurrent observations, capacity 64, exactly 736 dropped.
- `OutboundHttpAutoTraceGuestTest` now proposes a real Atto guest and
  loopback HTTP socket oracle for OFF vs ON root-header propagation. It has
  NOT_EXECUTED.
- C++ W3C header + native test at exact blob SHAs
  `643e7e3a5ac826a516ae8cbbee0f7f19274163dd` and
  `f6c1e612e9493ceb1b99b50681e9e05783da5c85`
  passed standalone GCC 14 and Clang 17 C++17 with ASan/UBSan. These are
  host-only, **not LLVM-compiled Atto source**.

## Exporter blocking dependency

The Java/Graal guest `http.request` and pinned transport now contain
an **authored, not guest-executed** bounded POST implementation. A fourth
argument admits UTF-8 `String` or `List<int>` (portable byte-octet bootstrap)
with a 1 MiB encoded-body maximum, immutable admission snapshot, reserved
transport-generated `Content-Length`, existing permission and canonical
loopback URL guards, and selector-driven physical I/O with deadlines and
cancellation. GET/HEAD remain supported without bodies. A true native
`Bytes` nominal guest type and network destination policies beyond
canonical loopback are still separate compatibility/security gates.

The native `ores.std/telemetry` module now provides a **pure**
OTLP/HTTP protobuf-JSON encoder (`CompletedSpan.to_otlp_span_json`,
`SpanBatch.to_otlp_json` for batches of 1..64) and
`post_otlp_json_async(endpoint, json)`. The encoder emits lowercase
hex W3C IDs, numeric span kinds and **quoted decimal** nanosecond times per
the [current OTLP JSON specification](https://opentelemetry.io/docs/specs/otlp/#json-protobuf-encoding).
It fixes low-cardinality operation names and carries no free-form attributes,
URLs, request bodies or credentials into spans. The native Atto constructor
now checks lowercase hex, exact 32/16-character W3C widths and nonzero IDs
using a source-level literal-split character counter. The future trusted
runtime bridge must independently validate the same properties and
receive samples from the bounded runtime staging queue.

`NativeTelemetryActorOtlpGuestTest` uses a real .atto one-shot
`TelemetryCollector` actor fixture, with actor mailbox delivery, pure
encoding, and async POST to a real loopback receiver. It is a **test source,
NOT_EXECUTED**, and is not a production collector with reusable mailbox
drain, retry, sampling, partialSuccess acknowledgement, exporter
self-instrumentation suppression or supervised shutdown. A status 200
alone is **not** proof of OTLP acceptance; a compliant collector must inspect
`partialSuccess` before calling `SpanBatch.clear_acked()`.

The full automatic HTTP SERVER → actor INTERNAL → HTTP CLIENT → OTLP
receiver trace still **has NOT_EXECUTED**. No source-level syntax,
host smoke or synthetic JSON packet substitutes for that result.

## 2026-11-12 security audit: transport, parentage, private CI

- Semantic span admission and `Completed` validation now reject an all-zero,
  uppercase, malformed or self-referential 16-hex parent span ID. Empty parent
  means root; `httpStatus` may be 0 (unknown) or 100..599 only. Added negative
  `SemanticTraceBufferTest` cases. Exact Java Git blob
  `9a065aa1ccddf65002fbe4db783320374f3001d1` and unchanged
  `TraceParent.java` blob
  `38315cf30146d587fff795aeb5538793f8a765a1` were compiled under local
  JDK 21 with a **minimal test-only trace gate stub**, then passed 8,026
  assertions including 800 simultaneous MPSC submissions, capacity 64,
  correct dropped accounting and W3C round trips.
  These tests do NOT prove JDK25, GraalVM, or actual Truffle deoptimization.
- For outbound POST, canonical destination NET permission now gates
  **before** copying up to 1 MiB of guest-owned bytes. The immutable
  `String` admission path rejects lone UTF-16 surrogate code units instead
  of relying on Java's lossy default UTF-8 replacement; valid supplementary
  pairs such as emoji are retained. The exact method body extracted from
  `OutboundHttpRequest.java` was compiled and exercised under local JDK21
  in an isolated class with 18 assertions (the complete source file and
  real .atto HTTP guest were NOT compiled).
- A source-free private runner capacity canary ran in
  `benefactor-cc/benefactor-e2e`, at commit
  `60b0b9d36feaa15a2658b20dfd2ad93b77cc1788` and GitHub Actions
  run `43330250212`: **SUCCESS, three executed job steps**.
  It did not check out, mirror, or expose private Atto source. This
  establishes *runner capacity only*, NOT source-transfer authorization.
- Source `.github/private-ci-approved-repositories.txt` and
  `.github/private-ci-carrier-contracts.json` require an exact reviewed
  destination and a PR-specific governed plan. The private successful
  benefactor carrier is **not approved for Java PR #2989**. No private
  source mirror was created. Exact-tree or exact-blob publication via the
  connector is not an allowlist/reviewer bypass.
- Full JDK25/Graal compiler, actor guest, HTTP POST/OTLP socket, native
  collector delivery, backend parity and true zero-instrumentation E2E
  remain **NOT_EXECUTED / NOT_IMPLEMENTED**. Keep this PR draft.

