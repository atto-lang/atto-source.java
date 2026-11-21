# Oreslang effects: soundness audit and staged contract (2026-10-20)

**Status:** design/audit plus one Java static-graph hardening slice. This is not
a claim that the Haskell-style `IO` type, effect handlers, effect polymorphism,
or complete `with` / `using` source-to-machine lowering already works. Keep
this change stacked on Java PR #1604 until the formal graph work is merged.

## Existing components and blockers

- Java `EffectContracts` (merged foundational PR #477) models only conservative
  compiler-supplied effects and call edges. It is **not** wired to all typed AST
  / HIR call sites in current `main`; no runtime Haskell effect interpreter exists.
- Java draft #244 / successor #264 contain callable `pure`, `trap`, `nlex`
  work. Java drafts #760 / #765 and later integration #774 describe canonical
  `with` effect bounds, scoped `using` state loans, and `critical` exclusivity.
  They are not evidence of a fully merged frontend. Do not silently merge their
  older parser ASTs over newer generator, actor, and ownership changes.
- Java draft #1290 separately proposes checked `throws`, `raises`, `panics`
  signatures, **without** verified runtime unwind lowering. The `trap` handler
  masks ordinary `THROW` only, not `RAISE`, `PANIC`, cancellation, unknown
  external effects, or any I/O already performed before a throw.
- LLVM draft #257 / successor #264 cover native state/effect admission. Runtime
  refinement, executable guest parity and cross-backend tests remain required.

## The semantic axes must remain distinct

| Axis | Contract |
| --- | --- |
| Static effect row | `with io, state.read, suspend` is a checked upper bound on what executing a call may do, never a grant of permission. |
| Capability | A lexically acquired `using state.read` or `using state.write` is runtime authority tied to a live actor/loan epoch. A declaration cannot forge it. |
| Exclusivity | `critical` controls single-flight admission, not an implicit effect nor permission for I/O/blocking. |
| Purity | `pure` forbids observable effects (I/O, ambient mutation, clock, random, actor send, scheduler interaction, unknown calls); locally owned allocation/mutation and exceptional control need precise rules. |
| Exception/supervision | Ordinary `throw`, `raise`, `panic` and cancellation have distinct propagation and trusted handlers. `trap` cannot catch process/runtime failure or cancel a sibling task. |
| Suspension | `await`, blocking channel/select, backpressure and host callbacks must carry suspension/blocking facts independently of `IO`. No live exclusive state/borrow loan may cross a forbidden suspension. |
| Haskell-style action | Creating a lazy/deferred `IO<T>` action may be pure only if its captured environment is legal. **Executing** it requires its effects and capabilities. |
| Algebraic handler | A handler may discharge only explicitly declared, compiler-checked user-defined operations; it cannot eliminate ambient effects, `panic`, cancellation, `raise` or actor-control events by assertion. |

## P0: unresolved call edges are never a symbol lookup

Previously the graph modeled every call as a string target. A dynamic callback,
reflection call or foreign invocation reported as `Call.direct("x")` could be
incorrectly treated as pure if the graph happened to contain a pure declaration
named `x`. This makes the call graph's soundness depend on accidental name
collisions, independent of trap handling.

The hardened library has two explicit kinds:

- `Call.direct(qualifiedSymbol)`: use **only** after trusted overload and
  dynamic-dispatch resolution, and reject missing bindings conservatively.
- `Call.unresolved(label)`: a diagnostic label for callbacks, dynamic dispatch,
  FFI/import gaps, reflection, or any other unproven target. **Always** produces
  `UNKNOWN_CALL`, even if `label` matches a defined pure symbol.

A source compiler MUST mark unresolved invocation sites as unresolved, never
silently synthesize a supposedly direct target. A dynamic dispatch with a
proven, closed target set may use a compiler-generated union/synthetic node
with individual authenticated edges; an open target set requires
`UNKNOWN_CALL`. `trap` must not erase `UNKNOWN_CALL`.

The library also rejects control/Unicode-format characters in graph symbol
identities and unresolved labels to prevent log/witness spoofing. These labels
are not interpreted as source identifiers or authority.

This change narrows an interface-level laundering risk; **it does not
authenticate compiler-produced `Call.direct` edges**. Source/IR witness
construction remains a mandatory independent gate.

## P0: source-to-effect-IR refinement

Before treating a `pure` or `with` annotation as enforced, derive effects
from a **typed and ownership-checked HIR**, not ad hoc parser tokens:

1. Lower every direct call, overloaded method, constructor, macro expansion,
   operator overload, implicit destructor/defer/finally, getter/setter,
   trait/typeclass dictionary call, actor message send, FFI, and runtime
   builtin. Give each a stable resolved symbol or fail closed.
2. For higher-order calls, preserve the callee's effect upper bound on
   `Function<Args, Result, Effects>` (or an equivalent sealed callable
   descriptor). The absence of a bound is `UNKNOWN_CALL`, not `pure`.
3. Infer strongly connected call groups, including nested closures and
   polymorphic instantiations, with bounded diagnostics and conservative
   open-world calls. Validate overrides, exported ABI, and imports against
   upper-bound variance rules: an override may require **no more** effects.
4. Macros do not certify purity: expanded HIR is rechecked; FFI metadata
   must be authenticated and version/digest bound; uncategorized externals
   remain unknown. Inlining and AOT optimization preserve effect facts.
5. A `pure` wrapper does not erase effects by returning a closure, `Future`,
   `Task`, `Observable`, `Stream`, or `IO` action that will later run.
   Store latent effect rows on callable/action values; check effects again
   at invocation/subscription/await/execution boundaries.

## Haskell interoperability, without conflating concepts

Haskell's `IO a` is an effectful computation description sequenced by bind;
it is not the same thing as algebraic effect handling or the declaration
`with io`. Keep `IO<T, E>`/effectful action value **library level** until
there is a sound latent effect/capture representation. Do not pretend an
action construction or `map` performs I/O. Conversely, do not allow an action
to be run in a `pure` scope without checking E.

Introduce user-defined algebraic operations/handlers only after static
builtin-effect enforcement and actor-loan fences are complete. Delimited
continuations are **one-shot** by default; multi-shot requires an explicit
copyable, isolated continuation proof. A continuation must not escape its
handler/actor epoch, retain a live critical/borrow/state loan, or resume after
cancellation/retirement. Cancellation and supervision are **not** ordinary
user-handled algebraic operations.

Effect polymorphism (e.g. an abstract row variable `e`) must be represented
nominally in typed IR, with deterministic substitution, occurs checks,
principal or explicitly bounded inference, and effect-safe higher-order calls.
Absence of an effect variable instantiation is not the empty row.

## Runtime and cross-backend requirements

- GraalVM and LLVM must agree on source acceptance, resulting effect rows,
  runtime capability checks, and the trap/raise/panic/cancellation taxonomy.
  Unsupported backend lowering rejects at compile time; it never silently
  compiles a missing effect into a no-op.
- Awaiting/registration with channels, `select`, ActorRef, streams, and
  `Future` must account for real suspend/block/IO costs and recheck actor
  permission on continuation re-entry.
- Both callback delivery modes (inline/deferred) and asynchronous closures
  need explicit non-escape, borrow, actor, and effect proofs.
- In actor classes persistent user data lives only in `state { ... }`; any
  state read/write is through verified `handle_state` / lexical scoped
  `using` plus the appropriate `critical` exclusion. A `with state.write`
  signature cannot acquire write authority by itself.

## Verification ladder / merge gates

- **Now**: JDK21 `tools/formal-standalone/verify-effects.sh` must pass
  98,304 bounded graph roots plus unknown-dispatch collision and trap
  controls. JUnit `EffectGraphAdmissionBoundaryTest` and full Maven must
  compile/run on this exact head. These are library/model tests, not
  source-language soundness evidence.
- **Next**: adversarial source tests for indirect callbacks, virtual
  dispatch, trait dictionaries, generic effect variables, macro-expanded
  side effects, imported FFI, effect-bearing Future/Stream/Observable values,
  exceptions during `finally`/`defer`, no-block critical regions, and
  actor-local state-loan capture/escape.
- **Then**: differential typed-HIR effect receipts (Java vs LLVM), including
  negative results, recursive SCCs, cancellation, actor cross-domain
  handoff, Graal Native Image and actual LLVM native execution.
- **Finally**: introduce effect-polymorphic action types, then user-defined
  algebraic effects/handlers (linear resumptions first), with model/runtime
  refinement. Do not mark a foundational PR ready based only on static
  graph tests or compiler frontend parsing.

One repository-specific CI constraint: any external-org GitHub Actions carrier
must mirror the **exact** private source Git tree, verify all blob/tree SHAs,
and run compilation/tests on that immutable revision. A workflow that merely
runs without checking out the matching source is not evidence of correctness.


## Round 2: opt-in source-front-end fact extraction

`SourceEffectAudit.inspect(source)` runs the normal
`OresCompiler.parseAndTypeCheck` (including ownership admission) before
classifying the checked AST. This is the first source-derived, **strict
overapproximation**, rather than an externally supplied graph:

- Literal-only bodies may produce an empty effect set.
- All calls (even obvious same-file call spellings), operator expressions,
  constructors, lambdas and unknown expression forms produce `UNKNOWN_CALL`.
  No guessed name lookup is treated as a resolved target.
- Names/member/index reads are conservatively ambient; assignments
  conservatively external writes; waits, channels and selects carry scheduler/
  synchronization/actor facts; unsupported declarations/imports invalidate
  whole-program `conservativelyEffectFree()`.
- Reflection over compiler-owned immutable AST records visits children, with
  strict node/depth budgets; a failed reflective read adds `UNKNOWN_CALL`.
  Deferred, handler and lambda bodies are included, never silently skipped.
- Runtime calls carry `FFI_UNKNOWN` as well as `UNKNOWN_CALL`. Neither
  category can be granted by a caller's `allowed` row: `Graph.validate()`
  rejects attempted declarations containing either.
- The legacy `new Call(label)` constructor is now conservative and unresolved;
  only explicit `Call.direct(verifiedTarget)` expresses a resolved edge.

This early scanner is **not** typed-HIR source-to-effect-IR refinement, effect
polymorphism, authorization for production codegen, or optimized operator
classification. In particular, `conservativelyEffectFree()` is only an
advisory closed-subset fact; it is not attached to Oreslang's ordinary compiler
admission, and it is not an artifact/signature that should be trusted by an
isolate supervisor.

## Verification progress / provenance

The Java source audit and new JUnit regressions still require a full exact-head
Maven/Graal run. A focused test-org mirror at
`zed-pkg-test/java-app` draft PR #225 stores exact Git blobs with SHA checks,
but **no workflow run had been recorded** at the time of this edit. The LLVM
`FunctionEffects.hpp` header was independently reconstructed byte-for-byte
and matched Git blob `c0f75dae6908ed1f844c780dcb7df2915149b249`
locally; a separate representative C++ smoke test passed all 1,048,576
declared/inferred valid-row pairs and all 1,024 unresolved-call denial cases
under GCC and Clang, debug and NDEBUG/release. This is narrower than running
the repository's actual `function_effects_test.cpp` or its complete native CI.


## Round 3: compiler gate, Haskell-style latent rows and cross-backend ABI

`OresCompiler.requireConservativelyEffectFree(source)` is a new **opt-in**
closed-subset admission path. It performs the existing compiler parse,
type and ownership checks, obtains a source effect report, and rejects on
any nonempty effect row or unsupported declaration. It explicitly does not
change default compiler acceptance or enable `pure` for general Oreslang
source. Literal-only functions are accepted; all calls (including apparently
same-file calls), unproven operators, unverified annotations, routines,
imports, unknown AST nodes and runtime-affine constructs are denied until
qualified HIR resolution/evidence exists. No empty source file receives a
vacuous purity certificate. Scanner budgets now cover the **whole** source
unit rather than resetting on each function, with a 1,024-declaration cap.

`EffectContracts.Effect` now includes explicit `STATE_READ`,
`STATE_WRITE`, `SUSPEND`, `BLOCK`, `ALLOC` and `FFI` vocabulary
distinct from older Java-only approximations. `EffectAbiV1` maps exactly
the 10 reviewed native `FunctionEffect` flags and rejects all
unrepresentable Java facts instead of dropping them: particularly unknown
calls/FFI, actor/scheduler facts and cancellation. The 1,024 legal native
masks round-trip by tests and JDK-only oracle. **A representable effect set
is not itself proof of complete compiler source-effect inference.**

`LatentEffectRows.Descriptor` is a compiler-side contract for callable,
`IO_ACTION`, Future, Stream and Observable values. Constructing a deferred
value does not invoke its latent effects. `compose` (map/bind) and
`as(kind)` preserve those latent rows; execution must check the row
against the enclosing declaration. Publicly unverified descriptors carry
`UNKNOWN_CALL` and can never be admitted as pure. Non-owned captures
cannot be invoked across suspension or into deferred work. This is a
**compiler metadata model, not executable Haskell IO, actor loan minting,
or a runtime continuation safety certificate**; full closure/capability
admission remains outside the model.

Verification: exact Git blob SHAs for **six** focused Java/native source
files were compared between the upstream branches and the funded test
repository PR #225; all six match, including the 1,024-row native tests
and bounded JDK Java graph oracle. These are focused mirrored files, **not
the full private trees**. An Actions run has not yet been observed for the
test PR, so no exact-CI test success is claimed for Round 3. Source-level
JUnit tests, full Maven, GraalVM Native Image, and compiled LLVM guest
execution remain required before merge.

## P0 work that remains

1. Have the compiler's **typed symbol-resolution pass** emit an
   authenticated direct-call ID/effect row for each call, method dispatch,
   constructor, overloaded operator and dictionary; keep every unproven
   call unresolved. This is what allows eliminating false-positive
   `UNKNOWN_CALL` facts safely.
2. Carry `LatentEffectRows` in actual callable/generic/Future/Stream/IO
   types and ensure suspension/cancellation/actor epochs are enforced by
   real runtime admission; the model's `requireInvocationWithin` checks
   declarations only and grants no capabilities.
3. Align Java `with` effect declarations, scoped `using` loans, LLVM
   `FunctionEffects` and checked exceptions only after the draft
   parser/AST stacks are semantically rebased onto their current main
   branches; do not merge obsolete stacked branches independently.
4. Run full exact-tree Maven, Java Native Image, LLVM CTest and actual
   guest execution on available funded CI, plus source-to-typed-HIR
   differential positive/negative effect witnesses.
