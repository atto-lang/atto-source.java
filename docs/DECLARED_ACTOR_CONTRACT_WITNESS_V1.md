# Declared actor contract witness v1 (static, no execution)

Status: Java source candidate only, stacked on PR #2890. It is not runtime
actor code generation, permission to dispatch messages, or five-backend parity.

## Example four-file package

~~~atto
// contract.atto
namespace foo;
define pub interface ActorXYZ as
  fnc bar(int x): int;
end

// actor.atto
namespace foo;
declare pub actor Foo implements ActorXYZ;

// methods.atto
namespace foo;
pub mtd (Foo f) bar(int x): int {
  return x;
}

// state.atto
namespace foo;
define state on actor Foo as {
  let int x : 5;
  val bool ready : true;
}
~~~

The declaration-only actor is a distinct AST node, not an executable class.
A complete inline definition still uses define actor Foo as ... end.
A bodyless define actor Foo; is invalid.

## Static proof actually implemented

- ActorReceiverBinder binds exact (package,namespace,actor,method,arity)
  identities and source-file provenance. Duplicated names or slots fail.
- ActorContractWitness.linkSourceSet checks every contract claimed by a
  declaration-only actor. ActorXYZ must be a local, ordinary non-module
  interface. It is distinct from a module-wide define contract.
- The first-stage type contract is deliberately simple: interfaces without
  generic parameters, inheritance, fields, structural parameters or checked
  effects; exact int/bool/string argument and int/bool/string/void return
  types. Method signatures must agree exactly on name/arity, parameter types,
  mutable qualifiers and return type; required implementation visibility
  must be public. The verifier rejects missing/private/mismatched/duplicate
  methods and a public actor whose interface is private.
- A declared actor must have actual constituents: external method or sealed
  state. State remains compiler-only metadata; no namespace-global state,
  borrowed state reference, or automatically runnable actor is produced.
- ActorContractWitness.Result includes immutable actor, interface, state and
  method provenance. Caller-supplied linkSourceSet data is NOT a trusted
  package attestation and cannot promote executable actor authority.
- ManifestActorContractBuild requires an explicit canonical .atto.pkg.toml,
  project.name/version and manifest-owned source.roots. Sources are closed
  and rescanned in sorted order (up to 4096 files, 131072 visited filesystem entries, depth 64, 16 MiB/file, 256 MiB
  aggregate), valid UTF-8, with no symlinks, escapes, overlapping roots or
  casefold collisions. It returns exact parsed file SHA-256 digests and
  checks manifest SHA-256 before and after scanning. Ambient import paths
  and unproven external imports are excluded by the first-stage contract.

SECURITY LIMIT: concurrent hostile filesystem mutation may still race path
resolution between operations. Production certification requires an immutable
source snapshot or directory-handle-anchored no-follow traversal. This
scanner is a static development API, not a secure process sandbox.

## Execution blockers / exact-head gates

1. Reconcile preexisting (unmerged) #2792 actor-specific nominal contracts
   and #2813 manifest namespace builder. This stage uses an ordinary
   nominal interface as the simplest actor method contract.
2. Integrate source-set witnesses with compiler-owned type checking and HIR/
   MIR. Today TypeChecker intentionally rejects declared actor execution.
   Static signature equality is not executable semantic verification.
3. Prove actor-owned heap layout, one-shot private init, sealed critical-read
   and critical-write lexical permissions, same-turn dispatch and mailbox
   authority, stale-incarnation rejection, teardown/cancellation and
   supervisor CONTROL routing.
4. Execute Java/GraalVM guest/compiler tests and Native Image, followed by
   LLVM, .NET Native AOT, Wasm and Forge parity. No cross-backend execution
   is claimed in this draft.
5. Run the 26 authored DeclaredActorContractWitnessTest JUnit methods and
   existing actor suites on an admitted JDK25 runner. Require actual test
   reports, no skipping and exact source/fixture blob receipt. Prior Actions
   runs in source org failed before job steps; CI failure badges are not
   compiler test results. Do not export private source without independently
   authorized security controls.

Fixture files: contracts/declared-actor-witness-v1/.

Additional defensive tests reject a manifest whose files contain no
`declare actor` witness and invalid UTF-8 source bytes. The manifest builder
fails rather than issuing an empty success receipt or silently replacing
invalid bytes.
