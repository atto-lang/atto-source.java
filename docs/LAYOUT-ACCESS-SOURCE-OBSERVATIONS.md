# Typed Oreslang source-access observations (v0)

This branch adds the first **actual Oreslang source-to-access observation** bridge:
`OresCompiler.inspectLayoutAccesses(String source)`.

Unlike static layout examples manually serialized to JSON, the entry point
first executes the production `Parser`, `TypeChecker` and
`OwnershipChecker`. It then walks a deliberately restricted portion of the
accepted AST for `for ... of ...` member selections.

Example:

```java
var report = OresCompiler.inspectLayoutAccesses(source);
for (var loop : report.loops()) {
    System.out.println(loop.owner() + ": " + loop.directMemberSelectors());
}
```

Each immutable observation contains its enclosing callable, iterable
expression's simple binding name if available, loop binding, syntactically
selected members, and whether only the narrowly recognized direct
expression/binding forms were encountered. Aliases/whole-row references,
computed iterables, awaits, calls, assignments and control-flow branches
make that loop **incomplete** for any optimization recommendation. The pass
traverses known enclosing statement structures and limits total statements,
loops and distinct members to defend against pathological inputs.

**Critical trust boundary:** even a successful, supported observation is
*not* complete layout evidence. An AST `MemberExpr` can represent language
property/member behavior, not necessarily a scalar storage column.
The current compiler has not proven the physical allocation layout,
row cardinality, stable handles, ownership-domain relocation,
cache/vector characteristics, hotness, or loop-carried dependencies.

Therefore `Report.completeForLayoutRecommendations` is always `false`.
Do **not** translate this report to Rust layout IR
`evidence_complete=true`, infer fields' byte widths from name spelling,
or automatically migrate SoA/AoSoA/ECS stores.

## Next proofs to enable source-based recommendations

1. Retain typed member-resolution identity and exact source spans in the
   validated HIR; distinguish fields from properties and function results.
2. Resolve owning physical store identity (`InvertedArray<T>`,
   `LayoutStore<T>`, ECS table, heap arena) and fields' actual ABI widths.
3. Prove aliasing/borrowing and record-vs-field access through CFG
   including function calls, suspension and actor-domain crossings.
4. Join with independent profile evidence for hotness, row counts,
   gather access order, migration counts, cache misses and SIMD target.
5. Emit the existing strict versioned Rust `--layout` IR only for complete
   verified facts; carry unknowns explicitly and suppress all advice if
   any region is invalid.
6. Differential conformance across Java/GraalVM, LLVM/C++, Rust and,
   eventually, the Oreslang-native analysis functions.

## Test

`mvn -B -Dtest=TypedLayoutAccessEvidenceTest test`

Tests include synthetic AST cases for direct fields, aliasing, computed
iterables and unsupported calls, plus an accepted Oreslang `for-of`
source that passes through the actual parser/type/ownership admission
pipeline. These Maven tests have not been verified on a GitHub runner.
