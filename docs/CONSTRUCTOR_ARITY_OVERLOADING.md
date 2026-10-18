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


## `AnyCallable`, transport safety, and spoofing (security audit)

`AnyCallable` is a **sealed compiler intrinsic top type for reifiable
function values**, implemented as a type-system singleton. It is *not* a
user-definable interface, trait, class, `impl` target, marker annotation,
structural `call` convention, or serialization capability. Its hierarchy
is `Fnc<signature> <: AnyCallable <: Any` (including compatible reifiable
lambdas/static functions); methods, non-reifiable `routine` declarations,
ordinary objects with a `call` member, and class constructor objects do
not automatically become callable. The erased `AnyCallable` type has no
known invocation signature and **must not be called or accessed as an object**
until a safe, explicitly checked narrower callable signature is proven.
Captures stay subject to their own lifetime/ownership rules.

- Actor mailbox DATA and generic network/persistence codecs are *separate*
  from local callable values. Neither `AnyCallable` nor any `Fnc<...>`,
  closure, static function, callable-containing collection/record/union,
  pointer, codec implementation object, or nominal host capability is
  `Sendable` or `Serializable` merely because it is a valid local value.
- `Sendable<T>` is compiler-only ownership/transfer evidence, not a
  user-implementable trait. `Serializable<T, F>` requires a distinct
  pinned format codec and independently granted I/O permission. Actor
  DATA additionally requires a receiver-pinned `ActorMessageCodec<T>`
  and atomic admission; supervisor CONTROL is runtime-authorized only.
- Reject module-qualified or imported lookalikes (`mod.AnyCallable`,
  `mod.NetworkCodec`, `mod.ActorRef`) and unsupported unknown nominal
  types, even when hidden inside `List`, tuples, records, generics,
  Option/Result, or async task arguments. No source-level trait name or
  user-provided schema ID can manufacture ownership or CONTROL authority.
- Native LLVM, WASM, .NET, and Forge must explicitly **reject** unimplemented
  erased callable semantics at type-check/lowering time, rather than
  silently treating them as an `Any`, ordinary object, FFI pointer,
  or sendable serialized data.

This feature branch includes focused JUnit checks for the erased callable
subtype relation, prohibition of implicit invocation and arbitrary
properties, reserved intrinsic names, nested actor-data capability rejection,
and preservation of legitimate in-process callable use. Independent JDK 25,
Native Image and cross-backend conformance are still required.


## Runtime lexical capture hardening (October 17, 2026)

The interpreter now constructs a **minimal lexical closure environment**:
`LambdaCapturePlanner` performs an explicit AST walk of the lambda's free
names, including nested lexical callbacks and their transitive dependencies.
The nearest lexical declaration of each required name is retained as its
existing `Slot`; the closure no longer snapshots all ancestor environments.
Unreferenced receivers (`self`), shadowed parent capabilities, and unrelated
locals must not be retained. Explicitly used receiver values remain governed
by compiler ownership and constructor-escape checks. `nlex` lambdas continue
to use a value-free declaration-origin lexical boundary.

This optimization does **not** prove `Sendable` or `Serializable`,
permit closure transfer between actors, or authorize supervisor CONTROL.
Other runtime continuations that intentionally capture a full suspended
activation are separate from lexical lambda creation and require their own
state-lifetime audit.

Regression coverage is staged in
`LambdaCapturePlannerTest` and
`scripts/test-lambda-capture-standalone.sh`, including a seeded 10,000-case
lexical capture matrix. The latter compiles the real Oreslang AST and planner,
without Maven. Neither JUnit nor the complete guest-runtime GC/reachability
and Native Image tests have passed on this exact branch head yet. Retain
security issue #1267 until those release gates run successfully and any
captures escaping through async callbacks are separately audited.


### Expression-match capture parity gate

The Java ownership checker and evaluator must agree about every value a
lexical closure retains. The previous ownership `scanExpr` visitor skipped
`Ast.MatchExpr`, while the runtime planner traversed its arms. This could
omit borrow/move validation for values referenced only inside a value-match
arm or its guard. The ownership visitor now checks the subject, guard,
arm value, and correctly scoped pattern bindings. The
`OwnershipMatchExpressionCaptureTest` uses the actual ownership checker
and runtime planner, including a negative borrowed-loan escape case.

A source inventory now finds 24/24 current expression AST variants covered
in both capture visitors (except the deliberately non-capturing literal
variant). This is a static coverage check, **not** proof of the absence of
semantic mismatches or of executed JUnit tests. Future AST variants must
carry cross-visitor tests and fail closed until the shared resolved-binding
semantic IR replaces duplicated walking logic.
