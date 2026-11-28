# Oreslang v2 typed-interface dependency prototype

Status: **experimental, fail-closed staging format**. This is not an
authoritative typed linker or an on-disk incremental build cache.

This PR is stacked on Java PR #934, which adds the v1 public-only envelope.
It leaves Java PR #924's existing incremental compiler unchanged.

## Provider-bound source imports

`ExportDependencyManifest.collect(checked, unitId, header, bindings)` requires
**one exact binding per import declaration**. Each binding records:

- Original import kind, wildcard/explicit names, namespace and path
- Provider source unit ID and exported namespace
- The provider's complete canonical v1 interface SHA-256 digest

It rejects missing/extra/duplicate imports, self imports, malformed hashes,
unsupported wire versions and noncanonical byte order. Reordering independent
imports or named symbols produces identical bytes. Retargeting a provider or
changing an imported ABI produces a different digest even if the source
unit text and *set* of dependency paths remain unchanged.

`verifyAgainstProviders(manifest, providers)` validates these claims
against the **actual supplied** provider interface files: digest, namespace,
compiler version, target ABI, feature policy and exported symbol names. It
admits explicitly named or wildcard imports of MODULE, FUNCTION, CLASS,
INTERFACE and TYPE only. Actor, contract, trait, struct and plural kinds
remain unsupported because this wire format does not prove their semantics.

Neither decoding the manifest nor supplying a provider file proves the
file's provenance. Only a trusted, type/ownership-checked driver may supply
these objects. Never take caller-provided digest strings as authorization.

## Canonical type identities

`CanonicalTypeIdentity.of(typeRef, resolvedNominals, genericBinders)`
produces deterministic, bounded, content-addressed typed tree descriptors:

- Record field order and union member order canonicalize independently of
  source presentation.
- Tuple/record/union/readonly, immutable borrow/mutable borrow and
  function types retain distinct tags.
- Generic references are encoded by lexical binder position, so alpha
  renaming does not create a distinct *type tree* identity. The enclosing
  exported declaration must still include public binder names when those
  labels support named type arguments.
- Every non-primitive nominal type requires an explicit
  provider-unit/namespace/symbol/provider-interface-digest mapping.
- `Unknown`, inference placeholders, incomplete type arguments,
  unrecognized compiler-only tags, recursive depth beyond 96 and more
  than 10,000 type nodes fail closed.

**Important:** The type-tree fingerprint function is not yet called from
`ExportInterfaceCollector` or `IncrementalCompiler`. It also cannot
replace the checker: it cannot infer alias resolution, trait implementation,
nominal ownership or method overload selection. Its resolver map must come
from checked symbol provenance, not a user-provided guess.

## Remaining production gates

1. Type checker must emit fully resolved exported symbol IDs. Encode those
   IDs in exported declaration signatures; do not rely on lexical `TypeRef`
   names for correctness.
2. Build a two-phase SCC interface linker. A cyclic import component cannot
   demand each member's final content digest before the component is
   resolved; use group contract fingerprints after declaration collection,
   with no guest execution during linking.
3. Compute **opaque** storage and serialization-layout ABI for public classes
   containing private fields. Do not reveal private declarations.
4. Bind compiler provenance to actual executable/version/flags, rather than
   accepting caller-provided `Header` values.
5. Adopt one authoritative versioned ABI descriptor in both Java and LLVM.
   Only then switch Java's incremental invalidation from its old digest and
   introduce native per-module object caching.
6. Run full Java/GraalVM Maven, ownership, actor-runtime and LLVM compiler
   suites and reproducible cold/warm/edit benchmarks before merging or
   claiming speedups.

## Verification

JDK 21 on an authorized private runner compiles exact `Ast.java`, v1 codec,
v1 collector, v2 manifest and canonical type identity sources.

The isolated tests cover v1 envelope invariants, 1,024 seeded mutation
round trips, exact import binding sets, provider hash/version checks,
same-set retargeting, canonical structural types and generic scope.

These tests are **not** the full Maven/GraalVM compiler suite. The source
organizations' Actions jobs are not executing full compiler tests.
