# State container and virtual actor VM substrates — experimental

**Status:** VM-internal components only, not executable guest declarations. The Java source parser intentionally rejects `define state container` and `define virtual actor` with explicit feature diagnostics until AST/HIR, ownership checker and actor runtime lowering are connected.

## State-container admission

`StateContainerAccessGate` is a bounded, non-parking read/write **capability gate**. Multiple read leases can be live; a write lease is exclusive. A refused acquisition returns `null`, not a blocking wait. Acquisitions and releases use a short VM monitor and never invoke guest code under it. A `Lease.requireActive(Mode)` check authorizes the corresponding access *at the point it is checked*, but separate physical-turn quiescence and heap-region ownership must still be established before allocating/returning memory. The gate itself stores **no mutable guest state** and does not prevent raw pointers escaping.

`close()` rejects new leases and immediately invalidates old authority. `leasesQuiescentAfterClose()` reports whether every outstanding lease has physically released; it does not attest absence of raw aliases, DMA operations, foreign runtimes or in-flight guest callbacks.

Future lowering must wrap actual state access with a verified lease and `finally` cleanup and enforce no escaped borrows, nested heap aliasing or suspension inside protected callbacks.

## Virtual actor admission

`VirtualActorActivationRegistry` stores bounded local logical identities `(domain, declaration, NFC(key))` and epochs that never rewind. It rejects malformed UTF-16/control characters and oversized keys. Resolving a logical reference does not create an actor. `activate` reserves a candidate incarnation; only the initializer's token can `publish` it; `abort` fences failed initialization. There is one published local incarnation per logical key; stale and foreign-registry handles fail closed.

`tryAdmit` returns an accounting ticket or `null` under bounded backpressure. Each ticket must be explicitly `settle`d with `PROCESSED`, `FAILED_CONTROL_NOTIFIED` or `CANCELLED_NOTIFIED` **after** the corresponding operation/notification has actually happened. The registry does not deliver messages or emit CONTROL notices itself. No no-argument `close` of an accepted ticket can silently treat work as completed. Deactivation and registry shutdown reject outstanding accepted tickets.

This registry is **not** an ActorRuntime, distributed grain engine, persistent activation store, verified mailbox, scheduler or fault supervisor. Guest-level `VirtualActorRef` and virtual actor methods remain unimplemented.

## VM-internal observational lookup (candidate, 2026-11-08)

`VirtualActorActivationRegistry.inspectExistingForHost(identity)` and
`inspectForHost(logical_ref)` are **package-private host-only snapshots**.
They never reserve an identity, activate a guest, consume an activation epoch,
admit mail, or return a live `Slot`/mailbox/heap reference. The immutable
observation distinguishes INACTIVE, INITIALIZING, ACTIVE, and CLOSED at one
instant; it includes the current local incarnation and accepted-count but is
not a physical-termination receipt. Another thread may change the state
immediately after return.

The registry identity is a **local** `(domain, declaration, NFC(key))` string
tuple and is NOT interchangeable with cross-backend ActorId v1's authenticated
tenant/namespace/type/tagged-key encoding. No physical address is presently
registered, and no address-to-activation management lookup is claimed.

**Security boundary:** these VM-private methods do not authenticate a
supervisor or tenant; a future authenticated `SupervisorDirectory` adapter
MUST validate caller/tenant, direct-child ownership, supervisor incarnation
and management-vs-inspection scopes **before** invoking them. Do not export
them to guest reflection or wire codecs. Logical lookup, physical location
and CONTROL authority must remain distinct. Negative inspection must not
implicitly consume the bounded identity pool.

New `VirtualActorLocalObservationTest` covers nonallocation under capacity,
two-phase publication, immutable stale snapshots, foreign-registry refusal,
and closed/inactive states. It is an **authored JUnit test, NOT_EXECUTED**
until an exact-head step-bearing JDK 25/Maven receipt exists. This slice
does not implement distributed fencing, durable IDs, child enumeration,
virtual actor guest lowering, or supervisor CONTROL dispatch. See AttoLang
spec PR #25 and conformance #227 for the target contract.

## Verification

Candidate JUnit classes:
- `StateContainerAccessGateTest`
- `VirtualActorActivationRegistryTest`
- `StateContainerVirtualActorAdmissionTest`

They require a real JDK/Maven execution receipt; adding them to a branch alone is `NOT_EXECUTED`. Do not promote `RUNTIME_VERIFIED` or backend parity from source review, model tests, or source-org Actions that failed with `steps: null`.

Companion draft spec: `oreslang-language-specs.md/spec/state-containers-and-virtual-actors.md`. Shared 20-case conformance target: `oreslang-platform-conformance/contracts/state-container-virtual-actor-v1.json`.
