# Borrowed projection Share guard — Java/GraalVM V2

**Draft, source-authored; no JUnit/GraalVM guest execution has been verified.**

## Failure mode and production correction

A member/index projection can produce a scalar `ValueInfo(..., COPY, null)` even when the receiver was an incoming borrowed parameter. Running ordinary `rt share` trait validation on that value can lose the **root's borrow authority**.

The production `OwnershipChecker` now runs `rejectBorrowedShareProjection` before evaluating the direct Share argument. It walks only member/index receiver projections to their named root, retrieves that root's lexical `VarState`, and rejects incoming/shared/exclusive borrowed or actor-confined roots with `E_SHARE_BORROWED_PROJECTION`. It does not reclassify general arithmetic/call results as aliases or introduce runtime borrow tracking.

A producer can explicitly form a **detached proven Copy scalar** first, then share the owned snapshot. This guard is conservative: a future typed MIR may safely allow more scalar-projection cases after proving the value truly detaches and preserves the root's lifetime, but must not silently promote a borrowed owner.

## Regression inventory

`src/test/java/dev/oreslang/OwnershipBorrowedProjectionShareTest.java` adds 16 dynamic JUnit cases:
- **12 negative** direct borrowed-class-field Share attempts: three independent fields × direct/parenthesized and return/local-binding forms. Each must parse and then receive the production checker diagnostic `E_SHARE_BORROWED_PROJECTION`.
- **4 positive** explicit scalar `rt copy` snapshots from borrowed fields, shared through detached scalar values. These demand production checker acceptance, not direct loan upgrade.

The source-owned `ownership-wave1-exact-head.yml` includes the new class in BOTH pull-request path filters and the focused Maven `-Dtest` selection. No executed test result is claimed until the assigned runner reports nonzero steps, immutable checkout SHA/tree, JUnit reports, and guest evidence where required.

**Unproven:** complete nonlexical lifetimes, actor isolation, Send/Share traits for general types, compiler-backed cross-backend parity. Independent review required; no cross-org private-source transfer.
