# Logical task trace context across await and suspension (Java v1)

Status: **host runtime context propagation substrate plus scheduler regression tests**.
This does NOT claim that compiler-generated spans, an AttoLang source-level
`using trace.span` statement, actor-mail propagation, OTLP export or native
backend parity have been implemented.

## Contract

- A trace is identified by an immutable, nonzero, W3C-shaped 128-bit trace ID
  and 64-bit span ID (32 and 16 lowercase hexadecimal digits), plus a sampled
  flag. The current narrow `TraceContext` type has no baggage or tracestate.
- `TraceContext.current()` is only a *carrier-turn cache*. The creating logical
  `OresScheduler.TaskRunner` captures it at admission.
- `TaskRunner.runTurn` reinstalls the saved context only after it has claimed
  the physical guest execution lease. It saves the latest context in the
  `finally` before restoring the carrier's previous context.
- A task's `await` and `cooperate` suspend its *execution*, not its trace
  context. Completion callbacks enqueue resumption without installing any
  awaited task's context on the producer thread.
- The scope must be restored on success, failure, cancellation cleanup and
  stale/duplicate-dispatch paths, including when another task uses the same
  single carrier. Child scheduler tasks capture the parent's current context
  at start; siblings do not inherit each other's context.
- Span creation and span end are **not** side effects of suspension or resume.
  Future compiler-generated lexical scopes must end once on actual lexical
  completion (including error and cancellation unwind), not once per turn.
  An external cancellation of a task that never resumes is not proof that
  its deferred lexical cleanup or span-end instrumentation has executed.
- `TraceContext` is diagnostic identity, not actor state authority or an
  ownership token. No host thread or ActorRef is stored in trace metadata.
- A disabled/unsampled span must retain the necessary propagation semantics;
  disabled recording cannot be interpreted as permission to lose remote
  parent context. Current v1 does not yet expose these APIs in source AttoLang.
- Trace events / export must not run in the scheduler's critical path, block
  carriers, take actor state loans, or change guest cancellation semantics.

## Present integration (reference Java runtime)

`src/main/java/dev/oreslang/runtime/TraceContext.java` defines the immutable
span identity and controlled carrier install/restore operation.
`OresScheduler.TaskRunner` owns a volatile saved context and rebinds it for
each physical resume. This deliberately does not rely on the existing
source-facing string `TaskContext` user keys such as `#trace_id`.

`TraceContextSuspensionTest` covers:

1. Actual scheduler await of an unresolved Future and later resume.
2. Active child span preserved; unrelated task on the same carrier has none.
3. Future producer callback cannot borrow the awaiter's active span.
4. Child task inheritance while the parent is waiting.
5. Cooperative handoff preservation.
6. Failure and cancellation isolation.
7. Malformed and all-zero IDs rejected.

This is **host scheduler execution**, not a compiled guest span fixture.
Any CI job with no executed steps is not a passing test.

## Next gates before claiming automatic distributed tracing

1. Add compiler-lowered start/end span intrinsics, with lexical frame state
   persisted through source async state-machine suspension, traps, defer/finally
   and authenticated cancellation cleanup. Prove at-most-once span end.
2. Treat an explicit span as active only while the *owning logical task* runs,
   with safe nested restoration. Use tracing-owned metadata, not guest-managed
   mutable thread-local variables.
3. Propagate traceparent/tracestate via HTTP and immutable message envelopes
   across actor ingress, pub/sub and tenant/VM boundaries. Use messaging span
   links when one message has multiple causal producers.
4. Fence actor generation, supervisor stop, hot reload, retries and stale
   continuation resumption. Never allow trace metadata to confer authority.
5. Implement bounded, nonblocking sampled span recording and asynchronous
   OTLP export, with drop accounting and safe redaction.
6. Run compiled guest integration tests on Java/GraalVM first, followed by
   LLVM, .NET, Wasm, Forge and JS semantics parity. Validate disabled mode,
   memory budgets, and production sampling overhead.

## External semantic references

- OpenTelemetry Context: https://opentelemetry.io/docs/specs/otel/context/
- OpenTelemetry Trace API: https://opentelemetry.io/docs/specs/otel/trace/api/
- Existing Atto/Ores source tracing design: docs/NATIVE_NETWORK_IO_AND_TRACING.md
