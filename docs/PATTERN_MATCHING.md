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

## Value-producing matches and explicit no-return matches

The canonical, value-producing form is `match subject over ... end`.
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

In a **value-producing** `match ... over`, the last top-level
`return value;` in each braced arm produces the **match expression's**
value, not a function return. The enclosing `return match ...`
performs the function return only after the match finishes. Results must
be non-`void`, type compatible (or explicitly declared union), and
exhaustive.

To execute branches without producing an overall match value, use the
explicit contextual modifier **`no-return`**:

```ores
match entry no-return over
  Some(value) -> { process(value); }
  None -> { handleMissing(); }
end

// An arm may produce a value, but the match discards it.
match entry no-return over
  Some(value) -> { return new Foo(1); }
  None -> { return new Foo(2); }
end
```

In a `no-return` match, a terminal `return value;` is an **arm-local
return**: its expression is evaluated and discarded. An arm can
instead simply finish after its side-effect statements. A
`no-return` match has type `void`, and cannot be assigned, returned
as a value, or used in any expression context. This avoids confusing
"discarding a branch result" with returning from the enclosing function.

`no-return` is a contextual, hyphenated modifier **after the match
subject and before `over`**, not an expression operator or a global
reserved identifier. `no - return` with spaces is not the modifier,
and `no-return` requires `over`. Standalone `match ... over`
without `no-return` is rejected to prevent silently discarding a value.
Legacy bare statement matches (`match value ... end` without `over`)
retain their original function-level `return` behavior for source
compatibility; the new `no-return` form has deliberate arm-local behavior.

Arms with a local `return value;` must place it at the end of the
arm. Earlier or nested `return` statements that might exit the
enclosing callable are rejected. Lambda bodies have their own
lexical return scope. `return;` is not an arm result; for a
`no-return` match, simply omit it. Branch decisions evaluate the
scrutinee once, execute only one chosen arm, and retain existing
ownership/capability checks.

Both modes enforce recursive nested `Option`/`Result` coverage,
exclusive pattern disjointness, and unreachable finite-constructor
arm diagnostics. The pattern `Some(inner)` covers both inner
`Option` variants; `Some(Some(value))` does not. Fallback
patterns remain `_`, `else`, `default`, and `Symbol.default`.
`||` remains lambda syntax, never a wildcard. No `match-and-return`
or `match ... with` form is accepted.

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
