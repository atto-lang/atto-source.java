# Oreslang class-method overload contract (pre-1.0)

> **Current implementation:** this file describes the arity-only implementation
> in Java/GraalVM. **New normative target:** same-arity overloading will be
> allowed **only** for eligible distinct nominal enum types, with arity first,
> static selection, and no structural lookup. See
> [NOMINAL_ENUM_OVERLOAD_CONTRACT.md](NOMINAL_ENUM_OVERLOAD_CONTRACT.md).
> The new rule is **not implemented** until coordinated compiler/runtime changes
> and backend parity tests land; current same-arity duplicates still reject.

## Closed-world selector

The canonical class callable slot is:

```
(owner-class, INSTANCE | STATIC, name, value-argument-count)
```

Within one owner and kind, two methods with the same name and value-argument
count **cannot** coexist, regardless of their argument or return types,
generic arity, parameter names, annotations, or async/suspension modifiers.

The receiver (`self`, implicit or explicit) is **not** a value parameter.
Instance and static slots are separate namespaces. Constructors are not part
of ordinary method overloading and remain separately validated.

## Optional inline receiver spelling

These two declarations have the same instance method identity and cannot
coexist in the same class or actor:

```ores
receive(Mail m): void { self.process(m); }
receive(self, Mail m): void { self.process(m); } // duplicate receive/1
```

The optional first-position `self` marker declares the existing implicit
instance receiver; it is **not** added to the value-parameter list, named
arguments, overload arity, public ABI, or the method's callable type.
`foo(self)` is the zero-value-argument spelling of `foo()`.
`foo(mut self, int x)` lowers to the same mutable receiver contract as the
pre-existing `foo(mut self)(int x)`. The legacy `foo(self Foo)(int x)`
remains available for explicitly typed receivers. An inline receiver must be
first, may not appear twice, cannot end in a dangling comma, and is forbidden
on `static fnc` declarations.

### Calling a method with an explicit `self` value

A receiver marker is recognized **only in method declarations**, never in the
argument list of a method invocation. The left side of `.` supplies the
receiver once; every expression in parentheses is an ordinary user argument,
including an expression spelled `self`. For example:

```ores
define class Foo as
  foo(int x): int { return x; }             // instance foo/1
  foo(self other, int x): int { return x; }  // instance foo/2
  example(): int {
    self.foo(5);        // foo/1; bound receiver is self
    self.foo(self, 5);  // foo/2; first value argument is self
    return 0;
  }
end
```

In `foo(self other, int x)`, `self` is the **type of the named value
parameter** `other`, not a standalone receiver marker; the declaration has
two caller-visible arguments. In contrast, `foo(self, int x)` is receiver
syntax and has one caller-visible argument. They may coexist, but
`foo(int x)` conflicts with `foo(self, int x)` in either declaration
order. If only `foo/1` exists, `self.foo(self, 5)` fails with **missing
foo/2**. If `foo/2` exists with an incompatible first parameter type,
the call fails **type checking of argument 1**, not overload fallback to
foo/1.

The receiver's authorization and lifetime derive from the left operand and
the selected method's receiver effect (`mut self` or read-only), **not**
from any user-provided `self` expression. The compiler must not silently
discard, inject, substitute, or rebind a receiver from ordinary arguments.
For actors, a value passed as an argument is additionally subject to normal
actor-isolation, Sendable and mailbox checks: writing `self` never grants
permission to transfer an actor's private instance/state or bypass a mailbox.

Normal parameter typing, named/spread arguments, omission/default expansion,
borrow checks and dispatch must all agree on the same caller-visible argument
vector. Receiver normalization happens at declaration parsing, before method
slot admission; it must never happen when resolving a call. Future default
arguments/varargs must reserve every reachable call-arity slot and reject
collisions rather than using runtime type/receiver guesses.

Receiver spelling has no bearing on nested lambda scopes. Use `nlex` when
a callback must not capture enclosing locals or receiver authority. In
particular, any use of `self` inside an `nlex` lambda is a name-resolution
error, not a way to obtain an actor's instance through a hidden binding.
The `nlex critical |state| -> { ... }` actor-state write-callback form is
subject to separate actor-state grammar/effect integration; this change does
not admit or implement that pending construct.

At a call site, select the slot by the receiver expression and the statically
expanded caller-visible value-argument count (including statically known tuple
spreads) before checking argument/return types. The receiver expression is
bound once, outside that expanded argument vector. Dynamic-length spreads
cannot silently select among fixed-arity method overloads. In particular, an incompatible
argument must produce a type error for the selected arity, not trigger
search for another overload. No implicit conversion ranking, structural
member-by-member matching, return-type scoring, or runtime reflection
may choose a callable slot.

## Abstract signature shorthand

Inside an abstract class, a method signature ending in `;` may omit
`pub abstract`:

```oreslang
define abstract class Foo as
    abc(): void;
    abc(int a): void;
    abc(int a, int b): void;
    abc(bool a, int b, bool c): void;
end
```

Such signatures are *implicitly public and abstract* unless a visibility
modifier was written. Explicit `private` remains private and is rejected
for abstract instance methods by the type checker; a signature-only
declaration in a concrete class is rejected. Executable methods in an
abstract class are still permitted and require a body.

A second `abc(int, bool)` conflicts with `abc(int, int)`: both select
`abc/2`. An implementing subclass must implement each inherited abstract
arity slot and keep the inherited signature contract, including substituted
generic types.

## Inheritance

- Child methods with the same selector override a parent slot; they do not
  form a new overload family. Signature compatibility is checked separately.
- Different arities contributed by different parents coexist.
- A diamond that inherits the same original declaration twice has one slot.
- Two unrelated declarations with the same selector require an explicit
  child implementation, not parent-order or type-based overload selection.
- Public inherited slots may not be replaced by private overrides.
- Method values without known arity need explicit callable context or an
  arity-resolving invocation, not dynamic overload search.

## AOT and runtime consequences

An AOT backend can resolve the method selector at compile/link time and
encode a deterministic per-class slot (for example,
`Foo::instance$abc$arity2`), while preserving dynamic receiver-class
selection for virtual overrides. Generic type substitution validates the
chosen signature; it does not change the selector.

`CallableSelector` and the Java type checker implement the selector
semantics. The Java evaluator now precomputes an immutable per-class
`CallableSelector -> MethodDecl` table before publishing the evaluator.
Dispatch uses two expected-constant-time lookups (class identity, then
selector) instead of scanning inherited method declarations. Table
construction rejects same-slot conflicts before execution and deduplicates
true diamonds by declaration identity. This is **not** proof of a specific
latency improvement, zero-allocation call site, or GraalVM Native Image
performance; benchmarks and exact-tree CI verification remain required.

The LLVM frontend's current native class subset has field declarations
but does not yet implement general class methods/abstract method vtables.
Do not claim full LLVM class-overload support until native guest execution
proves the same contract; see the LLVM parity issue.

Future default parameters or variadic callables must not introduce a
second method matching a source-call arity. A future desugaring into
explicit arity slots must check collision before code generation.
