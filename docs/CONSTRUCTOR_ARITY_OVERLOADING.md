# Oreslang constructor arity overloads (Java implementation slice)

Constructor slots have identity `(declaring class, explicit value-argument count)`. A class
may declare several `constructor(...)` members with **distinct arities**. The parser
rejects duplicate arities, including different parameter types at the same arity.
Constructors are not regular methods; they are not inherited or virtual, and return
types, generic specialization, implicit conversions and runtime argument types cannot
select one. Direct `new T(args...)` calls select the single arity slot before validating
positional argument types and ownership.

```oreslang
define class Counter as
  val int count;
  constructor() { self.count = 0; return; }
  constructor(int n) { self.count = n; return; }
end
val a = new Counter();
val b = new Counter(42);
```

The absence of **all** explicit constructors retains the legacy synthesized
positional-field behavior for existing source compatibility. Once any explicit
constructor exists, no synthesized overload is added. Unknown arities reject.
Each explicit constructor is checked independently for field initialization, single
writes, ownership, visibility, effects and partial-`self` escape. Declaration-site
field expressions must validate for every constructor's independently scoped,
scalar-only parameter set. No argument tuple or `self` is available there.
Constructor bodies are synchronous and may not publish incompletely initialized
instances. Actor constructors remain forbidden.

Constructor **delegation**, super-constructor chaining and async initialization are
not part of this change. Prefer named static factories when two creation modes need
the same arity, and actor lifecycle hooks for actor startup.

Backends: this is a Java/GraalVM compiler/interpreter slice. LLVM and other native
backends may currently reject or omit ordinary class-constructor overloads; do not
report native parity until source-identical positive/negative execution proves it.
The AST compatibility accessor `constructor()` exposes only the primary declaration;
all compiler transforms and semantic passes must use `constructors()` to avoid
discarding variants. Preserve constructor family signatures in public ABI hashing.


## Explicit constructor receiver spelling

Ordinary class constructors may spell the compiler-supplied construction receiver
as a leading bare `self`:

```oreslang
constructor(self, int value) { ... }
constructor(self, int value, string label) { ... }
```

The marker is syntax only. It is erased before the constructor parameter list is
formed, is never supplied by the caller, and never contributes to overload arity.
Therefore `constructor(int)` conflicts with `constructor(self, int)`, and
`constructor()` conflicts with `constructor(self)`. Constructor `self` remains
an unpublished partial-object capability with the same no-escape rules as implicit
constructor `self`. Actor constructors remain prohibited.


## Constructor callbacks and partial self

A constructor may accept ordinary callable parameters, including callables whose
parameters use `AnyCallable`. That does **not** relax partial-construction
ownership. A closure that captures constructor `self` cannot be passed to an
ordinary callback because the callback may retain or asynchronously invoke it:

```oreslang
constructor(self, int value, ((AnyCallable cb) => void) cb) {
  cb(|| -> {
    self.value = value; // rejected: partial self would escape in the closure
    return;
  });
}
```

A future explicitly scoped/no-escape callable contract may admit synchronous
borrowed callbacks, but ordinary `Fnc`/`AnyCallable` parameters are escaping
unless proven otherwise. No such no-escape exception is part of this change.
