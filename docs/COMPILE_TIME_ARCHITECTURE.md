# Fast compilation: language and compiler contract (2026-10-14)

Status: **staged design**, not a declaration of cross-backend support.
Java has a file-granular incremental compiler, arity-based live overload dispatch,
and a separate typed-overload model. LLVM remains a much smaller source subset.
This document defines the milestones and acceptance tests; every language-facing
change requires its own compatibility and backend admission gate.

## Non-negotiable invariants

1. **Determinism**: identical source, dependency interfaces, target, compiler version,
   feature policy, and build flags produce the same canonical typed decision and
   interface fingerprint, irrespective of filesystem traversal/declaration order.
2. **Soundness**: cache hits never skip syntax, borrow, effects, access, trait or
   ownership validation when any of their semantic inputs have changed.
3. **Fail closed**: an unsupported feature cannot be silently erased, interpreted
   by a second backend, or compiled with a guessed type.
4. **No import-side execution**: declaration discovery across a cyclic import SCC
   has no guest side effects; calls initiate execution.
5. **Isolation**: build outputs/caches must not incorporate another project's private
   content, or accept an unverified cache key from a different trust domain.
6. **Reproducibility**: compiler version, feature gates, target ABI, package resolution,
   native libraries, compile-time evaluation inputs, and build flags are cache-key
   dimensions where they influence emitted artifacts.

## Implemented Java increment in this PR

Within the process-local `IncrementalCompiler` instance, cache immutable,
annotation-expanded **parsed** units by normalized source ID and SHA-256
of the exact source text; retain the public ABI digest with the AST.

- An identical build does not reparse unchanged files.
- Implementation-only edits reparse/rebuild the edited unit; unchanged dependents
  reuse their checked units when their imported ABI does not change.
- ABI changes rebuild the conservative reverse-import closure.
- Imports are validated against the full current snapshot, even on parse-cache hits.
- Rechecking dirty units still runs type/ownership admission against current linked
  declarations; parsed ASTs and checked units are distinct objects.
- The cache is staged: any failed parse, import, type, or ownership check leaves
  the previously committed parse and compiled caches unchanged.
- `BuildResult.reparsedUnits()` reports which source files were actually reparsed.
- `clear()` or an empty source snapshot evicts caches.
- This is an **in-memory** optimization. No on-disk or cross-process cache is
  claimed. Dirty units may currently undergo a second parser pass during type
  checking; eliminating that pass requires a separate parity gate.

## Language-feature workstreams

| Workstream | Contract / design choice | Gate |
| --- | --- | --- |
| Explicit exported signatures | Public parameters and results must have resolved type identities; existing legacy forms need diagnostic/transition policy before enforcement | mixed Java/LLVM positive-negative fixtures |
| Bounded local inference | Solve locally with contextual expectations; never infer by traversing arbitrary import bodies; `Unknown` is unresolved, `Any` is an admitted type | recursive constraint, ambiguous generic, cyclic import timeout tests |
| Overloads | Same arity in live Java remains forbidden until resolved ABI identity propagates through ownership/runtime/LLVM; exactly one most-specific candidate; no source-order ties | permutation/property, numeric and nominal enum, ownership tests |
| Structural types | Canonicalize types including mutability/ownership/readonly capabilities; memoize only pure, environment-specific subtype queries | recursive type, width/depth, alias, negative type, cross-module tests |
| Generics | Bind explicit positional/named arguments, infer only bounded missing arguments, then apply declared defaults; distinguish `Any` from unsolved variables | conflicting bindings, defaults, recursive specialization, code-size tests |
| Compile-time execution | Deterministic constant evaluation with step/depth/allocation budgets; no IO or ambient environment by default | budget exhaustion and reproducibility tests |
| Module interfaces | Export stable typed descriptors for callable signatures, public layouts, re-exports, traits, effects, serialization/ownership ABI | modified body vs public signature, imported class layout, SCC tests |
| Incremental compilation | Fingerprint each source, interface and import edge; invalidate on changed ABI, target, compiler mode, or linking policy | cold, warm, local edit, ABI edit, deleted dependency, renamed import |
| Backend specialization | Share generic bodies or cache monomorphized variants under canonical type tuple and target ABI; cap instantiation fanout | per-target dedup, recursion, symbols, binary-size tests |
| Debug compilation | Separate fast development profile from optimized release; no relaxation of static safety checks | output parity and compilation-time benchmark |

## Interface-fingerprint hazards to audit before persistent caching

The current Java ABI digest is conservative but not a complete versioned
cross-process interface format. In particular audit the following before
reusing a digest outside the live compiler object:

- Public class physical layout, including private storage affecting native ABI,
  constructors, sealed variants and serialization wire shape.
- Re-exported type aliases, inherited visible members, traits, effects, generic
  defaults/constraints, implicit numeric conversion rules and compiler feature gates.
- Imported module and symbol identities, namespace normalization and resolution maps.
- Whether a public inferred field's initializer must affect its exported type
  and whether an annotation is API-visible.
- Ambiguous/disallowed overloads: never encode a resolved dispatch target unless
  both checker and ownership pass agreed.
- Canonical, length-prefixed encoding and schema-version changes to avoid
  ambiguous string concatenation or cross-version cache collisions.

Until these are proved, reuse only the existing process-scoped compiler cache.

## Measurement protocol

Measure *median, p95, CPU time and peak RSS* (at least 20 measured samples)
with independent cold, warm, body-only edit, public-ABI edit and import-cycle
cases. Include 10, 100 and 1,000 file graphs; 100+ overloads;
deep/nested structural types; generic specialization fanout; and an error run
followed by a repaired run. Report parse count, typecheck count, rebuilt count,
LLVM generation and optimization separately. Compare to the same base commit,
identical hardware/targets and compiler flags. No speedup claim without results.

## Delivery sequence

1. Java parse cache + transactional invalidation and tests (this PR).
2. Parse-once typed/ownership admission after AST immutability proof.
3. Typed public-interface schema with explicit compiler/target/feature versioning.
4. Canonical type/trait/overload resolver with bounded solver budgets.
5. Generic binding/default resolution parity and specialization memoization.
6. LLVM interface reader, debug/release profiles and incremental object pipeline.
7. Cross-backend conformance, mutation tests, fuzzing, size/time regressions;
   then enable language restrictions under an explicit version transition.

Do **not** conflate accepting a syntax proposal with implementing it in LLVM.
An unsupported LLVM feature must continue to fail closed.
