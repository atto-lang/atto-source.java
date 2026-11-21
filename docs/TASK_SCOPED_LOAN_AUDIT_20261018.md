# Scoped Task loan audit — October 18, 2026

**Status:** compiler-analysis helper only. NOT guest Task syntax admission, Java/Graal runtime execution, or LLVM compiler parity. Fresh hardened successor of draft PR #1448, stacked on Send/Sync PR #1439 at commit ceb7382ff3e91ade8319dd763314c4f6028ecdaf; shared semantics in common-source PR #90.

## Confirmed findings and mitigations

| Severity | Finding | Fix |
| --- | --- | --- |
| P0 | Public host class could look like a guest-usable permission API | Enclosing Java class is now package-private; guest Task borrowing remains disabled |
| P0 | READ/READ from unrelated trust domains could bypass exclusion | One scope domain; reject mixed-domain claims even for readonly access and even after prior child completion |
| P0 | Structural reallocation checked numeric ID only | Scan all live claims using conservative mayAlias, including uncertain roots with different IDs |
| P1 | Race on mutable admission/cancellation/retirement HashMaps | Synchronized ledger admission, cancellation, parent checks, quiescence and barriers |
| P1 | Parent permission queries accepted stale generations | Require known exact allocation generation, scope domain, and non-closed scope |
| P1 | Compiler memory retention/unbounded Tasks | Cap 4096 active children, 1024 claims per child and 65536 tracked allocations per scope; clear metadata on final close |
| P1 | Concurrency test used busy-spin | Latch-based eight-writer admission race keeps one successful writer live until all attempts finish |

## Remaining P0 integration gates

- Root IDs, allocation/trust-domain strings, generations and inline-disjointness flags are currently trusted *compiler inputs*. They are NOT unforgeable guest/runtime tokens or verified storage layout certificates. Compiler/MIR must mint proofs using real place and alias analysis; FFI and unknown layouts remain conservative.
- quiescent(ticket) models logical retirement, not an authenticated physical completion receipt. A future runtime caller releasing early would be unsound. Bind each Task to authenticated continuation/scope identity and wait for carrier unwind, native FFI/device pins, destructor/defer cleanup and code-version lease retirement before final loan release.
- A nominal Send/Sync callback is not a transport or permission grant. Validate transitive captures, interior mutability, lifetime, suspension safety and actor domain authority separately.
- The per-scope capacity is intentionally conservative. Future lowering may shard footprint graphs or retire provenance only with proven non-ABA lifecycle fencing.
- Compile-time synchronized metadata does not implement the runtime memory model. Real Task handoff needs acquire/release publication, scheduling and true join barriers; no guest Task source feature is enabled by this PR.

## Evidence and acceptance

- 37 JUnit regression methods committed; neither Maven/JUnit nor actual GraalVM guest compiler tests were executed in this audit.
- At the inspected 2026-10-19 03:10 UTC head, Actions fast-ci run 39193409339, concurrency-contract run 39193409592 and ci run 39193409489 all show sampled jobs failing with **zero executed steps**. This is not Java test evidence.
- Required before merge: JDK Maven exact-head TaskAccessVerifierTest and full relevant checks; positive/negative source compiler diagnostics; TaskScope cancellation vs physical quiescence; native LLVM and TSan/ASan where applicable; correct dependency revision.
- No private-source cross-organization mirror was created or authorized. Keep this PR DRAFT.

## Follow-up: owner-identity collision (P0), path budgets (P1)

**P0 actor/tenant isolation:** The first verifier version modeled trust domains only by
category string (for example `ACTOR_PRIVATE`). That is not an actor identity:
two separately owned heaps can both be actor-private. A helper that admits
READ/READ from those heaps into one scope violates the isolation boundary even
when individual allocations are disjoint.

