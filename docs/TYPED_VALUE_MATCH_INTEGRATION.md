# Typed value match: constrained Java/GraalVM integration draft

This branch is a **staged** integration on top of PR #634, not an implicit merge
of PR #567. PR #567 and this branch diverge deeply; defer importing its
continuation state machinery until control-flow and cleanup equivalence is proven.

## Current positive scope

```ores
fnc f(int n): int {
  const value: int = match<ret=int> n over {
    default { return 5; }
    on 7 { return 41; }
  };
  return value + 1;
}
```

This form produces an expression result, **not a containing-callable return**.
The compiler requires an explicit return type, canonical ordered `over`
dispatch, an exhaustively covered scalar subject (int, bool, string, float or
decimal), and exactly one direct `return scalar-expression;` in each arm.
Results are currently limited to the same scalar families. Subject, guard,
and returned expressions allow only literals/names and nested unary, binary or
conditional scalar expressions; calls/await/assignments and other complex
effects fail closed until ownership/continuation admission has been proved.
Async/suspending/generator callables and complex arm control flow are
explicitly unsupported in this stage.

`do match<ret=T>` remains **procedural**, so its `return` still leaves the
containing callable. This distinction must not be erased by later lowering.

## Follow-on integration work

1. Port an explicit lexical `ARM_RESULT` completion kind and context
   ownership from PR #567. Replace the direct single-return runtime path only
   once the continuation unwinder handles it correctly.
2. Expand type/ownership/effect checking for arm bodies, including non-scalar
   move/borrow semantics and calls, before admitting such expressions.
3. Cover nesting of match/switch and `try/catch/finally`, plus `defer` and
   `recover`. No local match completion can be mistaken for a guest exception,
   nor may it skip or run cleanup twice.
4. Make `await` suspend/resume at the exact execution-context and arm,
   preserving subject evaluation once, after proven cleanup semantics.
5. Differentially execute JVM, Graal native AOT, hybrid, and LLVM shared
   fixtures, including cancellation/error races. LLVM still has scalar-only
   support and a separately diverged integration stack.

Production admission remains **draft** until all these gates are satisfied.
