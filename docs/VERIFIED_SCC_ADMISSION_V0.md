# Oreslang VerifiedScc v0 — restricted primitive function linking

Status: **experimental draft**, stacked on #948, #944, #941, and #934.
This is the first SCC semantic-admission implementation, not yet a
production-wide linker, native code-cache grant, or LLVM contract.

## The new compiler-only type boundary

`LinkedFunctionContract.requirePrimitiveContract` validates that an imported
signature is a public, ordinary `fnc`, without actor state, generics,
annotations, structural/non-lexical/trapped behavior, async/suspension,
generators, borrowed, nominal, or dynamically inferred types.

It permits primitive parameter and return types (`int`, `float`,
`bool`, `String`; `void` only as a return). Parameters must have distinct,
nonempty names and cannot be mutable or structural.

`TypeChecker.checkTypesWithLinkedFunctions(source, linkedFunctions)` uses
`ImportRules` to establish exact imported names, loads *only* those verified
function signatures into the TypeChecker's callable lookup, disables the
legacy `Unknown` fallback for those names, and performs full per-unit
semantic validation and `OwnershipChecker.check`.

The source may not invent a linked provider by calling this low-level
method: only the trusted compiler/linker can supply true provider
declarations. This method does not itself authenticate the provider.

## All-or-nothing VerifiedScc admission

`VerifiedSccAdmission.verifyGraph`:

1. Stages public declarations for all units without recursively requiring
   finished hashes.
2. Resolves the entire local named-import graph using the two-phase linker.
   Wildcards, external packages, classes, generic/nominal/alias imports and
   advanced actor contracts still fail closed.
3. Looks up exactly one public provider function declaration for every
   imported selector, accounting for local aliases and exact target override.
4. Passes the resolved primitive function signatures into the real
   TypeChecker and OwnershipChecker for **every unit**. A failed member
   aborts the graph before any `VerifiedScc` result is issued.
5. Checks that each imported provider's signature remains unchanged after
   annotation expansion/semantic checking, avoiding stale checks against
   phase-one declarations.
6. Recollects interfaces from the post-checking ASTs, recomputes the SCC
   dependency graph and fingerprints, then issues immutable, privately
   constructed `VerifiedScc` and `VerifiedGraph` results.

## Audit hardening (October 14, 2026)

`SccVerificationInputs.snapshot` now captures exact source-unit identities,
compiler environment headers, and recursively copies the caller-supplied
per-unit import-resolution **maps** before any staged linker or checker runs.
Unknown/unused import selectors, noncanonical provider aliases, unknown target
units, missing/extra compilation environments, empty graphs and excessive
import counts fail before semantic admission. This prevents mutable caller
map references from changing what the linker checks versus what is fingerprinted.

The AST values themselves are **not deeply cloned**: `Ast.Program` and
declaration list fields are immutable records/copies, but arbitrary
caller-constructed literal payload objects may still be mutable. The verifier
expects trusted, non-mutating compiler-created ASTs, and this mechanism must
not authorize persistent object-code reuse from untrusted AST inputs.

`LinkedFunctionContract.requireStableSignature` now checks both the
originally imported declaration and the final verified provider against
the **same strict effect-free primitive policy** and compares names,
visibility, parameters, return types, effect flags, generics and annotations.
A provider must not gain an annotation or effect during expansion while
importers retain an older proof. Changes to an implementation body alone
are intentionally ignored at the interface layer; they must invalidate
implementation-sensitive optimized-code caches separately.

`verifySingleScc` rejects a graph with more than one component. A token
holds the complete verified source set and interface fingerprint. It is
not serialized and there is no decoder that can manufacture a token.

## Important limitations

- **Not yet full language cross-module semantics.** Qualified class/interface
  identities, alias substitutions, named/default generic parameters,
  actor effects and borrowed nominal types require additional checked
  symbol provenance. They are rejected rather than approximated.
- `VerifiedScc` establishes a restricted type/ownership semantic admission
  pass for the tested source set. It does NOT establish that runtime module
  initialization, entrypoint wiring or native machine-code caching is safe.
- The TypeChecker method depends on the actual Java compiler sources and
  Truffle dependencies. The independent JDK 21 runner cannot verify the
  complete integration, and must not be described as a full compiler pass.
- Optimized object-code caches must include implementation-dependent
  compiler/toolchain inputs. ABI/SCC fingerprints alone are insufficient.
- This does not connect the result to PR #924 or emit a production `.oresi`
  from one unified verified export/signature pass.

## Tests and verification

Repository JUnit tests in `VerifiedSccAdmissionTest` cover a mutual
function-call cycle, imported parameter/return type mismatches, rejection
of invalid member bodies, unsupported imported effects and nominal types,
unforgeable result constructors, and canonical input ordering.

Exact-source independent JDK 21 tests cover the primitive imported
function contract, public interface binary codec, actual import graph,
two-phase linker, SCC invalidation and randomized cyclic graphs.
Those tests DO NOT compile the entire TypeChecker/OwnershipChecker
integration or prove executable correctness. The new independently compiled
snapshot and provider-stability tests cover mutable source/override maps,
noncanonical bindings, import budgets, changing parameters, renamed symbols,
new annotations/effects and body-only stable ABI. Repository JUnit
counterparts are committed but remain unverified by the full Maven suite.

The full Maven/GraalVM suite, then an end-to-end compiled Oreslang program
which calls imported functions, is the required next verification gate.
Source-repository Actions workflows have been failing before test steps
execute, so no claim of full build success is made.
