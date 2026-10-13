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
- `x is type T` is a nominal type predicate and flow refinement.
- `x is type T name` additionally introduces a lexical refinement alias.
- Prefix `is T [name]` remains the compact type-pattern form inside `match`
  arms, where it is syntactically unambiguous.
- `x matches P` tests a complete pattern and exposes its bindings only on the
  successful control-flow edge.
- `x as T` is a checked cast. A failed runtime cast raises `CastError`.
- `x as? T` is a non-panicking cast with type `Option<T>`.
- Legacy `match x` **without** `over` is proof-checked disjoint pattern partitioning.
- Canonical `match x over` is **source-ordered**, exhaustiveness-checked dispatch. A provably shadowed later arm is rejected.
- Historical `match first x` remains a source-ordered compatibility form.
- `switch x` is constant/equality dispatch and never performs destructuring.

A refinement binding is not a copy. If `dog` is introduced by
`animal is type Dog dog`, both names identify the same ownership place. Moving
through either name consumes the same move-only value.

## Legacy exclusive match proof obligation

For a scrutinee domain `D`, each explicit arm is normalized to a predicate
`P_i(x)`. A legacy match without `over` is admitted only when the checker establishes:

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

If disjointness cannot be proved, the **legacy exclusive form** is rejected
rather than silently using source order. The canonical `match x over` form
already uses priority semantics, with explicit `on` arms and a completeness
proof. Historical `match first x` also permits priority dispatch.

This is the important semantic distinction **for the legacy (no-`over`) form**:

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

## Canonical ordered error/subtype matching

The current compiler's canonical `over` grammar is intentionally
source-ordered. This is necessary for nominal hierarchies, where a
`FileNotFoundError` also satisfies `FileSystemError`, `IOError` and `Error`:

```ores
match failure over
  on FileNotFoundError missing -> { handle_missing(missing); }
  on FileSystemError file -> { handle_file(file); }
  on IOError io -> { handle_io(io); }
  on Error other -> { handle_other(other); }
end
```

The compiler must reject placing `on Error` ahead of a more specific
unguarded subtype arm, since the latter would be unreachable. A guard
may make an earlier arm conditional; the checker must not treat a
guarded parent pattern as covering all descendants. Open nominal types
cannot be exhaustively enumerated without an unguarded ancestor or
wildcard arm; error classes are currently **not sealed**.

The old contract for disjoint/commutative matching is still meaningful,
but belongs to the non-`over` legacy grammar until a versioned
language-level distinction is finalized. Do not silently switch the
semantics of existing `match ... over` programs while building typed
exception catches.

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
