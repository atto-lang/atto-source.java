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

At a call site, select the slot by receiver and syntactic argument count
before checking argument/return types. In particular, an incompatible
argument must produce a type error for the selected arity, not trigger
search for another overload. No implicit conversion ranking, structural
member-by-member matching, return-type scoring, or runtime reflection
may choose a callable slot.

## Inline receiver marker and explicit `self` call arguments

For ordinary class and actor instance methods, all three definitions below
have the same **caller-visible arity** and overload identity:

```ores
foo(int x): void {}
foo(self, int x): void {}
foo(self Foo)(int x): void {} // legacy typed receiver form
```

They MUST NOT coexist in one owner: each defines `foo/1`.
Likewise `foo()` and `foo(self)` both define `foo/0`. A leading
`mut self` carries exclusive receiver borrowing, but does not add an
argument or distinguish an overload. Only the **first declaration parameter**
may be an inline standalone `self` / `mut self` marker. Trailing commas,
duplicate markers, non-leading markers and receiver markers on static methods
are errors. Methods with a normal `self`-typed *named parameter* are
different: `foo(self other, int x)` defines `foo/2`.

In a method **call**, the receiver is exclusively the left operand of the
dot. Every value expression in parentheses counts as a separate explicit
argument, even the expression spelled `self`:

```ores
define class Foo as
  foo(int x): int { return x; }           // foo/1
  foo(self other, int x): int { return x; } // foo/2
  example(): int {
    self.foo(5);        // foo/1
    self.foo(self, 5);  // foo/2, first value argument has type self
    return 0;
  }
end
```

If only `foo/1` is present, `self.foo(self, 5)` is **missing foo/2**.
If `foo/2` exists but its first parameter rejects the receiver-polymorphic
`self` value, report a first-argument **type/ownership** error and NEVER
fall back to `foo/1`. The `self` expression may not bypass move, loan,
actor isolation, mailbox or Sendable restrictions.

**Normalization boundary:** the parser erases a declaration's inline
receiver marker from `MethodDecl.parameters` before overload admission.
It does **not** erase expressions from `CallExpr.arguments`. The method
selector, type checker, ownership checker, interpreter, virtual dispatch,
AOT linkage and actor mailbox lowering all use the same resolved expanded
value-argument vector. Statically known tuple-spread expansion affects
call-site arity; the receiver never does. Default arguments or variadic
expansion (if later supported) must reject overlapping source-call arities.
The proposed nominal-enum overload extension remains separately gated and
must never use receiver spelling as a discriminator.

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

## Class and actor method spelling

The following instance methods all use the identical AST, receiver model and
`(owner, INSTANCE, name, value-arity)` overload selector:

```ores
define class One as
  foo(): void {}
end

define class Two as
  foo() -> void ::
  end
end

define class Three as
  mtd foo(): void {}
end

define class Four as
  mtd foo() -> void ::
  end
end
```

The `mtd` and older `method` prefixes are **optional, contextual markers**,
not globally reserved tokens. Inside a class or actor they are interchangeable
with bare method declarations and do not affect binding or overloading. The
separate type-alias extension spelling `define mtd ... on Alias` retains its
existing meaning outside class-member position. A member called `mtd()` stays
legal without a marker. Static class functions still use `static fnc`, never
`static mtd`.

End-delimited method bodies require an explicit `-> ReturnType ::` header and
consume exactly their own `end`; the enclosing class/actor consumes a separate
`end`. Colon-return braced bodies remain valid. Neither `mtd` nor `::` changes
explicit/implicit `self` or its exclusion from source-call arity.
