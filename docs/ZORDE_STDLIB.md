# Zorde in Oreslang core and stdlib

**Decision:** Zorde is a standard-library capability, not an optional third-party
schema engine. The independent `oreslang-serialization-and-validation`
repository remains its **incubation, conformance and codec-adapter repository**.
A caller should ultimately import the public validation surface from
`std/zorde`, while compiler-generated constraint descriptors and checked
construction are built on the shared core validation contract.

## Single source of truth

The compiler lowers all of the following to the same canonical constraint IR:

```ores
@MinLength(3)
@MaxLength(32)
type Username = string;

@Length(min=3,max=32)
type Username2 = string;

type Username3 = string with length gte 3 and length lte 32;
type Username4 = string with length >= 3 and length <= 32;

@Min(1)
@Max(65535)
type Port = int;

@Range(min=1,max=65535)
type Port2 = int;

type Port3 = int with value gte 1 and value lte 65535;
type Port4 = int with value >= 1 and value <= 65535;
```

These declarations have equivalent predicates for the same underlying
representation, except that `define newtype` additionally introduces a
nominal type identity. The runtime validator cannot reinterpret or silently
drop the compile-time predicates.

### Canonical annotation rules

| Annotation | Allowed base | Normalized predicate |
| --- | --- | --- |
| `@NotEmpty` | string | `length gt 0` |
| `@MinLength(N)` | string | `length gte N` |
| `@MaxLength(N)` | string | `length lte N` |
| `@Length(eq=N)` | string | `length eq N` |
| `@Length(min=A,max=B)` | string | `length gte A and length lte B` |
| `@Size(min=A,max=B)` | string | same inclusive length interval |
| `@Min(N)` | int | `value gte N` |
| `@Max(N)` | int | `value lte N` |
| `@Range(min=A,max=B)` | int | `value gte A and value lte B` |

All bounds are compile-time signed `int64` literals. Length bounds must be
nonnegative; contradictory constraints are compile-time errors. Scalar
comparisons use `gte/lte` or `>=/<=`; `ge/le` are not source aliases.
Integer comparisons are exact; no binary floating-point intermediate is
permitted. String length means Unicode scalar values (code points), not UTF-8
bytes or Java UTF-16 code units; no implicit Unicode normalization occurs.

### Core responsibility

- Canonical constraint AST/IR: property, comparator, bound, source span,
  nominal identity, effective normalized interval and exclusions.
- Pure constant evaluation, range proofs and refinement entailment.
- Compiler-generated static validator/schema descriptors and stable fingerprints.
- Exact UTF-8/Unicode scalar counting and integer range checks.
- Checked-construction error contract and fail-closed admission at untrusted
  boundaries, including typed decoding, actor DATA, RPC and FFI.
- Enforced purity for `where` predicates before any runtime lowering.

### Stdlib responsibility

- A public `Zorde` module / `std/zorde` import facade.
- Familiar builder/parse APIs: string length, numeric range, object fields,
  schema composition, validation reports, `Result`, safe parsing and
  typed `schema<T>()` when compiler generation exists.
- The module delegates to core validators and compiler-generated descriptors,
  never runtime reflection, implicit coercion or independent predicates.
- Format modules (JSON, MessagePack, CBOR, Protobuf, etc.) use the same compiled
  validator and typed metadata at decode boundaries.

## Implemented seed versus future work

`stdlib/zorde.ores` currently introduces a small, pure numeric entrypoint:
`Zorde.int_at_least`, `Zorde.int_at_most`, `Zorde.int_between` and
`Zorde.int_exact`. They operate on exact Oreslang integers, not floats. This
is **not** an implementation of compiled `@Min/@Max` semantics or the complete
schema API. Compiler admission still rejects refined aliases/newtypes until
checked construction, ownership and boundary enforcement are ready.

The existing `src/validation/validation.ores` and `src/zorde/zorde.ores` in
`oreslang-serialization-and-validation` are the native dynamic-model proving
ground. Their full promotion to `stdlib/` depends on public type exports,
correct ownership and immutability, integer-exact dynamic Value rules,
generated schema bindings, hostile-input tests and executed cross-backend
conformance. In particular, do not use the current float-based
`zorde_number(...)` to claim exact `int64` validation.

