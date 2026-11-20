# Checked error effects and failure policy for async chains (v1)

Status: normative DESIGN for Oreslang source semantics plus explicitly scoped
Java host-runtime error/cancellation hardening in draft PR #1887.
This document does NOT mean parser/type-checker support, GraalVM compilation
or LLVM cross-backend behavior is complete.

Applies to Future.all/allSettled/any/race/zip/map/flatMap, Task.series,
Task.parallel, Task.waterfall, Task.map_parallel, Task.inject, TaskQueue,
Channel, Stream/Observable operators, select and actor supervision.

## A. Four different terminal conditions -- NEVER conflate

1. SUCCESS: a value, possibly Unit / Option<T>. A null host reference MUST
   NOT masquerade as EOF or cancellation.
2. THROWS: a typed, recoverable source error in a declared throws set.
   Only a permitted typed catch/trap may discharge it; otherwise the
   calling function must declare that same throws effect. Source code
   must not silently erase the error in an opaque Future<T>.
3. RAISES: a typed, stronger escalation in a declared raises set. A
   permitted typed recover/escalation construct is needed to discharge,
   or the caller must declare raises. An unhandled source throw can
   escalate to raise according to the separately specified actor
   boundary rules; do not silently perform escalation at every await.
4. PANICS: an untrappable fatal effect in a declared panics set, forwarded
   to supervisor/CONTROL according to actor/guest runtime rules. It is
   NOT caught by ordinary catch, trap, Result.Err, allSettled or Rx
   onError, and it is never an ordinary cancelled TaskOutcome.
5. CANCELLATION: authenticated runtime/scope authority requests
   revocation. This is not a catchable throws or raises effect and is
   separate from a guest-created CancellationException/failure value.
   Cancellation completion can precede physical quiescence.

A Java Throwable class (including AssertionError, Error or
CancellationException) does NOT by itself identify one of Oreslang's
source-level effect classes. Only authenticated source lowering and
runtime capabilities may establish this classification. The
existing Java TaskHandle TaskOutcome.Success / Failed / Cancelled
is HOST staging; source throw/raise/panic categories require typed
metadata and backend lowering.

## B. Signature checking -- illustrative, exact spelling pending grammar

Illustrative type/effect form:

    // Syntax to be settled by the compiler work.
    pub async fnc load_user(int id): User
        throws HttpError
        raises TransportFault
        panics RuntimeInvariantError
    {
        ...
    }

    pub async fnc build_report(int id): Report
        throws HttpError, ParseError
        raises TransportFault
        panics RuntimeInvariantError
    {
        let user = await load_user(id);
        return await transform_async(user);
    }

No function, method, routine or callable trait may call a checked
function with effects that are neither handled by an allowed construct
nor propagated in its own corresponding signature category. A typed
callback or task factory carries its declared effects into the
combinator. An async chain effect is a statically computed
per-category union over all reachable stages and children; generics
must retain that union, and the source compiler rejects unknown,
erased or undeclared effects in strict profiles.

