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

## Explicit arm-local return matches

The optional, contextual `match-and-return` spelling is a value-producing
expression with *explicit* branch results. Unlike a normal `return` statement,
its arm's terminal `return value;` completes **only the match expression**;
the enclosing function continues afterward.

```ores
fnc classify(Option<Option<int>> entry): int {
  val int answer = match-and-return entry over
    Some(Some(value)) -> { return value; }
    Some(None) -> { return -1; }
    None -> { return -2; }
  end;
  return answer + 10;
}
```

`match-and-return` is a single hyphenated contextual operator; spaces around
its hyphens do not produce the operator. It permits `match first`-style
ordering only when priority matching is explicitly requested separately.
Both `over ... end` and braced-match delimiters are supported. Arm bodies
**must** be braced, must end in a top-level `return <value>;`, and cannot use
implicit final-expression results. `return;`, missing terminal returns, and
unbraced result expressions are errors. The `return` is lowered to the result
expression of the existing typed `MatchExpr`; it is not a new function exit
or an implicit closure. Earlier or nested `return` statements inside an arm's preceding
control-flow statements are rejected, rather than silently escaping the
enclosing function. A `return` inside an independently nested lambda still
belongs to that lambda.

All branches remain subject to the same exclusivity, recursive
`Option`/`Result` exhaustiveness, result-type compatibility, ownership, and
capability checks. A fallback after full coverage is unreachable. `Some(inner)`
covers *both* inner Option variants; `Some(Some(x))` does not.

The statement form `match subject over ... end` is still a side-effecting
statement, not a value assigned to a variable. The historical expression form
`match subject { pattern -> expression ... }` remains accepted on this
stack's compatibility branch; `match-and-return` deliberately requires
explicit arm returns rather than implicit expression-block values. Neither
form interprets `||` as a wildcard pattern.

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
`return` in a block exits the enclosing callable. Braces never create an
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
