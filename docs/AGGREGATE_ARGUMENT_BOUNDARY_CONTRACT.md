# Aggregate values across callable boundaries (Java / LLVM audit)

Status: **audit and proposed cross-backend contract, 2026-10-26**.
This document separates already evidenced behavior from proposals. The Java tests
in `AggregateCallBoundaryHardeningTest` are regression specifications, not a
claim of successful CI. Do not enable a feature in language documentation on
the strength of a parser test or source-free smoke alone.

## Four different value categories

| Category | Shape and identity | Call boundary | Mutation / keys |
| --- | --- | --- | --- |
| Fixed tuple | Ordered, statically known slots and slot types | Positional expansion `f(...tuple)` is implemented in Java | Position-based access; arity fixed |
| Closed record | Named, readonly structural fields; field order does not define type equivalence | Explicit destructure then forward is supported in Java; named-field spread remains proposed | Keys must be statically known and retain exact spelling |
| Concrete struct | Closed concrete shape distinct from structural records (with independently checked mutability) | Explicit field access or destructure only when permitted by its type and visibility | Do not silently coerce to a record or gain write authority |
| Map | Keyed collection, not a statically declared callable signature | No implicit fixed-arity expansion or parameter-name binding | Runtime keys and size are not callable ABI evidence |

Java’s `docs/RECORD_SOA_LAYOUT_CONTRACT.md` already distinguishes the
`INFERRED_RECORD` and `INFERRED_STRUCT` AST kinds and says they are not
implicitly interchangeable. The Python/JS notions of object `**kwargs`
are **not** a map-based implementation strategy for Oreslang.

## Named parameters: grammar and binding

The **proposed** value-call form is `sum(right: 23, left: 19)` and
declarations stay `fnc sum(int left, int right): int`. Labels are not
arguments in the runtime ABI. `name: value` is distinct from generic
type metadata `<T=int>`, ordinary assignments `name = value`, and
record literals `record{name: value}`.

The binding plan must resolve a statically known callable, evaluate the
callee and every supplied expression once in *lexical source order*, bind
labels by exact declared formal names, and finally pass evaluated values
in *formal declaration order*. Reject duplicate, unknown, missing,
positionally-colliding, and post-named positional arguments. Method
overloads cannot differ only by argument label; overrides must preserve
inherited parameter names.

**Current evidence:** draft Java PR #2082 adds `Ast.NamedValueArgumentExpr`,
a static name-slot binder, type/ownership checks and both ordinary and
suspendable interpreter direct-function invocation. It **only admits statically
resolved direct functions** at this stage; methods, constructors, first-class
functions, imported callables and record spreading are intentionally gated.
No successful exact-tree Java/GraalVM CI has yet proved this implementation.
LLVM has independent `named-call-*` fixtures, not verified Java/LLVM parity.

## Two deliberately different spread operations

1. `f(...tuple)`: expand statically known ordered tuple slots into
   positional formal slots. Reject a runtime-length list or map where
   fixed arity is required. Enforce exact arity and each slot type.
2. Proposed `f(...record)`: only after named-call binding exists,
   bind the record's statically known field keys to parameter **names**,
   not to positional slots or incidental physical field order.

For the proposed record form, require a closed structural record with
compiler-proven keys. There must be no implicit `Map`, object
reflection, nominal-struct erasure, private-field exposure, automatic
defaults, or dynamic keyword lookup. Reject duplicate keys, unknown
labels, collisions with explicit positional/named arguments, missing
required parameters and incompatible field types. Evaluate the record
producer exactly once, and preserve its original field initializer
order. The values' ownership permissions remain checked per bound
formal slot, not merely at the aggregate envelope.

The initial admission gate should reject mixing a record spread with
other named or positional spreads until a complete source-order,
collision, lifetime and error-cleanup proof exists. In particular,
merely reordering operand expressions by declaration order would
change observable effects.

## Returned values and destructured forwarding

These are three distinct operations:

```ores
fnc values(): (int, int) { return (19, 23); }
fnc named(): {left: int, right: int} {
  return record{left: 19, right: 23};
}
fnc add(int left, int right): int { return left + right; }

fnc tupleCall(): int { return add(...values()); }

fnc namedCall(): int {
  const {left, right} = named();
  return add(left, right);
}

// Proposed only; not admitted in Java:
// fnc recordCall(): int { return add(...named()); }
```

Destructuring a returned value into bindings is not proof that it may
be spread directly into a function. Nested or union aggregates must
validate all alternatives. No values or borrows may escape a destroyed
callee frame, cross an actor boundary illegally, or be consumed twice
through aliases or partial moves. Source order, suspensions, deferred
cleanup, exceptions, cancellation, and readonly/mutability permissions
must be preserved by Java interpreter, native image and LLVM lowering.

## Required conformance matrix

* Positive runtime: tuple return -> spread -> fixed-arity call; record
  return -> destructure -> explicit forwarding; two independent spread
  operands each execute once in source order.
* Negative compile: tuple width mismatch; wrong tuple-slot type;
  dynamic collection spread to fixed arity; record return missing
  requested field; record passed through *positional* tuple spread;
  concrete struct silently coerced to structural record.
* Named-value call (when enabled): positional, reordered named,
  mixed prefix positional + named; duplicate/collision/unknown/missing/
  post-named positional labels; static/instance/constructor/override;
  exact diagnostic and no bypass by generic instantiation.
* Record spread (when enabled): deterministic field-name binding,
  field permutation invariance, quoted Unicode keys, missing/extra/
  duplicate fields, default and overload ambiguity, move/borrow
  collisions, private keys and malicious runtime map substitution.
* Backend parity: parser, formatter idempotence, TypeChecker and
  OwnershipChecker, Java guest execution, Java Native Image AOT/hybrid,
  LLVM native -O0/-O2, and optimization/effect-order tests.

## CI evidence gate

Run the Java focused JUnit suites (including `ArgumentsTupleSpreadTest`,
`ReturnedDestructuringTest` and `AggregateCallBoundaryHardeningTest`)
on the exact candidate tree before merging. For mirror CI, verify the
**full Git tree SHA**, not a selective fixture copy, against the source
PR head before running JDK 25/Maven and Native Image gates. No
source-org token or cross-org checkout should be put into workflow
logs or destination secrets.

LLVM tests must be run on their own reviewed exact LLVM source tree.
A Java smoke cannot certify LLVM behavior, and a failing/no-run workflow
cannot be recorded as a passing conformance check.
