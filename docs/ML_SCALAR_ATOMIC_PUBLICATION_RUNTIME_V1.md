# Java actor-owned ML scalar publication: executable runtime substrate

**Scope:** This change is a production-source `SealedActorState` primitive plus
JUnit exercising an actual `ActorRuntime` mailbox turn. It is not a compiled
Oreslang tensor forward/backward implementation, not durable multi-shard
training, and not a substitute for authenticated gradient transport.

## Runtime contract

- Exactly one guest-owned `state { ... }` schema remains the source of persistent
  guest fields. `SealedActorState.values` is its runtime storage, not a second
  guest field. Publication replaces the complete runtime scalar field map.
- `prepareScalarPublication(versionField, expectedVersion,
  expectedSnapshotRevision, immutableReplacements)` requires owning actor
  context **outside** a state loan. The caller must carry the revision observed
  via `Access.revision()` **in the same read loan as the input weights**.
  A later scalar mutation cannot be laundered into an old optimizer candidate
  by staging that candidate after the mutation (TOCTOU). The compiler must
  eventually make this input-snapshot evidence mandatory rather than relying
  on trusted Java caller discipline. Every replacement is schema-validated,
  and the long version field must advance by exactly one.
  The `ScalarPublication` capability is issuer-bound, non-serializable and
  single-use.
- `critical(true, access -> access.publishScalars(prepared))` requires a
  live exclusive write grant, same state issuer, matching version and mutation
  revision. The internal next-state copy, scalar checks and string-byte quota
  preflight happen before swapping the map reference. After swap, only
  nonallocating assignments to the byte-accounting, revision and consumed flag
  remain. A stale or invalid candidate leaves version, weights and optimizer
  fields unchanged. No application callback runs after the swap.
- All scalar writes, owned-region transfers and owned-byte writes advance the
  checked mutation revision. Explicit retry means preparing a **new**
  candidate from a current authenticated snapshot; a stale candidate is never
  silently rebased.
- `SealedActorState.critical` now admits a physical carrier nonblockingly
  and exclusively. Nested loans on the same carrier keep the existing strict
  read-to-write denial; a different carrier fails closed instead of racing
  `current`, `values` or a state loan. This protects maxThreads=3 safety
  but is **not** a fair 3-thread state RW scheduler: a future source-level
  scheduler must enqueue/retry denied state loans after physical quiescence.
- Access use on another physical carrier is rejected even if a logical actor
  ID matches. A synchronous critical callback cannot suspend or return a loan.

## Remaining critical release blockers

1. The compiler does not yet lower a typed Oreslang optimizer transaction to
   this Java runtime API, and no compiled tensor VJP runs against it.
2. Caller-owned candidate values are not bound to a canonical signed gradient
   manifest. The exact activation-ticket capability **does not** grant
   authority to submit arbitrary named incoming edges. Sender identity must
   come from authenticated actor transport, bound to a registered graph edge
   and incarnation, with bounded one-time replay accounting.
3. There is no journaling, multi-actor two-phase publication, durable receipt
   compaction or crash replay. The single Java reference swap is a local
   in-memory transition, not crash-atomic storage.
4. The state gate fail-closes under simultaneous physical carriers; native
   1/3 carrier integration, cancellation, STOP and source-level loan escape
   coverage must pass before the execution-mode parity gate.
5. This branch is stacked on the existing `with/using` contract draft, not
   a standalone change on current main. Do not merge out of order.

## Verification identity

Source tests: `SealedActorMlScalarPublicationTest`. It drives a real
`ActorRuntime.spawnShared` turn, checked publication, denial under a READ
loan, clone/foreign-issuer rejection, replay, stale revision, malformed
candidate failure-atomicity and retry. These are committed JUnit test sources.
**Exact-head Maven/JDK25 and GraalVM guest CI remain UNVERIFIED.** Do not
count a job with `steps:null` as an executed test or successful compiler run.
No private-source external mirroring was performed.
