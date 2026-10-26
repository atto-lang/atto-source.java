# Java explicit exports v1 — reference slice

Status: draft implementation for the source-language contract in
`oreslang-common-source#150`. This document describes Java compiler behavior
on this branch; it is not a claim of cross-backend parity.

## Exact-source JDK25 audit and bounded type traversal

A follow-up local audit obtained checksum-verified Eclipse Temurin JDK
25.0.5+8 and the real JUnit Platform Console Standalone 1.11.4. The ten
parser/resolver/AST dependency and JUnit source files at head
`1ee3a0362d94cb29c516d55887712dc45ad527b9` were verified against GitHub Git
blob IDs and compiled without edits or compiler/runtime stubs. The original
focused suite passed **37/37 real JUnit tests**, zero skipped.

The adversarial follow-up reproduced a `StackOverflowError` in the original
resolver on an already-constructed, deeply nested type AST. The resolver now
walks type arguments iteratively. It enforces a maximum nesting depth of 256
and a shared namespace budget of 65,536 visited type references across all
exported signatures. Child work is reserved before traversal records are
allocated, and both limit failures remain explicit export admission errors.
These are local type-traversal limits, not a claim that every parser, source
graph, symbol collection or compiler allocation has been resource-qualified.

Five new real JUnit cases cover a 100,000-wrapper generated type, exact depth
and width boundaries, over-budget width and sharing the work budget across
two exported aliases. The patched focused suite passes **42/42**, zero
failed/skipped. The old resolver fails the deep regression with a host
`StackOverflowError`; the patched resolver gives a bounded export error.

The tested source/tool identities are recorded in
`docs/evidence/explicit-export-jdk25-audit.json`. The executable test command is
JUnit Console `execute --class-path <compiled-classes>` selecting
`dev.oreslang.ExplicitExportResolverTest` and
`dev.oreslang.ExplicitExportSyntaxTest`. Compile their actual dependencies:
`Ast`, `TaskCaptureNames`, `Token`, `Lexer`, `TemplateLiterals`, `Parser`,
`OresSymbol` and `ExplicitExportResolver`, using JDK25 `javac` with the pinned
JUnit jar on its classpath. No compatibility substitution is used in this run.

Evidence boundary: this is real exact-source parser/resolver JUnit execution
in the local trusted workspace. Full Maven/TypeChecker/OwnershipChecker,
AnnotationExpander/TreeShaker preservation, GraalVM Native Image, runtime/linker
imports and cross-backend parity still require their own executed tests.
The source-local workflow retry returned zero job steps. Its failure is not
a failed Java assertion or a demonstrated exhausted-minutes diagnosis.

The protected-main destination allowlist has no active entries and the sole
carrier contract allows PR #1482 only. It does not authorize this PR #2032.
No private source was copied to another GitHub organization during this audit.

Use the reconciled common-source #153 contract, revision
`explicit-exports-v1.2026-10-25.4`, when resolving differences from the earlier
#150 examples.

## Overload and generic-scope hardening

An implicit public callable export covers the complete local overload family,
including overloads without explicit modifiers. Explicit-private provenance
still rejects the entire exported family, whether publication is implicit or
explicit. The same public/default family produces one idempotent binding when
an explicit export is added. Scoped declaration visibility remains deferred.

Leakage checks distinguish generic binders from nominal local types. Function,
class, method, interface-member and type-alias generic names apply only to their
own lexical signature scope. Method binders never hide a private type in a
sibling signature. Function generic defaults are checked before introducing
their binder; nested nominal arguments and declared effect types retain normal
visibility checking. This is a local lexical check, not an imported-type
authority proof or complete post-typechecking identity representation.

Duplicate namespace entries cannot silently overwrite binding tables. Stable
local symbol kind strings use `Locale.ROOT`, not the host's default locale.

The dedicated source-local workflow runs `ExplicitExportSyntaxTest`,
`ExplicitExportResolverTest` and `ExplicitExportTransformPreservationTest` on
the exact source head using JDK25/GraalVM. A queued or zero-step workflow is
still NOT_EXECUTED.

Local audit evidence: an 18-case Java17 compatibility smoke exercised the real
parser/resolver source and AST dependencies after mechanically substituting
`List.getFirst()` with `List.get(0)` in private test copies. It reproduced the
original incomplete-overload/false-generic-leak defects and passed the revised
family, generic-scope, namespace, locale and four Symbol-form checks. It is
not exact-source JDK25, JUnit, TypeChecker/OwnershipChecker, GraalVM, native
image, import-linker or cross-backend execution. Those gates remain open.

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
