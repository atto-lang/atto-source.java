# Multi-actor reply collection and error propagation (v1)

Status: partial Java host observer implementation in draft PR #1887, NOT
fully implemented guest actor request/reply, Actor.quorum or source
effect/ownership conformance.

## Existing Java actor runtime evidence (inspected 2026-10-22)

- ActorRuntime.ActorRef<M>.ready()/getReady(): OresFuture<Void>;
  readyRef(): Future<ActorRef<M>>; done()/getDone(): finalization future.
  ready/done are explicitly NON-cancellable observers; awaiting them never
  grants authority to stop an actor.
- ActorRuntime.ActorGroup.workersReady()/workersDone() already snapshot
  managed actor refs and aggregate their ready/done Futures using
  OresFutures.all. These are lifecycle barriers, not message replies.
- ActorRuntime.invokeAsync(kind, message, invocation) produces a
  Future<R> for a ONE-SHOT actor callable and publishes the result only
  after the actor finalizes and its execution context/carrier exits.
  This is not a persistent actor request/reply API. Calling invokeAsync
  from within an actor turn is forbidden in its current contract.
- ActorRuntime.ActorRef.send(M) is admission-only and returns void;
  ActorRef.drain() is a processing barrier for accepted messages, not
  a typed application-result Future. ActorRef.outputs() is an
  ActorOutputStream and must not be confused with a per-request reply.
- ActorGroup membership is different from managed lifecycle ownership.
  Members may be joined into multiple groups and membership does NOT
  grant stop/cancel authority.

The existing OresFutures.all implementation cancels its input OresFutures
when its aggregate is cancelled. Actor ready/done Futures have their own
noncancellable admission rules, but this behavior MUST NOT be assumed
safe for general actor replies or other borrowed Futures. New
ActorResults explicitly uses NON-OWNING FutureCombinators.all/allSettled.
Do not change the legacy aggregate's broad semantics for this feature
without a separate compat/security audit.

## Java host implementation in this draft

- ActorResults.Pending<R>(ActorAddress address, UUID requestId,
  OresFuture<? extends R> response). A reply Future is already running;
  a Pending is a trusted-host typed correlation record, not proof that
  the sender is actually the claimed actor.
- ActorResults.gather(List<Pending<R>>) -> OresFuture<List<Reply<R>>>
  returns successful replies in INPUT order, even if replies settle
  out of order. Each Reply carries ActorAddress + correlation UUID +
  typed result. Genuine exceptions fail-fast with the ORIGINAL
  Throwable as cause, but this fail-fast observation alone cannot
  identify the offending actor without the separate per-reply
  settlement API.
- ActorResults.gatherSettled(...) -> OresFuture<List<Settled<R>>>
  awaits every input's logical Future settlement and retains
  address, requestId, success/failure/cancelled status, value and
  original error. OresFuture authenticates cancellation; a failed
  Future with an ordinary CancellationException remains FAILURE.
- Both are bounded to 65,536 entries with duplicate request-ID
  rejection. A single actor may have multiple independent requests;
  duplicate request IDs even across actors are rejected to avoid
  ambiguous correlation.
- Cancelling either collection unsubscribes from internal observers;
  it NEVER invokes ActorRef.cancel/kill/stop or cancels the original
  actor reply Futures. Collecting results does not give sender rights.
- These are HOST bookkeeping transformations. They do not execute
  guest callbacks directly on an actor producer/carrier thread.
  Before source exposure, actor reply token authenticity must be
  proven by ActorRuntime, not a public caller-controlled ActorAddress.

Tests: ActorResultsTest (8 JUnit cases) covers two actors with reversed
completion, correlated request IDs, genuine errors/cancellation,
consumer cancellation, duplicate correlation rejection, same actor
with two requests, empty fan-in and Void lifecycle values. No
test execution has yet been verified on exact head.