**Fix in this draft:** `Root` now carries a distinct `ownerId` alongside
the category, allocation ID, and generation. Every alias query, conflict check,
allocation-generation key, scope admission, parent access, and structural
barrier uses both category **and** owner identity. The four-argument
compatibility constructor is restricted to process-local provenance; non-process
roots require explicit owner identity. Tests exercise independent actor owners,
owner restarts/epochs, same-owner distinct allocations, untrusted-vs-actor and
process-vs-actor rejection. This prevents accidental category-based authority
confusion in the compiler helper; it is **not an unforgeable runtime token**.

**P1 bounded input:** Limit owner/domain labels and inline field names to 256
UTF-16 code units and projection path depth to 64 before copying. These are
compiler-analysis resource bounds, not runtime ECS limits.

**Residual P0:** Only the compiler's trusted place/alias/type engine may mint
`ownerId`, allocation IDs, generations, `uniqueAllocationProven`, and
layout-disjointness proofs. Actor lifecycle epochs and tenant boundaries must be
bound to those proofs, including when code hot reloads. Public string constructors
by themselves do not authenticate owners; source Task borrowing stays fail-closed.
The typed claim's referent type is also not yet checked against an authoritative
MIR place type. `quiescent(ticket)` is not authenticated to a physical Task join,
so the real runtime must not invoke it merely on cancellation or result settle.

**New evidence:** 33 committed JUnit methods (including six additional owner,
epoch, and resource-bound regressions), not run under Maven/GraalVM. The
source-org fast-ci run [39194705587](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39194705587)
and concurrency-contract run [39194705537](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39194705537)
reported failures with **zero recorded steps** and no runner assigned on
the inspected intermediate head. No compiler/JUnit pass is claimed, and
no private Git tree was exported to another organization.

### Final follow-up verification
- `admitTyped` now performs auto-trait proof and loan reservation under one
  synchronized compiler-analysis lock.
- A deterministic 2,000-pair range-alias matrix checks READ/WRITE footprint
  symmetry, half-open interval disjointness and conservative unverified parent
  projections. The test is checked in, **not executed**.
- Exact-head source files were re-read: 12/12 static checks passed (including
  delimiter balance, owner-scoped allocation keys, trait admission signature,
  cancellation preservation and test count). Static checks do not establish
  Java compilation, JUnit passes, or physical runtime safety.
