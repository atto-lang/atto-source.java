# Algebraic type intersections — phase 1 (Java, October 2026)

## Implemented contract

This document records the narrow **implemented** Java subset. It does not claim
general class/trait/method intersections or LLVM lowering.

- `A | B` remains a type union. `A & B` is an intersection **in type
  position** and binds more tightly than `|`. Parentheses override precedence.
- Leading `&T` / `&mut T` still denotes a borrow; the new infix operator
  does not create, release or weaken an ownership loan.
- `Ast.TypeRef.intersection` flattens nested intersections, deduplicates and
  canonicalizes operand order. The parser represents the intersection until the
  type checker resolves it.
- Semantic resolution currently admits identical types and compatible structural
  records; read-only members can narrow through an identical alternative in a
  union (`readonly {x: int|string} & readonly {x: int}` in the type model).
- Structural record intersections merge disjoint required keys. Conflicting
  **writable** keys must have identical resolved types. No last-wins merge,
  numeric coercion, or inferred setter conversion is allowed.
- Record-level read-only capabilities are not per-field: mixing a read-only
  record with a writable record is rejected until field-specific capabilities
  exist. A read-only view never becomes a writable view by structural assignment.
- Writable record fields are invariant under assignments, even when Oreslang
  permits numeric conversions or a read-only field could covariantly narrow.
- Incompatible primitive, distinct nominal, unresolved generic/unknown and
  unsupported intersection shapes are rejected rather than represented as
  arbitrary fabricated classes or method tables.
- Runtime type matching uses conjunction; return-value shaping verifies **every**
  intersection operand (not the first one that succeeds).
- The ownership pass independently rejects intersection record members that are
  borrowed, move-only, or reached through unresolved generic aliases. This is a
  deliberately restrictive safety gate: field-projection borrow/move tracking is
  not yet implemented for intersection aliases. Copy-only payload fields are the
  fully admitted first phase.

## Examples (source syntax)

```ores
type Header = {request_id: int};
type Message = {payload: string};
type Event = Header & Message;

fnc id(Event event): int { return event.request_id; }

type Broken = {value: int} & {value: string}; // error
type BrokenMethodField =
    {value: typeof fnc() => int} & {value: int}; // error
```

## Soundness and coverage boundaries

1. This phase has **no generalized intersection type** in the `Types.Type`
   hierarchy: it constructs an existing supported type or emits a compile error.
   The difference is intentional. Do not bypass the checker by pretending an
   unresolved intersection is a nominal subtype.
2. `Types.isAssignable` is a conversion-aware relation (including numeric
   widening), **not set intersection**. Intersections must never reuse numeric
   coercion as a proof of overlapping runtime values.
3. No explicit `Never` bottom type or algebraic normalization of arbitrary
   `(A|B)&(C|D)` is implemented. Union intersections only match identical
   alternatives; all other cases fail closed pending a bounded satisfiability
   algorithm. Do not promise full distributivity at this stage.
4. The record AST only carries field types, not method declarations,
   optionality or per-field mutability. Function-valued fields are not
   equivalent to instance methods. Method overload intersection requires a
   separate method-set model and effect/borrow-aware resolution.
5. `Unknown` and generic constraints are unresolved; permit their
   intersections only after actual constraint/witness checking exists.
6. Oreslang actor mailbox domain types must remain closed and serialize using
   stable nominal enum tags. A structural intersection never constructs
   or forges an actor message tag.
7. Exhaustiveness of tagged enums, `Option` and `Result` is a separate
   obligation (see the draft nested payload fix in Java PR #812).
8. LLVM must reject unsupported intersecting type expressions rather than
   silently erase an operand. **Java execution is not LLVM feature parity.**
9. All new behavior needs a full Java compiler / interpreter / ownership suite,
   sanitizer-enabled native parity and differential negative fixtures before
   being promoted from draft.

## Required next iterations

- AST and semantic representation of method sets (name, signature, effects,
  mutability, optionality, visibility and origin) distinct from data fields.
- True nominal/interface/trait intersections with coherence checks, explicit
  implementation witnesses, and deterministic default-method disambiguation.
- Per-member readonly/getter/setter contracts, plus correct use-site readonly
  borrow propagation through nested records and generic substitution.
- Bounded distributive normalization, overlap/inhabitance decisions, `Never`,
  and generic constraint solving without exponential blowup.
- Exhaustive ADT cases in Java and LLVM, ABI/MessagePack parity and tests that
  exercise execution, throws, suspend, actor isolation and borrowed payloads.
