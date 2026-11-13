# Native Atto actor action-node lowering, v1 (draft)

**Stack:** Java #2976 (unleased source) → #3030 (checked scalar actor output) → this change. No parent is merged. The base of this change is the exact #3030 head `4042de7b1c948ef0b6cd3443083c94c070213efe`.

## Delta from #3030

The existing `NamespaceActorUnleasedSpawnV0` parser still admits only one verified declared shared actor, a bounded literal source `main(): int`, distinct `const` spawn bindings, literal sends and bounded `for` repetition. Instead of walking a `List<Spawn|Send>` at *execution* time, its verifier now lowers each admitted source action into a distinct typed Truffle `Node` (`SpawnNode`, `SendNode`) installed as `@Children` of the actor `RootNode`.

Each invocation owns a **fresh session** with runtime-bound ActorRefs, immutable code image and admitted-send counters. This session is internal host/compiler data and not a guest-accessible actor heap or mutable loan. A spawn is recorded for cleanup *before* its readiness barrier, so failed actor initialization cannot orphan a spawned ref. Send count is incremented only following successful mailbox admission. Cleanup attempts to stop and await **every** spawned sibling even if one stop fails. The existing source/capability, code-image/descriptor, actor-owned critical-write and mailbox-receipt validations remain in place.

The nodes call existing Java actor-runtime methods through `@TruffleBoundary` host bridges. This is genuine **closed-world action-node tree lowering**, but **not** the full Atto expression compiler, nor a proof of optimized guest execution. The current `Context.eval` entry remains synchronous with bounded host readiness/drain/teardown waits. This entry refuses execution on actor carrier threads / within physical actor turns; nonblocking scheduler-aware `await` lowering remains mandatory.

## Four new authored JUnit cases

`NamespaceActorTruffleActionNodesTest` (not executed until CI starts):
1. The canonical `.atto` source produces 2 `SpawnNode` and 2 `SendNode` Truffle children, preserving parser order.
2. Two independently parsed targets have disjoint node identities.
3. A regular restricted `Context.eval` executes the compiled source without installing any host lease (retains the old literal return result).
4. Unlowered dynamic send, nonconstant messages, nonconst spawns, unapproved lifecycle operations and direct state reads are rejected before runtime.

The inherited #3030 workflow is modified to check the exact PR head, run the complete default Maven test suite on GraalVM 25 and require these **4** cases and #3030's **5** output cases, with no failed/errored/skipped required tests.

## Explicit nonclaims and blockers

- `return 42` remains a source literal. This PR **does not** make a computed actor reply visible to guest `main`; #3030's output-consumption test still uses a Java test observer.
- No guest `await` continuation, typed request/reply correlation, nonescaping borrow across suspension, independent native LLVM heap proof, LLVM/.NET parity, AMQP broker, JIT performance, or Native Image AOT proof.
- The stage still uses a narrowly scoped unleased source admission, **not** unrestricted general compiler lowering. No other Atto actor syntax is claimed supported.
- GitHub Actions has previously returned failed jobs with **zero steps** on source and carrier. Authoring a workflow is not evidence of execution. Do not retry admission-disabled queues indiscriminately; first verify billing/runner organization policy or use an approved funded private mirror and verify **complete tree SHA**.
- Keep all stack PRs **draft** until stepful, exact-tree CI and independent review. Never count the parent's 86/86 historical selected-blob proof as test results for this head.

## Next compiler gate

Replace host lifecycle waiting with scheduler-owned, cancellable `OresFuture` continuations, then permit real parser-checked `.atto` reply consumption using the existing bounded immutable `ActorOutputStream`. The source return must be derived from actual per-actor state (e.g., value 1000) and tested as a guest observation; do not route it through a compiler-private `StateReceipt` or hardcoded scalar. Test failure modes: output correlation, cancellation, stale actor generations, mailbox saturation, independent actor heaps/arenas, and return-time revocation. Run the identical fixture on LLVM and .NET NativeAOT.
