# M3.3b — scalar iterative dispatcher typed admission and corruption hardening

**Status:** implementation and adversarial JUnit tests AUTHORED, NOT_EXECUTED by
a hosted Java/GraalVM runner. Stacked on
[Java #2667](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2667).
No changes to the parent's branch or the source-frame ownership PRs.

## Audited hazards and fixes

1. **Silent primitive coercion / wrong return type.** M3.3a emitted scalar
   machine instructions without independently checking source expression
   types; a Boolean in an `int` return or arithmetic/call argument could
   enter a machine that later fails *during actor execution*. This
   violates the fail-closed pre-admission promise and can poison an actor
   for an unsupported source expression. M3.3b makes the compiler's
   expression visitor return an exact `INT` or `BOOL` kind. It verifies
   `int` function returns, int arithmetic/ordering operands, Boolean
   conditions, identical primitive kinds on equality and `int` call
   arguments, **before any actor frame is allocated**.
2. **Illegal rebinding of immutable source bindings.** The old narrow
   compiler had one name-to-slot map but discarded `Ast.BindingKind` and
   permitted `assign` to `val`, `const` and immutable parameters.
   The lowering now tracks each binding's declared kind; only explicit
   `LET` can be rebound. Legal `let` updates remain supported.
   Mutable referents remain a separate language capability, not
   permission to rebind `val`.
3. **Synthetic JVM wrapper conversions.** The scalar source path formerly
   converted `Integer` AST literals to canonical 64-bit `Long` values.
   Source parser already emits `Long` for signed `int` literals.
   M3.3b rejects synthetic Integer literal images rather than silently
   widening, consistent with AttoLang's no-implicit-conversion rule.
4. **Malformed frame SP and primitive tags.** The iterative machine now
   checks its 16-byte frame header's operand pointer against the current
   function's verified maximum **before decoding any opcode**. Its
   integer arithmetic and call parameter extraction fail-stop the
   actor on a non-Long encoded operand, rather than throwing a generic
   host type error without poisoning. Typed equality is limited to
   Long/Long or Boolean/Boolean; mixed types cause actor-local fail-stop
   instead of the JVM `Objects.equals` false result. Legitimate Boolean
   equality in a predicate still works.

This phase does **not** enlarge the accepted source language. Unsupported
source combinations take the old interpreter path if the rest of the
language's normal static checks admit them. No illegal source program is
authorized merely because the new scalar compiler rejects it.

## Additional tests — AUTHORED_NOT_EXECUTED

- **16** new parser/AST lowering tests: wrong primitive returns,
  Boolean arithmetic/unary coercion, non-Boolean condition, invalid
  local initialization, `val`/`const`/parameter rebinding, typed
  `let` update, Boolean call argument, mixed equality,
  valid Boolean comparisons, valid immutable local reads, and
  synthetic JVM Integer AST widening denial.
- **5** new actor-runtime machine tests: corrupt operand SP, Boolean in
  integer arithmetic, mixed equality, Boolean passed as int callee
  argument, and valid same-tag Boolean equality.

The stacked tests become **38 actor-machine host, 32 parser/AST,
5 canonical `.atto` methods** (75 total across the stacked Java
M3.3a/b change series), all **AUTHORED_NOT_EXECUTED** unless an
admitted stepful runner executes and reports them.

## Required execution and gate

```sh
mvn -B -ntp -Dtest=ActorScalarCallMachineTest,ActorScalarSourceLoweringTest,ActorScalarIterativeLanguageTest,ActorLexicalEnvSnapshotExecutionFenceTest,ActorLexicalTaskCaptureFenceTest,GuestCallDepthGuardTest,ProperTailCallTest test
```

The source repo's `fast-ci` workflow runs on `ubuntu-latest`.
Exact-head attempts have failed with `runner_id=null` and
`steps=null` and no accessible step log blob. This is an
**unresolved runner-admission failure**. Do not infer a particular
billing cause or claim Maven/JUnit or compiled guest tests have run.
The local container has Java 21 but lacks Maven, project dependencies
and GitHub DNS; an independently authorized funded test repository
may be used only with exact source-tree equality and approved
private-source transfer/security controls.

Do not merge or promote full actor stack isolation without independent
review, compiler-backed source positive/negative receipts, low-native-stack
process survival, cancellation/supervisor generation proof and full
Java/LLVM/.NET/Wasm/Forge parity.
