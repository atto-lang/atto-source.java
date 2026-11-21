# Canonical TaskScope physical-quiescence observation audit — 2026-10-19

**Status: DRAFT / pending full exact-head JDK25 Maven/GraalVM execution.**

## Confirmed concurrency issue

`OresTaskScope.physicallyQuiescent()` previously iterated across the owned scope tree by independently acquiring each descendant's `gate`. But the root and descendants admit new Tasks and child scopes under a common `treeAdmissionGate`, acquired before the individual `gate`. An observer could read the root and early descendants as empty, admit a new child Task into an already-inspected subtree, and finally return `true` despite the newly admitted executable work. This is an internally inconsistent snapshot, even though the method does not confer code-unload authority.

## Fix

Observe the **entire subtree** while holding the same `treeAdmissionGate` used by Task admission and `childScope()`. The existing per-scope gate continues to protect each owned slot and child set. Lock order remains `treeAdmissionGate -> scope.gate`. The observation's linearization point occurs while the tree gate is held, so no new descendant or executable Task can be admitted mid-traversal.

**Safety boundary:** a true quiescence observation does NOT close admission and does NOT certify physical image unload, FFI/GPU/shared region retirements, frame/code identity, or state checkpoint publication. The authoritative supervisor must still freeze old work and prove all non-Task generation leases physically exited.

## Cross-branch parity

Committed identical production `OresTaskScope.java` blob on both Java hot-upgrade integration PR #1367 and canonical structured Task PR #1411. Both PRs now also contain `OresTaskScopeCoherentQuiescenceTest`, using a real `OresScheduler` compiler frame and host-only reflection to hold the common admission monitor while a reader races the snapshot. The test asserts the reader is BLOCKED until the gate releases, and then observes a pending physical child Task as nonquiescent.

## Executed evidence

A source-free Java 21 model compiled under `javac --release 21 -Xlint:all -Werror` and passed **24,000 assertions in 4,000 deterministic tree-admission/observer races**. The model checks observer serialization at the shared gate, correct detection of a live grandchild, and true quiescence only after physical exit.

This executable model is independent of production source. The committed Java JUnit case has **not** yet run on a funded exact-head JDK25/GraalVM runner.

## CI and source confidentiality

The source repository's `.github/private-ci-approved-repositories.txt` on `main` contains **zero approved destinations**. Security/CI issues `oreslang-platform-conformance` #22, #77 and #69 plus `oreslang-source.java` #962 remain blocking. A funded alternate GitHub organization is **not permission** to copy this private Git tree without separately reviewed source/destination authorization. No cross-organization source mirror was created.

Remaining production blockers include actual Maven/JDK25/GraalVM tests, one supervisor authority across physical route/code/checkpoint/scheduler publication, exact terminal mail receipts under faults, code-generation and external-resource retirement, and protected PRIVATE/UNTRUSTED / LLVM parity.