- At source commit `e3c2a39e77fc5caf428380985ed2101914a7df7f`,
  [fast-ci #39195061794](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39195061794),
  [concurrency-contract #39195061990](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39195061990), and
  [ci #39195061823](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39195061823)
  all show **zero executed job steps** and no runner assignment.

## New hardening batch (October 19, 2026)

**Dependency drift fixed by clean stacking.** Original scoped loan PR #1448
was 44 commits behind its Send/Sync parent (#1439); newer changes included
fail-closed auto-trait derivation, ECS planner checks, and actor-transport
regressions. This new branch was created from the updated #1439 head at
`ceb7382ff3e91ade8319dd763314c4f6028ecdaf` and reapplies only the three
verifier/test/audit files, avoiding an unsafe pseudo-rebase or force push.
Recompare with parent if #1439 moves again.

**P1 algorithmic DoS prevented.** The original child/task counts allowed up
to 4096*1024 simultaneously live claims; admitting a new task could compare
against all of them (billions of checks). New limits: 8192 live claims and
2,000,000 maximum potential pairwise comparisons *per admission*. The check
is computed using 64-bit arithmetic before modifying the ledger. Retiring
a physically-quiescent child releases exactly its tracked claim capacity;
a cancellation request does not. New regressions check all-or-none rejection,
cancel-not-release, and reclamation on retirement.

**P0 type spoofing boundary tightened.** `admitTyped` now requires a
`PlaceTypeProof` callback from trusted resolved MIR place typing and checks
every `TypedClaim.type` against that actual referent before asking Send/Sync
and before reserving any borrows. Missing/mismatched types, including
the classic falsely annotating a native handle as `int`, reject atomically.
The original two-argument overload has been removed from this still-draft
helper. **This callback is not an authenticated proof token**: until actual
compiler place resolution supplies it, executable source Task borrowing
remains disabled.

**Evidence.** This branch has 37 committed JUnit tests, including
the new resource and resolved-place regressions. These are checked in
but not claimed executed. CI on current exact head must be inspected
separately. No repository source was mirrored to a separate organization.

## Compiler-to-verifier preflight slice (October 19, 2026)

- Removed the public, unchecked `admit(List<Claim>)` API from
  `TaskAccessVerifier`. Reservations now pass through `admitTyped` and
  its resolved referent + Send/Sync validation. `reserveVerified` is
  private. All 37 verifier JUnit fixtures were migrated to typed admission.
- Added `TaskBorrowCompilerBridge.admitResolvedScalars`: a *compiler-only*
  adapter converting a resolved `Ast.TypeRef` into the canonical
  `Types.Type`. It currently permits **whole, process-local, fully
  resolved `int`, `bool`, `float` only**. It refuses borrowed,
  unresolved, nominal, aggregate, field/range, actor or untrusted captures.
  It obtains types by looking up each place, never by taking a caller's
  type annotation. This is a safety preflight, **not** source Task emission.
- Added a cross-child referent-type consistency ledger. One place at one
  allocation generation cannot be admitted first as one type and later as
  another type, even with no remaining children. A checked structural
  barrier advances the generation and discards old place witnesses.
  Tracking is bounded to 65536 places; all changes commit only after
  a successful loan reservation.
- Added eight bridge JUnit regression methods for compatible/exclusive
  access, known-disjoint roots, unsupported types/layouts, actor isolation,
  inconsistent lookup and generation-fenced type transitions.
- **Current checked-in total: 45 JUnit methods** across the two classes.
  The exact-blob static check of four Java files passed 13/13 invariants,
  including syntax delimiter balance. **No source compilation or JUnit
  execution is claimed.** Exact-head
  [fast-ci 39206961093](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39206961093)
  failed with zero executed steps and no assigned runner.

### Stop conditions for guest compiler integration

- The current Send/Sync stack still has no `Ast.TaskExpr`/
  `Ast.TaskBlockStmt` nodes. They live in independent draft #1377.
  **Do not** copy the full #1377 source branch onto this stack and overwrite
  newer Send/Sync type-checker/ownership fixes. Reconcile dependency
  branches before adding a live `OwnershipChecker.checkTaskBody` call.
- The bridge's compiler lookup callback and root identities are not yet
  authenticated IR witness objects. A real source compiler must derive
  these directly from a typed and lifetime-checked MIR.
- Scalar-only preflight deliberately does **not** establish any SoA field
  disjointness or array range bounds. Future MIR field/range lowering
  must be accompanied by storage layout, alias and lifetime proofs.
- Scheduler TaskScope must authenticate *physical* completion (including
  defers, native pins, code-version leases, stop/restart) before any
  `quiescent` call is emitted. Cancellation alone never releases claims.
- Existing `TypeChecker` guest Task source fail-closed guard must stay on.

## Follow-up security audit and CI carrier investigation (October 19, 2026)

**P0: physical place identity is not its proof flags.** Previously
`knownPlaceTypes` keyed full `Place` records; `uniqueAllocationProven`
and field/range `disjointLayoutProven` booleans were part of equality.
The same allocation/generation/field could therefore be admitted under a
different referent type by changing only an evidence flag. This revision
introduces a canonical `PlaceIdentity` with allocation owner, numeric
allocation ID, generation and projection path, **excluding evidence flags**.
A conflicting type for the same physical place now fails even after all
earlier child tickets are quiescent. A checked generation barrier still
permits deliberate retyping with fresh evidence.

**P0: uniqueness proof escalation.** A root's `uniqueAllocationProven`
bit was not pinned between admissions. A later caller could claim the
same allocation ID was unique even if an earlier claim had uncertain
alias provenance. `knownUniqueness` now commits the first witnessed
allocation proof atomically, rejects any same-batch or cross-child
inconsistency, validates structural barriers against that witness,
and clears it on final scope closure.

**P1: validation callback reentrancy.** Java `synchronized` is reentrant.
The type/trait resolver could call `quiescent`, `requestCancel`,
`finishScope` or nested `admitTyped` while the outer admission held
the monitor. `admissionInProgress` now fails closed on nested admission
or those state mutations until validation and reservation complete;
a `finally` resets the gate on exceptions.

**P1: algorithmic preflight.** `admitTyped` now checks sealed status,
per-child and total live-claim quotas and the pairwise comparison-work
budget **before copying input or invoking compiler type/trait callbacks**.
The private reserve step rechecks quota and conflict conditions.

**Regression coverage.** The branch now contains 54 checked-in JUnit
methods across the verifier (46) and compiler bridge (8), including nine
new proof-identity, budget and reentrancy tests. Source-structural review
found 10/10 inspected conditions and balanced delimiters. **This is not
a source Java compilation or an executed JUnit pass.** A separate,
independently written isolated Java 21 proof-key model compiled and
completed 100,010 assertions over 50,000 randomized range pairs;
this tests the model only, not the real GraalVM repository.

**Cross-org GitHub Actions status.** Project security issue
[#962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
explicitly requires reviewed protected-source proof, immutable source
SHA/tree, authenticated source/destination numeric IDs, private
destination, and independent approval before mirroring. The checked-in
`.github/private-ci-approved-repositories.txt` on main contains **zero
approved destinations**. Consequently **no private source was mirrored
to another organization**. Read-only checks of three accessible private
repositories in unrelated organizations showed zero steps and no
assigned runners in sampled recent workflows; none confirms available
Actions minutes/budget. A different repo's `GITHUB_TOKEN` would not by
itself authorize reading this private source.

Keep this PR **DRAFT**. The required next CI proof is an authorized
runner executing actual exact-head Java 21+/Maven/JUnit tests without
exposing private source; another-org mirroring first requires #962's
protected policy and provenance gates. Source Task borrow execution
remains disabled pending MIR proof minting and authenticated TaskScope
physical quiescence.

## October 19 compiler-proof and field-alias hardening follow-up

1. `TaskAccessVerifier.mayAlias` now rejects claimed disjoint
   fields or ranges within **one allocation without a compiler-verified
   unique-allocation witness**, even if their layout flags are set.
   Underlying pointer/column aliasing otherwise cannot be excluded.
   Verified unique allocations still permit independently proven
   projections. Three adversarial JUnit methods cover this.
2. The scalar-only `TaskBorrowCompilerBridge` now requires **two
   independently supplied compiler lookups**: canonical allocation
   root (owner, numeric allocation ID, generation and uniqueness) and
   true referent type. It rejects a forged/stale root *before* invoking
   the type resolver; all-or-none semantics remain unchanged.
   Three more JUnit methods cover wrong owner IDs, false uniqueness,
   stale generations and later failed lookups.
3. The current proof lookup callback is still an **integration seam**:
   source Task capture compilation is NOT enabled and the callback
   supplied by unit tests (which echoes the claim) is NOT an authentic
   compiler-issued MIR allocation witness. Before any source Task
   admission, `OwnershipChecker` must provide independent,
   canonical, checked proof lookup.
4. Wrote `docs/TASK_CAPTURE_MIR_INTEGRATION_V1.md` with the precise
   reconciliation, ownership, MIR identity, alias/layout, TaskScope
   completion and conformance requirements for safely bringing
   parser PR #1377 into the newer Send/Sync stack.
5. **Current total: 60 committed JUnit methods** (49 scoped verifier
   + 11 compiler-bridge). Four changed Java files passed a source
   delimiter/balance review; all 20 bridge test call sites have four
   expected arguments. These are STATIC checks, not an executed
   full Java compiler or JUnit pass.
6. Exact-head fast-ci and concurrency-contract on the prior revision
   failed with zero job steps and no runner; see GitHub Actions for
   the latest revision. The protected private mirror policy remains
   empty and security issue #962 still blocks cross-organization copies.

**Do not enable the guest Task execution switch or claim safe parallel
SoA execution based on these intermediate proof interfaces.**
