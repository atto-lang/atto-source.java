# AttoLang direct-call implicit self lookup v1

Status: Java/Graal source implementation candidate; mandatory executable CI and independent review pending. This contract is not a claim that sealed actor `state {}` and `handle_state` are executable on Java main.

## Normalization

Within an instance method, a direct unqualified call `f(args)` resolves against the current lexical scope first. If no lexical value binds `f`, and the current `self` has an instance-method family named `f`, the checked call is exactly `self.f(args)`. The compiler must preserve the original call's type arguments, positional/named arguments, evaluation order, source origin and return type. There must be no method-closure allocation and no dynamic namespace walk.

A method family is selected **before** matching arity. `self.f` overload arity, visibility, mutability, actor ingress, critical/no-yield and suspension rules must then be enforced. If no overload matches, this is an error; never fall through to a same-named root function or import.

This applies to both ordinary classes and actors; it does **not** turn actor methods into independent schedulable tasks. The active actor receiver and turn lease are unchanged.

## Precedence and boundaries

1. Local callable/noncallable bindings and parameters take precedence. Noncallable shadowing produces an error; it must not silently select the method.
2. An instance method family on the **lexical declaring class**, not the runtime receiver's dynamic subclass, is next. Inherited methods participate; only after the lexical method family and overload have been selected does ordinary virtual dispatch choose the implementation. A subclass-only method must never hijack a base method's intended root-function call.
3. Existing explicitly imported / qualified / module / file callables follow their ordinary rules. Actor dependency isolation does **not** become weaker; actors must still import external dependencies explicitly. The separate draft for `pkg::` / `imports::` qualification is **not** implied by this implementation.
4. Actor lifecycle entry points `on_start` and `receive`, constructors, private/protected members from unauthorized scopes and unsafe method values remain forbidden exactly as for explicit self.
5. The syntactic call must have `CallOrigin.DIRECT`; piped call rewriting must not invent `self`.
6. Free functions and static methods have no implicit receiver. A bare `f` is never a captured bound method value, including when a method named `f` exists. A lexical variable or parameter literally named `self` is **not** proof of instance-method authority.
7. Only a compiler-created instance-method entry frame may grant the implicit receiver identity; child lexical frames inherit it, nonlexical/detached boundaries must revoke it. Constructor and field-initializer frames must not inherit or fabricate this identity. Runtime-linked/unchecked AST paths must independently enforce the same gate.
8. Compiler-owned direct calls (`Some`, `Ok`, `Err`, Boolean intrinsics, `init`, `print`, and task-context internals) retain priority over user method families. The shared `CallableSelector.reservedImplicitReceiverCallName` predicate is mandatory in the type checker, ownership checker, closure capture scanner, and Graal evaluator. User methods of those names require explicit `self.method(...)`.
9. Capture analysis must treat an unqualified method call in a lexical closure as capturing the identical borrowed `self` that `self.method(...)` would capture, even before AST lowering. Ordinary borrowed-self captures cannot escape through lambdas; nonlexical closures have no such inherited capability.
10. Nonlexical closures, async handoffs and task boundaries do not acquire new actor-self capture rights. An implicit call must pass exactly the explicit receiver ownership and effects checks.

## Reserved actor state method

Once sealed actor state lowering is implemented, `handle_state(callback)` will be a compiler-owned actor-instance intrinsic equivalent to `self.handle_state(callback)`. Its owner authority must be recorded as a sealed actor-intrinsic symbol, not inserted into a mutable ordinary lexical environment, inherited from user classes, or callable outside its current actor turn.

Both spellings must require the same actor state schema, critical admission for a mutable state loan, nonescaping `borrow mut` callback, sync/no-yield body, generation and turn fencing, and no actor-self or state-loan leakage. A lexical variable also named `handle_state` must follow the published lexical shadowing rules or produce a hard ambiguity diagnostic; it must never turn a state-intrinsic call into an unguarded callback. A user-defined actor method named `handle_state` must continue to be rejected.

**Current gate:** Java main intentionally rejects `state {}` pending ownership and `handle_state` lowering (`SealedActorStateAdmissionTest`). This patch only implements implicit calls for methods already declared on the receiver. It must not claim that the example WAL actor now compiles.

## Verification matrix

Positive: explicit/implicit class equivalence; direct actor-to-own-method calls; class and actor name collision with root function; local function binding shadows method; private/inherited dispatch where valid.

Negative: mismatched method arity cannot fall back to root; noncallable lexical shadow; bare method value; free function and static method (even with a parameter named `self`) lack receiver authority; constructor cannot call instance method; actor lifecycle entry points stay reserved; undeclared `handle_state` and unlowered `state {}` remain denied.

Execution gates: parse, type and ownership check all tests; GraalVM guest runtime, sync and suspendable call paths; existing actor isolation and loan regressions; AOT when available. A workflow with zero executed steps is NOT test evidence. Promotion requires exact PR-head SHA/tree and nonzero executed test receipts.
