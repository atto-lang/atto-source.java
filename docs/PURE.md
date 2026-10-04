# `pure` keyword and write-effect contract

Status: **normative compiler contract**

`pure` is a reserved callable modifier. In Oreslang, `pure` means **write-pure**, not mathematically referentially transparent.

## Core guarantee

A successfully checked `pure fnc` or `pure routine`:

- may read values from its parameters, module scope, imports, and other statically resolvable outside state;
- may allocate and mutate invocation-local state;
- must not assign to module/global/captured outside bindings;
- must not mutate state reachable from an input parameter;
- must not mutate state reachable through an alias to outside state;
- must not declare a `mut` parameter;
- may call only operations whose write effects are statically proven compatible with `pure`.

This definition intentionally permits outside reads. Consequently, `pure` alone does **not** imply determinism, referential transparency, memoizability, constant folding, or freedom from time/random/environment observations. Those are separate effect facts.

## Syntax

`pure` is globally reserved.

```ores
pure fnc add(int a, int b): int {
  return a + b;
}

pub pure routine normalize(): int {
  let int result = outside_seed;
  result = result + 1; // local write: allowed
  return result;
}
```

Modifier order is semantically order-independent where the parser accepts it. Formatting should prefer:

```text
[visibility] [pure] [trap] [lex|nlex] fnc ...
[visibility] [pure] [trap] [lex|nlex] routine ...
```

V1 restricts `pure` to top-level/module `fnc` and `routine` declarations. It rejects actor entry points and async callables until those effect boundaries have explicit typed semantics.

## Reads from outside state

This is legal:

```ores
let int threshold = 10;

pure fnc above(int value): bool {
  return value > threshold;
}
```

The read of `threshold` is allowed even if another part of the program can later mutate it. The resulting function is write-pure but not necessarily deterministic across invocations.

## Inputs are read-only

A pure callable cannot opt an input into mutation:

```ores
pure fnc bad(Bar mut value): Bar { // ERROR
  value.foo = "changed";
  return value;
}
```

Mutation through a parameter, borrow, member, index, or alias derived from the parameter is also rejected.

```ores
pure fnc bad(List<int> values): int {
  values[0] = 9; // ERROR: input mutation
  return values[0];
}
```

The same rule applies to aliases:

```ores
pure fnc bad(List<int> values): int {
  val alias = values;
  alias[0] = 9; // ERROR: still aliases input
  return 9;
}
```

## Outside writes are forbidden

```ores
let int counter = 0;

pure fnc read_counter(): int {
  return counter; // allowed
}

pure fnc bump(): int {
  counter = counter + 1; // ERROR
  return counter;
}
```

A nested closure created inside a pure callable does not provide an escape hatch: it is checked under the same write-effect contract.

## Local mutation is allowed

```ores
pure fnc sum(List<int> values): int {
  let int total = 0;
  for (val value of values) {
    total = total + value;
  }
  return total;
}
```

Locally created storage may be mutated as long as it does not alias input/outside mutable state.

## Transitive calls

Write purity is transitive. A pure callable may call another source callable only when the compiler can prove that target is pure.

```ores
pure fnc one(): int { return 1; }

pure fnc two(): int {
  return one() + 1; // allowed
}
```

Unknown, imported, indirect, host, or method calls fail closed until their effect summaries are part of the type/contract system. This is intentionally conservative.

## Interaction with `lex` and `nlex`

`pure` and capture mode are orthogonal:

- `lex` is lexical capture and is the default;
- `nlex` forbids capture of enclosing activation-local bindings;
- `pure` governs writes.

An `nlex pure fnc` may still read module/import/top-level state because `nlex` is an activation-capture barrier, not a global lookup ban.

## Interaction with `trap`

`pure trap fnc` is legal when the body otherwise satisfies the pure write-effect contract. `trap` changes how an uncaught ordinary throw crosses the call boundary; it does not grant write effects.

## Runtime intrinsics

Runtime/compiler intrinsics are only spelled through the reserved `rt` namespace.

```ores
rt copy(value)
rt share(value)
rt take(value)
rt borrow(value)
```

Whether an intrinsic is legal inside `pure` is determined by its effect summary and alias/ownership rules. The compiler must never treat an unknown runtime intrinsic as pure by default.

On the current compiler head, ownership/recovery runtime spellings are reserved and fail closed until their lowering is rebased onto the `rt` AST. This prevents legacy shadowable builtins from silently weakening the contract.
