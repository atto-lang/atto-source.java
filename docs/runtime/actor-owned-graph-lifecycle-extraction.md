# Actor-owned graph lifecycle: main-based extraction

This draft extracts `ActorOwnedGraphArena` from #558 without replacing `ActorRuntime`, `OresEvalRootNode`, `OresContext`, or `HotReloadManager` on current main.

## Provenance and scope
- Source: #558 `harden/actor-heap-isolation-gc-20261010`, `ActorOwnedGraphArena.java` blob `305d5e90f1ea6bb04c1ba1b3ee0230483ea8477c`.
- This extraction hardens callback/drop failure handling and adds six standalone JUnit lifecycle tests.
- The constructor and `retireFromActor` remain package-private; **this PR does not wire the arena into ActorRuntime**. The #558 integration of memory-slice quota charging, actor termination and owner checks is a separate, conflict-aware step.
- This is host-managed Java object lifecycle, not a native zero-GC guest heap, independent JVM GC, true untrusted Graal isolate, or source-compiler lowering.

## Acceptance before promotion
- Exact-head JDK 21+ compile and all six `ActorOwnedGraphArenaLifecycleTest` tests run through JUnit, plus full Maven tests on GraalVM/JDK 25.
- Verify `ActorRuntime.ActorMemorySlice` owner-check, quota-reservation rollback, lease-on-retirement, and all actor-kind integrations against modern main, without copying #558's stale runtime.
- Test the actual guest isolate and actor lifecycle separately. No private source export to any unapproved organization, and no release without native receipts.
