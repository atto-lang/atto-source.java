# Oreslang lexical lambda capture and shared-method contract (pre-1.0)

## Capture list grammar (Java/GraalVM implementation)

```ores
|int x| -> x + a                 // ordinary lexical captures, excluding implicit self
nlex |int x| -> x                 // no outer local captures
lex[a] |int x| -> x + a          // explicit whitelist, only a visible
nlex[a] |int x| -> x + b         // explicit blacklist, a forbidden, b allowed
lex[self] || -> self.foo()       // self is lexically visible; borrow rules still apply
nlex[self] |int x| -> x + b      // self is unavailable; ordinary b can be captured
```

The canonical list delimiters are **square brackets**, not parentheses. A
capture list is a sequence of lexical binding names resolved at the lambda's
declaration site, not expressions and not run-time values. Duplicate/unknown
names and names colliding with lambda parameters are errors. Empty
`lex[]` is an explicit empty allowlist; `nlex[]` rejects nothing except the
always-excluded implicit `self` binding. A bare `nlex` retains its original
stronger no-capture behavior, including transitive nested closures.

**No implicit class/actor self capture.** Normal lexical lambdas, and every
`nlex[...]` blacklist lambda, exclude `self` by default. Only `lex[self]`
explicitly admits `self` into name resolution. Name resolution is *not*
an ownership permission: ordinary class/actor `self` is borrowed and must
not be captured into an escaping closure. The ownership checker rejects
such captures unless a separately specified sealed actor-owned continuation
proves a nonescaping lifetime. This feature does not create that exception.

A nested lambda cannot obtain a name its enclosing lexical activation could
not capture. Binding grants are lexical, do not transfer privileged class
member access by themselves, and must preserve normal move/borrow rules,
capability limits, initialization barriers and cancellation constraints.

`nlex[self] critical |state| -> { ... }` is intended for future actor-state
write callbacks. Adding capture lists to ordinary lambdas does **not**
implement or authorize `critical` as a lambda-prefix modifier.

## Shared method code invariant

Oreslang method declaration forms `method(self, T value)` and
`method(T value)` select the **same** class method slot. Defining both in
one class is an overload-identity conflict. Neither receiver spelling emits
an extra per-instance method, closure, or copied code body.

At link time, a class has one effective immutable dispatch table per code
image (including resolved inherited slots). Each object stores its class
identity and own instance state, not method ASTs or per-instance vtables.
Invocation supplies its own receiver, arguments and invocation-local frame.

Important distinction: *one method body per class* does not forbid
per-invocation frames, or temporary lambda closure values explicitly built
inside a method. Those may be allocated at call time. The shared method
table and unbound receiver code must not be duplicated by class instantiation.

Native backends may use a shared vtable and compiled function address plus
receiver pointer. LLVM/.NET/WASM/Forge must either prove this contract in
compiler-backed tests or fail closed on unsupported syntax; a Java-only
passing test does not establish backend parity.

## Regression gates

1. Assert a class's effective method table holds one method definition per
   selector, regardless of instance count (1,024 instances in Java test).
2. Assert instance fields are separate, without per-instance MethodDecl or
   method table slots.
3. Assert default/inline-self/legacy receivers collide at identical arity.
4. Assert allowlist, denylist, bare-nlex and ordinary lexical semantics,
   including denied-name diagnostics, parameter shadowing, nested lambdas,
   and borrowed actor-self rejection.
5. Re-run JVM/JIT, Native Image and actor/effect conformance on the *exact*
   Git tree SHA of every proposed change.
