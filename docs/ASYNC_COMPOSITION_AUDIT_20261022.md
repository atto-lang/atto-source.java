# Async Task, Future, Stream and multi-actor composition audit — 2026-10-22

Status: source audit + partial Java host-runtime fixes on DRAFT PR #1887.
Nothing merged. Code has NOT passed exact-head Maven/JUnit/Graal native or
LLVM execution; static source inspection and delimiter checks do not prove
type correctness, concurrency safety or semantic parity.

## Critical issue fixed: aggregate cancellation revoked borrowed sources

Prior behavior of OresFutures.all/race/allSettled:
aggregate.cancel(false) invoked each child.cancel(true), even when
sources belonged to independent actors/TaskScopes. A consumer
observation must NEVER confer ownership/cancellation authority.

Change: cancellation only detaches aggregate's own runtime waiter
registrations. True cancellation of an input is still forwarded to
the aggregate as a cancelled terminal state, without cancelling
any other input. Structured Task children are explicitly cancelled
by TaskScope, not implicitly by Future.all observation.

The new OresFuturesOwnershipTest checks all/race/allSettled,
authentic source cancellation, user-raised CancellationException
as a failure, input error preservation and Void null result slots.
This is a semantically significant change to the existing
language-level OresFutures helper: downstream callers that previously
assumed implicit source revocation require a compatibility audit
before merge.

## High issue fixed: no core Future fan-in bound

Prior OresFutures.normalize accepted arbitrarily large List inputs
and subscribed every element, causing unbounded listener/retention
allocation. Change: MAX_FAN_IN=65,536, validate BEFORE allocation,
then List.copyOf the caller list before registering callbacks.

The same fail-fast validate-before-copy policy applies to
TaskMapParallel.mapLimit. ActorResults.gather/gatherSettled now
snapshot the caller's mutable list before subscription and enforce
their existing 65,536 cap. FutureCombinators already had that cap.

These are hard ITEM caps only; **no per-tenant byte budget or
actor isolation accounting** has been proved. The source compiler
must impose stricter quotas under restricted profiles.

## High issue fixed: settled cancellation classification

Legacy OresFutures.Settled(value,error) cannot authenticate
CancellationException versus genuine runtime cancellation.
New OresFutures.allSettledDetailed returns explicit
FutureCombinators.Outcome<T> with SUCCESS/FAILURE/CANCELLED.

A Future that fails with a guest-raised CancellationException is
FAILURE, not CANCELLED. An authenticated runtime cancellation is
CANCELLED. A native Oreslang panic remains supervisor-bound and MUST
NEVER be transformed into a normal allSettled failure. Host Java
Throwable inspection by itself cannot enforce that source effect.

## Earlier branch fixes reviewed

- FutureCombinators all/allSettled/any/race/map/flatMap/zip observe
  already-running Futures; no shared-source cancellation.
- AsyncSequences cursor has one active read with terminal revocation,
  cancel/close detach, and EOF versus failure classification.
  A Channel read is at-most-once receipt, not a durable queue ACK.
- AsyncSequenceCollectors has explicit count cap, one tracked scheduler
  Task and a separate cursor-close+quiescence getDone barrier.
- TaskWaterfall has typed heterogeneous lazy stages, a single-use
  plan, owner-admission gate, fail-fast and StageFailure index + original
  cause. This does NOT implement source automatic async-lift of |> .
- TaskMapParallel has TaskScope-owned child admission and physically
  retired credit recycling; MapHandle.childFailures preserves
  secondary error/cancellation evidence, and getDone is a distinct
  physical retirement barrier.
- ActorResults.gather/gatherSettled preserve input order and
  ActorAddress/requestId metadata, never revoke other actor Future
  producers on collector cancellation. New snapshot guards mutable
  caller lists and duplicate request correlation IDs.

## Open P0/P1 audit gates (NOT claimed implemented)

1. **No exact-head executed compile/test pass.** The repository's
   Actions jobs repeatedly report failure with zero recorded steps.
   That does not demonstrate a Java compiler failure or success.
   Run the entire focused test suite on an authorized runner, then
   full JDK/GraalVM Native Image/LLVM and source guest tests.
