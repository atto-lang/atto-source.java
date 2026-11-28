# Virtual actor local directory inspection v1

**Status: HOST_REGISTRY_PARTIAL.** This change extends the existing local activation registry with immutable metadata inspection and a versioned address codec. It does not connect the registry to ActorRuntime, supervisor CONTROL, persistent state, remote transport, clustered placement or guest-visible APIs.

## Added
- `VirtualActorActivationRegistry.Inspection`: immutable logical identity, latest burned activation generation, activation-presence/publication state, outstanding accepted admissions and registry-closed state.
- `inspect(LogicalRef)`: side-effect-free inspection through an already issued capability.
- `inspectIdentity(Identity)`: side-effect-free lookup which does not allocate a logical slot and returns empty for unknown or foreign-domain identities.
- `VirtualActorAddressV1`: canonical versioned Base64URL locator with strict UTF-8, size, control-character, surrogate, NFC and canonical-form checks.
- `VirtualActorDirectoryInspection`: read-only snapshots addressable by an existing local reference, identity or canonical address.

## Security boundary
The address is a locator, not a bearer credential or authorization token. Base64URL is encoding, not encryption: callers who can read an address can decode its components. Do not place secrets or private personal data in actor keys. The API returns no mutable activation, state, mailbox payload, host pointer, supervisor CONTROL reference or diagnostic exception.

Inspection never calls `resolve`, `activate`, `publish` or `tryAdmit`. Unknown lookups do not consume identity capacity. Snapshot `partial=true` is mandatory because this registry is not a complete ActorRuntime directory or supervisor view.

## Verification
A new exact-head workflow targets `VirtualActorDirectoryInspectionTest` and the existing `ExecutableVirtualActorStateContainerTest`. Required cases include Unicode/NFC address round-trip, malformed/noncanonical address rejection, unknown lookup without allocation, activation-state snapshots, generation burning after failed initialization, outstanding admission visibility, passivation metadata, and cross-domain denial.

The workflow must show nonzero executed steps and successful Surefire reports at the exact PR head before these host tests can be considered passed. Even then, it proves only this local Java registry/codec slice, not guest execution or Orleans-style runtime semantics.

## Remaining P0
1. Attach logical identity to a supervisor-owned runtime record and issue an authorized inspect capability.
2. Route inspection through the existing bounded CONTROL dispatcher, never inline on the requesting actor/carrier.
3. Track real actor incarnation generation in ActorRuntime and reconcile delayed/duplicate failure receipts.
4. Wire logical references to actual ActorRuntime mailbox invocation and generation-fenced state commit.
5. Add source-compiled guest execution and shared VA01–VA20 parity fixtures.

## Supervisor-bound worker inspection extension

The follow-up host adapter `VirtualActorSupervisorDirectory` binds a logical identity to a real Java `ActorRef` only when the actor is registered as a managed worker of the exact same `ActorSupervisor` through its `ActorGroup`. Cooperative group membership alone does not prove lifecycle ownership.

A successful binding mints/publishes a local activation generation and records the real ActorId, ActorRef generation and kind. It observes the worker's runtime-owned `done()` Future; only after physical finalization and turn exit does the callback retire the logical activation. A subsequent managed worker can bind to the same logical ID with a strictly newer logical activation generation. If accounting or retirement fails, the binding remains visible/quarantined instead of being reported passivated.

Read access is granted by an opaque host-issued session-bound object and submitted through the already configured ActorRuntime CONTROL dispatcher. No inline fallback or new executor is created. A queued caller timeout does not free the CONTROL admission slot until the physical CONTROL turn ends. Grants are revocable and scoped to one tenant plus one exact supervisor.

### Remaining limitations

This integration is a local host-side management adapter. It does not intercept normal virtual-actor guest method calls, route mailbox DATA to the bound worker, participate in supervisor panic/restart policy, persist actor state, or provide a distributed directory. Activation replacement is currently an explicit trusted-host binding operation after the old worker has physically completed; it is not an autonomous restart strategy. Keep the feature opt-in and do not expose this API to guest code or a public HTTP endpoint.

Exact-head JUnit coverage now additionally includes same-supervisor managed-worker binding, rejection of another supervisor's worker, generation increase after physical worker termination, CONTROL-dispatched ID/address inspection, session/scope rejection and unavailable-CONTROL fail-closed behavior.

## Stable logical identity, supervisor ownership and activation plans

The follow-up `VirtualActorSupervisorDirectory` no longer embeds the supervisor UUID in the logical identity. The registry identity is tenant/domain + namespace-qualified declaration + NFC key. Supervisor ownership is a separate runtime-owned edge: `ActorRuntime` atomically claims each logical identity for one supervisor, and a second supervisor cannot claim the same identity. Cooperative ActorGroup membership does not establish this edge.

The actual `VirtualActorActivationRegistry` is retained by `ActorRuntime`, not by the replaceable directory facade. Only one facade can be active for a given supervisor at a time. Closing a facade releases its service ownership token but preserves logical identity records, activation history and directory revision for the remaining runtime lifetime. Reopening with incompatible identity/mailbox bounds is rejected.

Use `resolve(namespace, declaration, key)` to distinguish identical declaration/key pairs in separate application namespaces. The original two-argument overload resolves in the `default` namespace. The host-provided tenant/domain string must be stable and unique in the relevant storage/routing domain; include a stable runtime-domain qualifier when multiple runtime domains share a tenant naming space.

