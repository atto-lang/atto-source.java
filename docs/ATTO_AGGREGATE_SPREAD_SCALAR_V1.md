# Closed aggregate spread: executable scalar v1

Status: **Java/Graal guest implementation candidate on this branch**.
Parsing already recognized aggregate spreads, but older type/ownership/runtime
paths rejected them. This slice adds a deliberately narrow admitted subset.
It is not LLVM, .NET, Wasm, Forge or Native Image parity evidence.

## AttoLang source

```atto
const source = record{id: 42, name: "Ada", age: 37};
const updated = record{...source, age: 38, active: true};
const renamed = record{user_id: source.id, display_name: source.name};

const point = infer struct{x: 3, y: 4};
const moved = infer struct{...point, x: 5};
```

Aggregate **field names** are statically known; a spread does not create a
dynamic map or support computed field names. The result category is chosen by
the target literal: `record{...}` produces a readonly structural record,
while `infer struct{...}` produces a distinct concrete closed struct shape.
A source struct may be expanded into a record, and vice versa, by constructing
a new value; this is not implicit assignment between the two categories.

## Evaluation and conflict semantics

- Each initializer item is evaluated exactly once, in left-to-right source
  order, including each spread operand.
- A spread reads the closed source's named fields; a later field or spread
  replaces the value of an earlier field with the same key.
- Repeating an **explicitly named** target key is still rejected at AST
  construction, even if spread expressions intervene.
- The source aggregate is not consumed or modified by scalar spreading.
- No reflection, host Map traversal, type erasure or unknown-shaped source is
  accepted. The runtime also checks that the value is an internal guest
  closed struct or record.

## Ownership/typing gate

Only proven `Copy` scalar source fields are currently eligible. The Java
type checker admits the compiler's primitive int/float/bool/string domains
(including string and boolean literal types); the ownership checker also
requires the corresponding source TypeRef to be a proven scalar `Copy`.
Nested aggregates, arrays, maps, classes, actor references, channels,
borrowed references, MutexGuards, callbacks and unknown/generic source field
types must not be spread until an independent field-wise copy/move and
exception-cleanup proof is implemented.

Explicit `struct{schema}{...initializer}` spreads remain unsupported, because
each declared member's definite initialization must be proved separately.

A renamed field is expressed with an explicit static initializer:
`record{user_id: source.id}`. Automatic `project<T>()`, key-based
`map_fields`, and reflective iteration are **not implemented** in this
stage; they need an independent target-schema and ownership design.

## Required verification

Execute `AggregateScalarSpreadTest` against the exact branch head, then
verify parser/type/ownership/runtime negatives and one-evaluation/ordering
across all supported backends. A parser pass, JDK compilation, an unassigned
GitHub Actions run, or a source-free smoke is not evidence of guest execution
or cross-backend parity. Keep the rollout candidate-only until the actual
Java/Graal guest test succeeds, then address native AOT, LLVM, .NET, Wasm
and Forge separately.
