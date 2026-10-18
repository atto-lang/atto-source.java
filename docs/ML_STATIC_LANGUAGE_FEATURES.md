# ML-family features for Oreslang: static-signature and full-AOT contract

Status: language decisions and implementation roadmap. The `signature` spelling is
implemented in the Java frontend on this branch. Other features below are **not**
implicitly implemented by this document or by accepting example syntax. The LLVM
backend must fail closed until each guest feature has verified lowering.

## Non-negotiable closed-world invariants

1. A module is a static namespace/declaration group, never a runtime value.
2. A class is statically declared; class **instances**, not class definitions, may be
   allocated during execution. Actor instances may be spawned from compiled actor
   declarations. No source `eval`, dynamic class/module creation, or module
   instantiation is admitted to the full-AOT guest profile.
3. Module interfaces, static-class interfaces, and ordinary instance interfaces
   remain three distinct semantic categories.
4. A full-AOT artifact has a fixed graph of reachable code and declarations,
   complete with capabilities, effects, ABI identities, and type layouts. Runtime
   value allocation, closed-world virtual dispatch, message routing, and closure
   environments are allowed. Runtime code generation/guest interpretation is not.
5. Hot upgrade in an AOT deployment requires separately precompiled and verified
   artifacts; development-time Truffle guest JIT is a distinct build profile.

## `signature` and existing `contract` compatibility

`signature` is a **strict source-level synonym** for `contract`, not a fourth
kind of interface, not an object type, and not a replacement checker.

```ores
pub define signature Storage as
  fnc read(string key): Option<string>;
end

define module MemoryStorage conforms Storage as
  pub fnc read(string key): Option<string> { /* implementation */ }
end

pub define static signature Factory as
  fnc create(int id): int;
end

define class Widget implements static Factory as
  pub static fnc create(int id): int { return id; }
end

import signature Storage from "./storage.ores";
```

The examples show intended spelling and category relationships; member bodies
are abbreviated rather than executable fixtures.

Accepted declaration permutations mirror `contract`, including
`pub define signature`, `define static signature`, and the legacy
`signature define` spelling. `import signature` is normalized to
`ImportKind.CONTRACT`, so linked provenance, aliasing, incremental ABI
invalidation, and type-only import rules are identical. The parsed AST remains
`InterfaceDecl(moduleContract=true, staticContract=...)`. No runtime
signature value or table is generated. A matching name declared once as
`contract` and once as `signature` remains a duplicate.

An ordinary instance `interface` is still distinct. A module may
`conforms` only a module contract/signature; a class's static members may
`implements static` only a static contract/signature. An actor must not
acquire an implicit independent static-storage domain. Existing class-static
field ownership, mutability, variance, initialization, and linking rules apply
unchanged.

The word `signature` is reserved globally as a keyword. Introducing it may
require renaming a previously valid variable called `signature`.

## ML feature program: semantics and admission gates

| Feature | Source precedent | Oreslang rule | Implementation gate |
| --- | --- | --- | --- |
| Exhaustive patterns and ADTs | OCaml/SML | Closed constructor sets; prove coverage and overlap, refine branches, track moved/borrowed payloads; opaque guards cannot count as coverage | Parser, solver, ownership CFG, interpreter/native decision DAG |
| Bidirectional local inference | ML | Infer local bindings, lambda arguments and generic substitutions only where unambiguous; public boundaries explicit; no type-based overload selection; ML-style value restriction for effectful/mutable generalization | Constraint solver + overload/ownership negative tests |
| Static signatures | SML/OCaml | Compile-time synonym of existing module or static-class contract; never first-class | **Java frontend syntax implemented**; existing conformance checker reused; LLVM guest admission pending |
| Abstract representation types | SML | Nominal opacity outside signature, unforgeable importer provenance; no reflective back door | Symbol/linker/typechecker ABI tests |
| Type-indexed protocols, GADTs | OCaml | Closed-world constructors and compile-time refinement; owned transitions linear/affine; actor's one `state` schema unchanged | Exhaustiveness, unification, move checking, erasure layout tests |
| Option/Result combinators | ML/F# | Tagged values with typed errors; no implicit exception or void coercion | Stdlib implementation plus native/Graal differentials |
| Immutable persistent collections | ML | Immutable sharing only with proven ownership, sendability and allocator contracts; `core` remains freestanding | Native arena/zero-tracing tests, amortized cost and drop tests |
| Static effects and handlers | OCaml 5-inspired | Closed effect vocabulary/rows, transitive effects through callbacks and FFI; no capability forging; one-shot ownership-safe resumptions; `throw`/`raise`/`panic` distinct | Effects+call graph, state-loan tests, continuation state-machine AOT lowering |
| Parametric polymorphism | ML | Compile-time generic substitution or precompiled closed-world dictionary/dispatch; no runtime type/class definition | Cross-import coherence + monomorphization/ABI evidence |
| Tail calls | SML/OCaml | Proper self-tail-recursive lowering where possible; general tail-call guarantee requires a backend/ABI proof | Deep recursion and stack-usage tests on both AOT backends |
| Polymorphic variants/row polymorphism | OCaml | Bounded, closed-world, ABI-stable row sets; explicit versioning/unknown tag handling at transport boundary | Canonical tags and sound exhaustiveness tests |
| Active patterns | F# | Pure, non-suspending, bounded, ownership-safe destructors; totality evidence required for exhaustiveness | Match solver and lifetime proof fixtures |
| Units of measure | F# | Compile-time dimension algebra on numeric values, erased in runtime ABI, explicit conversion for scaled units | Dimensional solver and cross-backend layout tests |
| Schema/type providers | F# | TypeSpec/JSON Schema/WIT providers run at **build time** only; generated types independently checked against peer schema authorities; digest-bound receipts | Reproducible provider sandbox, provenance and schema-drift tests |
| Functional composition/pipelines | F#/OCaml | Library-first combinators; evaluation order, actor suspension and effects preserved | Existing value-pipeline regression suite and differential fixtures |

