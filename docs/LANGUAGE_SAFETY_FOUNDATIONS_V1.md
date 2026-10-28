# Oreslang language safety foundations v1 (pre-1.0 breaking contract)

**Decision date:** 2026-10-27. **Status:** normative design decision / implementation and validation NOT established. **Scope:** source semantics for all backends (Graal/JVM host, LLVM, .NET, WASM and future targets). This document is not executable conformance evidence. It supersedes contrary proposed *semantics* in older RFCs; it does not itself change compilers.

## Foundational independence

Oreslang is its own language, type system, standard library, memory model, scheduler and ABI. No Java language, reflection, object model, exceptions, collections, futures, nulls, classloading, bytecode, JVM package resolution or foreign Java libraries may be assumed by an Oreslang program, its core stdlib, its native ABI or its semantic tests. A Java-based compiler/runtime implementation is an implementation detail, **not Java interoperability**. Existing `java:` imports/`JAVA_INTEROP` capabilities in the Java host are legacy or optional host-adapter surfaces, not core semantics; they must not silently enter the default source profile. Deprecation/removal and adapters need separate, reviewed migrations, not an undocumented instant compatibility break. LLVM/.NET/WASM are not expected to emulate Java APIs.

## Strict evaluation and task lifetime

1. Ordinary argument expressions, bindings, calls, collection elements and explicit effects evaluate **eagerly, exactly once, left-to-right** (unless an operation's *documented* control-flow semantics are selective). No Haskell-style default lazy expressions, implicit thunks, implicit deferred async execution, or hidden memoization.
2. `&&`/`||` and `and`/`or`, ternary branches, pattern/selection arms, and explicitly deferred constructs are the *documented* exceptions: only the selected branch runs. `defer` is cleanup scheduling, not a default evaluation strategy.
3. Creation/start of a `task` or an asynchronous operation does not depend on subsequent polling or `await`; task admission and actual start time are distinguishable, and neither grants permission for a blocking carrier. If an API wants laziness, its type and syntax must say so explicitly.
4. Spawned work is owned by its lexical scope, actor or supervisor. Completion, cancellation, errors and physical resource retirement have explicit rules. Observing a borrowed `Future` never grants cancellation authority over its producer. Detached work is explicit and supervised.

## No reentrant actor-state interleaving

5. An actor processing a logical handler holds exclusive ownership of its mutable actor state for that handler's *entire logical turn*, including across `await`, `select`, cooperative yield, carrier handoff and preemption. Other messages may queue but **must not execute a second handler against that state** during suspension. Physical carrier release is not logical reentrancy.
6. No implicit actor reentrancy: waiting on self or cyclic actor dependencies must not start the next mailbox handler as an escape hatch. Diagnose potential self-deadlocks statically where possible; enforce bounded waiting, cancellation, deadlock detection and supervisor policy at runtime.
7. Concurrency greater than one is opt-in only for provably disjoint request/message-local state or explicitly partitioned regions. Cross-handler shared mutable actor-state access is rejected; no implicit unlock/revalidate burden is imposed on programmers. An exclusive loan may persist across suspend; a mutable loan must not escape its generation. Never claim the current `ActorStateLoans` helper alone proves source actor safety.
8. Hot reload, cancellation and restart must respect the old generation's outstanding tasks/loans before state transfer or an atomic routing switch. Preserve stable actor identities only with proven ownership and quiescence.

## Types, arithmetic and operator design

9. Default safe source rejects implicit *type conversions*. A type synonym, literal contextual typing and proven refinement narrowing are not runtime coercions. Widening, narrowing, string/number, bool/number, numeric/float, Option promotion and foreign representation changes must be explicit checked conversion calls or casts, with specified overflow, precision and failure policies.
### Checked i64 to binary64 explicit conversion (partial P0 implementation)

A source `as float` or `as? float` currently means **runtime type assertion/test**, not numeric representation conversion; accepting it statically is not proof of a checked integer-to-float conversion. Do not use `as` as a supposed coercion escape hatch.

The Java/Graal draft now stages `Integer.toFloatExact(int) -> float` as an explicit named operation. It returns an IEEE754 binary64 number only when the original signed64 integer can be represented **without any loss of precision**, otherwise traps. The positive upper boundary `2^63` is checked *before* converting the double back to a signed64 integer, because the Java narrowing cast saturates and native C++ would have undefined behavior on an out-of-range float-to-int cast. The exact signed minimum `-2^63` is representable. There is no implicit rounding fallback; intentionally lossy conversions and `Float.toIntExact` require separate versioned source contracts and implementations.

The Java branch contains 11 reference cases with expected exact IEEE754 hexadecimal bits or `INEXACT_CONVERSION` errors in `contracts/v1/strict-integer-to-float-exact-v1.json`; equivalent reference helpers/fixture tests are staged in LLVM #1113 and .NET #217. These do **not** yet prove compiled native guest calls or same typed guest error channels across backends, and the Java/Graal implementation has not passed an admitted exact-head Maven/Graal execution.

10. **No user-defined operator overloading**, including assignment, arithmetic, comparison, boolean, bitwise, indexing or call syntax. Permitted built-in syntax has fixed language-defined meaning for admitted primitive/container types; adding an `operator` method or implicit conversion must not change its dispatch. Define named trait methods/functions for custom behavior (e.g. `add(a,b)`, `get(index)`). No Java-esque coercion rules.
11. Remove `%` (modulus) and `^` (including legacy bitwise XOR or `^^`) from the accepted source expression operator set after migration. Use named pure functions for remainder/modulus, exponentiation and XOR. Proposed family: `Integer.mod(a,b)`, `Integer.pow(base,exponent)`, `BooleanOps.Xor(a,b)`, and an explicitly named bitwise XOR function. **API status:** this draft stages Java/Graal guest intrinsics `Integer.rem`, `Integer.mod`, `Integer.pow` and `Integer.bitXor`, using a Java-only checked `StrictIntegerArithmetic` oracle, and freestanding Oreslang `ores.core/numeric.{rem_int,mod_int,bit_xor_int}` wrappers. The core module no longer spells `%` or `^`, but those operators still exist in other source code and in the grammar; **do not remove them before repository-wide migration**. `BooleanOps.Xor` already exists in the Java builtin surface. Exact-head execution, other backends and typed guest error lowering are **not yet verified**.
12. Specify `mod` precisely (truncating remainder versus Euclidean modulus; negative dividend/divisor; zero divisor) and integer `pow` (nonnegative exponent domain; checked overflow) *before* enabling replacement functions. Require identical results and error modes for Graal, LLVM, .NET and WASM. Do not silently remap an old `^` bitwise XOR to exponentiation.
13. Built-in numeric operations have exact portable overflow/division/NaN/signed-zero semantics. Shape-sensitive numeric APIs reject mismatches unless an explicitly specified broadcasting operation is requested. Equality/hashing are type-governed; no universal polymorphic equality on unsupported functions/actor state.
14. No `null` or ambient missing value semantics in safe guest code. Absence is `Option<T>`, failures use typed `Result<T,E>` / declared effects, and omission of a parameter is a separate call-site event.

## Nested generic types: preserve all layers

15. `Option<Option<T>>` and `Future<Future<T>>` are distinct from `Option<T>` and `Future<T>`; type formation, inference, assignment, generics, serialization and ABI **must never automatically flatten** nested forms.
16. `Some(None)` remains distinguishable from `None`. `Option<T>.unwrap()` removes exactly **one** layer; `await Future<T>` resolves exactly **one** layer. Explicit `flatten()`/combinators may join one layer only at programmer request, with documented ownership/cancellation behavior. `unwrap_all()` and `await_all` remain separate explicitly named capabilities where supported.
17. Nested futures do not silently acquire lazy/poll-start semantics or transfer producer cancellation authority during flatten/observation.

## Overloads, optional parameters and named arguments

18. A callable family dispatches by owner, callable kind, name and caller-visible **value arity**, never by runtime type, return type, default expression, generic arity or parameter labels. The receiver `self` is not a value argument. Do not activate prior proposals for same-arity enum/type-directed overload discrimination without a separately approved contract revision.
19. Optional parameters (`T? name` or the backend-specific admitted omission spelling), named arguments, defaults, tuple spreads and varargs **must not introduce ambiguous call shapes**. Every declaration family is validated for the intersection of its permitted call shapes, including inherited/overridden callable sets. If even one admissible syntactic call could select two distinct overload slots, reject the declarations statically. No best-match, type-scoring, preferred exact arity or declaration-order tie breaker.
20. A named call's arguments evaluate exactly once in **lexical source order**, even if parameters are bound in declaration order. Duplicate, missing, unknown and conflicting arguments are errors. `Option<T>` as a required parameter is not silently treated as omittable. Omitted argument is not equivalent to explicit `None` or a default value.
21. Optional/variadic/spread normalization must produce a unique compile-time call plan and a statically resolved ABI. Any dynamic callable without enough metadata for a unique call must fail closed rather than runtime-reflect or guess.

## Effects, FFI, real time and portability

22. Pure/suspending/blocking/allocating/IO/unsafe/raising/state effects are statically observable across direct and indirect calls; accidental effects are rejected in restricted regions. Hard-real-time paths forbid unbounded allocations, blocking, unbounded control work and suspension; proof must include transitive callees and dynamic-dispatch admissibility.
23. No unsafe foreign boundary may silently weaken ownership, nullability, type checking, actor isolation or deterministic destruction. Dynamic `any` remains an explicitly checked runtime value, not a permission to bypass safety; `unknown` must be refined before typed use. No implicit Java interop fallback.
24. Reflection-free compiler-generated serialization, protocol dispatch and validation are defaults for AOT. Actor transport uses explicit `Send`/`Sync`/`Sendable`/`Serializable` distinctions. Ownership/borrow constraints survive suspension and containers.
25. No implicit mutable globals, environment-dependent semantic conversions, silently suppressed errors or unstructured task ownership. Macro hygiene, source-order evaluation and deterministic initialization are required. Safety must remain semantically identical under JIT and AOT.
26. Language changes require source tests for positive behavior and **negative admission**, including malformed/coercing source, overflow, await/actor races, cancellation/finalization, inherited overload overlaps and cross-backend parity. A zero-step CI failure is **NOT_EXECUTED**, never test evidence.

## Call-shape collision examples (reject as a family)

```oreslang
fnc connect(string host) -> Connection { ... }
fnc connect(string host, int? port) -> Connection { ... }
// connect("localhost") matches both -> reject declarations.

fnc f(int a, int? b) -> int { ... }
fnc f(int a, int b, int? c) -> int { ... }
// f(1,2) matches both -> reject declarations.
```

An allowed nonoverlapping family: `fnc g()` and `fnc g(int x, int y)`, with no omitted slots affecting these arities. In mixed named calls, consider parameter labels in the *syntax acceptance* of a candidate, but do **not** treat label differences as permission for same-arity overloads.

## Required regression and release gates

| Gate | Acceptance test | Status as of this RFC |
| --- | --- | --- |
| Parser | reject `a % b`, `a ^ b`, `a ^^ b`; retain literals and legal boolean short circuit | NOT VERIFIED |
| Stdlib | named modulus, integer power and bitwise XOR with negative, zero and overflow cases | Java/Graal code + JUnit written; exact-head guest execution NOT VERIFIED |
| Conversion | positive explicit checked casts; negative string/number, float/int, null/Option implicit conversions | NOT VERIFIED |
| Generic types | `Some(None)` vs `None`; `await` and `unwrap` peel exactly one layer | Existing staged work; exact-head execution NOT VERIFIED |
| Overloading | overlapping omission/default/named/inheritance call shapes rejected at declaration | NOT VERIFIED |
| Evaluation | left-to-right eager side effects; short-circuit and conditional exceptions | NOT VERIFIED |
| Actor | no competing mutable handler across suspension, cancellation or hot reload | NOT VERIFIED |
| Effects/RT | transitive forbidden effects statically refused; no accidental carrier blocking | NOT VERIFIED |
| Backend | same fixtures under Java host, Graal Native Image, LLVM, .NET Native AOT and Wasm where admitted | NOT VERIFIED |
| Governance | exact commit/tree and real executed CI steps; security-approved runners only | NOT VERIFIED |

### Migration sequence

1. Audit parse/type/runtime/stdlib/tests and downstream usages of `%`, `^`, `^^`, Java host assumptions, eager/lazy evaluation, actor reentrancy, implicit casts, option/future flattening and optional overloading.
2. Implement explicit pure stdlib functions and parity fixtures **before** removing legacy operator syntax from admitted release profiles.
3. Implement checked call-shape intersection and negative compile tests before enabling omission/default/variadic combinations.
4. Complete actor logical-turn exclusion integrated into source state access, then deterministically test suspension, nested calls, cancellation, hot reload and concurrent requests.
5. Remove/disable forbidden operators and implicit conversions with actionable diagnostics. Update formatter/docs/examples and perform exact-head CI tests on authorized execution resources.
6. Do not merge a semantic break until its affected positive/negative tests and consumers have been audited and the change has independent review.

### Audit note

The existing `docs/NESTED_OPTION_FUTURE_EXTRACTION.md` already specifies one-level `unwrap`/`await` and explicit `flatten`; preserve that policy. The existing actor reentry RFC describes serial actor logical-turn exclusion, but explicitly says its loan component is not yet source-wired. Existing `docs/grammar.ebnf` and Java parser still accept `%` and `^`; this document does not pretend those operators are gone. Existing `docs/LANGUAGE.md` describes explicit Java host interop; this document changes the *core-language assumption*, not proof of its removal.

## Partial arithmetic implementation on draft PR #2193 (2026-10-27)

- Java oracle `StrictIntegerArithmetic.rem`: signed truncating remainder, sign follows dividend; zero divisor throws. `mod`: Euclidean modulus, nonnegative, strictly smaller than the mathematical absolute divisor, including `Long.MIN_VALUE`. `pow`: exponent must be nonnegative and **checked** on every required 64-bit multiplication; overflow throws. `bitXor`: 64-bit two's-complement XOR. These are reference semantics, not a Java-interop dependency.
- Guest `Integer.{rem,mod,pow,bitXor}` are implemented for the Java/Graal interpreter only; the compiler rejects non-`int` operands, arity other than two and type-parameter markers. Runtime evaluates arguments eagerly and dispatches with the checked signed-64 oracle, including async/suspendable call paths. Guest `ores.core/numeric` exposes `rem_int` and `mod_int` by delegating to those named intrinsics, and `bit_xor_int` by `&`, `|` and `~` without caret syntax. There is not yet a freestanding `pow_int` source wrapper; named `Integer.pow` is interpreter-only. Removing source `%`/`^`/`^^` still requires full consumer inventory, formatter/parser removal and native-backend implementations.
- A deterministic local isolated Java 21 probe of the reference arithmetic class covered 101,542 BigInteger-backed comparisons and negative error cases; it was **not** the full exact-head Oreslang compiler or JUnit integration build. The committed JUnit test has not executed on an admitted source runner; source-org CI failures with zero job steps are `NOT_EXECUTED`, not test failures or successes.
- Shared decimal-string signed-64 vectors are now pinned at `contracts/v1/strict-named-integer-arithmetic-v1.json` (26 cases validated against an independent BigInt oracle in-session). Native-backend execution of that fixture, typed guest errors, exact-tree Maven/JIT/Native Image runs and LLVM/.NET/WASM parity are **not yet established**. No claim of source syntax migration completion.
