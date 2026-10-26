# HTTP hot deployment and in-flight actor generations v1

**Status: proposed contract with a new host-only lifetime ledger; NOT live Oreslang hot deploy.** The HTTP listener, actor route publisher and compiler-generated code pins are not yet connected to this ledger. See [Java #1971](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1971), [common #73](https://github.com/ores-truffle-oreslang/oreslang-common-source/issues/73) and [common #149](https://github.com/ores-truffle-oreslang/oreslang-common-source/issues/149).

## Product decision

For ordinary/config-only rolling deployment, **finish existing HTTP exchanges on the generation that admitted them, and assign newly admitted exchanges to the new generation**. Maintain the OresVM process and stable listener. No unbounded global pause while a streaming request or WebSocket is open. Never substitute "the handler returned" for completion of the response transport and its continuation/resource leases.

Stateful **in-place** replacement is a separate transactional actor upgrade: preserve logical ActorRef/mailbox/state only after source ABI, state migration, execution quiescence and one atomic publication are proven. Do not alias an old actor's mutable state into a new actor pool.

## Linearization and accounting

1. A supervisor-controlled, immutable route-generation descriptor contains code image digest, compiled import ABI, actor handles, config/policy snapshot, and epoch. It must be staged and healthy before publication.
2. At the same synchronization/atomic reservation point where an HTTP request chooses its route, the ingress path acquires a bounded generation exchange lease. The ledger alone is **not** an alternative router. Never publish then increment a per-generation counter in an unrelated step; that race could retire code with an untracked request.
3. The promotion CAS replaces the active descriptor for **new request admissions**; it never invalidates already admitted exchanges. A request in a transport accept queue but not yet assigned a route is not automatically old: classification is determined by the explicit route-admission boundary. Record the exact epoch once.
4. The outgoing request/response body, parser, headers, auth middleware, async continuations, request-scoped owned handles and final response callbacks retain the same immutable generation. A response object, logical Future settlement or cancellation request is **not** physical quiescence.
5. The old generation enters DRAINING, rejects new exchange leases, but allows already-held leases to finish, including asynchronous completion. Retire only after **both** handler execution/cleanup and transport response cleanup have finished, plus **all physical pins** (suspended tasks, streams, native/FFI calls, callbacks, borrowed shared regions) have been released.
6. A bounded retention budget may defer new promotions while old generations remain pinned. A deadline can request cancellation and stop accepting; it cannot certify physical completion or force-unmap pinned executable memory. If a native cleanup fails, record quarantine rather than falsely reporting DONE.
7. Stale completion receipts must be bound to request, actor incarnation, route epoch, and authorized transport owner; old receipts cannot decrement a new generation. Exactly-once application *effects* are not implied by completion accounting.

## HTTP protocols and edge cases

| Type | Hot deployment behavior |
| --- | --- |
| HTTP/1.1 ordinary request | Finish on old code. The next request on the same keep-alive connection may use new code after admission, provided connection parser/transport settings remain compatible. |
| HTTP/1.1 pipelining | Bind each parsed/admitted request before handoff; preserve ordered responses and outstanding body/framing ownership. No response from a later generation may bypass wire order. |
| HTTP/2 or HTTP/3 | Lease and route per *stream*, not per TCP/QUIC connection. A stable listener and connection can serve old and new generation streams concurrently if transport policy allows. GOAWAY is for draining a **connection**, not required for actor-only route promotion. |
| Streaming body, SSE, chunked response | Retain old-generation lease until final bytes/trailers or transport abort plus handler/source cleanup. Returning an HTTP response object does not release it. |
| WebSocket / CONNECT / long-lived tunnel | Treat as a long-lived generation pin. Keep old generation alive, or explicitly close/reconnect under a bounded policy. Seamless live takeover requires an independently specified state/ownership handoff; never transparently move a live socket/stream between incompatible actors. |
| Slow client / endless stream | Enforce configured idle, max-age and resource quotas. At deadline, initiate graceful protocol-specific termination, then track physical cleanup; never treat timeout as release proof. |
| Failure before headers | Send an appropriate 503/504 if still permitted, release only after cleanup. |
| Failure after headers | Cannot replace the HTTP status line. Close/reset the stream/connection as protocol allows, report an indeterminate partial response, and still await resource reclamation. |
| Non-idempotent POST / payments | Never silently replay a potentially processed request after failure or upgrade. Retries require application idempotency/dedupe and explicit policy. |

For **HTTP/2 and HTTP/3 connection shutdown** (as opposed to actor-only promotion), honor their GOAWAY stream-ID rules and drain established streams. See RFC 9113 §6.8 and RFC 9114 §5.2.

## Source/runtime attachment

The first implementation target is a stable Java/Graal listener + one SHARED HTTP actor pool whose requests each carry a generation lease through `NativeHttp.Server.receive`, `Handler.dispatch`, response-body streaming and close/abort. Promotion must compose with the **one** existing actor route publisher (Java draft #1967); do not introduce an independent HTTP active pointer. Once the Java adapter executes real source actors, reuse the identical observable fixture for LLVM native. UNTRUSTED/private/code-image migration remains gated separately.

`HttpGenerationDrainV1` is deliberately a package-private *host-only* accounting primitive. It never invokes guest code, changes an ActorRef, closes a live socket or proves an actor has stopped. The caller must deliver trusted, physically proven handler and transport completions. A leaked or stuck pin intentionally blocks retirement rather than concealing a use-after-free.

## Conformance / fault-injection gates

- Slow old GET overlaps new generation GET; old body and middleware version remain old, new exchange is new; stable process/listener.
- Queued-before-promotion versus admitted-after-promotion exchange, including pipelined HTTP/1.1 and multiplexed HTTP/2 streams.
- Actor returns before slow response finishes; upgrade must not reclaim old generation.
- Client disconnect before actor terminates; cancellation request does not falsely retire old code.
- Suspended continuation, blocked FFI, streaming callback and shared borrow each retain a physical pin until actual completion.
- Concurrent admit-versus-drain races, exhausted admission slots, duplicate/stale receipts, repeated promotion, quota/OOM errors, abort-before-headers versus after-headers.
- SSE/WebSocket/CONNECT long-lived policy with bounded memory/code-generation retention; no indefinite unbounded version accumulation.
- Exact-head JDK25/Graal integration + LLVM source execution; unit ledger tests alone are not an end-to-end HTTP or AOT proof.

## Explicit current blocker

`NativeHttp.Server.shutdown(graceMillis)` currently stops the **transport/listener** and may abort active exchanges. It must **not** be repurposed for a code-generation-only hot deploy, which must keep the process/listener alive and drain the old handler generation independently. [Java #973](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/973) records a race between HTTP actor activity and Graal Context close; this ledger is not that fix until the real transport and ActorRuntime quiescence hooks are wired.

## Avoid Erlang/OTP old-code purge pitfalls

Normative contract: [common-source HOT_UPGRADE_ERLANG_PITFALLS_V1](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/main/docs/HOT_UPGRADE_ERLANG_PITFALLS_V1.md).

- Unlike Erlang's normal current/old two-version code model, a third actor image does **not** force a purge of a still pinned earlier image. Bounded generation retention must reject additional deploys or apply a documented graceful-drain policy, never silently kill the old actor just to make room.
- The HTTP-ledger `status()` returns immutable, per-resource-kind blockers (e.g. CALLBACK, FFI_CALL, HTTP_STREAM, SUSPENDED_TASK). An observable timeout or cancellation request is never a physical-release receipt. The image-loader's independent quiescence/cleanup proof is still mandatory.
- A V1 streaming request/callback may remain pinned while V2 and V3 handle new admissions. The existence of a later generation never invalidates an already admitted request.
- At the implementation boundary, host code must not mix old middleware, response framing, callback code, source actor method dispatch or dependency ABI with newer code halfway through an existing request.
- Legacy `ActorUpgradeEpochGate` and `ActorRoutePublicationV1` must remain the only supervisory route/code authorities; this HTTP diagnostic ledger must not become a second code-loader/publisher.

## Bounded image retention governor (draft host-only)

`HotCodeGenerationRetentionV1` now provides a supervisor-only **admission budget** for code images, separate from the authoritative route publisher. Each staged, serving, draining or quarantined image consumes one generation slot and its reserved byte charge. A new candidate is rejected atomically if it exceeds either configured bound. The oldest image is **never purged** just to create capacity for a third deployment.

- `reserve(generation, digest, bytes)` admits only canonical code identities and positive size reservations. A failed attempt changes no budget counters. Byte-cap arithmetic avoids signed overflow. Generation IDs strictly increase across the lifetime of the governor: a cleaned or aborted ID can never be reused (ABA protection).
- `activated(ticket)` and `draining(ticket)` are bookkeeping **after** the real supervisor publication boundary. They do not move HTTP routes, change actor state, or authorize image loading.
- `quarantine(ticket)` does **not** refund capacity. A currently SERVING image cannot be quarantined without first being detached from the active route and marked DRAINING. Staged failures likewise keep their reservation until the trusted loader has actually cleaned its resources.
- `physicalCleanupCompleted(ticket, loaderResourceId)` produces a one-shot host-only cleanup receipt bound to the exact retention owner, ticket identity, monotonic image revision and code digest; serving images cannot mint one. `acknowledgePhysicalCleanup(ticket, expectedRouteEpoch, http, receipt)` separately validates the HTTP route epoch and drain state before releasing capacity. This receipt is still **trusted-loader evidence**, not guest authority or cryptographic attestation; the loader must create it only after real image cleanup and after independent Task/FFI/closure quiescence has been proven. Image revision is deliberately not equated with route epoch.
- `status()` returns immutable snapshot blockers (generation, image digest, reserved bytes, phase), enabling operators to distinguish graceful drain pressure from executable-image leaks. Reserved bytes are a **configured charge**, not verified RSS or actual executable mapped bytes.

This quota ledger intentionally **does not** introduce a second active route pointer or an automatic purge API. Before promoting the new generation, the supervisor must reserve budget; if exhausted, reject/defer the deployment while keeping all serving generations valid. To reclaim budget, complete real image/HTTP retirement then acknowledge physical cleanup. The fact that this bookkeeping admits a new image does not mean its code or state schema passed ABI/effect checks.

Focused host-only JDK25 CI is specified in `.github/workflows/hot-code-generations-exact-head.yml`. It must show actual executed steps and JUnit reports before promotion.

## Stacked route integration (#2034)

The first composition layer now lives in `HttpActorGenerationCoordinatorV1` on top of the single `ActorRoutePublicationV1` authority.

- `ActorRoutePublicationV1.withCurrentAdmission(...)` binds route observation and HTTP generation admission under the same route lock. A request cannot observe V1 and accidentally pin V2 during a concurrent publication.
- Compatible rolling publication does **not** wait for previously admitted HTTP exchanges. After the one route cutover, new admissions use V2 while V1 exchanges retain V1's `HttpGenerationDrainV1` and image reservation.
- Actor mailbox/Task leases remain a different boundary: if the logical actor's own `ActorUpgradeEpochGate` is pinned, publication waits, while ordinary HTTP arrivals continue on the still-published old route.
- Image revision and route epoch are distinct monotonic domains. An aborted staged image consumes its image revision forever, preventing ABA reuse, but because it never published, the next candidate may still target the same next route epoch.
- Image-count/byte-budget exhaustion during staging rolls back the temporary actor freeze and preserves the current route. The new deployment is rejected/deferred; no old generation is killed or evicted.

`NativeHttpRollingDispatchV1` is the first transport adapter. It intentionally supports only **fresh non-pooled handlers**. Once a generation lease is bound:
1. handler-side quiescence is signalled only by the fresh actor's `ActorRef.done()`;
2. transport-side quiescence is signalled only after the raw HTTP exchange is physically closed/aborted;
3. the generation retires only after both sides and any supplementary physical pins are gone.

Pooled HTTP workers are rejected by this adapter until the runtime provides a per-request authenticated receive-turn completion receipt. Response Future settlement is not sufficient because the worker may still be executing post-response code, which is exactly the lifecycle race tracked by #973.

Current stacked draft: https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2034
