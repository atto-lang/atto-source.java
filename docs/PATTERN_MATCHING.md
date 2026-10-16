# Pattern matching, structural matching and refinement

The canonical form is `match value over ... end`. Every canonical arm starts
with contextual `on` and uses `->` before a block. The subject is evaluated
once. Arms are considered **in source order**; the first matching pattern whose
guard succeeds executes. A provably unreachable later arm is a compile-time
error. A final `on _` covers remaining cases.

```ores
fnc classify(Animal value): int {
  match value over
    on structural { name: string, age: int } detailed -> { return 2; }
    on structural { name: string } named -> { return 1; }
    on _ -> { return 0; }
  end
}
```

Structural patterns test a value's **public shape**, not its `implements`
edge. `structural Trait name` and `structural { field: Type } name` allow
additional public fields and methods. Required methods must have compatible
signatures, mutability and effect contracts. Recursive tuple patterns use
exact positional arity, e.g. `on (structural {x: int} x, _) -> { ... }`.
Match-list `[...]` patterns are unsupported. Canonical match has no
`default` or `else` keyword; use `on _`.

A structural binding is a read-only view; pattern matching must never
upgrade its capabilities, move the owner, erase an actor identity, or let a
borrow escape across a suspension boundary. A contextual `when` guard
cannot change those rules.

For callable **parameters**, write `fnc use(structural Shape value): ...`,
`routine use(structural Shape value): ...`, or a class method with the same
parameter syntax. Passing a compatible argument creates an **implicit
read-only borrow**, and does not consume its owner. Unmarked `Shape`
parameters remain nominal. Declaration-level `structural fnc`,
`structural routine`, a `structural` method, or `@structural` mark the
callable, not all of its parameters; these are distinct AST properties.

`match` used as an expression yields a value from each arm, and an optional
`match<T>` constraint applies to its result. `do match` is procedural:
arm `return` statements can exit the enclosing routine. The old spelling
`match value ... end` remains a compatibility form using disjointness
and coverage proofs unless written as `match first value`. Do **not**
apply legacy exclusivity requirements to canonical `match ... over`.

Related operations remain distinct: `x is y` tests identity;
`x is type T` provides nominal refinement; `x matches P` is a pattern
predicate with success-branch bindings; `x as T` and `x as? T` are
casts. `switch` does equality dispatch, not destructuring.
`infer struct{...}` creates an inferred type, not a structural pattern.

Java/GraalVM is the reference/bootstrap backend. Matching must use guest
type and public-member metadata, not arbitrary Java reflection identity.
Other backends must implement shape checking, ownership, ordered reachability
and typed bindings before accepting the feature, otherwise **fail closed**
without emitting success-shaped output. See `docs/grammar.ebnf` and
`StructuralCrossSurfaceParityTest`, `StructuralTypingHardeningTest`,
and `StructuralTuplePatternMatchTest`.