2. **Trusted actor reply provenance.** ActorResults.Pending currently
   accepts a caller-supplied ActorAddress and UUID and can be
   instantiated by trusted host Java callers. This is NOT an
   authenticated actor reply; a guest API MUST only accept
   OresVM-issued correlation capability bound to
   (runtimeId, actorId, incarnation/generation, requestId).
   Reject spoofed/duplicate/late replies and limit outstanding requests.
3. **ActorRef.ask() not implemented.** Preserve naming decision
   ActorRef.ask(...): Future<R> with no _async suffix and nonparking
   semantics. ActorRef.send(M) is one-way; ActorRuntime.invokeAsync
   is a separate one-shot callable; ActorGroup.workersReady and
   workersDone are LIFECYCLE fan-ins, not request/reply results.
4. **Checked source effects** throws/raises/panics/suspends must
   survive all callable and Future/Task generic types. A panic
   cannot be caught, trapped or downgraded into Future.error or
   Observable.onError. Source compiler negative tests remain
   outstanding (issue #1010).
5. **Nested cancellation/failure races.** Run deterministic schedule
   replay and model-check cancellation vs task retirement, scope seal,
   inline producer callbacks, stream close, actor restart and
   generational fencing, preserving primary cause and all independent
   secondary failures without double-completion.
6. **Fairness of large fan-ins.** Callback detachment and diagnostic
   collection currently can traverse up to 65,536 elements as part of
   a terminal host callback. Enforce bounded work per scheduler turn
   and quotas under watchdog/admission profiles before production.
7. **Source actor/Task memory authority.** Physical TaskHandle.getDone
   and ActorRef.getDone are not substitutes for verified native arena
   loan release, FFI finalization and cross-isolate buffer leases.
   Cancelled observers cannot reclaim borrowed producer state.
8. **TaskQueue reliable ACK/NACK, capacity and Zorde schema validation**
   still need source+runtime integration. NeoQueue saturation events
   alone are NOT producer backpressure.
9. **Cross-backend parity** Java/GraalVM, LLVM, JS/WebWorkers,
   Wasm and .NET remains unproved. Actor messages, task callbacks
   and effect metadata must preserve semantics, not identical heaps.
10. **No implicit guest blocking** via join/get or arbitrary Java
    CompletableFuture callbacks. Native scheduler owner and actor
    domain dictate where guest callbacks execute.

## Minimum test command and audit sequence

    mvn -Dtest=FutureCombinatorsTest,OresFuturesOwnershipTest,AsyncSequencesTest,AsyncSequenceCollectorsTest,TaskWaterfallTest,TaskMapParallelTest,ActorResultsTest test

Then complete JVM unit/integration suites, Native Image and
compiler guest-execution tests. Check real job logs/steps, not CI
status labels alone. Confirm a Source/Java GRAAL typechecker
compile path and matching LLVM continuation/ownership tests.
Independently review changed ownership semantics and existing
callers before merge.

This code is intentionally still a draft. Do NOT use a second
GitHub organization to mirror private source or run CI without
an approved allowlist/protected-main security policy.


## Additional audit: cleanup failure across structured Task scopes

Source runtime previously reported successful TaskScope/TaskMapParallel
physical `getDone()` even if a child logical lock release had thrown.
Child TaskHandle.getDone() was Failed(CleanupFailure), but TaskScope
ignored the outcome; a parent could incorrectly treat successful
quiescence as proof of safe lock/arena reclamation.

TaskScope now aggregates exact child cleanup errors and fails its
observation-only getDone on a release failure. TaskMapParallel preserves
that scope failure in its own getDone rather than replacing it with
successful completion. All sibling child cleanup attempts still run.
An original Task throw/cancellation remains visible on result() and
CleanupFailure.originalOutcome(), separate from cleanup diagnostics.

Automatic release of *arbitrary native/thread-affine mutexes* remains
unimplemented; attempting it from an external cancellation notifier
would be unsafe. Only transferable, nonblocking logical lease tokens
are eligible. SourceTask continuation unwinding and typed raise ->
defer -> recover remain P0 implementation gaps.

GitHub Actions on the source org failed without recorded steps. The
checked source policy and empty allowlist explicitly block private
mirroring to a different org until security-owner approval of PR #1538.
No test-success, verified minutes exhaustion, or approved mirror claim.
