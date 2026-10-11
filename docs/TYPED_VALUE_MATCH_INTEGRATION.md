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
and returned expressions allow only literals, locally resolved **owned scalar**
names, and a vetted subset of unary, binary and conditional scalar operations.
Borrow-producing `&` / `&mut` operators, borrowed return contracts, unknown
imports, identities, calls, await, assignments and other complex effects
fail closed until ownership/continuation admission has been proved.
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

## Audit hardening and exact-source evidence (2026-10-11)

The first implementation had two additional admission/traversal risks:

- A borrowed `match<ret=&int>` / `match<ret=&mut int>` contract was dereferenced
  before the owned-scalar check; unary borrowing operators were treated as
  pure scalar syntax. Type checking now preserves the declared borrow and
  rejects these forms.
- The new `ValueMatchExpr` AST was not visited by `OwnershipChecker` or
  closure capture scanning, risking missed borrowed/moved reads and missing
  outer-variable captures inside match arms. Subject/guards/direct returns
  now participate in ownership and capture checking with isolated pattern
  scopes and conservative branch-state merging.

The production compiler continues to reject nested arm control flow,
unresolved or borrowed name inputs, async/suspends/generator contexts, and
non-scalar ownership effects. No general ARM_RESULT continuation or
`await` lowering is claimed.

**Exact source head `e8e892e28ea299dd1ace19c53f89c8dc23e46c77`**
was mirrored by Git tree identity:
`src/main=bd4b3ffc8aa8c5897eee87cf7d8f32f2b3416a41`;
`src/test=369af93906f29806af402f97d8d41fde59e38ae6`.

[Funded GraalVM Maven run 38178032210](https://github.com/zed-pkg-test/java-app/actions/runs/38178032210)
**PASS**: 15/15 focused integration tests and 1,453/1,453 full Maven
tests, no failures/errors/skips. Both native-image jobs for this exact
source are tracked in
[run 38178032203](https://github.com/zed-pkg-test/java-app/actions/runs/38178032203);
they were still in progress at the audit snapshot and must not be
represented as complete until confirmed.
