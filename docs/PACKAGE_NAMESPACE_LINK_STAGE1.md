# Package namespace linking, Java/Graal — stage 1

This is the first compiler-side implementation slice for the shared
`oreslang-common-source#188` namespace contract.

## Implemented on this branch

- The parser preserves a distinct provider namespace in `import namespace billing as
  payments from "checkout";` (as `Ast.ImportDecl.sourceNamespace`), while retaining
  compatibility with existing five-argument import AST constructors.
- `PackageNamespaceIndex.load` reads explicitly supplied schema-v2
  `.ores.pkg.toml` manifests and checks that each enumerated source is in the
  caller-admitted source snapshot. Entries use canonical filesystem identities.
- The index keys namespace providers by the **manifest's canonical package-instance
  ID plus the declared namespace**, not by the namespace string alone.
- Only exact, caller-pinned dependency aliases (or `"+/"` for the importer's
  own manifest package) can select an aggregate.
- Duplicate manifest membership, traversal, symlinks, unlisted source files,
  missing files, missing namespace headers, duplicate exported symbol signatures,
  and ambiguous package aliases reject at admission.
- The opt-in `IncrementalCompiler.compile(sources, resolutions, index)` overload
  records manifest-backed package identities and namespace provider dependencies
  and invalidates cached compilation units if their package identity changes.
- The legacy single-file import linker rejects namespace imports without a
  verified index. `LinkedProgramRunner` **fails closed**, rather than
  silently selecting just one file as the runtime provider.

## Not yet implemented

Namespace-qualified calls, static type provenance, `pub[pkg]` cross-file
visibility, multi-file symbol-to-evaluator runtime dispatch, manifest discovery
and dependency resolution in `LinkedProgramRunner`, and actual 10-file runtime
execution. The opt-in index is **link admission metadata**, not a final namespace
runtime implementation. Do not merge or advertise end-to-end namespace support
until exact-head executed tests and the full static/runtime linker are present.

## Remaining integration work

1. Wire schema-v2 package manifests into host project loading, with pinned
   dependency instance identities and immutable snapshot admission.
2. Extend type checking/IR lowering for exact provider-per-symbol dispatch.
3. Extend the Truffle linked runtime so each namespace member resolves to
   its own vetted provider code unit, not one wildcard source file.
4. Add runtime and negative conformance tests for the complete ten-file fixture,
   ABI changes to any contributing file, private members, actors/classes/types,
   and module name/value collisions.
5. Port the canonical contract to LLVM, .NET NativeAOT, and Wasm.
