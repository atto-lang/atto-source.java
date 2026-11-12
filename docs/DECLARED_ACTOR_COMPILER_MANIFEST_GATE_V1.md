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

## Physical same-namespace multi-file conformance

A single manifest package may declare either `namespace foo;` or
`use namespace foo;` independently at the top of multiple `.atto` and
`.ores` source files. The contextual `use` spelling is normalized by the
parser to the same `Ast.Program.namespace()` value, without introducing a
globally reserved keyword. The explicit namespace is
the compilation identity, not the directory path or a filename. These files
are discovered from the canonical manifest's source roots and their root-level
actor contracts, nominal declarations, external `mtd` declarations, and
sealed state attachments are linked using the same (package, namespace,
actor) identity.

The `SameNamespaceDeclaredActorPackageTest` suite constructs **seven real
physical source files** including four `counter.atto` files in separate folders:
one actor declaration, two actor contracts, three external methods and one
sealed-state attachment. It checks the cross-file descriptor and method
origins, plus a mixed `.atto`/`.ores` package; shuffled source enumeration;
multiple actors with identical selectors in one namespace; identical actor
names in separate namespaces; independent state; both supported `mtd`
receiver spellings; fingerprint invalidation after adding another source;
plus deterministic, immutable actor/method index iteration and mixed
namespace-header spellings; and strict rejection
of duplicate nominal actors/contracts, method slots,
state schemas, foreign/missing namespace, missing files, mismatched signature,
private contract implementation and source outside the manifest roots.
Negative parser tests also reject malformed/dotted `use namespace` headers.

For example, three distinct files may independently declare the same flat
namespace (the full tested package also contains separate reset-contract,
reset-method, read-method and sealed-state files):

```atto
// src/contracts/counter.atto
namespace foo;
define pub contract CounterApi for actor as
  mtd increment(int delta): int;
  mtd get_count(): int;
end

// src/declarations/counter.atto
namespace foo;
declare pub actor Counter implements CounterApi, ResetApi;

// src/methods/counter.atto
namespace foo;
pub mtd (Counter c) increment(int delta): int { return delta; }
```

The namespace is shared **only within the same manifest package**.
A matching directory, filename, namespace in another package, or untracked
source file never supplies implicit actor authority.

This is **static manifest/linker conformance only**. The package's
`build()` still fails with `DECLARED_ACTOR_CODEGEN_UNSUPPORTED`; no claim is
made that external method bodies run as actor code or that any backend has
passed end-to-end guest execution. The focused CI must execute the new suite
and produce exact-head, unskipped Surefire receipts.

## Opt-in scalar external mtd body analysis (NOT executable)

The new `PackageNamespaceBuild.auditScalarContractBodies()` operates on
**one exact manifest-derived source snapshot**. It first runs the same
`auditDeclaredActors()` contract/receiver/descriptor validation and then
verifies one deliberately restricted subset of each contract-required external
`mtd` body:

- exactly one terminal `return` statement,
- `void` with `return;`, or `int`/`bool`/`string` with a literal of
  the exact declared type or a same-typed declared scalar parameter,
- one method owner and source unit proven by the cross-file witness,
- no actor receiver escape or duplicate source slot.

Unsupported expressions fail closed with
`EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED`. Wrong return types fail with
`EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH`; a displaced source witness fails
with `EXTERNAL_MTD_BODY_ORIGIN_MISMATCH`.

This checker intentionally rejects arithmetic, calls, actor state access,
assignments, control flow, effects and suspension. It does **not** prove
their safety and does not implement general Atto expression typing. It is
not a dispatcher, an executable HIR/MIR plan, an actor capability or evidence
of mailbox ownership. `build()` retains
`DECLARED_ACTOR_CODEGEN_UNSUPPORTED` even after every scalar return passes.

Fifteen new physical multi-file tests exercise positive int/bool/string/void
returns, wrong literal/void types, missing names, illicit receiver or state
access, calls, unchecked arithmetic, nontrivial control flow, source file
revocation, source fingerprint changes, immutable reports, and overlapping
method selectors on two independent actors.

## Test inventory

PackageNamespaceBuildTest: 21 previously authored cases.
DeclaredActorContractWitnessTest: 27 authored cases.
DeclaredActorCompilerManifestGateTest: 17 authored cases.
SameNamespaceDeclaredActorPackageTest: 25 authored cases.
ExternalActorScalarBodyCheckTest: 15 authored cases.
ActorContractTargetCompilerIntegrationTest: 9 authored cases.
The existing namespace actor-state, receiver and parser tests are also
required in cross-file-actor-mtd-junit.yml with exact-head Surefire checks.
All are **authored, not executed** pending actual runner evidence. The four
new descriptor regression cases cover verified layout/signature, deep immutable
collections, deterministic fingerprint + mutation invalidation, and empty state.