### Design constraints

- Do not add OCaml-style **first-class modules**, dynamically instantiated
  functors or runtime-created module objects. Optional functor-like source
  templates must be instantiated/linked before execution.
- `Any` and erased `AnyCallable` remain non-invocable without a concrete
  callable type; type inference must not fabricate dynamic invocation.
- No implicit capture of instance `self` in lexical lambdas.
- Actor state is accessible only through verified state-loan/critical APIs;
  continuation suspension never preserves invalid loans.
- `pure` means observational purity; it does not imply no allocation,
  no throw, bounded execution, or termination.
- Module/static signatures are contracts, **not** mutable global state or
  implicit capability grants. Static initialization effects must be proven.
- Schema providers cannot execute in serving-process address space in AOT.
- All source extensions require identical program meaning across GraalVM and
  LLVM; a backend not supporting a feature must reject it before emitting IR.

## Verification milestones

1. **Syntax and aliases:** parity of `contract` and `signature` AST, imports,
   local/nested declaration spelling, category errors, duplicate identities,
   class-instance permission and module-instance rejection. This branch adds
   `StaticSignatureSyntaxTest` for Java.
2. **Semantic proofs:** error-code-oriented positive/negative fixture matrix for
   exhaustiveness, row refinements, borrow/move after pattern, polymorphic
   value restriction, public API inference boundaries and call-graph effects.
3. **Static linkage:** imports of opaque types, ABI diff/invalidation, FFI
   signature trust, generic specialization, AOT dependency-closure manifests
   and zero-runtime-compiler assertions.
4. **Native execution:** feed identical guest fixtures through Java/Graal and
   LLVM where implemented; execute compiled outputs; verify trace, output,
   resource drops, scheduling and failure equivalence.
5. **Security/robustness:** malformed sources, forged metadata, annotation
   expansions, hot-upgrade ABI mismatch, unsafe callbacks, unresolved indirect
   calls and schema-provider nondeterminism must fail closed.

Never equate a passing parser test, Java host native-image smoke test,
or host-only LLVM helper test with true Oreslang guest full-AOT parity.

## 2026-10-18 audit: incomplete namespace-value confinement

The `signature` spelling normalization does **not** itself prove that module
namespaces are never reified as values. The Java typechecker currently resolves
a bare module name to a structural module shape, and the interpreter uses an
internal namespace facade when evaluating a module name. This is an outstanding
closed-world soundness gap until the compiler rejects module names in all
value positions while preserving direct static member access and lexical
shadowing. `StaticModuleNamespaceConfinementTest` deliberately records this
regression as a strict admission gate. No passing CI receipt exists for these
new tests; PR #1386 must remain draft until the gate is repaired and executed.

The prior exact-source private mirror represented the previous commit only.
New audit/test commits invalidate its exact-head test provenance. Never use
an older successful tree hash to claim that a newer branch was tested.

## 2026-10-18 namespace confinement implementation (unverified)

Java frontend now has an internal `Types.ModuleNamespace` marker, disallowed in
normal assignability (including the unknown/generic cases). `TypeChecker`
rejects bare local module names and imported module / wildcard namespace aliases
as runtime values. Explicit direct member receivers consult module conformance
shapes through `typeOfStaticNamespaceReceiver` instead of widening namespaces
to structural records in every expression context. Lexical locals take priority
over same-named static namespaces.

Additional adversarial fixtures cover value bindings, function arguments,
return values, tuples, Option payloads, imported aliases and shadowing.
These are committed *tests*, not a validated test receipt. Do not claim these
changes solve all compiler or interpreter escape vectors until full Maven/JDK25,
ownership and linked-module tests execute on the exact commit. Native backend
admission remains fail-closed until guest lowering is implemented.
