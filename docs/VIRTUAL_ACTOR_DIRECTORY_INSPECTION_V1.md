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