The current parser supports the annotations on aliases and newtypes only.
Field, parameter, return and local annotation enforcement is **planned**:
until compiler/runtime support is complete, the parser must reject an
unimplemented enforced annotation rather than preserve it as inert metadata.

## Standard-library acceptance gates

1. `@MinLength(3) @MaxLength(32)` must normalize to the same bounds as both
   `with length gte 3 and length lte 32` and the symbolic form.
2. `@Min(1) @Max(65535)` and `@Range(min=1,max=65535)` must match the
   `Port` keyword/symbolic forms exactly.
3. Negative lengths, wrong base types, duplicate bounds, contradictory
   intervals, malformed numeric constants and overflow must be rejected.
4. `int64` extremes and Unicode surrogate/emoji/combining-mark fixtures must
   be covered with actual executed tests.
5. Dynamic Zorde checks must produce the same accept/reject outcome as the
   compile-time proof evaluator, without lossy coercion.
6. Public `Valid<T>` typestate may not be forged or invalidated by mutable
   aliases, FFI or deserialization.
7. Java, LLVM and other backends may not claim stdlib refinement support until
   the same conformance fixtures and trust-boundary gates execute successfully.

## Current compiler-side schema planning milestone (October 17, 2026)

`dev.oreslang.compiler.ZordeSchemaGenerator` now provides a **non-executable**
compile-time plan over already parsed Oreslang aliases/newtypes:

- `planSource(source)` parses declarations without bypassing
  `OresCompiler.parseAndTypeCheck` (which still rejects executable
  refinements).
- `plan(program)` emits an immutable, deterministically ordered
  `Map<qualifiedType, RefinementRules.Descriptor>`, rejects unresolved imports,
  generic/parameterized refinements, duplicate schema identities, unsupported
  predicates and malformed nominal identities.
- `Plan.schema(qualifiedType)` retrieves a compiler-generated descriptor.
- `Plan.tryFrom(qualifiedType, raw)` is a **host-side reference operation**
  that accepts immutable `Long`/`String` only, checks the generated
  descriptor and returns private-constructor `CheckedScalar` or structured
  `ConstraintError`. It is **not** guest `T.try_from()`.

- Host `CheckedScalar.intoInner()` and `RefinementRules.NominalValue.intoInner()`
  now **consume their scalar payload exactly once**,
  including under concurrent Java calls. A second unwrap or transfer throws
  `IllegalStateException`; string/long metadata remains diagnostic only.
- `Plan.revalidateInto(targetType, source)` explicitly checks a source
  scalar against this plan's trusted-at-call-site target descriptor and moves
  the immutable scalar on success. Failure preserves the source unchanged;
  arbitrary source fingerprints and nominal names are NEVER an admission
  shortcut. A descriptor digest is an equality check, not provenance.
- This host facility is deliberately **not** the guest `Valid<T,SchemaId>`
  feature. Analysis plans are constructible from arbitrary source, the host
  wrapper is allocated/managed by the JVM, and no actor, FFI, arena or code
  generation trust boundary has been certified. Future AOT/zero-GC profiles
  must lower ownership directly, not retain this Java reference wrapper.

Stable schema ABI v1 fingerprints SHA-256 over a canonical representation
of normalized base, property, inclusive interval, sorted effective exclusions
and compiler-qualified nominal identity. The independently implemented Python
oracle contains matching fixed-fingerprint fixtures. Redundant exclusions
outside an admissible interval are discarded so equivalent constraints do not
receive different schema identities.

Example *host compiler test harness* (not executable Oreslang syntax):

```java
var plan = ZordeSchemaGenerator.planSource(
    "namespace app; @Range(min=1,max=65535) type Port = int;");
var schema = plan.schema("app.Port");
var result = plan.tryFrom("app.Port", 443L);
assert result.ok();
```

A bare nominal `define newtype T(string);` also emits an identity-bearing
schema for the full base domain. A nominal type must have an explicit source
namespace to avoid cross-unit identity collisions.