## Proposed source-facing APIs (illustrative and NOT implemented)

    // A typed asynchronous ask must have its own capability / signature:
    let reqA = workerA.ask(#compute, inputA);
    let reqB = workerB.ask(#compute, inputB);

    // Observes requests already in flight without cancelling workers.
    let replies = await Actor.collect([reqA, reqB]);

    // A failed request is attributed without hiding other answers.
    let results = await Actor.collectSettled([reqA, reqB]);

    // Explicitly bounded actor fanout from lazy request factories.
    let mapped = await Actor.map_parallel(actors, actor =>
        actor.ask(#analyze, payload),
        concurrency: 8,
        order: #input);

    // Return after N valid responses agree (policy must specify quorum).
    let accepted = await Actor.quorum(requests,
        required: 3,
        validate: value => validate_reply(value));

    // Typed heterogeneous collection should create a record/tuple,
    // not erase all results into Any or a string-keyed map:
    let summary = await Actor.zip(
        priceWorker.ask(#quote, product),
        inventoryWorker.ask(#stock, product));

    // ActorGroup workersReady / workersDone are lifecycle APIs and
    // must remain distinct from ask/collect application-level replies.

Compositions of Tasks and Actors may use Future.all/Future.race on their
already-running Future values, provided source-level generic typing
and effect signatures allow them. A collection that OWNS TaskSteps
may cancel Task children; it must never gain actor cancellation
authority merely because an ActorRef contributed a reply Future.

## Public API naming decision (2026-10-22)

Use **`ActorRef.ask(message, ...)`**, not `ask_async`. The `ask`
operation is intrinsically asynchronous and returns `Future<R>` (or a
checked awaitable that projects to `Future<R>`) without parking the actor
or caller scheduler. Awaiting is explicit: `let answer = await worker.ask(...)`.

`send(message)` remains one-way admission and MUST NOT be confused with
the typed `ask(message)` reply Future. A `Task.waterfall` stage may
return the Future from `ask` just like any other awaitable. This name
choice does not weaken the required correlation, deadline, ownership,
cancellation, typed error/effect or generation-fencing semantics.
`ask` is a SOURCE API proposal: persistent-actor reply transport is not
implemented by this document or the host-only ActorResults collector.

## Request/reply transport still REQUIRED

1. Typed ActorRef<Request, Response> or checked message trait/interface,
   including the on-message reply schema. Keep existing ActorRef<M>
   backwards compatible; choose a separate ask trait or a compiler
   verified request-reply endpoint rather than breaking one-way sends.
2. ActorRuntime issues unforgeable request correlation IDs and
   matches (runtime id, actor id, generation, requestId). Public
   user-supplied UUIDs or ActorAddress are not authentication.
3. Source effect union over per-actor replies: throws/raises/panics.
   An actor PANIC is escalated to supervisor/CONTROL and cannot be
   silently converted to ordinary allSettled failure / Rx onError.
   Actor termination before answering is a typed failure, distinct
   from an authenticated Task/Scope cancellation.
4. Bounded outstanding request table, mailbox and per-actor quotas,
   reply-size quotas, nonparking admission, distinct timeout/deadline
   events. Timeouts do not silently kill an actor or rollback a
   message that was already processed.
5. Cancellation policy: cancelling a request observer detaches only.
   If a caller owns an explicit actor/request cancellation capability,
   it can request revocation, but physical actor/Task arena release
   still needs authoritative finalization barriers.
6. Exactly one logical terminal reply per correlation; duplicate,
   unexpected and cross-generation stale replies must be rejected.
   Maintain per-request generation/fencing tokens and prevent ABA
   after hot reload or actor restart.
7. Do not run guest continuations on mailman, producer completion,
   IO callback or foreign isolate threads. Resume awaiting caller
   on its OresScheduler/actor mailbox, preserving serial callbacks
   across await and actor ownership proofs.
8. Group fanout must snapshot members at a defined logical instant;
   distinguish ALL, first success, quorum, timeout and best-effort.
   Never assume every group member implements the requested
   contract or belongs to the group's lifecycle manager.
9. For quorum, specify: distinct actor identities vs multiple
   requests from one actor, value agreement/equality, Byzantine
   versus crash failures, policies for tie/quorum-impossible,
   partial errors, and whether remaining sources continue running.
   A quorum of responses is NOT physical quiescence and NEVER
   implies sibling actor shutdown.
10. Observables and streams may collect actor outputs but
    ActorOutputStream is a potentially multi-emission channel;
    unlike one request/reply Future, it needs bounded demand,
    subscription lifecycle, and event generation/correlation.
11. If actor output serialization is via Zorde/MessagePack,
    validate schema version, ownership, payload size, and
    private shared-memory capabilities at the actor boundary.
12. Cross-backend Java/GraalVM, LLVM, .NET, JS/WebWorker and WASM
    must pass the same typed/error/cancellation/generation tests
    without assuming equivalent physical heap implementations.

## Shared invariants

- Future logical settlement, actor turn quiescence, actor finalization,
  arena reclamation and reply processing ACK are distinct conditions.
- Per-actor result/response errors should have attributed actor
  identity, request ID, and source effect class for diagnostic
  purposes, while retaining original typed cause.
- No fan-in algorithm is allowed to mutate original errors
  shared across independent observers.
- No false success on timeout, actor crash, mailbox full, stale
  generation or missing quorum.
- Never claim a true persistent-actor ask or quorum implementation
  solely because ActorResults.gather/gatherSettled can observe
  existing Futures.

This document complements ASYNC_CHAIN_ERROR_EFFECTS_V1.md and
ASYNC_COMPOSITION_CROSS_ECOSYSTEM_V1.md. Keep PR #1887 DRAFT.
