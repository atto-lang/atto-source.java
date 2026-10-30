# HTTP request-bound context and request-local storage (draft)

Status: Java candidate implementation, NOT exact-head executed and NOT five-backend parity.

## Lifetime and authority distinctions

| Scope | Lifetime | Storage and lookup |
| --- | --- | --- |
| ThreadLocal | Physical carrier thread | Only scratch / temporary dispatch marker; clear at exit; never request or actor authority |
| ActorLocal | Generation-fenced actor incarnation through physical teardown | Sealed actor state, accessed through handle_state(critical[read/write]) |
| TaskLocal | Generation-fenced logical execution through await/carrier migration | Continuation-owned metadata and explicitly scoped values |
| RequestLocal | Generation-fenced HTTP exchange from admission through physical retirement | Request-owned slots accessed via the live, correctly owned HttpExchange |

HTTP transport threads, actor carriers, timers and response writers need not be the same thread. A pooled actor serves unrelated requests, so actor state cannot stand in for request-local state.

## Implemented Java candidate

NativeHttp.State owns one immutable HttpRequestContext (server-issued request ID, monotonic admission timestamp, timeout, method and raw request target) and one HttpRequestLocals string-slot table. Both remain with the same State on supervisor-to-actor move/claim; the Exchange's existing owner/generation checks guard every new access. Neither record nor request ID is a bearer capability.

Guest-visible API on the currently owned HTTP exchange:

```oreslang
val request = http.claim(mail.value);
val id = request.request_id;
request.set_request_local("trace_id", id);
val body = await request.body_text();
val same_id = request.request_local("trace_id");
val exists = request.has_request_local("trace_id");
val deadline_left = request.request_deadline_remaining_ns;
await request.respond(200, "text/plain", same_id);
```

Only immutable String values are accepted. Missing reads return "" and has_request_local distinguishes absence. Up to 32 keys, each 1–64 ASCII identifier characters, 1024 UTF-8 bytes per value, 8192 value bytes total. Update/read is scoped under the existing synchronized exchange ownership check; no per-thread or global request registry is created. Slots are cleared when physical HTTP exchange admission is released. Mutable objects and state borrows cannot enter this table.

An immutable HttpRequestContext metadata snapshot may outlive an exchange for trace/logging, but cannot read its slots or provide access, permissions or guest entry. The exchange's live request State, not this snapshot, is the lifetime owner.

## Future context.current lookup (NOT implemented)

1. At admission, allocate an independently generation-fenced request entry per HTTP request/stream.
2. Attach the request binding to the scheduler-admitted actor-turn/task envelope (not to a carrier thread). Use authenticated execution + actor generation + request generation to look it up.
3. Await/select captures a logical task-bound request binding and an immutable callback-provenance snapshot. Producer callbacks may settle/enqueue but cannot mint guest execution authority.
4. Resume only after fresh scheduler admission; reject stale, canceled, or wrong-request bindings. Detached tasks receive an explicitly limited snapshot or leased request capability, never implicit request lifetime extension.
5. On response/cancellation, close new state admission. Physical memory retirement waits for in-flight I/O and continuation/async lifetime pins. A late callback can release a pin, not resurrect the request.
6. Monotonic deadlines cannot be extended by actor pool reuse or carrier migration. Incoming trace headers are untrusted data.

## Acceptance

Run Java's HttpRequestLocalsTest and NativeHttpTest guest request-local regression on JDK25/GraalVM exact head; add 3/7/15 concurrent client stress, timeout/cancel, stale owner, pool reuse and physical retirement coverage. Then author identical guest-source fixtures for LLVM, WASM, .NET NativeAOT, and Forge. Each must supply exact source SHA and real compiled guest evidence; unsupported/zero-step CI is not PASS.

Do not export private source into alternate organizations without independent protected-main review and explicit allowlisting. Keep draft until independently reviewed and genuinely executed.
