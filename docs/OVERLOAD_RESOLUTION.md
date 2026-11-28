# Callable overload resolution — current contract and next type-directed phase

Status: **name/arity dispatch executable on Java; primitive/enum overloads
with identical arity not yet enabled.** Relevant implementation and test
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

## Future type-directed overloads (not yet implemented)

Proposed stable key for a distinct overload is:

```
(declaration namespace, static-or-instance domain, function name,
 expanded explicit argument arity, canonical admitted parameter-type IDs)
```

The `fnc`/`routine` kind remains **not** a discriminant; neither does
the return type, overload source order, effect type, or generic parameter name.

Candidate resolution must use static types before runtime dispatch:

1. Filter by callable namespace, receiver domain and arity.
2. Reject candidates with unsatisfied accessibility, ownership, effects
   or type constraints before allowing them to become runtime symbols.
3. Rank exact primitive and enum **type** identity above safe numeric
   widening. Example: `f(int)` wins over `f(float)` on a known int
   argument, but float wins on a known float argument.
4. Where conversions tie, fail with an ambiguity diagnostic listing each
   candidate. Never silently use source-order, callback identity, tag shape
   or return type as a tie breaker.
5. Emit a resolved, versioned ABI symbol and carry it through ownership,
   Truffle, native lowering and all method-vtable/inherited slots.

`f(Color)` and `f(Direction)` are future distinct overloads because
enums are **nominal declarations**. `f(Color.Red)` and `f(Color.Blue)`
are not: those are variants of the same parameter type `Color` and
belong in `match`. Overloading on different erased generic
`Option<int>` / `Option<String>` (or `Enum<int>` / `Enum<String>`
without runtime reification) is forbidden. Partial `Any`/`Unknown`
static types, mixed numeric promotions (e.g. multiple equally ranked
widening paths), and structural interfaces must fail closed until
precisely defined.

No constructor is selected from the actual message's variant tag as a
substitute for static typed overload resolution; actor message ownership
and immutable type descriptors must be checked first. Runtime dispatch
may use only a compiler-proven selector, even on GraalVM where the JVM
would normally erase generic payload parameters.

## Parity prerequisites

- Java TypeChecker/OwnershipChecker and Truffle's AST/vtables agree for
  fnc, routine, instance method and static fnc calls.
- Java/Graal JIT, Native Image AOT/hybrid, and LLVM select the same source
  declaration on shared conformance fixtures.
- Inherited, overridden, interface-bound, actor and async calls preserve
  chosen overload identity, argument conversions and ownership effects.
- Static callback extraction has contextual signature selection.
- Exact-match/widening/ambiguity/property-based tests cover primitive
  tuples, multiple nominal enums and cross-module identical short names.
- `Box<T>` recursive enum payloads and serializer tags must be executable
  and independently verified before type-specific enum overloads are enabled.
