# Typeclass and effect-system compiler foundation (draft)

> Status: prototype compiler libraries and JUnit contract tests only. **Not yet
> a new source-language feature.** Keep this PR in draft until the frontend,
> type checker, lowering, and exact-tree GraalVM CI are complete.

## Existing language features (do not fork their semantics)

- `trait` is currently parsed as an interface-like declaration. That does not
  yet provide Haskell-style instance resolution, implicit evidence passing,
  or coherence enforcement.
- `pure` has detailed prior design in [PURE.md](PURE.md), but current `main`
  does not yet contain the full `pure/trap/nlex` callable-scope implementation.
  Coordinate with #244 and integration successor #264; do not cherry-pick older
  parser/type checker wholesale or erase generator/ownership work.
- `trap` already has special compiler and runtime behavior; its exact ABI is
  being reconciled between existing branches and [TRAP.md](TRAP.md). It is
  **not** an ordinary user macro: a macro may *generate* trap syntax, but
  compiler-verified dynamic boundaries must lower the effect handler.
- `nlex` forbids lexical captures. Capture authority and effect authority
  are separate axes: a non-capturing function can still do I/O.

## First-phase Java implementation

`TypeclassRegistry` is a **compile-time-only** coherent instance table. It
requires explicit typeclass definitions, enforces arity, rejects orphans,
performs first-order type-head unification to reject overlapping instances,
ensures repeated generic variables match consistently, and fails closed for
missing evidence. The dictionary identity is a symbol, not a user-visible
class/module value or an actor-shared heap object. Instances are globally
unique for the relevant compilation namespace; no priority, specialization,
implicit structural instance, or overlapping fallback behavior exists.

`EffectContracts` is a **conservative** call-graph summary analysis with an
immutable finite effect vocabulary, fixed-point convergence through recursive
cycles, and declared upper-bound validation. Unresolved dynamic/FFI calls
introduce `UNKNOWN_CALL` unless a future trusted compiler boundary supplies
verified metadata. Call edges must refer to resolved, overload-specific symbols
in the eventual integration; textual name resolution is not performed here.
`pure` permits local computation and does **not** automatically assert
`nothrow`, termination, memoization safety, or `nopanic`.

A trapping **callee declaration** (not an untrusted caller assertion) erases
only an ordinary `THROW` effect when callers see its result. `RAISE`,
`PANIC`, `CANCELLATION`, other effects, and unknown interop remain visible.
The compiler must not allow user macros, imported ABI records, or reflection
to forge trusted trapped/pure flags.

## Source-language direction (illustrative, not accepted syntax yet)

```ores
// Proposed: compile-time typeclass interface with no data fields.
define trait Show<T> as
  fnc show(T value): String;
end

// Proposed: an explicit, coherent instance; no class or module is passed
// as a first-class argument.
impl Show<User> for User as
  fnc show(User value): String {
    return value.name;
  }
end

// Proposed generic constraint, elaborated to an invisible dictionary slot.
pub fnc describe<T: Show>(T value): String {
  return Show.show(value);
}

// Proposed effect upper bound (notation needs parser RFC).
@effects(io, environment)
pub fnc read_config(): Config { ... }

// Existing spellings keep their existing semantics and ABI.
pub pure fnc add(int x, int y): int {
  return x + y;
}
```

The examples are **future syntax**; this PR does not claim that they parse.

## Integration gates before ready for review

1. Represent `trait` separately from ordinary interfaces in the AST and
   preserve existing `interface`, `contract`, class/module, actor and
   recursive-type behavior. Introduce explicit instance declarations; attach
   a qualified typeclass identity and origin to each dictionary.
2. Elaborate bounded generic constraints to evidence parameters at compile
   time; enforce orphan/overlap checks across imports and incremental
   generations, including qualified names and versioned packages. Do not
   permit a typeclass to be passed as a class or module value.
3. Connect the effect graph to AST symbol resolution, parameter alias/escape
   analysis, imported effect metadata, the actor/sendability checker, and
   optimizer. Method overrides and higher-order function values must carry
   compatible effect upper bounds; unknown dispatch is forbidden in pure
   contexts without proof.
4. Preserve `pure`, `trap`, `nlex`, async/generator semantics in the
   current-main reconciliation of #264. Actor runtime ownership and
   `trap`/panic/cancel supervision boundaries stay compiler/runtime owned.
5. Allow macros only to *produce source* before semantic verification.
   Expansions, including AOT/JIT expansions, are rechecked in the target
   generation. No actor memory is read by macro expansion and no effect
   authority is conferred by a macro.
6. Add Oreslang source-level pass/fail tests for trait coherence, foreign
   instances, recursive evidence, imported ambiguity, pure transitive effects,
   dynamic calls, FFI, closures, actor boundaries, and trap result types.
   Run full Maven and exact-tree CI before changing PR to ready.

## Running the current foundation tests

```bash
mvn -B -ntp -Dtest=TypeclassesAndEffectsFoundationTest test
mvn -B -ntp verify
```

These are proposed test commands, **not passing-test claims**.
