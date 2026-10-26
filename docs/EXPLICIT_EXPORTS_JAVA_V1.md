# Java explicit exports v1 — reference slice

Status: draft implementation for the source-language contract in
`oreslang-common-source#150`. This document describes Java compiler behavior
on this branch; it is not a claim of cross-backend parity.

## Pipeline

The Java front end keeps export syntax separate from ordinary declarations:

1. `Parser` records unresolved `Ast.ExportDecl` values on the owning
   synthetic-root or named-module `ModuleDecl`.
2. Imports and exports may be interleaved before the first real root
   declaration; export metadata does not close the import prelude.
3. All declarations are collected.
4. `ExplicitExportResolver` builds one namespace export-binding table.
5. `OresCompiler.analyze*` requires export resolution before type and
   ownership admission.
6. Source export bindings are not mapped to JVM, Native Image, Wasm, or native
   linker exports by this slice.

## Implemented semantic subset

Public source export bindings are resolved for declarations currently represented
by Java main:

- class and actor class;
- fnc and routine overload families;
- interface and contract;
- type alias;
- const, val, and let module/root bindings.

Declaration-site `pub` and explicit exports share one binding/collision table.
The resolver supports aliases, exact duplicate idempotence, inline-pub plus
identical explicit export, kind checks, unknown-target rejection, ambiguity
rejection, overload-family visibility consistency, and alias collisions.

Explicit-private provenance is retained as a kind-qualified logical symbol key.
This prevents a private function named `Same` from incorrectly blocking a
typed export of a class also named `Same`.

Default-private declarations may be explicitly exported. Explicit-private
declarations may not.

## Parsed but deliberately not lowered

The parser preserves the common v1 grammar for several features whose Java
semantic substrate does not exist yet:

- `export[file]` and `export[pkg]`: rejected by the resolver until scoped
  visibility is implemented;
- Symbol declaration exports: `#ready` syntax and exact
  `#ready as #ready` self identity are parsed, but Java main does not yet have
  a top-level Symbol declaration family to resolve;
- typed trait exports: current `InterfaceDecl` does not retain interface-vs-trait
  declaration provenance;
- module, struct, and model export kinds: syntax is reserved/preserved but the
  corresponding current Java declaration model is not sufficient for binding
  resolution.

These cases fail closed. They must not be treated as successful export support.

## ABI boundary

An Oreslang source export is an API/visibility binding. This branch does not
turn it into:

- a JVM host-accessible symbol;
- a GraalVM Native Image exported entry point;
- an ELF/Mach-O/COFF dynamic symbol;
- a Wasm export;
- an ES/CLR export.

Any such exposure requires a separate backend ABI contract.

## Verification boundary

Focused JUnit parser/resolver tests are authored on the branch. Current source
organization Actions can fail before runner assignment; a zero-step workflow is
`NOT_EXECUTED`, not passing or failing compiler evidence.


## Transform and optimizer preservation

Export metadata is part of semantic AST state and must survive compiler rewrites.

This branch preserves `exports` and explicit-private provenance through
`AnnotationExpander` and the TreeShaker rewrite pass. In library mode,
resolved public explicit exports are additional tree-shaker roots, so a
default-private declaration exposed only by `export` cannot be pruned as
unreachable.

TreeShaker also preserves checked function/method effects, method suspension
metadata, and actor concurrency metadata while rewriting declarations. Effect
types participate in reachability because they are part of the source ABI.

Compiler admission resolves exports both before type checking and again on the
type-checked/annotation-expanded program. The second check prevents generated
public members from introducing a narrower-type API leak after the initial
export validation.

## Cross-file linker boundary

This reference slice does **not** yet make explicit exports available through
cross-file imports.

Current `ImportGraph`, linked class/contract metadata, and runtime imported
value/callable lookup still admit declaration-site `pub` declarations. They
therefore continue to reject a default-private declaration that is visible only
through an explicit export.

That is deliberate fail-closed behavior. The next linker slice must consume the
resolved export-binding table end-to-end, including external aliases, instead of
changing only import validation. In particular, all of these must move together:

- import validation by external export name;
- external-name -> local-symbol binding;
- linked class/contract metadata;
- runtime imported function/class/value lookup;
- module-member lookup;
- class construction authorization while retaining private-constructor rules;
- incremental ABI digest invalidation for export aliases/binding changes.

Until those are implemented together, cross-file explicit-export execution is
not claimed.
