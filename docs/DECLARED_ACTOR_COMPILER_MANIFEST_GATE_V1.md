# Declared actor compiler/manifest integration gate — Java v1

**Status: DRAFT, source-authored / NOT EXECUTED.** Child of PR #2896, stacked on #2890. No actor dispatch, allocation, or critical state loan is enabled here.

## Canonical Atto source

~~~atto
// contract.atto
namespace foo;
define pub contract ActorXYZ for actor as
  mtd bar(int x): int;
end

// actor.atto
namespace foo;
declare pub actor Foo implements ActorXYZ;

// state.atto
namespace foo;
define state on actor Foo as { let int x : 5; }

// method.atto
namespace foo;
pub mtd (Foo f) bar(int x): int { return x; }
~~~

The actor is nominally declared, not instantiated or implemented inline.
A complete inline actor still uses define actor Foo as ... end.
Bodyless define actor Foo; is rejected in favor of declare.

## Integrated staged Java components

- #2792: actor-targeted nominal contract with mtd requirements is a separate
  AST/ABI category from a structural interface or module contract.
  For inline actor implementations, TypeChecker requires direct public
  concrete instance methods with exact parameter types/mutability and return
  types. Classes, static/abstract/inherited slots, generic/structural and
  reserved lifecycle/CONTROL names are not interchangeable witnesses.
- #2813: manifest-aware PackageNamespaceBuild, ActorMethodIndex and 21
  authored package-regression tests ported to this child branch.
- #2896: ActorContractWitness validates each declared actor contract, external
  mtd selector, source unit, visibility, exact signature and unique sealed
  state schema. The same package actor/method key indexes inline methods
  and externally attached mtd, without runtime authority.
- ManifestActorContractBuild now delegates to PackageNamespaceBuild:
  just ONE canonical source scanner/provenance policy. Its auditDeclaredActors
  method derives a static witness from exactly one canonical .atto.pkg.toml
  source tree and emits exact parsed source SHA-256 and manifest SHA-256 hashes.
- The audited source snapshot now yields immutable
  `DeclaredActorDescriptor` metadata (package/namespace/nominal identity,
  actor origin, public/private visibility, actor kind, scalar initial-state
  layout, exact required method types/mutability and contract/mtd origins).
  Length-prefixed SHA-256 covers the canonical manifest hash, every source
  unit hash, layout and signatures. This fingerprint detects source changes;
  it is NOT a signed attestation or authority to dispatch/allocate actors.
  It excludes extra methods not required by an actor contract and MUST NOT
  be interpreted as a complete executable vtable.
- PackageNamespaceBuild.build() first validates the complete manifest-owned
  declared actor witness and the unified method index. Missing or spoofed
  obligations emit specific static errors; a correct witness still emits
  DECLARED_ACTOR_CODEGEN_UNSUPPORTED, because executable HIR/MIR external
  dispatch and critical state loans are not implemented.
- Raw IncrementalCompiler.compile(Map) refuses declaration-only actors with
  DECLARED_ACTOR_MANIFEST_GATE_REQUIRED before incremental ABI hashing and
  caching. TypeChecker independently retains its own fail-closed gate.
- IncrementalCompiler and ExportInterfaceCollector distinguish the
  actor-contract target in exported fingerprints without changing non-actor
  interface serialization.

## Limits and release blockers

- The scanner rejects symlinks, malformed UTF-8, source-root escapes, case
  collisions, overlapping roots and missing/changed manifests; enforces
  4096 source files, 16 MiB per file, 256 MiB aggregate, 131072 visited
  filesystem entries, and 64-level directory depth.
- A hostile writer might race ancestor path replacement. Before production,
  use an immutable exact-blob snapshot or directory-handle-anchored secure
  traversal. Static source hashes alone are not a race-free filesystem proof.
- The actor contract v1 requires scalar exact sync methods, no unresolved
  generics, borrows, effects, structural arguments or untracked imports.
- The witness does not type-check external mtd BODIES or prove actor-local
  runtime dispatch, state loans, one-shot init, cancellation, supervisor
  hierarchy, generation fencing, or zero-GC.
- Prior CI in the private source org often failed before running any steps.
  This branch must execute exact-head Java/Graal JDK25 test suites, negative
  fixtures, actual actor guest execution, AOT and four additional backend
  conformance stages before merge; no such passing result is claimed.
- Do not mirror private source to other orgs without separately approved
  security and exact-blob verification.

## Test inventory

PackageNamespaceBuildTest: 21 previously authored cases.
DeclaredActorContractWitnessTest: 27 authored cases.
DeclaredActorCompilerManifestGateTest: 17 authored cases.
ActorContractTargetCompilerIntegrationTest: 9 authored cases.
The existing namespace actor-state, receiver and parser tests are also
required in cross-file-actor-mtd-junit.yml with exact-head Surefire checks.
All are **authored, not executed** pending actual runner evidence. The four\nnew descriptor regression cases cover verified layout/signature, deep immutable\ncollections, deterministic fingerprint + mutation invalidation, and empty state.
