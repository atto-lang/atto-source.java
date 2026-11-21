# Guest outbound HTTP request API — current loopback contract

Status: **implemented on the Java/Graal source branch, pending exact-head JDK25 execution**.

This document describes the first guest-facing outbound HTTP contract built on the
current `AsyncNativeIo` and proxy-loopback lifetime substrate. It is intentionally
narrow: the ownership and streaming contract is real, while arbitrary Internet
egress is still disabled.

## Canonical surface

Async is the default Oreslang I/O model, so the API is **not** named
`http.request_async`.

```ores
val response = await http.request(
  "GET",
  "http://127.0.0.1:8080/status",
  [("Accept", "application/octet-stream")]
);

stdio.println(response.status);

for (let int i = 0; i < response.header_count; i = i + 1) {
  stdio.println(response.header_name_at(i));
  stdio.println(response.header_value_at(i));
}

val body = response.take_body();
while true {
  val chunk = await body.read_chunk(8192);
  if chunk.size == 0; then break; fi
  // consume chunk
}
```

The current request is bodyless and supports the same GET/HEAD loopback transport
admission as the proxy foundation. Request-body streaming is a later extension.

## Runtime values

`http.request(method, url, headers)` returns
`Future<HttpClientResponse>`.

`HttpClientResponse` is a sealed runtime capability, not a Java interop object.
Its guest members are:

- `status: int`
- `header_count: int`
- `header_name_at(index): String`
- `header_value_at(index): String`
- `header_values(name): List<String>`
- `take_body(): HttpClientBody`
- `close(): Future<void>`

Repeated response fields remain separate indexed entries. In particular,
multiple `Set-Cookie` values are not comma-folded.

`take_body()` transfers the single body capability exactly once. A second take
fails closed at runtime.

`HttpClientBody` exposes:

- `read_chunk(max_bytes): Future<List<int>>`
- `close(): Future<void>`
- `closed: bool`

Each byte is represented as an integer 0–255. An empty chunk is EOF. One body may
have at most one pending read. `max_bytes` is 1–65536.

## Backpressure and lifetime

The body producer runs only on the native-I/O worker. It feeds a fixed 32 KiB
runtime pipe. If the guest stops reading, the native worker—not an actor/root
carrier—waits for buffer credit. The underlying loopback transport also retains
its existing total response byte ceiling.

The response Future may resolve after headers while the physical native
operation remains live. A separate runtime lifetime Future remains owned by the
originating actor until the body reaches EOF, is explicitly closed, fails, or is
cancelled. `AsyncNativeIo` independently holds the physical actor operation
receipt until the worker and transport have actually retired.

Therefore a completed `await http.request(...)` is **not** evidence that the
network operation has physically retired, and an actor cannot use response
publication to bypass its native-I/O retirement gate.

Explicit `await body.close()` / `await response.close()` requests revocation
and waits for retirement. If a native worker is blocked in a delivered response
read, the runtime schedules response closure on the bounded native-I/O CONTROL
lane; the actor carrier never performs blocking host close work.

## Ownership and stale-completion rules

The response and body are bound to the actor that created them. Use from another
actor turn fails closed. Root-owned values cannot be smuggled into an actor turn.

`HttpClientResponse` and `HttpClientBody` are forbidden actor-mailbox payload
types. They are not serializable data and cannot be redefined by user classes.

Every request has distinct Future/runtime-object identity. Header publication,
body completion, cancellation and lifetime settlement are single-shot runtime
transitions. A late completion can settle only the objects belonging to its own
request; there is no reusable raw guest ticket that can target a later request.
The existing `ActorProxyRequestSlab` remains an optional future optimization
for proxy data-plane slot storage rather than a prerequisite for correctness of
this first client value.

## Header and target policy

The current target remains deliberately restricted to canonical literal IPv4
loopback HTTP authorities accepted by `ProxyOutboundLoopback`. There is no DNS,
TLS, redirect following, CONNECT, proxy chaining, userinfo, fragments, or query
support in this stage.

Request headers use `List<(String,String)>`. The runtime validates each field
before native admission and caps both field count and aggregate bytes.
Transport/framing fields are owned by the transport and rejected, including
`Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `TE`,
`Trailer`, `Upgrade`, `Expect`, proxy-authentication/control fields and
HTTP/2 settings fields. Header names must be valid HTTP tokens; values reject
CR/LF/NUL, other controls, DEL and non-ByteString characters.

This contract intentionally does not claim that loopback policy is the final
egress policy. The next transport phase must add DNS resolution with connect-time
IP pinning, per-connection re-resolution, redirect reauthorization, SSRF/private
network policy, TLS hostname validation, deadlines, pool limits and idle
eviction before arbitrary outbound targets are enabled.

## Merge gates

Before this surface is merge-ready, execute the exact branch head with nonzero
JDK25 steps and require at least:

- `OutboundHttpRequestGuestTest`
- `ProxyOutboundLoopbackSuiteTest`
- `ProxyLoopbackBridgeContractTest`
- `AsyncNativeIoTest`
- parser/typechecker coverage for the sealed response/body types
- GraalVM Native Image AOT/hybrid coverage for the exact source tree

A zero-step GitHub Actions runner-allocation failure is infrastructure evidence,
not a passing or failing code test.
