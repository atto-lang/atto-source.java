# Actor-state field borrow admission and physical loan parity (Java) — V1

**Status:** Java source-level fail-closed hardening, draft stacked on
[PR #2841](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2841) and
[PR #2831](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2831).
No backend/runtime claim is made without executed CI.

## Security gap and narrow change

The canonical `.atto` `state { ... }` syntax is already represented by
the existing `Ast.ClassDecl.fields()` owned by the actor incarnation.
The Java runtime added supervisor-scoped `withSupervisorRead/Write` on
`ActorStateSupervisorLoans`, but the compiler **does not yet lower
individual source fields, indexed array slots, nested ADT/struct members,
or borrowed aliases to a region-backed physical loan**.

Before this patch, `OwnershipChecker` could permit an immutable
`rt borrow self.field` or `rt borrow self.items[0]` and trust only
the lexical, compiler-side borrow ledger. Without a corresponding
runtime field-path allocation domain/loan, this would be premature
proof that such a borrow is safe to share or continue.

This patch adds `rejectUnloweredActorFieldBorrow` to the four
relevant compiler entry points:

1. Persistent lexical `rt borrow` binding (which bypasses
   `checkExpr`).
2. Persistent legacy unary `&`/`&mut` binding.
3. Expression-level `rt borrow`.
4. Expression-level legacy unary borrowing.

It tracks the complete root of nested `Ast.MemberExpr` and
`Ast.IndexExpr` projections via `isRootedAtActorSelf` (including
tracked actor-self borrow aliases). If the projection is rooted at the
current actor instance, the compiler **rejects it before backend
lowering** with an explicit physical field-loan diagnostic. A normal
`self.count` Copy read remains legal as a value snapshot rather than
a borrowed state reference. Ordinary nonactor class projection loans
continue under their existing checks.

This patch **does not** grant new supervisor access, invent a raw
pointer, force a lock on every actor scalar read, or claim to enforce
runtime scopes of arbitrary guest fields. It is deliberate rejection
of an operation for which no end-to-end proof exists.

## Required next implementation (not present here)

After compiling a typed actor field-path identity that includes
(actor ID, incarnation generation, region/allocation domain,
nested projection path, RW mode), admission should produce one
runtime-scoped field projection that:

- Is limited to the owning physical actor turn or one authenticated
  supervisor CONTROL turn, with exact principal and generation.
- Acquires many READ loans or one exclusive WRITE loan fairly, never
  allowing a borrowed reference to outlive its physical execution.
- Does not expose a raw cross-arena pointer, address, mutable Java
  object, reusable unscoped Loan/Future or dynamically synthesized
  type/proxy bypass.
- Invalidates outstanding views synchronously at STOP and only
  reclaims memory after all physical loans and callbacks retire.
- Rejects unsupported suspension, closure capture, return escapes,
  alias transfers and mutable projections at compile time.

Until this genuine lowering is compiled and tested, actor-rooted field
borrows must remain errors.

## Test and evidence contract

Eight authored `ActorStateFieldBorrowBoundaryTest` JUnit cases:

- Stored field borrow via `rt borrow` is rejected by the **real**
  `OwnershipChecker` and full `TypeChecker` entrypoint.
- Temporary expression `rt borrow` is rejected.
- Nested indexed actor-state projection borrowing is rejected.
- Legacy unary `&self.field` is rejected, preventing a compatibility
  bypass of the canonical syntax.
- Nested `rt take self.account.balance` is rejected.
- Ordinary scalar `self.count` snapshot passes normal compiler
  checking.
- An actual Graal guest executes the safe actor scalar snapshot and
  produces `5`.
- Existing nonactor indexed projected borrows still pass the full type
  and ownership compiler, preventing accidental global over-restriction.

Four new negative + one positive canonical `.atto` fixtures accompany
these tests. The new focused CI gate is **27 RW + 32 CONTROL + 12
state-group + 7 return-fence + 8 field-borrow = 86 authored JUnit cases**, with mandatory
non-skipped Surefire XML receipts. Authored is **NOT_EXECUTED** until a
real runner executes JUnit and compiler-backed guest tests.

A useful mutation test after CI recovery is to remove the
`rejectUnloweredActorFieldBorrow` call from each of its four entry
points separately; at least one corresponding negative test must fail
for each mutation, and the normal passing snapshot must remain valid.

## CI and private-source export hold

[Java #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
still requires independent source/export authorization and protected
policy proof. The current main allowlist includes a historical
review-dependent `beamscale/bmscl-compiler` entry, **not approval of this
source head/tree**. [Conformance #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
and [#77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77)
remain open security incidents.

Source-free [sonus-auris-test carrier PR #31](https://github.com/sonus-auris-test/clients-api-contract/pull/31)
executed [workflow #42953407440](https://github.com/sonus-auris-test/clients-api-contract/actions/runs/42953407440)
successfully on an assigned runner (six steps). The carrier included
**no private Java source** and does not certify the compiler suite.
Do not copy a new source tree into any alternative organization
unless independent resumption, numeric destination ID and exact
source-commit/tree approvals are recorded and verified first.

Keep the PR draft until compiler source executes on an authorized
runner, Java/Graal/AOT receipts and independent approvals are present.

## Integration with returned-borrow fence

This stacked branch preserves Java #2841's actor-self *returned-borrow* checks and its intentional negative mutation. Early rejection of field projections makes their return-specific diagnostic unnecessary for those fields; whole-`self` borrowed returns remain governed by #2841. The mutation test is therefore targeted at `wholeActorSelfCannotEscapeViaBorrowedReturn`, whose source does not have an intermediate projected borrow, ensuring guard removal still produces an assertion failure.
