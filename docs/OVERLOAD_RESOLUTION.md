# Callable overload resolution — current contract and next type-directed phase

> **2026-10-17 amendment:** See [ENUM_VARIANT_OVERLOAD_EXTENSION.md](ENUM_VARIANT_OVERLOAD_EXTENSION.md) for a later proposed **static case-type specialization** phase (`Foo.A <: Foo`). The preexisting case prohibition below describes the older nominal-enum-only target; it is not the latest variant proposal. Runtime and linked Java/LLVM source admission remain unchanged until executable end-to-end parity is proven.

Status: **name/arity dispatch executable on Java; same-arity nominal enum
selection is a staged extension, not generally executable.** Relevant implementation and test
coverage: `TypeChecker`, `OwnershipChecker`, `CallableSelector`,
`OresEvalRootNode`, `MethodOverloadTest`, `FunctionArityOwnershipTest`.
Typed dispatch implementation target: [issue #843](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/843).

## Currently executable

A module has one callable family for each name. `fnc` and `routine`
occupy that **same family**; `pick()` and `pick(int)` are distinct
slots, regardless of declaration kind. `fnc pick(int)` and
`routine pick(float)` conflict because both have arity 1. Parameter type,
return type, generic parameter count/name, exception effects, async/suspend
effects, and callable kind cannot create a new same-arity slot.

Class **instance methods** and **static functions** occupy distinct
dispatch domains, with each domain currently keyed by (name, explicit
argument arity). The implicit `self` receiver never counts as an argument.
Inherited same-slot overrides obey signature/visibility/ownership rules.
Independent parents defining the same slot must be resolved explicitly;
inherited overloads with different arities remain available.

After selecting the overload by arity, its primitive, class, union, generic
and other parameter types are **checked positionally**. A mismatch is a
compile-time diagnostic; selecting a different same-arity overload by type
is not allowed in the current implementation. An overloaded callable
extracted as a function value needs an expected signature or explicit
selection; bare unknown-arity extraction cannot guess.

**Ownership invariant:** each pass must select exactly the same callable
declaration. In particular a moved class/enum-like value cannot be treated
as a harmless primitive argument because ownership lookup chose a different
same-name function. The ownership pass now keys module function/routine
signatures by the same arity as the compiler and runtime.

## Canonical future extension: nominal enums only (not yet generally enabled)

The authoritative pre-1.0 decision is
[NOMINAL_ENUM_OVERLOAD_CONTRACT.md](NOMINAL_ENUM_OVERLOAD_CONTRACT.md).
The earlier plan to differentiate `fnc f(int)` and `fnc f(float)`, rank
primitive conversions, or choose by numeric widening has been **superseded**.
Do not activate that behavior from the experimental
`TypedOverloadResolver` helper or issue #843. Its ranking API is an older
design oracle, **not** the overload-admission or runtime dispatch contract.

An executable enum-aware slot will be identified by its resolved declaration
namespace, instance/static domain, callable name, explicit argument arity, and
the compile-time selected canonical signature ID. `fnc` and `routine`
share a domain; return types, effects, generic names, source order, and enum
*cases* are never dispatch discriminators.

Admission must happen before executable dispatch is enabled:

1. Select scope/receiver and expanded explicit argument **arity first**.
2. For a same-arity pair, require at least one corresponding parameter with
   **distinct, eligible nominal enum declaration IDs**; every other
   parameter must have the same canonical admitted type ID. Primitive type
   differences alone, numeric widening, structural compatibility, generics,
   unions and `Any`/`Unknown` do not admit a pair.
3. Each enum discriminator's complete case set must have only nullary or
   primitive-scalar/fixed-primitive-tuple payloads, as defined by the
   canonical contract. Enum case names and numeric wire tags are irrelevant.
4. Resolve the unique candidate from compiler-proven nominal identities.
   Ambiguous/incomplete static type proof must fail closed. Validate normal
   argument assignability, ownership, effects and visibility **after**
   selection; those checks cannot rank a different candidate.
5. Carry an immutable selected declaration ID through the type checker,
   ownership checker, interpreter/direct call, optimizer, method vtable,
   imports, FFI and native/AOT lowering. Never dynamically scan candidates
   by a runtime value, payload, structural shape or enum variant.

For example, `f(Color)` and `f(Direction)` may become legal when both
enum declarations are eligible and carry different qualified IDs. In
contrast, `f(Color.Red)` and `f(Color.Blue)` are not separate overload
types: both represent `Color` and belong in a `match`.

The Java experimental direct-call implementation is stacked on unmerged
enum work; class method vtables and LLVM guest execution are not yet
equivalent. Existing stable production code must continue rejecting
same-arity enum overloads until its own proof and binding path are complete.

## Parity prerequisites

- Java TypeChecker/OwnershipChecker and Truffle's AST/vtables agree for
  fnc, routine, instance method and static fnc calls.
- Java/Graal JIT, Native Image AOT/hybrid, and LLVM select the same source
  declaration on shared conformance fixtures.
- Inherited, overridden, interface-bound, actor and async calls preserve
  chosen overload identity, argument conversions and ownership effects.
- Static callback extraction has contextual signature selection.
- Declaration-pair disjointness and ambiguous-proof property tests cover
  primitive-only negative cases, eligible nominal enums, fixed primitive
  payload tuples and cross-module identical short names.
- Recursive, borrowed, dynamic, and generic enum payloads must be rejected
  as overload discriminators while remaining usable in ordinary enum/match;
  serializer and message boundaries must preserve nominal ID provenance.
