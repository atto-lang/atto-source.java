# Typed value match: constrained Java/GraalVM integration draft

This branch is a **staged** integration on top of PR #634, not an implicit merge
of PR #567. PR #567 and this branch diverge deeply; defer importing its
continuation state machinery until control-flow and cleanup equivalence is proven.

## Current positive scope: lexical ARM_RESULT

```ores
fnc f(int n): int {
  const value: int = match<ret=int> n over {
    default { return 5; }
    on 7 {
      const next: int = n + 1;
      defer stdio.println("cleanup");
      return next;
    }
  };
  return value + 1;
}
```

The parser emits `Ast.ArmReturnStmt` inside value-producing match
arms, but `Ast.ReturnStmt` for ordinary function/procedural returns.
The runtime delivers `ArmResultSignal` only to the closest value-match
evaluation boundary, **after** nested `executeBlock` defer/finally
unwinding. Guest `try/catch` does not intercept this lexical signal.
A nested value-producing match has its own independent result boundary.

This form produces an expression result, **not a containing-callable return**.
The compiler requires a typed result contract: explicit `<ret=T>` for
an arbitrary value expression, or the enclosing callable's declared
return type for a **direct** `return match ... over` expression.
Canonical ordered `over` dispatch and exhaustive scalar subject
coverage (int, bool, string,
float or decimal). Each arm must **prove a lexical result on every reachable
normal path**. The checker admits owned scalar bindings, nested blocks,
exhaustive `if/else`, nested typed value-matches used as scalar initializers,
and `try/catch/finally` when `finally` does not override the result.
Only explicitly admitted synchronous `stdio.print/println` calls are allowed
as arm-side-effect statements and deferred cleanup in this stage.
The arm-local result and conditions still require pure owned scalar
expressions, with a vetted subset of unary/binary operations.
Borrow-producing `&`/`&mut`, non-scalar ownership, unverified calls,
await, assignments, loops, procedural nested dispatch, and general
`recover` continue to fail closed.
The serial-actor integration adds a narrow resumable SourceTask path for
typed match results across vetted `await`/`rt cooperate` suspension,
with lexical owner identity and cleanup ordering. Generator support and
general effects/control flow remain rejected. Current-branch guest and
model tests are added, but the updated source head is not yet CI-verified.

`do match<ret=T>` remains **procedural**, so its `return` still leaves the
containing callable. This distinction must not be erased by later lowering.

## Follow-on integration work

1. Extend the synchronous `ArmResultSignal` into the resumable
   execution-context/`SourceFlow` completion contract from PR #567,
   retaining single-delivery and exact lexical ownership across await.
   This is **not yet done** in the current draft.
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

## Lexical completion gate (October 11, 2026)

The new implementation uses `Ast.ArmReturnStmt` and
`ArmResultSignal` rather than consuming a normal `ReturnSignal`.
It is an intentionally **synchronous** engine. The compiler rejects
async, suspends and generator callables around `match<ret=T>`;
this means these changes do not claim `Future<T>` continuation support.

The compiler proof rejects fallthrough, loops, nested procedural
dispatch, effectful arm results and unsupported cleanup calls; the
ownership scanner now traverses all admitted arm statements and
captures pattern-local names correctly.

Exact-source funded [Maven CI 38180097957](https://github.com/zed-pkg-test/java-app/actions/runs/38180097957):
**1,460/1,460 full tests**, including **22/22 typed-match integration
tests**, zero failures/errors/skips. The source/test tree hashes are
`5387e4ea418f56485d743ca5368891761b07b47a` and
`cea57b0e4903f870e268b8c599778b0e30b30c13`.
Native-image guest conformance for this revision is tracked separately in
[run 38180097958](https://github.com/zed-pkg-test/java-app/actions/runs/38180097958).

Synchronous `executeBlock` now runs all deferred expressions in LIFO
order even if an earlier defer fails; it preserves the original execution
failure and attaches distinct cleanup/release failures as suppressed.
Package-local Java tests cover suppression and self/duplicate guards,
while guest tests verify defers still execute after an error. Graal's public
`PolyglotException` projection does **not** guarantee that Java
`Throwable.getSuppressed()` is exposed to users.

Generalized `defer`/`recover` effects, suspendable `ARM_RESULT`,
arbitrary multi-statement borrowed/owned results, and LLVM parity remain
release blockers, not completed features.

## Synchronous cleanup-failure hardening

Source head `cb2be437daca062c8b3e6f964fd8b4f7454844c4` adds
`OresEvalRootNode.preserveCleanupFailure`, complete LIFO deferred
cleanup iteration and direct Java regression tests in
`ArmResultCleanupFailureTest`. Exact source/test trees:
`76d2edeba86317b07548c01c12acfc149a6b01a5` /
`2a6241ac6eb2739cdb5dfe1cf9d6ab0f8ce471f9`.

[Funded JVM verification](https://github.com/zed-pkg-test/java-app/actions/runs/38180629636)
and [native-image guest verification](https://github.com/zed-pkg-test/java-app/actions/runs/38180629619)
cover this revision; consult each run's completed conclusion before
claiming production readiness.

## Security audit: effect authority and AST visitor coverage

The lexical `ArmResultStmt` model is represented by the actual
`Ast.ArmReturnStmt` node; capability checking must explicitly traverse it
and `Ast.ValueMatchExpr` even though ordinary function-return checks do not.

The audit found and closed two concrete authority-admission gaps:

1. **Built-in shadowing:** syntax-only `stdio.print/println` whitelisting
   would mistakenly admit a call to a user object named `stdio`. The
   TypeChecker now proves that `stdio` is not a lexical binding before
   admitting immediate or deferred stdout effects in a value match.
2. **Missing capability traversal:** `CapabilityChecker` previously
   skipped both new match/result AST nodes. It now examines subject,
   result type, every arm pattern/guard/body, and `ArmReturnStmt.value()`.
   The same audit extended scanning to nominal/structural/tuple/tagged
   patterns and constructor names, casts, type tests, pattern tests, and
   explicit generic call type arguments. These locations must not hide
   SharedMutex or other restricted capabilities from untrusted actors.

Regression tests cover shadowed cleanup effects, nested matches carrying
stdout/process-info access, pure capability-free matches, restricted
SharedMutex types in patterns/casts/generic arguments and zero-arity
nominal constructor patterns. These are compiler admission tests; host
and actor-runtime restrictions remain independently necessary.

**Latest implementation source:** `aa97121e32bd989e3a6a5bb87038d2c3c7e27891`.
Exact `src/main` tree
`6ef7f3272b03e30724e858d4a0258dbeb7b1c359`;
exact `src/test` tree
`62c9c127f148cfad8a49e7da2b723a9a5b911d4c`.

[Exact-source funded JVM CI](https://github.com/zed-pkg-test/java-app/actions/runs/38183135411)
and [native AOT/hybrid CI](https://github.com/zed-pkg-test/java-app/actions/runs/38183135320)
are the final verification gates for this revision. Keep draft until
both conclude successfully. The broader async/SourceFlow/LLVM work is
not included or asserted complete here.
