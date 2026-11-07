# AttoLang Java: compiler-local source-place provenance

**Status: draft, not executed by production JUnit/GraalVM CI.**

The existing Java `OwnershipChecker` now resolves bounded (at most 8) named field/index projection chains to their current lexical `VarState` entry using a typed `ResolvedOwnershipPlace` record, instead of merely walking source syntax and allowing an unknown root to pass. The record carries the root's name, actual compiler-managed state, ordered projections, and index-projection marker.

Before direct `rt share projected.field` or `rt share projected[index]`, missing/unknown/computed/untracked lexical roots reject with `E_SHARE_UNTRACKED_PROJECTION`. Borrowed and actor-confined roots reject with `E_SHARE_BORROWED_PROJECTION`. An explicit scalar `rt copy` snapshot remains a separate value and may be shared when Copy/Share traits independently pass. The existing `rt take` named-owner preflight now uses the same lexical resolver and continues rejecting untracked computed or partial-move expressions.

`tests/OwnershipResolvedPlaceOriginTest.java` adds **25 parse-first production checker JUnit cases**: five owned field Shares, five borrowed field rejections, five untracked/global field root rejections, five valid named scalar takes, and five invalid borrowed scalar takes. The source-owned exact-head `ownership-wave1-exact-head.yml` includes the test class in its pull-request path filter and Maven `-Dtest` execution selection. Tests are **authored only**, not proven to pass under real Java/JUnit/GraalVM.

This bounded source checker is a transition toward typed HIR/MIR; it does not yet create allocation-origin IDs, prove root disjointness, implement NLL, or authorize cross-task transport. Field/index moves remain fail-closed where projection-level invalidation is unavailable.

**Gate:** actual immutable-head runner assignment/stepful JUnit and GraalVM guest tests, semantic diagnostics rather than parser rejection, independent review, and no unapproved source transfer.
