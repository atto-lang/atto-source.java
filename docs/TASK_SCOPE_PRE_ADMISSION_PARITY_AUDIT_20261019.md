# Canonical TaskScope pre-admission ownership parity audit — 2026-10-19

**Status: DRAFT.** Java SHARED actor/Task host/runtime hardening only;
not a proof of guest task lowering, PRIVATE/UNTRUSTED placement, or LLVM AOT.

## Root cause

On the canonical Task/suspends branch (#1411), both TaskScope paths originally
registered a Task in `Set<OresTask<?>>` **after** the scheduler or
AsyncRuntime had admitted executable work. Creating the OresTask handle, growing
the HashSet, or registering its physical-exit observer could then fail after
execution started, leaving an unowned carrier and (for source actor Tasks) a
possibly orphaned code-generation pin.

The hot-upgrade integration branch (#1367) already introduced
`OwnershipSlot`, preallocated OresTask shell, and an independent exit fence
before admission. Those stronger contracts are now also applied to #1411,
without changing the source-language Task syntax.

## Hardening across both branches

- Canonical OresTask uses an admission-time shell whose completion reference
  is attached by allocation-free assignment, not by constructing a new Task
  object after scheduling.
- AsyncRuntime's Task path creates TaskExit/physical quiescence and its OresTask
  shell before executor admission; its internal submission path returns the
  existing completion Future and attaches it to the shell.
- OresTaskScope's `OwnershipSlot` is inserted before either
  AsyncRuntime.submitTask or OresScheduler.start executes. Task lifetime is
  retained through physical completion, not merely Future cancellation.
- An allocation failure before admission removes the reserved slot and
  releases any acquired generation lease; a failure **after** admission
  keeps ownership, requests cancellation, and conservatively joins physical
  exit at shutdown.
- Secondary owner-Set removal failures are now added to the original
  rejection as suppressed failures. They may leave scope bookkeeping
  conservatively retained, but must never prevent release of a pin whose
  scheduler submission was provably rejected before admission.
- All existing descendant cancellation-tree snapshot/preflight and
  seal-before-revoke behavior is preserved.

## Production JUnit regressions committed

`OresTaskScopePreAdmissionOwnershipTest`, on both branches, checks:

1. Fault-injected HashSet owner reservation OOME before an AsyncRuntime Task
   launches, then a recovered successful admission.
2. Compiler-owned scheduler frame slot-allocation OOME, requiring the prepared
   external image lease to release exactly once without executing the frame.
3. Runtime-closed admission rejection cannot retain an uncommitted Task slot.

`OresTaskScopeFailedSlotCleanupTest`, on both branches, injects an error in
the owner's Set.remove during scheduler rejection, requiring lease release
despite the cleanup fault and explicit suppressed error provenance.

Canonical `OresTaskScopeCancellationOrderingTest` has been adapted to use
the actual OwnershipSlot representation for synthetic fault injection.

**These JUnit tests are committed but NOT EXECUTED** under Maven/JDK25/GraalVM:
the source-owned GitHub Actions jobs fail before running any test step.

## Independent offline stress evidence

A separately authored, source-free Java 21 owner-admission protocol oracle
compiled with `javac --release 21 -Xlint:all -Werror` and passed three
consecutive executions:

- 40,000 deterministic protocol scenarios covering pre-admission reservation
  failure, secondary removal failure, result-vs-physical-exit separation, and
  ownership on successful admission.
- 2,000 contested rounds with four racing producers (8,000 attempts) checking
  that reserved owner slots are present at the admission linearization.
- Recent outputs: 174,206 / 174,166 / 174,196 assertions, variation depending
  on how many racers acquire the admission gate before cancellation.

This is an **independent protocol model, NOT exact production source**, and
cannot substitute for the unexecuted source tests.

## CI and confidentiality

The cross-org mirroring route remains **NOT AUTHORIZED** while the
oreslang-platform-conformance private-source security incidents #22 and #77
and protected-source runner bootstrap #69 are open. A funded GitHub Actions
organization is not a separate approval to copy private repository blobs,
refs, or artifacts. No private source was copied to an alternate org.

Required release gates: an authorized exact-head JDK25/Maven/GraalVM CI run,
TaskScope/AsyncRuntime/scheduler failure and race matrix, atomic logical
actor route/state/code publication, physical continuation/FFI/lease retirement,
and protected PRIVATE/UNTRUSTED plus native LLVM parity.

Leave PR #1367 and PR #1411 **Draft** until these tests genuinely execute and
pass on the exact source tree.
