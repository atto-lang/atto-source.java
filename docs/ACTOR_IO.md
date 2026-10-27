# Actor-local I/O contract

This document defines the source/runtime split between process standard I/O and actor-local I/O.

## Namespaces

`stdio.*` and the root `print(...)` intrinsic are process/root-runtime I/O. They are not
available during actor execution. Actor code uses the implicit `io` capability instead:

```ores
pub suspends actor fnc worker(string value) -> void {
  await io.out.write(value);
  return;
}
```

`io` is execution context, not a field on `self`. It is not ordinary movable actor state,
must not be serializable or message-sendable, and must resolve against the current actor
incarnation/generation.

The actor-local namespace is reserved as:

- `io.in` — inbound byte/data stream.
- `io.out` — ordinary outbound data stream.
- `io.err` — structured error/diagnostic stream, distinct from process stderr.
- `io.events` — typed progress/lifecycle/application events.
- `io.meta` — bounded immutable/versioned metadata snapshots plus authorized updates.

Short names are canonical. Do not make `stdio` context-sensitive and do not add
`self.stdio`.

## Physical ownership

Actor-local channels are logical capabilities. They never imply ownership of host stdin,
stdout, stderr, sockets, file descriptors or browser streams. A supervised runtime router owns
physical endpoints and may map logical channels to them.

There must be one physical descriptor owner/sequencer per Ores context/process endpoint.
This contract extends the single-owner stdio work tracked in #609 and must not restore the
competing descriptor pumps from historical PRs #539/#540.

Process stdin is never broadcast implicitly. A router must grant one reader lease or configure
an explicit bounded fan-out policy.

## V1 executable slice

Java/Graal V1 exposes `io.out.write(value) -> Future<void>`. Actor callables remain non-`async`; callers that await this operation use the existing `suspends`/continuation-lowered actor path. It is backed by the existing
generation-owned, bounded ActorRuntime output channel used by `self.send`. The returned
Future currently completes when bounded logical transport admission succeeds. It does **not**
mean a host descriptor has been flushed, and it does not grant a process `STDOUT` capability.

`io.in`, `io.err`, `io.events`, and `io.meta` are reserved by the type checker/runtime but
fail closed until their actor-local routers, quotas, and lifecycle semantics are implemented.
Source must not silently fall back to process stdio for those channels.

A later backpressure implementation may keep `io.out.write` suspended until logical admission
without changing the source-level signature. Admission, transport-write, OS-flush, and durable
storage acknowledgements are distinct concepts.

## Required invariants

Every actor-local I/O handle/operation is scoped to the current runtime, actor identity, and
generation. Retirement or hot reload revokes stale continuations and subscriptions.

All guest-reachable I/O is asynchronous or bounded nonblocking admission; actor carriers must
not block on host I/O. Queues, pending writers/readers, subscribers, metadata, and retained
bytes have explicit per-actor and context-wide quotas.

Per-channel ordering is FIFO for one actor. No ordering is promised across independent
`out`, `err`, and `events` channels unless an explicit merged stream is used.

Cancellation, close, EOF, half-close, broken sink, slow subscriber, overflow, and actor
retirement must release reservations exactly once. Security diagnostics must never disappear
through an undocumented drop policy.

Metadata is bounded and capability-gated. It must not become a secret-bearing unbounded map.
Events and diagnostics require authorized subscriptions and sanitized payloads.

## Backend parity

The same source semantics are required for Java/GraalVM, LLVM, .NET, WASM and JS
transpilation. A backend without a channel implementation must reject/fail closed rather than
redirect to process stdio or claim parity.

Conformance must cover actor generation ABA, 100+ concurrent actors, per-actor ordering,
fairness/noninterference, stalled sinks, cancellation races, UTF-8 split chunks, binary data,
CR/LF/CRLF input, explicit stdin fan-out, hot-reload drain, restricted/untrusted actors, and
physical worker retirement.

Exact-head JVM/native/other-backend evidence must record commit/tree identity and nonzero test
execution. Private source must not be copied to another organization without explicit policy
authorization.
