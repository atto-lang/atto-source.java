# Logical HTTP request binding across actor awaits (draft v1)

This is the next narrowly scoped integration atop Java PR #2325. **Not executed on JDK25/GraalVM; no five-backend parity claim.**

## Exact binding points

1. A supervisor accepts a request and creates `NativeHttp.State` with a private request ID, monotonic deadline and bounded `HttpRequestLocals`.
2. `Exchange.moveTo()` changes exchange ownership to a specific actor incarnation and exposes only a one-time transfer token to the actor's mailbox.
3. `http.claim(mail.value)` authenticates ownership/generation, consumes the transfer token and calls `ActorRuntime.bindCurrentHttpExchange(exchange)` **inside the admitted mailbox receive**. It rejects missing active user mail, stopped actors and attempts to bind two different requests in one receive.
4. `ActorCell.activeHttpExchange` is associated with `activeMail`, not with `ActorId` or `Thread`. It remains visible via volatile publication if a source receive suspends and is resumed on another carrier, and clears on ordinary completion, continuation settlement, failed enqueue or stop/drain.
5. Every `Exchange.checkOwner()` after claim also requires that exchange to equal the active logical mailbox's bound exchange. This closes reuse by a later receive on the same pooled actor even when the actor ID is unchanged.
6. A host producer callback or unrelated actor invocation has no admitted `ActorExecutionContext` and cannot gain a request binding from immutable callback trace provenance.
7. `ActorCell` does not acquire its lifecycle monitor in the exchange lookup path. The exchange may already hold its own `State` monitor, so avoiding the actor lifecycle lock prevents lock-order inversion during cancellation and finalization.

## Guest API

Read-only implicit lookup within the authenticated active HTTP receive:

```oreslang
val exchange = http.claim(mail.value);
val request_id = http.current_request_id;
exchange.set_request_local("trace_id", request_id);
val body = await exchange.body_text();
if http.has_current_request_local("trace_id"); then
  val trace_id = http.current_request_local("trace_id");
fi
```

The implicit `http.current_request_id` and `http.current_request_local` do not return a mutable request State or issue new capabilities. Mutation remains explicit on the owned `exchange` handle. Outside an active bound receive, they fail closed. The request remains tied to its HTTP exchange while the actor's immutable actor-local identity persists independently across pooled requests.

## Scheduler continuation hardening (additional draft change)

A mailbox receive has a monotonically assigned message sequence. A transient
`CURRENT_HTTP_MAIL_SEQUENCE` marker is installed *only* while the scheduler
actually executes that specific envelope and is removed/restored in
`HttpMailDispatchScope.close()`, including exception paths. This marker is a
carrier-local check value, never the request owner or a request-local store.

`ContinuationTarget` captures the originating message sequence at registration
time, before any foreign Future/HTTP producer completes. The corresponding
continuation envelope carries that sequence. `currentHttpExchange()` requires
both this exact dispatch marker and the retained `activeMail.sequence()` to
match before consulting the active exchange. Unassociated SourceTask commands
and anonymous runtime continuations carry -1 and fail closed rather than
ambiently inheriting a suspended request.

The suspension-completion continuation snapshots the source-mail sequence under
the same lifecycle lock that marks `sourceReceiveSuspended`; it does not read
`activeMail` later on a producer callback thread.

**Important missing path:** SourceTask continuations which resume a guest
`await` but do not use `ContinuationTarget` still have no authenticated
request sequence and must fail closed. The JDK25/Graal guest test will expose
this if the current compiler uses that route. Before promoting ambient lookup,
such SourceTask entries must explicitly carry a scheduler-minted logical
execution/request binding (not copy `activeHttpExchange` from actor-wide state).

## What this does not do yet

- A fully general `TaskLocal<T>`, `ActorLocal<T>`, `RequestLocal<T>` generic type or a language-wide `request_context.current()` intrinsic.
- Request identity on child tasks detached from the HTTP receive; those must later accept an explicit immutable snapshot or separately pinned capability (no automatic inheritance).
- Capture of request binding by a foreign callback; callbacks carry provenance only and can enqueue resume, never install active authority.
- Full HTTP/2 stream-level isolation, distributed tracing propagation, stale continuation proof for every scheduler path, or all five backend implementations.
- Prove that every guest `await` path retains `activeMail`; this is a candidate relying on the existing `suspendCurrentUserMailboxUntil` path and requires exact-head integrated guest tests.

## Verification gate

Run `mvn -B -ntp -Dtest=NativeHttpTest,HttpRequestLocalsTest,ActorCarrierIdentityCleanupTest -DfailIfNoTests=true test` on the exact GitHub SHA using JDK25/GraalVM. Specifically verify unique request IDs and fresh request-local slots across 3/7/15 concurrent POST clients, preserved identity across `await request.body_text()`, pooled actor reuse, producer callback denial, stale exchange access, actor/HTTP termination and physical retirement. Require nonzero executed CI steps, zero skipped JUnit cases and independently validated compiler/guest output.

Then promote a cross-backend source-level manifest in `oreslang-platform-conformance`: Java, LLVM, WASM, .NET Native AOT and Forge must compile and execute the *same source bytes*, or explicitly report unsupported with a targeted compiler diagnostic. No private source mirroring to other organizations absent protected-main authorization and destination allowlisting.
