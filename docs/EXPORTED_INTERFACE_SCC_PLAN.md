# Oreslang: checked public type origins and SCC interface plans

Status: **experimental, draft-only infrastructure**. This design is stacked
on the v2 interface groundwork in Java PR #941 (itself stacked on #934).
It does not change the production compiler cache, importer, LLVM backend,
or code-generation runtime.

## Public type IDs from the checker

`TypeChecker.checkTypesAndSnapshotPublicNominals` executes its normal
annotation, cleanup, import validation, collection, semantic validation,
and ownership-checking sequence. It then returns a detached view of **the
nominal symbols actually installed in that checker instance**. Unqualified
names ambiguous across class/interface families are absent; module-qualified
names remain available. Type aliases are deliberately excluded: unchecked
alias substitution is not safe, and the separate alias-normalization pass
is not implemented in this PR.

`CheckedPublicTypeIdentity.publicFunctionReturnType` is an opt-in,
single-source-unit bridge. It:
1. Executes the compiler's checked public-nominal snapshot;
2. Collects a validated local v1 exported interface (which rejects private
   native layouts and unresolved exported signatures);
3. Produces provider-qualified nominal identities using the unit's
   exported-interface digest;
4. Locates a public function by exact module, name and arity, and fingerprints
   its return type with its checked generic binder slots.

This intentionally refuses unresolved imports. Provider-provenance checks,
full alias expansion, generalized function signatures, actor/trait contracts,
and external nominal resolution are still future work. The caller-supplied
compiler/header identity is not an authentication credential.

## Dependency-first strongly connected components

`InterfaceSccPlanner.compute` consumes **trusted** canonical local
interfaces, resolved local dependency edges, and verified external provider
pins. It uses the existing `ImportGraph.initializationGroups` Tarjan
decomposition rather than introducing a second, divergent cycle algorithm.
All members of a cyclic group share a group contract hash. A change inside
one member invalidates the entire SCC and downstream dependents; modules
outside the affected closure retain their fingerprints.

The group descriptor includes every member's local exported-interface hash,
exact local edges, provider pins, and already completed prerequisite SCC
digests. All serialization is deterministic with UTF-8 length framing,
bounded unit/edge counts, and strict rejection of inconsistent compiler,
target-ABI or feature-policy envelopes. Mutable caller collections are not
exposed from the resulting plan.

`fromValidatedImportGraph` resolves imports using Oreslang's real import
rules and optional exact import overrides. It validates named exported
symbols and refuses relative missing targets or unpinned external imports.
It explicitly fingerprints each **selector -> effective resolved target**
binding, not merely the set of dependencies: swapping two import targets
while preserving the same dependency set still invalidates the plan.
External imported symbol selectors are also part of the fingerprint, even
when the external provider hash is unchanged.

## Formal correctness obligations

The following properties are testable and must remain invariants:
- Source/input map permutations preserve SCC membership and all digests.
- An implementation-only change that leaves the trusted public interface
  unchanged cannot dirty importers through this graph.
- A public ABI change invalidates its SCC and all reverse importers.
- Any changed import selector, effective target, or provider digest
  invalidates the corresponding importer and its SCC.
- Missing external pins, ambiguous/incorrect import names, self-imports,
  unknown local units and mixed target environments fail closed.
- SCC group hashes never depend on unfinished hashes of their own members.

This is **not** a complete linker proof. Compiler validation, authorization
of provider fingerprints, public/private native layout proofs, external
ABI imports, and production cache transactions remain independent gates.
Do not assume a content hash authenticates the compiler or source origin.

## Testing and integration status

The private CI runner compiles the exact planner, actual Oreslang
`ImportGraph.java`, `ImportRules.java` and `Ast.java` using JDK 21.
Targeted tests cover SCC order, cyclic invalidation, independent-unit
stability, exact internal/import selector retargeting, pinned external
symbols and corrupt/unknown dependency rejection.

`TypeChecker.checkTypesAndSnapshotPublicNominals` and
`CheckedPublicTypeIdentity` have repository JUnit regression tests, but
their full compiler integration is **not yet verified** by the isolated
CI runner, which intentionally avoids copying the entire private
GraalVM/TypeChecker build into another repository.

Before merging: run the complete Java Maven compiler+ownership suite,
validate static-analysis equivalence across incremental and clean builds,
test multi-file nominal alias/overload effects, then adapt the LLVM cache
and conformance runner.
