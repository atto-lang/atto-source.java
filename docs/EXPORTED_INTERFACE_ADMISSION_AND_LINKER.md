# Compiler-issued admission and two-phase Oreslang interface linking

Status: **experimental / draft-only**. Stacked on Java PR #944, which is
stacked on #941 and #934. This implementation **does not enable the production
incremental compiler cache or native linker**.

## Compiler-owned checked programs

`TypeChecker.CheckedPublicNominals` is no longer publicly constructible.
Its private constructor accepts a validated AST and the actual resolved
public nominal names only from the type/ownership checker.

`OresCompiler.admitLocal(source)` mints a final `CheckedProgram` token with a
private constructor *only* after both type and ownership admission pass.
The token carries checked public nominal origins, diagnostics and its admitted
program. Its constructor is not a public record constructor, and there is
no `.oresi` decoder that can mint one.

`CheckedPublicTypeIdentity.publicFunctionReturnType(token, ...)` consumes
that token instead of building nominal origins from arbitrary supplied maps.
`AdmittedInterfacePlanner.planLocal` similarly requires compiler-minted
admission tokens rather than ordinary caller-defined `AnalysisResult` values.

**Scope limitation:** local admission intentionally rejects source imports.
No caller may remove imports, mint a local token and claim that imported types
were checked. Multi-unit checked tokens require a real cross-file TypeChecker
and OwnershipChecker execution over the linked dependency component.

## Two-phase declaration / import planning

`TwoPhaseInterfaceLinker.collectCheckedDeclarations(programs, headers)`:

1. Validates source/environment identity, canonical unit IDs, and 4,096
   total-import budget.
2. Collects all units' public declarations without chasing imported
   dependencies. The v1 collector still rejects unsupported private
   physical layouts and unresolved public signatures.
3. Produces an immutable declaration-stage token. This phase deliberately
   cannot construct cross-file nominal type evidence from lexical names.

`TwoPhaseInterfaceLinker.resolveLinkedImports(stage, overrides, emptyPins)`:

1. Resolves every local import against all staged declarations. Rejects
   duplicate/ambiguous, missing and private imported symbols.
2. Refuses external packages, wildcard imports, and advanced import kinds
   without a complete verified contract. Exported class/interface/type
   signatures are not yet a substitute for typed linker admission.
3. Reuses `InterfaceSccPlanner` and `ImportGraph` for SCC-atomic,
   dependency-first fingerprints and exact import-selector target binding.

**The staged API is not the authoritative semantic linker.** It still accepts
caller-provided purportedly checked AST programs. Only a trusted compiler
driver may call it after actual multi-file type and ownership admission.
Until such a driver can prove the entire SCC, the stage must not authorize
cached executable code. The local-only opaque checked tokens are the
authoritative admission entry point implemented in this PR.

## Security and correctness gates

- An ABI hash cannot authenticate its own producer.
- All provider/interface headers must match a trusted compiler configuration.
- A source-only compilation plan is not an object-code cache key: inlined
  bodies, cross-module optimizer decisions, const eval, macros, target flags,
  and implementation-sensitive inputs must invalidate optimized output.
- Imported nominal identities, generic/alias normalization, and opaque
  class/actor storage layout are still unsolved.
- A linked import cycle must complete semantic and ownership admission for
  *all* its members before any member becomes executable.
- A wildcard or external provider is not deemed safe simply because a path
  or a caller-supplied SHA-256 is present.

## Verification

An authorized private JDK 21 runner compiles exact Oreslang `Ast`,
`ExportInterfaceCodec`, `ExportInterfaceCollector`, `ImportGraph`,
`ImportRules`, `InterfaceSccPlanner`, `TwoPhaseInterfaceLinker`, and
supporting v2 components. It checks successful cyclic function declaration
resolution; order independence; missing/private symbol rejection; wildcard
and actor import rejection; incorrect target ABI; path normalization;
resource bounds; and ABI invalidation.

**The additional TypeChecker/OresCompiler/AdmittedInterfacePlanner classes
and their JUnit tests are not compiled by that isolated runner.** Their
correctness requires the full repository Maven/GraalVM build. Source-branch
GitHub Actions failed with no assigned runner and no executed job steps,
so those failures do not establish a source-level regression or success.
Do not merge on the strength of isolated tests alone.

## Next integration target

Introduce trusted multi-file type and ownership admission that produces a
checker-minted verified component result, including resolved import identity
and SCC semantic proofs. Connect it to source interface emission, and only
then to Java PR #924's transactional incremental build cache.
