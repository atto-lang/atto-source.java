# Error-type pattern matching: proof and backend audit (2026-10-22)

Status: compiler proof hardening and regression sources; not a claim that `std/error`
or cross-backend typed catches are released. The draft error taxonomy lives in
[#811](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/811)
and currently conflicts with `main`.

## Type/value matching is not catching

`match error over` dispatches a *value* by runtime nominal identity.
It never intercepts a control-flow `throw`, `raise`, or `panic`.
`Result<T, Error>` remains a value container: `Err(e)` can be
deconstructed without discharging an exceptional callable effect.
A typed catch or permitted trap must discharge `throws`; `raises` must
be handled by its own permitted recovery construct or propagated; `panics`
are **never** caught by ordinary user `try`/`catch` or `match`. An
actor panic is a supervisor CONTROL event, not a catchable `ActorError`.

## Static proof invariants

1. Canonical `match value over` is source-ordered. Specific nominal
   descendants must precede unguarded ancestors; a provably shadowed arm
   is a compile-time error. An earlier guarded ancestor is not unconditional.
2. Open `Error` hierarchies cannot be proved exhaustive by listing known
   concrete error subclasses or published numeric codes. Admit a proven
   unguarded ancestor covering the **entire static subject type** or an
   explicit `on _` fallback. New application error subclasses are legal.
3. Exhaustiveness, reachability and the evaluator must agree on *runtime
   membership*, not assignment compatibility. Widening coercions, shape-only
   similarity and code equality cannot prove nominal error identity.
4. Nominal interfaces such as `ActorApiError` and `RetryAdvisory` match
   only declared, transitively inherited interface membership; a class
   with equivalent methods or numbers must not match by duck typing.
5. Guarded arms are never sufficient for completeness. A guard must be
   read-only/pure; arbitrary function effects and suspension in guards
   remain a separate admission audit until enforced across all lowering paths.
6. A matched error value does not authorize exposing its guest trace, cause,
   or private fields across actor trust boundaries. Matching never grants
   capability access or bypasses ownership rules.
7. Nested `Option`/`Result` patterns require recursive payload coverage;
   `Err(FileNotFoundError)` plus `Ok(_)` does not cover other `Err(Error)`
   values. An unmatched case is a compile-time proof failure in required
   exhaustive contexts, **not** an implicitly catchable PatternMatchError.

## Proof hardening in this change

`TypeChecker.patternCoversType` previously checked a type pattern with
`typesMayOverlap(subject, target) && assignable(subject, target)`.
Assignment compatibility is more permissive than runtime type membership.
It now uses the existing conservative `runtimeValueTypeCovered` proof
(shared with arm subsumption), and excludes negative type patterns from
positive irrefutability proofs. This is intended to reject questionable
programs, never broaden what counts as exhaustive.

The new `PatternMatchingHardeningTest` regressions use an explicitly
declared local Error-like hierarchy so they run against `main` without
implicitly treating draft `std/error` as released. They cover open-world
incompleteness, ancestor shadowing, guarded ancestor ordering, and guest
runtime dispatch. Actual imported `std/error` cases are separately in
`StdErrorCatalogTest` on #811.

## Verification and integration gates

Run on the **exact head SHA**:

```sh
mvn -B -ntp -Dtest=PatternMatchingHardeningTest,ValueMatchExpressionTest,TypedExceptionalEffectAdmissionTest test
mvn -B -ntp verify
```

After reconciling #811 against current `main`, run its imported catalog
and nominal interface test suite as well:

```sh
mvn -B -ntp -Dtest=StdErrorCatalogTest,PatternMatchingHardeningTest,TypedExceptionalEffectAdmissionTest test
```

Do not claim LLVM/Wasm/.NET/JS parity from Java source checks. Confirm
runtime type tags, transitive class and interface ancestry, module-unique
nominal identity, guarded priority dispatch, open-world fallbacks, and
`Result.Err` payload lowering separately on each target.

Additional unresolved audit targets: enforce guard purity in typechecking,
validate imported module identities against same-name impostors, confirm
patterns cannot move/borrow error payloads unsafely across suspension,
verify nominal interface matching across imported linked units, and ensure
host exceptions/supervisor CONTROL are never implicitly converted into
catchable guest Error values. Keep this change in draft until tests execute.
