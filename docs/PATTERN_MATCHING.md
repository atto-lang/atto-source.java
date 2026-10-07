# Pattern matching, refinement, and casting

Oreslang deliberately separates type refinement, full pattern matching, and
value-case dispatch.

## Surface syntax

```ores
if value is type Dog dog then
  dog.bark();
fi

if result matches Some(value) then
  use(value);
fi

val dog = animal as Dog;
val maybeDog = animal as? Dog;

match result
  Some(value) -> { use(value); }
  None -> { recover(); }
end

switch status
  case 200, 201 -> { success(); }
  default -> { failure(); }
end
```

Every `if` closes with `fi`, including brace-bodied forms. Executable arms
use the slim arrow `->`. The fat arrow `=>` remains type-level syntax for
function types and interface callable signatures.

## Semantic separation

- `x is y` is reference/handle identity.
- `x is type T` is a type-conformance predicate; it never implicitly narrows `x`.
- `x is type T as name` introduces a typed lexical alias on success; the older `x is type T name` spelling is accepted.
- `is T` is a test-only type pattern inside `match`.
- `T as name` and `is T as name` are equivalent checked-cast binding patterns.
  The older `is T name` spelling is accepted. A failed pattern cast skips the
  arm; it never throws the ordinary checked-expression cast error.
- `is structural { name: String }` tests the public structural contract.
  `structural { name: String } as named` also binds the successful view.
- `x matches P` tests a complete pattern and exposes its bindings only on the
  successful control-flow edge.
- `x as T` is a checked cast. A failed runtime cast raises `CastError`.
- `x as? T` is a non-panicking cast with type `Option<T>`.
- `match x` is proof-checked pattern partitioning.
- `match first x` is the explicit ordered/priority escape hatch.
- `switch x` is constant/equality dispatch and never performs destructuring.

A refinement binding is not a copy. If `dog` is introduced by
`animal is type Dog dog`, both names identify the same ownership place. Moving
through either name consumes the same move-only value.

## Exclusive match proof obligation

For a scrutinee domain `D`, each explicit arm is normalized to a predicate
`P_i(x)`. A normal match is admitted only when the checker establishes:

```text
for every i != j:  D(x) && P_i(x) && P_j(x) is UNSAT
exhaustiveness:    D(x) && !(P_1(x) || ... || P_n(x)) is UNSAT
```

An unguarded final `else`, `_`, or catch-all binding is not treated as an
ordinary universal arm. It denotes the complement of all preceding explicit
arms.

The first proof engine is intentionally conservative. It has exact reasoning
for:

- booleans and literal equality;
- `Option` constructors `Some` / `None`;
- `Result` constructors `Ok` / `Err`;
- nominal subtype/interface relations when a relation is provable;
- simple numeric guard intervals using `< <= == >= >`;
- wildcard/catch-all coverage.

If disjointness cannot be proved, ordinary `match` is rejected rather than
silently using source order. Code that deliberately needs priority semantics
must say `match first`.

This is the important semantic distinction:

```ores
match value
  is Dog dog -> { dog.bark(); }
  is Animal animal -> { handle(animal); }
end
```

is rejected because a `Dog` witnesses both predicates. The ordered form is
explicit:

```ores
match first value
  is Dog dog -> { dog.bark(); }
  is Animal animal -> { handle(animal); }
end
```

Arbitrary calls inside guards are opaque to the proof engine unless a future
verified predicate/contract system gives the compiler a sound logical summary.
The checker must never infer exclusivity from undocumented function behavior.

## Lowering contract

Patterns are represented by dedicated Oreslang AST nodes rather than generic
binary operators. The compiler can therefore normalize them into a decision
DAG and constraint representation before backend lowering.

The language contract is independent of Java object identity. A backend must
implement Oreslang type tests through Oreslang type/constructor metadata:

- stable type identity;
- superclass/subtype edges;
- implemented interface/trait edges;
- constructor tags for sum types;
- reified generic information only where the language declares it reifiable.

The Java/Truffle evaluator is a reference/bootstrap backend. Its internal Java
objects may represent Oreslang values, but Java `Class.isInstance`,
reflection, or arbitrary host `instanceof` relationships are not Oreslang
type semantics.

See `NATIVE_RUNTIME_ABI.md` for the native/JNI boundary.

## Value-producing `match` and statement-only `do match`

The canonical value-producing form is `match subject over ... end`.
It can be used in a binding, as an argument, or directly after `return`:

```ores
val Foo matched = match entry over
  Some(value) -> { return new Foo(1); }
  None -> { return new Foo(2); }
end;

return match entry over
  Some(value) -> { return new Foo(1); }
  None -> { return new Foo(2); }
end;
```

In a value-producing `match ... over`, the terminal top-level
`return value;` in each braced arm produces **the match expression's**
value, not a function return. The enclosing `return match ...` exits
the function only after match evaluation. Results must be non-`void`,
type compatible (or an explicitly declared union), and exhaustive.

