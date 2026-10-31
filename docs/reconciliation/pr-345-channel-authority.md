# PR #345 — channel-callable authority semantic reconciliation

Historical head `04f2ac25394f7e2245f88d23c305d1f491c96b63`; latest Java main `d63f2611fb77fdda361807ab608da6f6656d1e8e`.

The historical TypeChecker snapshot applied an unconditional prohibition to callable payloads in every Channel. Current main deliberately distinguishes **owned local callbacks** that may flow through locally owned channels from executable authority crossing actor transport boundaries, where capability and transitive ownership restrictions apply. Do **not** replace the 562-KiB modern TypeChecker with its older 307-KiB snapshot or copy historical `channelsCarryDataNotCallableAuthority` which would ban a legitimate local transport class.

Preserve main `ChannelSelectSyntaxTest`, including source `do select`, typed arms, deferred execution/synchronization semantics, and actor cancellation tests, rather than restoring the older test class. Historical differences are retained in first-parent ancestry for review.

Current main already carries dedicated executable coverage in `ChannelCallableTransportTest` and `DeferredChannelOwnershipHardeningTest`: local callable channels are accepted and executed, transferred callables are move-only, nested callable payloads are rejected at actor boundaries, borrowed payloads and captures are rejected, and deferred channel callbacks/select arms preserve ownership fences. Remaining follow-up is narrower: generic specialization and cross-Task/await escape cases not already covered by those suites. **Do not claim the old blanket channel prohibition is canonical.**

This commit is conflict resolution only. Exact-head compiler/JVM tests and independent review remain required.
