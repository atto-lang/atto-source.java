# ADT and typed destructuring contract (October 2026)

## Status and scope

This change adds **per-binding declared types** to the existing Java/GraalVM
tuple, sequence, record and for-of destructuring pipeline. The ADT discussion
below describes the required semantics and explicitly distinguishes features
already supported by the Java frontend from work needed for user-defined tagged
enums and LLVM.

Products and sums must not be conflated:

- **Product values**: finite `Tuple[T1, ...]` and closed records
  `{field: Type, ...}` can be destructured directly because their shape is
  statically known.
- **Sums**: `Option<T>`, `Result<T,E>`, and future user-defined nominal
  `enum` variants must first select/prove a constructor with `match`.
  Unconditional `const [x] = maybe;` is not a valid sum elimination.
- **Unions**: destructuring `A | B` is permitted only if every alternative
  admits the requested projection. The extracted type is the union of each
  alternative's projected type; an annotation may constrain it but must
  satisfy the ordinary safe assignment relation.
- **Named patterns** (future/user-defined message domains): these denote
  closed *type domains*, not runtime constructors and not erasure of nominal
  enum tags. They cannot be used to manufacture payload values.

## Type annotations on local destructure bindings

```ores
fnc user(): Tuple[int, string] {
  return (42, "Ada");
}

fnc record(): {id: int, name: string} {
  return struct{id: int, name: string}{id: 42, name: "Ada"};
}

// Existing inferred form remains valid.
const [id, name] = user();

// Equivalent checked annotations; never implicit casts.
const [int id, string name] = user();
const [id: int, name: string] = user();

// Record projections retain field names.
const {id: int, name: string} = record();
const {int id, string name} = record();

// Typed rest captures the actual remaining shape.
const [int first, ...tail: Tuple[string]] = user();
const {id: int, ...tail: {name: string}} = record();

// Inline mutability declarations preserve the existing propagation rules.
[const int id, let name: string] = user();
name = "Grace";
```

Every explicit annotation is an **upper bound on the extracted value's
static type**; the compiler resolves aliases/generics and verifies
assignability before inserting the lexical binding. The declared type must
not erase readonly provenance, grant mutable-referent authority, convert a
borrow into an owning value, turn a move-only payload into Copy, or alter the
constructor tag. There is no dynamic cast, reflection or host-Java fallback.

The `const`, `val`, `let` and `mut` modifiers remain orthogonal to the
type annotation. An outer kind seeds the pattern; an inner explicit kind
changes inheritance for following names. `_` discards one sequence slot
and cannot be typed; a rest binding must occur last. A bare `_` is invalid
as an object field name. Duplicate *local names* are invalid even if their
types match. Any duplicate record field must already be rejected by the
record type/literal validator.

The `for const [T a, U b] of iterable` form uses the same per-element type
checks on the iterable element. This does **not** authorize the separate
`for const T item of iterable` surface, which remains reserved.

## Tagged sums and typed pattern bindings

On today's Java frontend, `Some/None` and `Ok/Err` support constructor
patterns, and nested nominal type patterns allow:

```ores
fnc extract(Option<int> value): int {
  match value over
    on Some(int item) -> { return item; }
    on None -> { return 0; }
  end
}
```

The nested `int item` is a *refined alias* in the successful arm, not a
copy. A wrong type must make the arm unreachable or invalid rather than
coerce the payload. Repeated use of a moved payload must remain an ownership
error; loans cannot outlive the arm or escape into an actor message.

For future user-defined tagged sums, require a separate compiler-parity
milestone before promising this example as executable:

```ores
define enum Event as
  Created: Tuple[int, string] = 1
  Deleted: int = 2
end

do match event over
  on Event.Created(int id, string name) -> { /* ... */ }
  on Event.Deleted(int id) -> { /* ... */ }
end
```

User-enum constructor qualification, payload layout, stable tag identity,
case-sensitive field labels, exhaustive constructor checking, AST traversal,
backend lowering and wire codec parity must be delivered together.
The present Java mainline's `ConstructorPattern` type checker recognizes
`Option` and `Result`; it does not prove acceptance of arbitrary
`Event.Created` syntax. LLVM source-level enums and typed match lowering
are a separate, outstanding implementation task.

## Security, correctness and runtime invariants

1. **Static shape**: a tuple projection has exactly the declared arity
   (unless a final rest capture is present); a record projection has every
   selected field. All members of a union must satisfy these checks.
2. **Failure atomicity**: evaluate the source once; validate its runtime
   shape/bounds/constructor before making any lexical binding observable.
   Failed destructuring must not leave half-initialized locals.
3. **Ownership**: destructuring an owned sum/tuple never duplicates ownership.
   Projection transfers or borrows each payload exactly once according to
   the source's ownership and the binding modifiers. Discarded move-only
   components must be dropped exactly once. A type annotation cannot waive
   borrow, drop or actor-isolation checks.
4. **Borrow lifetime**: projected borrows are lexical loans. No projection
   into an owned tuple/record, async suspension, `await`, `select`, closure
   capture or actor payload may create an untracked escaping borrow.
5. **Exhaustiveness**: no guards may be assumed true by default. A tagged
   sum match is exhaustive only when every constructor is covered or there
   is an explicit unguarded fallback. A wildcard does not turn an invalid
   payload type into a valid constructor pattern.
6. **Ordering**: current canonical `match value over on ...` lowers
   source-ordered; legacy bare `match value ...` preserves its older
   exclusivity contract. These semantics must be deliberately reconciled
   in one language contract before 1.0, not silently inferred from stale
   documentation.
7. **AOT/wire**: constructor tags are semantic identity, not local pointer
   addresses or compiler-layout ordinals. Java and LLVM must validate the
   same canonical case/shape descriptor, schema version and payload types
   before accepting actor messages. No reflection, dynamic `Any` cast or
   handwritten host callback may skip that proof.

## Required parity matrix

| Case | Java front end | LLVM source compiler |
| --- | --- | --- |
| Typed tuple/record destructure | Implemented by this change; CI pending | Not yet demonstrated |
| Typed for-of destructure | Implemented by this change; CI pending | Not yet demonstrated |
| Typed `Some` / `Ok` / `Err` patterns | Existing general nominal type-pattern path; regression added | Not yet demonstrated |
| User-defined qualified enum cases with typed nested bindings | Future milestone | Future milestone |
| Exhaustive nested constructor proof | Extend proofs as enums become real | Future milestone |
| Ownership-preserving projection/drop and cross-actor wire round trip | Requires deeper runtime regression | Requires native proof |

Before merge, run Maven/JUnit for `TypedAdtDestructureTest`, the existing
`ReturnedDestructuringTest`, `DestructureAndForOfMutabilityTest`,
`PatternMatchingHardeningTest`, and `OptionResultSemanticsTest`, followed by
AOT/hybrid and LLVM parity probes on *exact-source* revisions. A passing
JVM type-check test is not evidence of LLVM implementation.
