# Callable identity is not nominal inheritance

Status: language/compiler contract (Java frontend and constrained LLVM native admission).

## Decision

An Oreslang `fnc` is first-class executable behavior; a `routine` is direct-call-only.
Neither is a class, actor, interface, trait, or constructor target. Instance
methods are direct-call-only receiver dispatch and cannot be implicitly lifted
into a bound callable. A `static fnc` is reifiable without a receiver.

The function type `Fnc<Args..., Result>`, its `Function<...>` synonym, and
`typeof fnc(...) => ...` are *signature types*, not nominal types. The
identifier `Callable` is reserved from nominal type declarations; it does
not introduce a new runtime interface or implicit `call` protocol.

`Callable`, `Fnc`, `Function`, `Routine`, and `Method` cannot be
declared as user classes, actors, interfaces, traits, or contracts. They
cannot be targets of `extends`, `implements`, or `impl` in those
nominal declarations, even when written in qualified form. A type alias
to `Fnc<...>` is allowed but cannot be inherited or implemented; class or
interface heritage must still resolve to the appropriate nominal declaration.

## Examples

```ores
// Compile errors (not a callable object protocol).
define class Fake extends Callable as end
define class Fake2 implements Callable as end
define class Fake3 extends Fnc<int, int> as end
define interface FakeInterface extends typeof fnc(int) => int as end

// Functions remain values; the signature is not a class.
type IntUnary = Fnc<int, int>;
fnc twice(int x): int { return x * 2; }
fnc apply(IntUnary f, int x): int { return f(x); }

// Ordinary data can HOLD a function value. This does not attach arbitrary
// user properties to the function value or make its code subclassable.
define class Handler as
    val IntUnary callback = twice;
end

// A routine has no first-class callable value.
routine log_once(): void { return; }

// Instance methods need an explicit wrapper for callback use.
define class Worker as
    pub method execute(int x): int { return x; }
    pub static fnc identity(int x): int { return x; }
end
```

## State and closures

A lexical lambda is allowed to capture an environment according to existing
ownership, lifetime, and actor-isolation rules. That capture is an
implementation environment for a closure, **not** a dynamically extensible
property table or a subclassable Oreslang class. `nlex` remains the explicit
capture-free guarantee. There is no implicit `__call__`, `invoke`, method
named `call`, or `operator()` conformance protocol that converts a user
class to `Fnc`; `Foo()` always means constructor invocation where supported.

The backend may share immutable code and maintain a separate, ownership-
checked environment for a closure, but should not expose or allow arbitrary
data to be pinned to the function code object. Reflection, FFI/polyglot
interop, dynamic `any` values, serialization and hot reload must preserve
the same separation and must not forge callable signatures from nominal
objects.

## Enforcement and parity

- Java Parser rejects reserved nominal declarations and direct callable-shaped
  heritage.
- Java TypeChecker independently rechecks those boundaries for manufactured or
  imported ASTs; aliases to function types already fail nominal parent/interface
  resolution.
- LLVM's current native subset has no class inheritance or interface
  implementation lowering; these constructs must remain fail-closed.
  The native frontend also rejects callable-named classes and actors.
- Regression gates cover forbidden names, signatures, inheritance, implementation,
  interface/contract parents, AST bypasses, and unaffected ordinary callbacks.

Open follow-up: verify FFI and `any` reification, closure capture/escape,
method reference syntax, serializable callable transport, and cross-generation
hot reload are not allowed to smuggle an object in as a callable.