**Pending:** replace host-only `Plan.tryFrom` with compiler-generated guest
`Result<T,ConstraintError>` constructors, enforce nominal ownership/casts,
make `zorde.schema<T>()` an actual typed intrinsic, and connect generated
schemas to stdlib codecs, actors and FFI. Importing arbitrary host plans or
forging `Valid<T>` must never become a guest-language capability.

## Architecture decision: Zod-style schema-first, with one canonical IR

**Chosen direction:** A statically known Zorde schema is the authoritative
definition of the validation rule. Oreslang derives the refined *output type*
from that same rule. Existing `@Min/@Max/@Range/@MinLength/@MaxLength` and
`with` syntax are alternative **frontends** that lower into the identical
static schema IR. Runtime parsing executes that IR, not a separately maintained
ad-hoc validator. Schema-first does **not** mean executing arbitrary userland
validation functions at compile time.

Proposed standard-library source API (currently **analysis-only** in the Java
compiler's `ZordeSchemaGenerator.planSource`, not guest-executable):

```ores
namespace app;

const PortSchema = Zorde.int().min(1).max(65535);
type Port = Zorde.infer<PortSchema>;

const NameSchema = Zorde.string().minLength(1).maxLength(32);
type Name = Zorde.infer<NameSchema>;

// Future guest runtime API once checked constructors and boundary lowering land:
const result = PortSchema.safeParse(rawInput); // Result<Port, ConstraintError>
```

For comparison, these give the same **structural constraint semantics**:

```ores
@Range(min=1,max=65535) type PortA = int;
type PortB = int with value >= 1 and value <= 65535;
```

The parser AST contains real `const` bindings and
`Zorde.infer<SchemaConstant>` aliases. The experimental compiler planner now
visits those declarations, statically folds **only** the built-in
`Zorde.int()` / `Zorde.string()` scalar chains with signed literal bounds,
and emits the same `StaticZordeSchema` -> `RefinementRules.Descriptor` and
`InferredScalarType` used for annotations and `with`. Unrecognized calls,
runtime values, user callbacks, rebindable schema definitions, shadowed Zorde
intrinsics, incorrect base-property combinations, incompatible generics and
contradictions fail closed. Ordinary `OresCompiler.parseAndTypeCheck` does not
yet admit these types.

**Core invariant:** `StaticSchema -> normalized ConstraintIR -> checked
descriptor -> {static proof, runtime validation, generated type}`. There
must never be independent interpretation of `gte/lte/ne` (or Unicode scalar
length) in the type checker versus checked parsing. The Java host prototype
shares one scalar metric and one interval-membership kernel. Native Oreslang
Zorde and LLVM must implement the *same versioned descriptor contract* and pass
the same fixtures before those backends admit executable refinements.

### Type inference limits and nominal identity

- `Zorde.infer<ConstSchema>` is a **compiler intrinsic type query**, not
  a normal generic parameter that user code can implement or dynamically call.
- Static schema resolution is closed-world: no arbitrary callbacks,
  environment-dependent validation, async, mutation, reflection, or unchecked
  `where`. Runtime-only schemas can validate dynamically but must **not**
  fabricate a statically narrowed type.
- `Schema<Input, Output>` must eventually track both sides. Pure validators
  accept `int` or `string` as input and return a refined output; coercions and
  transformations, if added, must declare their input and output types
  separately and cannot bypass validation.
- Structural schema inference does not manufacture a new nominal type.
  `define newtype` or explicit compiler-verified branding is required for
  nominal identity. Fingerprints use compiler-qualified type names.
- Literal values known at compile time can be proved/rejected using the same
  descriptor; dynamic values require `Result<T,ConstraintError>`.
  Direct casts, aliasing/mutation, FFI, serialization or actor DATA must not
  mint `T` without the same check.
- The current dynamic `ZordeValueSchema` and `ValidValue` facade is not yet
  proof-carrying typestate: some payloads retain mutable aliases. Production
  typed parsing requires immutable scalar payloads and deep freeze/ownership
  transfer for mutable collections.

### Source-derived named type import edges (non-executable)

The import-aware staging entrypoint
\`CompilerZordeSchemaRegistry.stageSourceWithParsedImportsForAnalysis\`
now parses the **original Oreslang source AST** and keeps strict
\`import type Name from "unit.id";\` declarations as immutable dependency
metadata. It rejects module/function imports, aliases, wildcards, multiple
names in one import, duplicate local imported names, names that shadow local
schema exports, traversal-style paths and source-unit self-imports. The
legacy source-only staging method continues to reject all imports.

Source staging now enforces an explicit budget of 128 named type imports
per compilation unit, with at most 32 imported type names from any
individual target unit and at most 128 ASCII characters per imported type
identifier, before attempting a closed-world link. Unit
identities and imported paths use the same canonical segment grammar:
empty segments, dot-dot traversal, trailing separators and ambiguous
normalization are rejected. The strict import dialect uses dotted
unit IDs rather than filesystem paths.

The linker method \`verifyParsedTypeImportsForAnalysis\` compares **exactly**
the imported unit names from the source AST with the independently pinned
dependency map; an edge may not be added, removed, or redirected simply by
supplying an otherwise well-formed lockfile entry. For every named import
the target staged source must export exactly one schema with that imported
simple type name. Only an explicitly `pub` refined alias/newtype counts as
a cross-unit export; private generated schemas cannot be imported merely
because their names and descriptors are present in linker analysis. The target's source digest must match the independently
pinned hash, the target and pin sets must be complete, and the pre-existing
cycle/orphan/ABI/generation/schema collision checks still apply. The older
\`verifyForAnalysis\` graph-only route explicitly rejects units with parsed
imports to prevent an admission bypass.

**Limitations:** this is only a restricted, parser-derived **type import
dependency check**, not general Oreslang symbol resolution. It currently
does not support qualified module imports, aliases, wildcard imports, an
imported type in a local Zorde schema expression, or actual linking of
imported declarations into the source typechecker. The linker still expects
source hashes and generations from independently **approved** policy input;
user-selected pins are not credentials. It does not authenticate compiler
binaries or resolve a signed dependency lockfile. No executable
\`Valid<T,SchemaId>\` issuance, actor arena allocation, immutable graph
ownership, FFI transfer, or native LLVM parity is enabled.

### Closed-world pinned linker manifests (non-executable)

\`CompilerZordeLinkManifest.verifyForAnalysis\` now builds a deterministic,
length-prefixed SHA-256 **exact-tree integrity manifest** from an explicitly
pinned, closed graph of source-staged \`CompilerZordeSchemaRegistry\` instances.
The manifest commits to the named compiler build, target ABI, entry roots,
each unit's source digest and generation, explicit schema-versioned exports,
and dependency edges with the exact pinned source digest of each target.
Exports, dependency edges and units are sorted before hashing.

The verifier rejects missing or extra pins, stale unit source/generation,
unknown dependencies, source-digest mismatch, schema-name collisions,
self-dependencies, cycles, unreachable units, invalid unit keys, and graphs
above 128 units. A separately constructed manifest with the same exact tree
has the same digest but a distinct non-serializable **link-instance seal**.
The compiler's internal checked type marker is now scoped to both its
compilation-instance seal and its verified link-instance seal. A type issued
for another linkage or target ABI cannot be assigned interchangeably merely
because the schema name and hash match.

This is deliberately a **compiler-analysis prototype**, not a complete
linker or a runtime capability:

- The caller must obtain source-digest/generation pins from independently
  approved build inputs; deriving pins from untrusted candidate sources
  defeats the integrity check. Hash equality alone never authenticates code.
- The original graph-only path still accepts build-driver edges for
  import-free units. A separate strict path verifies parser-derived
  `import type` edges, but full imported module AST/interface resolution
  and externally authenticated source-to-module attestation remain separate
  gates.
- The compiler build ID and ABI label are pinned metadata, not a signature,
  compiler-binary attestation or permission to run on that target.
- The \`checkedTypeForLinkedAnalysis\` method creates only a private static
  type marker, **not** an owned/frozen \`Valid<T,SchemaId>\` runtime value.
  Source syntax and all production proof construction remain unavailable.
- Shared-library load/unload, actor transfer, FFI, generation rollback and
  immutable native actor-arena codegen remain unimplemented.

The next prerequisite is a trusted linker context that derives the pinned
graph from verified module imports and an approved exact-tree lockfile,
followed by checked owned construction and independent native CI execution.

### Compiler-local schema registry staging (non-executable)

`CompilerZordeSchemaRegistry` is a non-public compiler-package structure that
parses one Oreslang source unit and stages only the normalized, statically
derivable Zorde scalar schemas from that unit. It rejects unresolved imports,
dynamic validators, missing schemas, duplicate schema identities, unsupported
predicates and invalid compilation-unit/generation metadata. Schema entries
are immutable and are not user-registrable by calling a public API.

Every staging instance owns a **fresh opaque compilation seal**. The private
checked type's identity now includes that seal (by object identity), in
addition to the scalar kind, fully qualified schema name and normalized
fingerprint. The same text, digest, compilation-unit name and numeric
generation supplied in a second staging call **cannot** recreate the first
seal; types from different staging instances cannot be assigned to one
another without a future explicit, checked compatibility/revalidation step.
A public descriptor copy with the same SHA-256 digest is not the admitted
descriptor object.
The registry also records a deterministic SHA-256 digest of the exact staged
source text for reproducibility and comparison. This digest, like the numeric
generation or schema fingerprint, is public metadata and **never** sufficient
to mint the private per-compilation seal.

`checkedTypeForAnalysis` checks strict int64 or Unicode-string membership
before returning an internal type marker; it **does not return a validated
runtime value**. Legacy, unsealed analysis types cannot impersonate
registry-scoped markers. Source `Valid<T,S>` still fails compilation.

This is a **closed-world analysis staging mechanism**, not trusted executable
proof minting: it has not yet linked or verified imports against a signed,
versioned module manifest; it does not attest the compiler binary, immutable
ownership, actor-domain identity, hot-reload generation, lifetime/arena or
FFI-safe storage. Numeric generation metadata is diagnostic, not a security
credential. A future authenticated compiler/linker pass must authorize
registries and enforce transitive ownership before guest code can construct
or transfer a `Valid<T,S>` value.

### Internal checked scalar type identity (non-executable prototype)

The Java type system has a private, sealed \`Types.CheckedScalarType\`
representation. The only same-package compiler analysis factory checks an
immutable signed-int64/Unicode scalar against a normalized descriptor and
records both the compiler-qualified schema name and semantic fingerprint.
Even two schemas accepting identical values do not become assignable when
their qualified identities differ. A changed descriptor invalidates
assignability for the same name.

This **is not schema admission authority**. Host \`Descriptor\` records and
\`ZordeSchemaGenerator.planSource\` can be produced from arbitrary source
text. The factory is deliberately unavailable to Oreslang guest source,
and does not grant code generation, serialization, actor transfer or
runtime representation. The parser and typechecker still reject all
source-level \`Valid<T,SchemaId>\` construction.

Both \`Types.isAssignable\` and the higher-level \`TypeChecker.assignable\`
protect compiler-checked types against structural aliasing, \`Unknown\` and
generic erasure (including nested \`Option\`, \`Result\`, records, borrows,
function signatures and unions). Stale linked nominal metadata named
\`Valid\` or \`*.Valid\` is also rejected rather than normalized into a
forged proof. Actor/async/FFI transfer remains **forbidden** until the
compiler and runtime prove ownership and generation safety.

The next trusted-construction gate must bind a reviewed, exact-tree
compiled schema registry and domain/generation capability to this marker,
then lower checked owned construction and consuming unwrap without boxing
or managed wrappers. This is prerequisite—not sufficient evidence—for
actual guest-language Zorde support.

### Implementation status and remaining release gate

The Java AST planner and static schema DSL exist and are exercised on
source-level tests, but the latest GitHub Actions jobs failed before runner steps executed; no exact-head Maven/JUnit pass is verified. Guest codegen for
`Zorde.infer`, `schema<T>()`, `T.try_from()` and `safeParse` is not
enabled yet. Source-first real `Parser.parse` JUnit testing and native Zorde
wire-decoding tests are required before claiming complete integration. LLVM
continues to reject unsupported refinements rather than erasing validation.
