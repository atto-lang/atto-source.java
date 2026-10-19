# Task Capture Admission: OwnershipChecker ↔ Typed MIR ↔ Runtime

**Status: integration contract only. Guest Task execution remains disabled.**
Dependency order: Java Send/Sync #1439 → scoped verifier #1473;
independent Task parser/AST #1377; runtime cancellation #1402 and
physical-completion TaskHandle #1407; shared source contract #90.

## Why this is not yet enabled

The Send/Sync + scoped verifier branch does not contain the `Ast.TaskExpr` /
`Ast.TaskBlockStmt` nodes or `OwnershipChecker.checkTaskBody` from draft
#1377. Replacing the whole OwnershipChecker/TypeChecker from #1377 would
discard newer Send/Sync and actor-transport work, so do **not** do so.
Only merge semantically reconciled AST, parser, capture checker, type checker
and lowering changes with verified source fixtures.

## Required compiler-issued evidence

At a Task spawn site, `OwnershipChecker` must gather the transitive free
variables of its body, including nested lambdas, control-flow branches,
deferred cleanup and captured method receivers. Use the ownership-root
identity (not the lexical binding name) and verify no moved, frozen,
borrow-escaped, guard-bearing, actor-confined, or partially initialized
owner may be captured.

Each captured access must have an immutable compiler-issued typed MIR
place and a TaskScope lifetime witness:

- **Physical identity:** trust/execution domain, owner + epoch, backing
  allocation ID, *generation*, and exact field/range projection path.
- **Allocation proof:** independently resolve canonical allocation identity
  and uniqueness from the checked borrow graph. Numeric IDs, flags, or
  caller-supplied `Root` objects are **not** themselves evidence.
- **Type proof:** independently resolve the true referent at the same
  normalized place. Nominal/generic types use resolved TypeChecker identity
  and transitive `Send`/`Sync` checks; unresolved data fails closed.
- **Layout proof:** field offsets/lengths or bounded half-open array spans,
  authenticated against the allocation's current AoS/SoA/AoSoA layout and
  version. No unproved view/FFI/pointer-alias or stale relocated column.
- **Lifetime:** checked scope dominance/outlives, no cross-domain actor
  private pointer, and a runtime physical join that controls loan release.

In current #1473, `TaskBorrowCompilerBridge.admitResolvedScalars` accepts
**only whole PROCESS-owned int/bool/float**. Both source allocation roots
and referent types must be independently resolved by trusted compiler
callbacks. Tests pass `place -> place.root()` strictly as a *test fixture*
and MUST NOT use that echo lookup as actual compiler issuance. For
arrays/records/SoA columns, fail closed until the typed MIR layout
witness exists.

## Wiring acceptance sequence

1. Reconcile #1377's `TaskExpr`/`TaskBlockStmt` with current AST and
   `OwnershipChecker` without replacing the newer type checker.
   Keep `TypeChecker`'s explicit source-execution rejection.
2. In `OwnershipChecker.checkTaskBody`, perform capture/transitive
   free-variable analysis and move/borrow checks. For owned copyable scalar
   captures, copy them into a new independent frame; do not synthesize
   shared-memory loans for by-value copies.
3. For permitted *scoped borrowed* captures, have the checked MIR
   provenance, referent and layout analyses mint the normalized place,
   then invoke `TaskAccessVerifier.admitTyped` through a checked bridge.
   Type aliases and runtime writer permissions cannot change identity
   or escape the scope.
4. Couple each verifier Ticket to one owned TaskHandle and physical
   completion receipt. Cancellation only signals intent; do not call
   `quiescent(ticket)` until defers, host/native pins, continuations and
   code-generation leases are fully retired.
5. Enable source execution behind a feature gate **only after**
   exact-commit Java 21+/Maven/JUnit, Graal, and shared LLVM parity tests
   pass. A zero-step Actions failure is not a JUnit test result.

## Minimum positive and negative conformance matrix

| Source shape | Before runtime integration | Final intended behavior |
| --- | --- | --- |
| By-value `int`/bool/float Task capture | Reject guest execution | Copy to frame; schedule and await |
| Two Task writers to proven separate SoA columns | Reject guest execution | Accept with layout/root/lifetime witnesses |
| Two overlapping writers | Reject | Reject |
| Unproven backing allocation with two distinct field names | Reject | Reject |
| Stale actor owner epoch or allocation generation | Reject | Reject |
| Reinterpreting same place as a new type by toggling flags | Reject | Reject |
| Borrow surviving parent scope exit | Reject | Reject |
| Cancelled child with active carrier or FFI pin | Retain loan | Retain loan until physical join |
| Foreign or duplicate physical-completion receipt | Reject | Reject |
| Untrusted actor-local pointer captured in PROCESS Task | Reject | Reject |
| Layout migration during any live overlapping Task loan | Reject | Reject |

**CI security:** main policy `.github/private-ci-approved-repositories.txt`
has no reviewed destinations; issue #962 blocks cross-organization
Git-tree mirroring until approved immutable source/destination identity
and provenance enforcement exists. No source should be exported merely
to obtain another organization's Actions minutes.
