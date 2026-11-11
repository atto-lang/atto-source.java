# Atomic inherited supervisor child publication — V1 stacked draft

**Status: opt-in experimental Java runtime integration, NOT_CERTIFIED.** Stacked on Java [#2785](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2785), which already carries the runtime escrow and DATA admission fences. Existing independent terminal CONTROL implementations (#1222/#1320) still require canonical ledger reconciliation.

## Previously blocked behavior

An actor executing under `SupervisorFailureEscrowRuntimeBridgeV1` could not spawn a nested actor: `ActorRuntime.spawnInternal` rejected it before reserving an actor slot. Without this restriction a child was physically published but did not have an inherited supervisor CONTROL node and would escape `SupervisorEscrowSubtreeRecoveryV1`'s descendant cancellation snapshot.

## New publication boundary

Under the **existing** `runtimeLifecycleLock → parent.lifecycleLock`, a nested spawn obtains the parent's active `SupervisorEscrowBinding`. For supervised parents only, it also holds **that exact bridge's publication monitor** while:

1. Checking the parent's runtime-owned generation against the same failure-fencing ledger.
2. Reserving an immutable child terminal notice identity via `ledger.registerChild`, **before publication**.
3. Registering the runtime-minted child `ActorRef` in both the bridge's identity map and the existing `SupervisorEscrowSubtreeRecoveryV1` cancellation registry.
4. Publishing the actor in parent-child, optional group-owner, and `ActorRuntime.actors` registries.
5. Releasing the bridge monitor, allowing an ancestor `failedFromRuntime` to fence the now-complete subtree.

The bridge's `failedFromRuntime` and `finalizedFromRuntime` are serialized on the **same monitor**, so no failure dispatcher can snapshot a reserved-but-not-published child. Neither the guest actor nor its factory receives a CONTROL capability or ledger node ID. All public spawn entrypoints pass through the same `spawnInternal` path.

When publication fails, the inner catch removes actors, parent links, inherited group membership and the child's escrow binding **before releasing the bridge monitor**; `rollbackUnpublishedChild` also verifies parent/generation, zero DATA leases, no terminal fault and no descendants. The recovery coordinator removes its unexposed ref. Only then does the outer constructor/admission rollback retire the reserved actor count. CONTROL identities and reservation sequences are not recycled.

Unsupervised actors continue using their existing parent/group publication path without an extra bridge lock.

## New executable gates (authored, not yet hosted-executed)

- Updated `SupervisorEscrowAdmissionLinearizationIntegrationTest`: real private parent behavior creates a supervised nested actor and the ledger must show the correct inherited generation and parent node **before** the child is returned; after a real panic the CONTROL executor cancels and physically retires that child without poisoning healthy siblings. Direct unpublished reservation/rollback can be rebound and cannot forge a terminal notice.
- New `SupervisorEscrowInheritedChildRaceIntegrationTest` (2 tests): a real parent turn produces 16 real child ActorRefs, their node parentage is checked, the parent's panic must retire every descendant and preserve an unrelated sibling; a stale parent generation cannot mint a CONTROL slot or leave a ghost ledger node.
- The exact-head `scripts/verify-supervisor-escrow-runtime-bridge-v1.sh` pins the ledger, runtime, bridge, coordinator and all JUnit blobs; 4 suites now require **13 real runtime tests**, none skipped, no errors/failures; the existing JDK21 dependency-free 557-assertion/65,536-attempt escrow stress remains mandatory.

## Security and unverified limits

- No full Maven/JDK25 compile or real `ActorRuntime` JUnit has executed successfully on this exact branch yet; source-org Actions still show zero job steps, and the local environment lacks Maven/JDK25. **Do not merge based on this description.**
- These host actor-behavior tests are NOT compiled `.atto` guest tests or GraalVM Native Image runs; source syntax and lowering for `define supervisor` require separate validation.
- `ActorRuntime.done()` is NOT a physical native-FFI/stack/arena release receipt. The restart verifier supplied in some tests is intentionally synthetic and NOT production-safe.
- `SupervisorEscrowSubtreeRecoveryV1` coordinates opt-in host supervisors only. Real runtime-wide group admission, policies (one_for_one / one_for_all / rest_for_one), crash-consistent external guardians, exact-once decision journals and comprehensive accepted-work cancellation are future release gates.
- `MAX_NODES=4096` bounds the in-memory ledger; aborted reservations are removed, but successful actor incarnations remain historic, so long-lived generation growth eventually fails closed. No hidden eviction or ID reuse is permitted.
- No cross-organization private-source transfer without an approved destination and security review.

The shared conformance [#659](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/659) includes a **synthetic** differential trace and executable validator for DATA/CONTROL/fence/retirement ordering. It does not attest backend resource release.

**Keep DRAFT, do not merge, until exact-SHA, real executed host and guest traces plus independently reviewed cancellation/rollback proof exist.**
