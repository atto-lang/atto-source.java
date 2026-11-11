# AttoLang actor/supervisor RW: authenticated CONTROL and guarded scalar V1

**Status:** Java runtime implementation draft, stacked on Java
[PR #2816](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2816),
which itself depends on [PR #2758](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2758)
and [PR #2296](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2296).
**Do not merge, publish, or claim production language safety on authored tests.**

## Threat: a host reference is not a CONTROL execution

Before this slice, `ActorStateSupervisorLoans.redeem(grant, supervisor)`
required only that the current Java thread was outside actor and Mailman
execution. A host holding a valid `ActorSupervisor` object could claim the
supervisor principal without entering its CONTROL dispatch. Likewise, a
host could release `Loan.close` outside a verified CONTROL turn.

This slice adds `ActorRuntime.dispatchSupervisorStateControl` as a
**package-private, runtime-trusted** wrapper around the configured VM CONTROL
dispatcher. It validates an exact live supervisor object and submits a
runnable to the configured dispatcher. The runnable must execute on a
**different physical carrier** from the submitter; inline dispatch is denied.
It installs a private thread-local `StateSupervisorControlExecution`
(runtime identity, exact supervisor instance, physical carrier) only for the
synchronous CONTROL callback, then removes it in `finally`.

`requireSupervisorStateHostContext(supervisor)` now checks the active
runtime, exact supervisor and carrier; arbitrary host threads, actor turns,
Mailman execution, nested CONTROL entry, foreign runtime and foreign
supervisor identities are rejected. The grant's principal is separately
matched by object identity. A Java stable ID is never an authority token.

The wrapper is **not a public guest API**, and installing a malicious
`ControlDispatcher` from privileged host code is outside the guest
security boundary. An actual AttoLang `define supervisor` CONTROL
execution must be lowered through the VM-owned scheduler to this boundary;
that integration is **not yet implemented**. Logical CONTROL migrations
must resume through a newly authenticated physical turn rather than
reusing the old thread-local.

## Guarded primitive state: no raw reference escapes

`ActorStateSupervisorLoans` now contains one actor-owned primitive
`long` proof slot (`protectedScalar`). It is deliberately **not** a
pointer to arbitrary actor class fields. The owner and supervisor access
**the same storage** behind one FIFO RW arbiter:

- `Loan.readScalar()`: requires authenticated designated supervisor
  CONTROL and a physically live, not revoked read/write loan.
- `Loan.writeScalar(value)`: additionally requires the exclusive
  `WRITE` grant. A read loan cannot mutate, even inside the right CONTROL.
- `Loan.close()`: requires designated CONTROL; its logical release flag
  is set atomically relative to guarded operations, then physical release
  happens **outside** the region lock so synchronous Future notifications
  cannot re-enter a held mutex.
- `withOwnerScalarRead(reader)` and
  `withOwnerScalarUpdate(updater)`: run inside the owner actor's
  synchronous lexical `withOwnerRead/Write` physical loan, using the
  same scalar. Only primitive snapshots/results cross the callback;
  no guest mutable alias or borrowable object escapes. If STOP seals
  the region between calculating and committing an owner update, the
  second admission check rejects the write.

The runtime only returns immutable primitive scalars, not a raw
`ActorOwnedGuestState`, `ByteBuffer`, or pointer. All guarded scalar
access and logical release serialize on the region monitor and recheck
the underlying loan epoch/owner.

**Important safety limit:** this is a proof slot, not yet lowered access to
arbitrary source-level actor fields. Host-only loan handles may survive one
CONTROL callback and be used in a later authenticated CONTROL callback.
That is acceptable only while no source guest state or raw reference
is exposed; a future production compiler must enforce lexical guest
borrow scopes and verified physical-unwind release.

## Source evidence vs executable evidence

Focused JUnit files:

1. `ActorStateRwLoansTest`: 26 authored arbiter tests,
   including bounded/FIFO grants, cancellation, STOP,
   notification drainage, epoch exhaustion and constructor visibility.
2. `ActorStateSupervisorLoansTest`: 21 authored integration tests
   against actual `ActorRuntime` and a test CONTROL executor. The
   eight added in this slice cover unauthenticated host redeem/read/write/
   close, denied write through read grant, same owner/supervisor
   primitive slot, wrong CONTROL principal, stale released loan,
   nested CONTROL denial, inline dispatch denial, and STOP denying
   data access while holding the physical loan until valid release.

**47 authored tests are not 47 passing tests.** The exact-head workflow
`.github/workflows/actor-state-rw-ownership.yml` requires both suites,
checks the PR source SHA/tree, checks Surefire XML (`>=26` and `>=21`)
with zero failures/errors/skips, and retains receipts. GitHub job
`steps=null` means **NOT_EXECUTED**, not compiler-backed proof.

## Remaining P0 gates

- Authenticate real compiler-lowered `define supervisor` CONTROL actors
  through the VM CONTROL scheduler, not a test dispatcher.
- Lower `state { ... }` fields and typed read/write projections through
  `OwnershipChecker` and this region. Reject aliases, forged grants,
  use-after-move, cross-actor loans and suspension under mutable loans.
- Tie guest loan close to proven physical lexical/async unwind. No
  foreign or prematurely closed loan may release an actively executing
  source borrow; resource pins must survive STOP and cancellation.
- Test negative source fixtures through actual Java/Graal compilation
  and Native Image AOT, then LLVM, .NET NativeAOT, Wasm and Forge.
- Execute all 47 focused JUnit cases with nonzero job steps, inspect
  any errors, obtain independent approval, and reconcile the stacked
  base PRs before merge.

## Source transfer hold

GitHub security incidents
[conformance #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22),
[conformance #77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77)
and [Java #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963)
still prohibit unapproved copying of **private Java production source**
into another organization for Actions minutes. A destination being private
is not independent source-export authorization; obtain a reviewed exact
repository ID, immutable head and tree before any such transfer.
