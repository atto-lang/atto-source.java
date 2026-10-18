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

## Nominal enum extension (not enabled in source dispatch)

The normative policy is [NOMINAL_ENUM_OVERLOAD_CONTRACT.md](NOMINAL_ENUM_OVERLOAD_CONTRACT.md),
which supersedes the earlier primitive-ranking proposal in issue #843.

1. Resolve namespace, receiver domain, name and expanded explicit arity first.
2. Validate every pair in the selected same-arity family. Each differing
   position must contain distinct eligible nominal enum declaration IDs;
   all other positions must match exactly. Primitive differences invalidate
   the family even when another position differs by enum.
3. Require a static enum identity proof at every differing position. `Any`,
   `Unknown`, enum cases, payload values and numeric conversions cannot
   establish these proofs. Reject an unprovable or nonunique choice.
4. Bind the declaration, then check every argument's assignability,
   accessibility, ownership and effects. These checks cannot select another
   overload. A single arity slot needs no type-based selection.
5. Carry the resolved symbol through ownership, callbacks, imports, virtual
   override tables and every backend. Only exact-signature virtual overrides
   may vary with the dynamic receiver.

`TypedOverloadResolver` is a compiler-side model, not a source feature.
Its selection API now requires a registry of compiler-verified eligible
nominal enum IDs. The caller must validate complete declared enum payloads
before registering them; parsed names and runtime tags are not proof.
The model does not implement that declaration analysis or enable source
same-arity declarations. Primitive argument types remain useful for signature
identity and subsequent compatibility checks, but never rank candidates.

## Remaining release gates

Source enum dispatch remains disabled until declaration identities, complete
payload eligibility, resolved call IDs, ownership, imports, callbacks,
inheritance and native lowering are implemented together. Verify actual
compiled execution on every supported backend before claiming parity.
Current JUnit interpreter execution and host policy tests do not prove
optimizing Graal JIT, Native Image, native enum ABI or actor-wire parity.