The **`do match`** statement explicitly executes an arm for side
effects without producing a match result:

```ores
do match entry over
  Some(value) -> { process(value); }
  None -> { handleMissing(); }
end

// Values are evaluated and discarded; the containing function continues.
do match entry over
  Some(value) -> { return new Foo(1); }
  None -> { return new Foo(2); }
end
```

Within `do match`, a terminal `return value;` is **arm-local**:
its expression runs, its result is discarded, and the enclosing function
continues. An arm may instead finish normally after side effects.
`return;` without a value is not an arm result; omit it. Earlier or nested
returns which could escape the containing callable are rejected.
Nested lambda bodies retain their own lexical return scopes.

`do match ... over` is a statement, never an expression: assigning it or
returning it as a value is a compile error. `do match` requires `over`.
Standalone `match ... over` without `do` is rejected in statement position
to prevent silent discarding. The retired `no-return` spelling is invalid.

For source compatibility, legacy bare statement `match value ... end`
without `over` still retains enclosing-function `return` semantics.
This compatibility form is not synonymous with `do match`.

The same `do` marker explicitly opts into side-effect-only **static**
channel selects:

```ores
do select {
  case readch incoming: val item
    process(item);
}

do nb select {
  case readch incoming: val item
    process(item);
}
```

In `do select` / `do nb select` arms, a terminal
`return value;` evaluates and discards its value **within that arm**,
never returning from its enclosing function. Terminal `return;` simply
finishes the arm. Earlier or nested returns are rejected. Unprefixed
legacy static selects preserve their historical return behavior.

Static `select { ... }` and `nb select { ... }` also remain accepted
in the current runtime for backward compatibility. Static `nb select`
schedules a detached actor continuation and requires an actor domain;
it **does not yet** return a completion `Future<void>`. Dynamic
`select from cases` produces `SelectResult`, while
`nb select from cases` produces `Future<SelectResult>`. Consequently,
`do select from cases` and `do nb select from cases` are rejected
rather than silently detaching or dropping a result/future. Value-producing
static select arms and future-backed static `do nb select` require a
separate runtime/AST change, not merely a parser alias.


## Value-producing matches

Both brace-delimited and `match ... end` forms accept value-producing arms:

```ores
val getter = match value {
  structural { name: String } as named -> || -> { return named.name; }
  _ -> || -> { return "unsupported"; }
};
val name = getter();
```

The scrutinee is evaluated once. Only the selected arm body executes; an
explicit closure body executes when the resulting function is called.

A plain expression supplies an arm's result. A block supplies its final
expression without a semicolon. Block-local variables stay local; defers and
lexical guard cleanup execute when the block exits, after evaluating the result.
For legacy brace-delimited expression matches, `return` in a block exits
the enclosing callable; value-producing `match ... over` instead uses
its terminal arm `return` as the match result, and `no-return` evaluates
and discards any terminal arm-local return value. Braces never create an
implicit closure. Explicit closures use Oreslang's existing `|| -> { ... }`
or `|parameters| -> { ... }` syntax and explicit return statements.

The checker enforces one result type across normally completing arms. It uses
an expected contextual type or infers a common assignable type. It rejects
incompatible results and missing final values. It does not invent a union or
fall back to `Unknown`/`Any`. Explicit union annotations may admit heterogeneous
values. Function results follow ordinary callable compatibility rules, including
parameter authority, async mode, and result types. Exiting arms have no result
value and do not constrain the type of normally completing arms.

Expression matches retain the same exclusivity/exhaustiveness obligations,
structural visibility rules, capability admission, and ownership alias rules as
statement matches. Returned closures may capture an arm binding under the
ordinary ownership rules. Borrowed results cannot outlive arm-local owners.
A module namespace is not an ordinary value and cannot be a scrutinee/result;
a qualified module path must select an exported non-module value.

## Inline structural method requirements

Structural targets can combine field types and interface-style method signatures:

```ores
structural {
  name: String;
  fnc greet(String prefix) => String;
} as named -> named.greet("Hello ")
```

`is`, checked `as`, optional `as?`, and typed match bindings use the same public
declared shape. Inherited instance methods participate; private/static methods
and function-valued fields do not satisfy method slots. Checking a shape never
executes a method. Overloads are distinguished by arity, with contravariant
parameter types, covariant results, invariant mutation authority, borrow modes,
and async declaration mode. An `async fnc load() => int` requirement differs from
a synchronous `fnc load() => Future<int>` requirement.

Methods stay direct-call-only on the bound view. An explicit closure can capture
the view and call the method later. Generic inline method declarations and `self`
types are rejected; concrete parameterized signature types are supported.
This introduces inline contracts, not new nominal trait or struct declarations.
