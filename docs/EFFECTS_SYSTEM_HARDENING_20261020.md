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
