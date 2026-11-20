# Borrow checker audit: projected receivers and cross-backend contracts

The checker must hold a receiver loan while evaluating every later argument,
not merely check receiver mutability before the argument list.

The audit reproduced three unsound admissions through both the integrated
TypeChecker and standalone OwnershipChecker: a projected shared receiver allowed
an argument to mutably borrow or move its containing owner; a projected exclusive
receiver allowed an overlapping mutable borrow. For example:

```ores
return holder.item.read(mutate(rt mut borrow holder));
```

The receiver now acquires a shared/exclusive root loan before checking arguments
and releases it in `finally`. This uses existing stable field/index place
resolution. It conservatively reserves the whole containing owner, including
siblings; it does not claim field-disjoint mutable loans. Unstable computed
indexes fail closed. The existing named-receiver two-phase behavior is unchanged.

`ProjectedMethodReceiverLoanTest` covers move/mutation conflicts, incoming
exclusive aliases, indexed owners, suspension during argument evaluation,
nested read calls, separate owners and release after successful mutable calls.
The negative controls failed before the patch because no exception was raised.
They check both integrated and standalone entrypoints.

The identical `borrow-contract.tsv` is checked into this repository and
`oreslang-source.llvm/tests/fixtures`. Its 18 cases cover shared/exclusive argument
combinations, nested evaluation, distinct owners, loan release and reborrow
privileges. Java executes admitted programs through the language runtime;
LLVM compiles and executes native binaries. Rejected programs must produce
borrow diagnostics, not merely fail parsing. Keep the two copies byte-identical.

Canonical `rt borrow` now also unwraps an existing borrow type when reborrowing,
matching `rt mut borrow` and retaining the same root provenance. Previously the
shared spelling produced `Borrow<Borrow<T>>` and incorrectly rejected valid
forwarding calls. Retained reborrow tests cover execution and move/escape rejection.

This is bounded regression evidence, not a proof of complete memory safety.
Existing tests also exercise branch joins, loop moves, retained projections,
closure escape, affine extraction, select/await lifetimes and runtime ownership
models. Unsupported lifetime relationships must remain rejected. In particular,
async instance methods remain rejected pending task-safe receiver ownership;
ordinary loans cannot span suspension. Java's GC does not replace these checks.

Validation on macOS: 154 Java tests across 23 ownership/borrow/lifetime/escape
suites passed on Temurin 25; 421 native CTests passed, including native execution
and sanitizer cases. The common 18-case corpus SHA-256 is
`cc42b9bd3c4ff27033eecb526aae25e29bf8d176915ff0fd9e6abe33167f9cbe`.
This audit's new changes have local evidence; earlier fallback CI results apply
only to their recorded source commits, not automatically to this head.

## Returned-loan receiver follow-up

A second negative control reproduced an unsound admission through a returned
borrow: `identity(rt borrow holder.item).read(mutate(rt mut borrow holder))`.
Call-return loans have no call-site root/lifetime summary today. Their use as
implicit receivers previously bypassed the explicit-borrow argument gate.
Method, field and index receivers now reject a borrow with unknown provenance;
this also prevents recovering ownership of an affine field through such a loan.
Even simple receiver arguments stay rejected until return-lifetime summaries
prove their root. Named incoming/retained borrows and owned temporary receivers
remain admitted. The follow-up has 159 passing local tests, including seventeen
projected/returned receiver tests. LLVM already rejects source borrow returns.
The 154-test Linux run on `f49073f19` does not cover this later follow-up.