### Activation reservation and persistent identity

`prepareActivation(logical, stateSlot)` reserves a non-reusable activation generation before physical worker construction and returns a single-use host-only `ActivationPlan`. Its immutable descriptor contains:
- the stable logical identity;
- a runtime-local monotonically increasing logical activation generation;
- a deterministic opaque persistence actor key (`va1_` + SHA-256-derived Base64URL), plus trusted tenant and state-slot values.

The opaque actor key is independent of supervisor UUID and activation generation, so it remains the same when a supervisor/activation is recreated for the same stable tenant, namespace, actor type, and key. `publishActivation(plan, actor)` publishes only after the worker is ready, alive and registered as a managed child of the exact supervisor. `abortActivation(plan)` burns a failed candidate generation. Directory recreation within one ActorRuntime consumes the runtime-wide generation allocator, so local generations do not reset when a directory object is replaced.

**Important:** this logical activation generation is runtime-local metadata, NOT a durable storage epoch and NOT provider fencing authority across process restarts. The trusted persistence provider must independently issue/recover a monotonically increasing durable epoch and enforce it at commit/restore. Do not pass the `ActivationPlan` through DATA or guest code; the descriptor is key metadata only. The existing draft [Java persistence sidecar PR #2282](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2282) provides host-side CAS/fencing and durable receipts but is not yet connected to AttoLang source lowering or this directory.

### Current lifecycle connection and remaining gap

The directory binds only a real ActorRuntime worker managed by the specified supervisor. It observes that worker's runtime-owned `done()` Future and passivates the logical activation only after finalization/physical turn exit. A replacement worker can then be bound under the same logical key with a higher runtime-local generation.

This is still an explicit host adapter: callers must reserve a plan, construct/initialize the real actor, and publish its binding. It does not yet intercept guest virtual actor invocation, route logical mailbox DATA, schedule automatic restart according to OTP policy, or restore snapshots before activation. Persistence key metadata is ready for the trusted host bridge, but no state is saved/restored by this PR.

## Supervisor enumeration and cursor semantics

`VirtualActorSupervisorDirectory.requestList(...)` provides bounded pagination on the configured CONTROL dispatcher. It accepts page sizes 1–128, returns immutable enriched snapshots, a directory revision and an optional last-identity cursor. The registry's lower-level page limit is 256. Ordering is deterministic by namespace-qualified declaration then canonical key; one supervisor registry has one tenant/domain.

The cursor is valid only for its exact directory revision. The revision advances on logical identity admission/discard, activation reservation, publish, abort, mailbox admission/settlement, passivation and registry close. Any intervening change makes continuation fail with `StaleDirectoryPageException`; callers restart from the first page rather than mixing snapshots from different revisions. There is no unbounded list-all operation. Enumeration is limited to the one registry retained for that exact supervisor.

### CONTROL timeout race

A bounded inspection request has one atomic phase: queued, running, terminal. If timeout wins while queued, it atomically transitions queued → cancelled-before-start; the eventual queued dispatcher callback becomes a no-op and cannot inspect. The per-session grant can be reused immediately, but the global CONTROL admission credit remains charged until that stale callback is physically drained (or the dispatcher proves that it will never execute). If the CONTROL worker wins queued → running, only its physical finally path releases both grant and global slot. This prevents queued-timeout floods from creating unbounded stale CONTROL work and prevents a second concurrent capture under the same grant.

## Physical termination outcome visibility

When a bound worker's runtime-owned `done()` Future settles, the directory waits for that physical completion barrier, deactivates the logical activation, and retains one immutable terminal record: last physical ActorId, ActorRef generation, kind, terminal time, and (for abnormal failure) only the unwrapped exception **class name**. Cancellation is separately represented from normal passivation and failure. It never retains the Throwable, exception message, stack trace, guest payload or mutable failure graph.

Inspection can therefore distinguish `ACTIVE`, `TERMINATION_PENDING_RECONCILIATION`, `PASSIVATED`, `CANCELLED` and `FAILED`. A failure result is diagnostic metadata only; it is not a supervisor policy decision, a reserved child terminal receipt, or proof that automatic restart occurred. The current Java `ActorSupervisor` substrate remains advisory and must be integrated with the durable terminal CONTROL/restart-ticket work before claiming OTP/Orleans-style recovery.

## Host supervisor entry point

The canonical host acquisition path is now `ActorSupervisor.virtualActorDirectory(tenant, maxIdentities, mailboxCapacity)`. It is guarded by the existing `requireSupervisorContext` and runtime-affinity checks. Actor turns, ActorGroup Mailmen and guest code cannot acquire the directory by invoking that method. Direct construction of the implementation is also guarded by the same host-only runtime registry checks.

## Host authentication boundary

`issueInspectionGrant(authenticatedSession, ttl)` binds a short-lived grant to the exact host session object and the directory's configured tenant/supervisor scope. It does **not** authenticate that session, prove the tenant string, or perform application authorization itself. The host embedding service must authenticate the caller, derive the tenant from trusted credentials, authorize the supervisor scope, and only then ask the directory to mint the opaque in-process grant. Never mint grants from request fields, guest arguments, cookies, address contents or actor messages alone.

Activation-plan construction is failure-atomic: if immutable descriptor/plan allocation fails after reserving a new generation, the candidate is aborted and that generation remains burned. No inaccessible unpublished activation slot is left behind.
