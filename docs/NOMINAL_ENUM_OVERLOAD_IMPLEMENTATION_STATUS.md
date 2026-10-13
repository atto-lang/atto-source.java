# Nominal enum overload resolution — executable admission foundation

Status: implementation branch stacked on draft Java tagged-enum PR #623.
The compiler and evaluator **do not yet admit** same-name/same-arity
declarations, even if their nominal enum parameters differ.
This is an intentional ABI safety gate, not a claim of feature completion.

## Compiler-owned static policy implemented

`NominalEnumOverloads` implements:

- Prevalidated enum discriminator eligibility: every variant is nullary or
  has only directly declared primitive scalar fields (including bounded
  `Tuple` products of scalar fields). No structural record, classes, maps,
  lists, actor references, nested enums, borrows, unknown/generic or dynamic
  fields are accepted as overload discriminators.
- Canonical enum symbol identity derived from the declaring module and enum
  declaration. Two enums with the same cases and primitive payload shapes
  still have different nominal IDs. Ambiguous unqualified names fail closed.
- Immutable signature keys composed only from **primitive type tokens** and
  **resolved nominal enum IDs**. Structural, mutable, borrowed or unresolved
  parameters do not enter same-arity overload lookup.
- Pairwise family comparison: different arities are distinct; for the same
  arity, every differing position must contain **distinct eligible enum IDs**.
  All non-discriminator positions must be identical admitted primitive IDs.
  Different primitive types alone never create an overload.
- A compile-time-only unique signature selector requiring explicit proven
  nominal enum IDs at discriminating positions. Zero or multiple matches fail.
  There is no inspection of enum cases, payloads, structural members, runtime
  class kinds, or conversion ranking.

Compiler diagnostics in `TypeChecker` now distinguish:
(1) a potentially valid nominal enum overload that is deliberately **not
enabled** until callable slots and prebound invocation paths exist, and
(2) invalid overloads discriminated by primitive/structural/generic types.

The Java source evaluator and `CallableSelector` continue using arity keys.
They MUST NOT accept two same-name/same-arity declarations until the compiler
has independently prebound every direct call and the evaluator dispatches
only by that emitted callable ID. The same rule applies to virtual dispatch,
imports/exports, callbacks, actors and first-class function references.

## Remaining atomic implementation milestones

1. Add a compiler-produced, immutable and per-program **resolved call table**
   containing exact declaration/specialization IDs (no global mutable cache
   and no evaluation-time type introspection).
2. Extend top-level module function tables to permit closed, pairwise distinct
   eligible enum signature IDs, and make ordinary external or dynamic
   invocation of an ambiguous same-arity family fail rather than choose first.
3. Extend class/interface method selectors and virtual override tables with
   those exact IDs. Resolve inherited ambiguity and public/private authority
   at compile time; do not fall back to parent-search order.
4. Preserve those bindings across tail calls, suspension, actors and
   imported code-unit linking; add compiler ABI/hash checks.
5. Implement same static proof and fail-closed ABI in LLVM.
6. Run focused Java/JUnit, full Maven, GraalVM native/AOT and LLVM/CTest
   suites and shared cross-backend positive/negative fixtures at exact heads.

## Migration rule

Do not implement this by changing only `TypeChecker.putFunctionSlot` or
`CallableSelector`. That would create apparently accepted programs which
the runtime might dispatch to the wrong overload.
