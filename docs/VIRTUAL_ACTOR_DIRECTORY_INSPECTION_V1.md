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