For Pipeline stage i: A_i -> Future<A_(i+1)>, the compiler must
resolve and validate A_i plus throws/raises/panics/suspends before
constructing the chain. Auto-lifted \`|>\` inserts awaited stage
suspensions only in an allowed suspendable context, preserving every
effect and exact value type. Existing synchronous \`|>\` semantics
remain unchanged; a non-suspending caller cannot auto-lift Futures.

## C. Composition matrix

| Operation | Primary outcome on error | Other work | Error evidence |
| --- | --- | --- | --- |
| Future.all | first observed failed/cancelled Future; source failures preserve cause | other *borrowed* producers stay running | no physical-retirement guarantee |
| Future.allSettled | ordinary success/throws/raises only when explicitly permitted by typed effect API; panic NEVER converted | observes all Futures | per-input typed Outcome with AUTH cancellation marker |
| Future.any | first success wins; only if every input fails report aggregate | borrowed sources stay alive | causes ordered by original input; preserve cancellation classification |
| Future.race | first terminal event wins, including legitimate failure/cancel | detach observer, never revoke shared source | winner has original cause/effect |
| Future.map/flatMap | mapper or awaited source's original error | never invoke downstream mapper on upstream error | source cancellation authentic, thrown CancellationException ordinary failure |
| Task.series | first failure stops all later lazy stage admission | cancel and join owned children if any | offending stage index and original error |
| Task.waterfall / async |> | first failed stage stops downstream | no later stage factory called | stage index, original error, no implicit recover |
| Task.parallel/map_parallel | first genuine error requests scoped sibling cancellation | no new admission, drain children to get_done | original primary + ordered secondary failure/cancel diagnostics |
| Task.inject | node failure prevents dependent nodes admission | cancel other owned subgraphs according to policy | typed node identifier and source error, no erasure |
| TaskQueue | job failure does not silently ACK | bounded retries / dead letter only by explicit policy | per-job typed error + provenance / attempt count |
| Observable/Stream | one terminal failure, never a normal EOS/Option.None | detach subscribers; owned workers cancel/join | preserve throws/raises; panic supervision outside onError |

For fail-fast owned scopes, caller-facing result MAY become terminal
before physical child retirement. Memory, scheduler-domain and arena
loans stay alive until a SEPARATE get_done physical barrier confirms
all owned descendants have retired. A terminal Result.Err is NOT a
proof that finally/defer cleanup has run to physical completion.

The Java prototype currently checks all retired children and prefers
a genuine failure over cancellation among the SAME observed snapshot.
If multiple genuine failures are simultaneously retired, primary is
the lowest input index for deterministic diagnostics. In races where
one child physically settles later, whichever failure the coordinator
observes first may still become the primary. Do not falsely promise
global wall-clock first-failure order. All admitted child Failed and
Cancelled outcomes become input-ordered ChildFailure records from the
MapHandle.childFailures() observation Future after getDone.
That Future is itself uncancellable by an observer.

Do not mutate the original Throwable to append sibling errors; it
may be shared by other observers. Preserve original identity as
result failure and expose a separate immutable failure-diagnostics
sequence. Never let an expected sibling cancellation overwrite the
first genuine failure already observed.

## D. Recovery is deliberate and typed

Explicit recoveries:
- \`catch\` may recover only declared throws, with type-checked handler
  return type; if handler itself throws/raises/panics, propagate effects.
- \`recover\` is reserved for permitted raises according to language
  syntax/policy; never invoke as a blanket generic Future handler.
- \`panic\` is untrappable and escalates directly to CONTROL/supervisor.
- Cancellation may be observed/handled by TaskScope/ownership policy,
  but guest code cannot fabricate a source cancellation capability.
- \`retry\` is never automatic on all throws/raises/panics. Require
  an explicit retryable error set, maxAttempts, delay/backoff bounds,
  cancellation handling, deadline and idempotency strategy; retry
  factories must be lazy, and previously admitted work physically
  retired before exclusive state is reused.
- \`on_error_continue\`, \`map_error\`, and \`allSettled\` MUST NOT
  silently turn a raise/panic into a lower-severity error or success.
- \`tap_error\`/diagnostic observers cannot swallow errors; telemetry
  callback failure must not replace primary error or panic.

## E. Queue/stream-specific error contracts

Channel read EOF is normal only on clean closure. A closed channel with
a cause must fail the stream read even after previously buffered items
drain. A cancelled consumer read does not cancel another producer.
A channel read commits delivery BEFORE successful job processing;
exactly-once processing is never implied.

TaskQueue:
- reserve, start, complete, ACK, NACK, and physical retire are separate
  transitions;
- on worker THROWS: release/retry/dead-letter based on explicit bounded
  retry policy; no automatic success ACK;
- on worker RAISES: fail the permitted scope boundary or escalate under
  source rules; no silent infinite retries;
- on worker PANICS: supervisor/CONTROL sees it, and the job cannot be
  silently rewritten as \`Outcome.failure\`;
- on cancellation: release/requeue only with explicit at-least-once
  policy and correct lease/fencing; protect idempotency side effects;
- exhausted/dead-letter jobs retain structured failure identity and
  original attempt provenance.
Reactive stream operators have exactly one terminal outcome, bounded
subscriptions and subscriber-specific failure/cancellation ownership.
A stream terminal \`Option.None\` is never an exception sentinel.
Task cancellations/unsubscriptions do not free borrowed source state
while another actor or scope still owns it.

## F. Regression proof obligations

- A guest-raised CancellationException is TaskHandle.Failed, never
  TaskHandle.Cancelled; authenticated owner cancellation is Cancelled.
- A genuine failure and cancelled sibling keep original error identity;
  ChildFailure diagnostics retain both, ordered by input index,
  only after physical retirement; diagnostics Future cannot be cancelled.
- \`Task.waterfall\` skip later lazy stages on synchronous throw,
  asynchronous failure, cancellation and host Error, without altering
  the original cause or treating Error as authenticated cancellation.
  Note: Java Error is NOT an Oreslang source panic proof.
- Proven parser signatures and callable-type effects; compile negative
  tests for undeclared throws/raises/panics, handler disallowed for
  panic, and no unchecked effect erasure in Future/Task generics.
- Failure from map_async, Observable flatMap and Task.inject never
  executes guest code on producer callback threads or under locks.
- Concurrent failure/cancellation races, slow physical-resume cleanup,
  admission/quiescence claims and JDK/Graal native + LLVM conformance.

Until explicit source effect metadata and negative compiler tests exist,
DO NOT claim full checked-chain error handling; the Java host
TaskHandle outcome distinctions and failure diagnostics are partial.

## Existing language-level Future ownership audit (October 22, 2026)

The pre-existing `OresFutures.all/race/allSettled` implementation
used aggregate cancellation hooks that **called cancel(true) on every
source Future**. This was incorrect for shared Task, actor-readiness,
actor-request and other borrowed Future values: an observer must not
gain source cancellation authority. In draft #1887 these hooks have
been changed to *detach their own runtime waiters only*; source work
is left live unless an actual TaskScope / actor owner revokes it.
The operation still propagates an authenticated **source**
cancellation into the aggregate result, but never revokes siblings.

The pre-existing `OresFutures.Settled(value,error)` view does not
have an explicit CANCELLED field. Its API is retained for legacy
compatibility, while the new
`OresFutures.allSettledDetailed(inputs)` returns
`FutureCombinators.Outcome<T>` with independent
SUCCESS/FAILURE/CANCELLED status. A user-thrown
CancellationException is FAILURE; runtime-cancelled Future is CANCELLED.
This is HOST-runtime-only classification; the compiler MUST still
reject attempts to wrap untrappable Oreslang panics into a result
value, and an actor supervisor must receive those panics.

Run `OresFuturesOwnershipTest` along with existing Future tests,
especially when source consumers assume that cancelling all/race
can implicitly revoke independent child Tasks. Such destructive
ownership belongs on explicit owning TaskScope APIs, not Future
observation. No exact-head passing test evidence is recorded.
