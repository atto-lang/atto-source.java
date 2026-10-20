# Runtime-owned actor ↔ supervisor application reply bridge: integration stage 1

Status: **host runtime component**, not a source-visible actor call API.
Head branch: `integration/actor-genserver-reply-future-20261020`

## Implemented in this branch

- Port existing `GenServerCallPort<T>` onto the current Java `main`
  (**not** by blindly merging stacked #1216). It uses the already merged
  `GenServerReplyLedger` generational-slot implementation and the real,
  current `OresFuture<T>` with a cancellation admission predicate.
- Implement package-private `registerAndPublish(Target, TrustedPublisher)`,
  which acquires caller correlation credit and Future before invoking a
  trusted publisher **outside** the port lock; synchronous publication
  rejection fails the corresponding Future with the original cause and
  releases credit. The publisher must guarantee that failure implies no
  mailbox publication, and must restore original payload ownership itself.
  This method is not a source-call or DATA capability.
- Preserve actor-target indexed retirement, incarnation fencing, full close
  and timeout settlement, late/double reply rejection and exact ticket
  identity.
- Add new focused JUnit regression tests for callback/cancel revocation,
  synchronous reply, admission denial and 128 race iterations for
  reply-vs-timeout and publication-vs-close.
- Add a standalone smoke that compiles the **unmodified production OresFuture**
  alongside the actual production call port and ledger.

## Explicit boundaries

**No ActorRuntime mailbox or source handler was modified.** This PR does
not introduce `ActorRef.request()` on current `main`, typed
`SupervisorAppRef`, an actor->supervisor DATA endpoint, true guest-source
execution, a functioning scheduler/continuation dispatch, interprocess
authentication, or native LLVM/Java parity.

The existing source-actor request design PR #456 remains severely behind
current `main` and conflicts semantically with intervening ActorRuntime,
OresScheduler and capability changes. DO NOT overwrite current
`ActorRuntime.java` with that legacy branch. Reconcile source compiler and
unary mailbox ABI in a dedicated child PR after this port's full exact-head
JDK25/Graal test matrix runs. The host-only GenServer call port is *not*
permission to ship a response authority in guest DATA or CONTROL.

## Next runtime integration (stage 2 — executable guest proof)

1. Resolve the current effective private `run(Request): Response` signature
   from the source actor's type/ABI metadata; reject mixed `run/receive` and
   foreign generic specializations until admitted and linked.
2. In `ActorRuntime`, allocate typed correlation/Future via a runtime-private
   port, reserve mailbox + response bytes and transfer request ownership
   together; attach ticket only to the sealed envelope metadata (not the
   message graph). Validate origin ActorRef and endpoint incarnation before
   publication, then reject self-request that cannot progress under serial
   admission.
3. Invoke the callee's private handler on its own single-flight actor turn.
   The runtime resolves the sealed ticket with the actual receiver identity,
   validates/copies/fences the response and completes the real Future. Schedule
   the originating guest continuation **only** on its own actor execution lane.
4. On admission failure, unwind both quotas and return untransferred payload
   ownership. On actor stop/raise/panic/restart, settle only that incarnation's
   tickets. Timeout/cancel cannot undo an accepted handler's side effects.
5. Authorize child->supervisor calls through an explicitly granted service
   ActorRef on the **normal DATA lane**. Never use or forge reserved CONTROL
   terminal notices. A standalone supervisor application endpoint can later
   reuse this correlation primitive without ever exposing reply tokens.
6. Run source executable scenarios: supervisor->actor, actor->supervisor
   service, actor->actor, concurrent out-of-order replies, cancel-before-send,
   cancel-after-dispatch, quota exhaustion, forged reply and actor restart.
   Pin compiler/runtime test blobs and full Git tree. Do not treat a
   standalone host reply-port smoke as end-to-end proof.

## CI proof boundaries

The source organization has previously seen zero-step Actions failures; a
private funded carrier is acceptable only with explicit authorization and
private-source exposure controls. A narrow private carrier for this change
mirrors **exact production Git blobs**, including unmodified OresFuture. It
compiles only those classes using minimal compile-only shims for OresScheduler
and ActorRuntime; no guest actor or real scheduler executes there. This
permits useful verified host call-port testing without misrepresenting it as
a full branch conformance run.

Blocking merge gates: source JDK25 Maven `GenServerCallPortTest` and
`GenServerCallPortTransactionalTest`, full ActorRuntime guest integration,
native-image/hybrid and isolation-ABI checks. Keep this PR DRAFT until those
have been executed and reviewed.
