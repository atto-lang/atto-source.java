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

## Leading inline self receiver (Java parser hardening)

An instance method may spell the hidden receiver in the ordinary parameter list,
**only as its first standalone item**:

```oreslang
define class Foo as
    foo(int x): void { return; }          // caller-visible arity 1
    // foo(self, int x): void { return; } // ERROR: same caller-visible arity 1

    bar(self): void { return; }           // arity 0
    baz(mut self, int x): int { return x; } // arity 1
    pair(self, self peer, int x): int { return x; } // arity 2
end
```

These are equivalent receiver bindings: `foo(int x)`,
`foo(self, int x)`, and the existing `foo(self Foo)(int x)` receiver form.
The compiler creates only one receiver and excludes it from
`MethodDecl.parameters()`, its `arity()`, dispatch selector, callable-value
signature, inherited override identity, and optional/default-argument binder.

A standalone `self` expression **at the call site** is always a PROVIDED
user argument, never a second hidden receiver. Thus `self.foo(self, 5)`
has caller-visible arity 2, not 1; it fails if only `foo/1` exists. It can
select `foo/2` if there is a real first user-argument formal that accepts
`self` as an ordinary value. Do not silently discard, shift or reinterpret
that expression, even if a default or named argument is present. The receiver
to the left of the dot is always evaluated/checked independently.

This parser rule also applies to actor *method declarations*. It does **not**
authorize transporting raw actor-owned `self` as an actor message: mailbox,
Send/ownership, capability and lexical state-loan rules remain binding.
Actor constructor declarations remain forbidden.

Static class functions cannot declare an inline `self` receiver; other
formal parameters named `self` as a **type**, e.g. `self peer`, are
ordinary caller-supplied values. The separate two-list explicit receiver form
remains valid and retains its receiver mutability/type contract.

Leading receiver binds BEFORE optional slot/default normalization. Default
annotations must not target `self`; otherwise inserting receiver in an ABI
argument slot could bypass overload or constructor authorization. Contradictory
forms, duplicate receiver binders, non-leading bare `self`, dangling commas,
and static receivers must fail closed.


At a call site, select the slot by receiver and syntactic argument count
before checking argument/return types. In particular, an incompatible
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
