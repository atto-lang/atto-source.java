# Task / Send-Sync / scoped region integration checkpoint

**Status: DRAFT, compiler+host reference only — NOT guest-executable Task borrowing.**
Date: 2026-10-19 UTC. Base: Java Task/async/suspends integrated PR #1411,
not `main`. The base's existing `nb` TypeChecker **execution rejection remains unchanged**.

## What was integrated

1. Spawn-time structural `Send` capture checking from #1452 on the exact
   #1411 source `OwnershipChecker` base. Captures are checked **before**
   move-only ownership transfer. Nonlexical Task scopes now **fail closed**
   rather than skipping transitive capture analysis.
2. AST `SendSyncTraits` from the latest observed #1447 production source
   and independently resolved `ThreadSafetyTraits` from #1439.
   The lowered evaluator's previous `Channel<T>: Send` rule has been
   removed: the unverified handle is now denied in BOTH representations,
   even if its payload is `Send`. `Task`, actor capabilities, native
   handles, thread-affine resources, and unverified loan tokens deny.
3. `TaskAccessVerifier` from #1448 with generation/owner/domain checks,
   claim quotas, atomic admission, disjoint field/range conflicts and a
   cancellation that NEVER releases leases. A new
   `admitCoherentTyped` proof admits only when source AST **and**
   resolved internal types match a deliberately narrow provisional
   scalar/Array/List subset AND both agree on `Send` for WRITE or
   `Sync` for READ. Named Option/Result wrappers, borrowed references,
   foreign, recursive and unsupported nominal layouts **deny without a
   trusted resolved-identity/lifetime witness**.
4. Copied the existing parser+ownership and loan verifier JUnit tests;
   added `ScopedTaskTypeBridgeTest` with two disjoint writes,
   overlapping denial, parent-access blocking, cancellation without
   borrow release, source/lowered mismatch, Sync rejection for mutable
   Array, capability spoof denial, rollback and bounded nested types.
5. Added `OresTaskPartitionQuiescenceTest`, exercising **real host**
   `OresScheduler` + `OresTaskScope.startCompilerFrame` with two
   disjoint segments of one `int[]`, a cancel-before-exit fence and
   release hooks that fire only after physical task exit. This test
   uses **manually fixed** region ranges; it is NOT a compiler-issued
   privilege and does not prove guest-level parallelism.

## Trust and lifetime contract

- `Send` proves ownership transfer across logical Tasks/worker carriers,
  not fixed OS-thread affinity or actor transport authority.
- `Sync` proves shared immutable borrows can be read from other workers,
  not mutable aliasing or sharing private actor heap allocations.
- `TaskAccessVerifier` is package-private compiler-analysis state.
  `Root` IDs, `disjointLayoutProven` flags and typed claims must be
  derived from trusted source resolution/MIR allocation provenance,
  not guest-provided token names or source annotations.
- `admitCoherentTyped` validates type and access admission but **cannot**
  authenticate a runtime physical-completion report. Runtime code-image/
  region releases must be gated on scheduler `getDone` or its
  verified physical-exit callback, including cancellation and failure.
- Existing direct `nb` guest execution remains **rejected by TypeChecker**.
  No `nb scope` syntax, typed child capture ABI, TaskScope await lowering,
  zero-copy `InvertedArray` integration, or LLVM equivalent is asserted.
- The built-in `Array`, `List`, `Option` and `Result` Send
  admission remains a **compiler-only preflight**; review their actual
  guest allocation, copy/move and worker transfer ABI before lifting
  the TypeChecker execution gate.
- Host `OresTaskScope.close()` blocks and cannot run on a guest actor/
  Task carrier; guest scoped Task completion must be a nonparking
  suspension with verified physical child quiescence.

## Validation and remaining blockers

- Production files/test sources committed by GitHub connector. Copied
  `ThreadSafetyTraits`, `TaskAccessVerifier`, capture checks and
  test classes from the existing reviewed *draft* workstreams.
- New integration-specific JUnit test classes have **NOT** been executed
  against an exact-branch checkout or complete Maven/GraalVM compilation
  in this pass. A test file being committed is NOT a test pass.
- Prior source-org workflows report failures before runner test steps.
  Private Oreslang source must not be mirrored to an unapproved organization
  just to get Actions minutes; security ticket #962 remains open.
- Review the branch's actual exact-head workflow results separately.
  Do not merge until compiler/JUnit tests, cancellation stress and
  guest source positive/negative conformance actually execute.
- Next: typed Task capture/borrow IR and canonical compiler-minted
  `ExecutionPermit<Region,AccessSet>` from MIR; bind physical callback
  to release of opaque runtime region pins; then add a real two-Task
  `.ores` program and LLVM parity. ECS can consume the common footprint.

## October 19 follow-up: alias-unit, lifetime and identity audit

- **Fixed:** `admitCoherentTyped` previously matched a `Types.Named`
  `Option`/`Result` by spelling only. An imported nominal class with
  that spelling could impersonate a compiler builtin. Named wrappers now
  deny until a compiler-authenticated resolved symbol/intrinsic identity
  exists. This intentionally narrows accepted static claims.
- **Fixed:** a borrowed source type and matching lowered
  `Types.Borrow` formerly passed structural correspondence, despite no
  lifetime/outlives or real Task physical-completion witness. ALL borrowed
  source/resolved types now deny this provisional route. Scoped borrows
  require a separate compiler-minted temporal privilege.
- **Fixed:** byte-based ranges and element-index ranges formerly shared
  one numeric coordinate space. `RangeUnit.ELEMENTS` vs
  `RangeUnit.BYTES` is now explicit; mismatched units always alias
  conservatively. The existing 3-argument `Range` and `Place.range`
  APIs remain **element-index only**; byte callers use
  `Place.byteRange`. Trusted MIR must still prove units and lengths.
- **Tests added (committed, not JUnit-executed):** intrinsic
  name-spoof regression, source/resolved borrowed lifetime rejection,
  byte/element overlap denial, and a host `OresTaskScope` + static
  Task loan ledger test that waits for both physical `getDone`
  completions before discharging static tickets. This is not a
  compiler-emitted runtime ticket binding.
- **Local executable evidence:** the byte/element interval rule passed
  120,000 seed-stable randomized Java 21 model cases, with 120,000
  symmetry checks. This is a **standalone model**, not the actual
  `TaskAccessVerifier` compiled against the production AST.
- **Native sibling evidence:** exact LLVM #661 header Git blob
  `4410aa5f4f14679935ca5e5fd228fad7a6283e56` and test blob
  `a7f3086512201efe52787002bb57b38a54069ef8` ran locally:
  GCC and Clang strict C++20/ASan/UBSan **PASS**. These native
  tests cannot establish Java PR #1466 correctness or LLVM guest parity.
- **Private GitHub Actions transfer gate:** source main file
  `.github/private-ci-approved-repositories.txt` has **no approved
  destinations**. Issue #962 requires pinned protected source provenance,
  reviewed destination numeric ID, and security approvals. No private
  source bytes were sent to any alternate organization.
- **Important remaining authority gap:** `admitCoherentTyped` receives
  provisional source trait objects and a `Types.ListType`, not a
  nonforgeable compiler-resolved builtin/region identity. Source-level
  `Array/List` admission must remain disabled in executable guest
  lowering until the resolver supplies a trusted identity witness.
  Its tests demonstrate static footprint logic only.

## Related pull requests

Java [#1411](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1411),
[#1452](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1452),
[#1447](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1447),
[#1439](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1439),
[#1448](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1448),
[#1407](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1407),
LLVM [#661](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/661).
