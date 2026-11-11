# Actor hot upgrade: CONTROL-owned atomic logical route v1

## Actual delivered component

`ActorRouteCoordinatorV1<R>` (trusted host package) composes the pre-existing
`LogicalActorProxyMailboxV1` and `ActorUpgradeEpochGate` into one authoritative
serving tuple: (logical actor ID, route epoch, immutable code-image manifest,
validated bounded state checkpoint, physical route, old dispatch watermark).

- `LogicalRef.send()` admits immutable copied bytes under bounded message/byte
  credits and retains a **stable logical identity** across physical code versions.
- `dispatchNext()` reserves one exact old/new route and executes the transport
  callback **outside the coordinator lock**. Only an authenticated
  `PhysicalCompletion.acknowledge()` after real execution refunds the mail
  credit. Returning from enqueue/submit is not enough; early ACK cannot retire
  a still-executing submit callback.
- `stage()` freezes dispatch but keeps bounded ingress available. It is
  serialized under the same coordinator monitor as dispatch.
- `Stage.publish()` checks old-watermark drain, in-flight physical callbacks,
  separate old task pins, same state/message/capability ABI, and explicit
  checkpoint schema identity. The mailbox handover and epoch gate are
  committed under the coordinator lock **before** one volatile published tuple
  becomes externally observable. Any ambiguous post-commit exception poisons
  the route, never reopens stale V1.
- `abort()` explicitly reopens old code with the same route epoch and FIFO.
  `close()` rejects live logical mail, prepared stages, or task/transport pins.
- Physical actor/native-image/FFI/borrow/Timer retirement remains owned by the
  actual runtime and must wait for external physical quiescence receipts.

`ActorRuntimeHotRouteBridgeV1` is the first real runtime adapter for trusted
SHARED host actors. It submits through `ActorRef.send()` and acknowledges
logical mail only after `ActorRef.drain()` completes. `drain()` proves
**message processing**, *NOT* that suspended Tasks, FFI, actor arena borrows,
timers, or native code-image holders are retired. Those need independent pins.

## IMPORTANT: security and functionality boundaries

- This is **not yet a real .atto guest hot-swap**; the HOST demo simulates state
  transfer and the `ActorRuntime` integration test uses Java Behavior factories.
- The logical send handle is new and package-local, **not the original
  `ActorRuntime.ActorRef` or a language-level `ActorRef`**. Callers must
  prohibit any direct send to the underlying physical ref. Otherwise DATA could
  bypass the logical mailbox and break cutover.
- The candidate physical route and checkpoint are supplied by trusted
  CONTROL. This component cannot independently prove compiler signature,
  candidate readiness, schema migration correctness or old physical quiescence.
  Guest code must never obtain the coordinator seal or completion receipt.
- PRIVATE/UNTRUSTED workers, cross-VM migration, and auto-recovery after a
  post-commit crash are unsupported. Do not generalize SHARED host receipts.
- The old physical actor can coexist with V2 but **must not be torn down** until
  the host demonstrates no outstanding turn, continuation, Task, timer, FFI,
  borrow or code-image lease.
- This avoids wholesale replacement of overlapping stale draft PR #1967.

## Next production steps

1. The claimed physical SHARED ActorRef now fails closed on raw send/sendAsync.
   Next make compiler-generated guest ActorRefs address the stable logical
   route rather than the sealed physical actor reference.
2. Require a real runtime-minted candidate-ready/state-restore witness and
   old-version quiescence attestation before publication (including Task/FFI).
3. Wire physical resource leases and supervisor CONTROL lane (PR #2835, #1476).
4. Run real `.atto` guest v1→v2 with count 10→15 at one stable guest ref,
   plus invalid ABI, candidate crash, starvation, cancellation, FIFO, and
   cross-backend parity tests. PR #2845's source test fixtures are a starting
   point, NOT such a hot-swap test.

## Verification

```sh
mvn -B -ntp -Dtest=ActorRouteCoordinatorV1Test,ActorPhysicalHotRouteIngressFenceTest,ActorRuntimeHotRouteBridgeV1Test -DfailIfNoTests=true test
```

A private exact-blob mirror can execute the dependency-free gate, logical
mailbox, coordinator, host JUnit and demo on a funded alternate-org runner;
that is NOT evidence for the real ActorRuntime adapter or .atto compiler.

## 2026-11-11 physical ActorRef ingress hardening

- A host-only, one-way `ActorRuntime.claimPhysicalForHotRoute()` claim seals the physical actor's `ActorRef.send()`, `sendAsync()`, and ActorGroup Mailman direct DATA sends. The permit is issued by the actual `ActorRuntime` to a ready SHARED actor only.
- `ActorCell` checks the fence before reserving mail and again inside the physical mailbox publication monitor. Pending asynchronous sends are rechecked to prevent races with a route claim.
- `ActorRuntimeHotRouteBridgeV1` now requires a runtime-issued `HotRoutePhysical<M>`, never a naked physical ActorRef. Every send consumes exactly one coordinator-minted, route-and-epoch-bound physical receipt before physical admission.
- Each physical endpoint binds to one logical coordinator. A duplicate owner, foreign-runtime successor, unready actor or stale physical receipt fails closed. Old endpoints retire monotonically once the v2 logical route publishes.

**Remaining limitations:** the logical send handle remains host-side, not yet the language-level guest `ActorRef`. Fencing a raw physical ActorRef does not redirect that raw ref. Physical mail admitted before claim is now captured by a runtime `ActorRef.drain()` barrier immediately after one-way sealing; both the old stage and a new candidate must wait for their respective preclaim processing receipts. This is not a physical Task/FFI/borrow/continuation quiescence proof, which remains an independent requirement. Candidate state is host-supplied rather than compiler-migrated. Do not treat source Actions runs with zero executed steps as guest evidence.
