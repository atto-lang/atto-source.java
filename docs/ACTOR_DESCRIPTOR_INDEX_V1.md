# Immutable declared-actor descriptor — Java static milestone

Status: **DRAFT / NOT EXECUTED**. Stacked on Java PR #2901; no
runtime executable actor, Native Image or five-backend parity is claimed.

The manifest package builder now derives exactly one immutable descriptor
per declaration-only actor, after its package-level nominal contract witness
and actor-method index have passed. API:

```java
var config = OresProjectConfig.load(Path.of(".atto.pkg.toml"), Map.of());
var build = new PackageNamespaceBuild(config);
var audit = build.auditDeclaredActors();
var descriptor = audit.descriptors().actor(
    new ActorMethodIndex.ActorKey("demo@0.1.0", "foo", "Counter"));
```

The compatibility wrapper `ManifestActorContractBuild.build()` also exposes
`receipt.descriptors()`. The public descriptor value is **not constructible**
by ordinary callers (private constructor). No public method on the index
instantiates or promotes an actor.

## Canonical input and descriptor provenance

```atto
// contract.atto
namespace foo;
define pub contract CounterApi for actor as
  mtd increment(int x): int;
end

// actor.atto
namespace foo;
declare pub actor Counter implements CounterApi;

// state.atto
namespace foo;
define state on actor Counter as {
  let int count : 5;
  val bool ready : true;
}

// method.atto
namespace foo;
pub mtd (Counter c) increment(int x): int { return x; }
```

The descriptor contains:
- Package/namespace/actor nominal key, visibility, actor kind, declaring
  source unit and manifest SHA-256
- Ordered actor contract names and their actual source unit identities
- Method selectors, source units, public/private visibility, arity,
  parameter types/mutability, and return types
- **State layout metadata only:** ordinal, field name, let/val kind,
  scalar int/bool type and typed literal default, plus state source unit
- Two different length-framed SHA-256 digests:
  `abiSha256` of the declarative method/contract/state schema, and
  `sourceSha256` of manifest identity plus exact relevant constituent
  source SHA-256 digests, without treating method body edits as ABI changes.

The descriptor builder verifies that the contract witness, source-set
membership, nominal actor index and actual external method-source origins
agree exactly. Missing source hashes, malformed digest syntax, foreign
package identity or unproven method slots fail closed. The source hashes
are trusted **only** when provided by the canonical package scanner; calling
the lower-level static matcher with a caller-selected map is not a trusted
manifest attestation.

## Explicitly NOT implemented

A descriptor is a compile-time receipt, **never** runtime authority.
`PackageNamespaceBuild.build()` still checks the manifest-linked witness
and then rejects executable codegen with
`DECLARED_ACTOR_CODEGEN_UNSUPPORTED`. Direct
`IncrementalCompiler.compile(Map)` continues to reject untrusted actor
declarations with `DECLARED_ACTOR_MANIFEST_GATE_REQUIRED`.

This change does **not** typecheck external mtd bodies, emit HIR/MIR
actor methods, allocate state, implement one-shot init, permit public
ActorRef dispatch, or implement critical-read/critical-write lexical loans.
All of those require independent ownership/effect analysis and exact-head
executed guest tests.

Filesystem limitation: the scanner enforces manifest/path/source budgets,
no-follow symlink checks and hashes, but a hostile concurrent writer could
still race ancestor directory replacement. Use a read-only content-addressed
snapshot or secure directory-handle traversal before production admission.

## Test inventory and evidence requirements

`ActorDescriptorIndexTest`: 11 authored JUnit cases for correct provenance,
ABI/source separation, state schema change/deletion, immutable maps,
private helper slots, source removal, signature mismatch and codegen denial.

`ActorDescriptorProvenanceTest`: 4 additional authored JUnit cases for
missing/invalid hashes, package identity spoofing and ordinary-interface
contract impersonation.

The parent actor tests and nine existing focused suites remain required.
`.github/workflows/cross-file-actor-mtd-junit.yml` now requires both
descriptor test suites and exact-head Surefire reports. These tests are
**authored and committed, NOT verified as executed** until a real JDK25
runner starts and the Maven test reports are observed. GitHub jobs on
previous exact heads were failure/zero-step; that is runner-admission
failure rather than a compiler test result.

Do not merge to main, enable runtime dispatch, or mirror private source to
another GitHub org without the required code review, security procedures,
exact source SHA validation, and real guest execution.
