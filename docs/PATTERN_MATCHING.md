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

match result over
  on Some(value) -> { use(value); }
  on None -> { recover(); }
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
- `x is type T` is a nominal type predicate and flow refinement.
- `x is type T name` additionally introduces a lexical refinement alias.
- Prefix `is T [name]` remains the compact type-pattern form inside `match`
  arms, where it is syntactically unambiguous.
- `x matches P` tests a complete pattern and exposes its bindings only on the
  successful control-flow edge.
- `x as T` is a checked cast. A failed runtime cast raises `CastError`.
- `x as? T` is a non-panicking cast with type `Option<T>`.
- `match x over ... on P -> { ... } end` is ordered pattern matching
  with conservative exhaustiveness and dead-arm checking.
- The legacy `match x` / `match first x` forms remain accepted; the
  current checker also evaluates these in source order.
- `switch x` is constant/equality dispatch and never performs destructuring.

A refinement binding is not a copy. If `dog` is introduced by
`animal is type Dog dog`, both names identify the same ownership place. Moving
through either name consumes the same move-only value.

## Ordered match and conservative exhaustiveness

In current Oreslang, `match value over` dispatches in source order. Overlapping
patterns are allowed, but a later arm completely subsumed by an earlier,
unguarded arm is rejected as dead code. Exhaustiveness is a separate
proof obligation: every possible scrutinee value must match at least one arm.
No match may silently fall through.

A pattern on a sum variant is exhaustive *for that variant* only if its payload
is covered. These are **not** exhaustive:

```ores
match value over
  on Some(0) -> { return 1; }
  on None -> { return 0; }
end
```

`Option<int>` also admits `Some(1)`. Likewise `Ok(0)` never covers every
`Ok(int)`. The checker proves nested `Option` and `Result` cases recursively
and recognizes complete finite Boolean partitions:

```ores
match value over
  on Some(true) -> { return 1; }
  on Some(false) -> { return 2; }
  on None -> { return 0; }
end
```

Guards do **not** establish unguarded exhaustiveness, even if they appear
tautological. An unguarded `on _` is the explicit catch-all. For open domains
or patterns the checker cannot yet prove complete, it rejects the match rather
than guessing. Type-pattern coverage must never treat `Unknown` or an
unconstrained generic as a provably finite or identical runtime type.

Do not confuse this ordered checker with the older design proposal for
exclusive/non-overlapping matches. That exclusivity proof is not enforced
by the current `match ... over` implementation.

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
