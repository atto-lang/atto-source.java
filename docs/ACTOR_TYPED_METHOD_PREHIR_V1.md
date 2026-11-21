# Typed external actor method pre-HIR (Java v1)

**DRAFT, authored and UNEXECUTED.** This is a compiler admission milestone
stacked on PR #2913. It does not implement runnable namespace actors.

## Safe body-checking slice

The package builder derives the fully manifest-bound source/contract/state
descriptor from the *exact same parsed source snapshot*, then constructs
a non-executable typed pre-HIR for supported external actor method bodies:

```java
var config = OresProjectConfig.load(Path.of(".atto.pkg.toml"), Map.of());
var builder = new PackageNamespaceBuild(config);
var typed = builder.typecheckDeclaredActorMethods();
var actor = typed.actor(new ActorMethodIndex.ActorKey(
    "demo@0.1.0", "foo", "Counter"));
assert !actor.executable();
```

The scalar v1 slice supports **only**:

- non-generic source-verified `mtd` with scalar int/bool/string parameters
- immutable method argument value reads by lexical parameter name
- literal int (Long), bool (Boolean), string (String) values
- int +, -, *, /, % operations represented as **CheckedIntBinary**
  (the operation is **not executed**; overflow/div-zero lowering is missing)
- zero or one terminal `return` statement. Non-void methods must return
  exactly the declared scalar type; void permits an empty body or `return;`

Everything else **fails closed**, including captures of globals/namespaces,
returning the actor receiver, unknown names, member/state access, method
calls, async/await, arbitrary expressions, borrow/ownership operations,
multi-statement bodies, private lifecycle/receive hooks, general control flow, thrown effects or direct
critical state loan callbacks. This is not a complete Atto type checker
and must never be generalized through source-name matching.

`ActorTypedMethodIR.ActorPlan` retains the verified immutable actor
descriptor, its sealed attached-state layout, contract identity and a
deterministic list of checked methods. The state loan status is
`SEALED_STATE_LOANS_UNLOWERED`, dispatch status is
`MAILBOX_DISPATCH_UNLOWERED`, and `executable()` is always false.
Actor plan and result constructors are private; their maps/lists are immutable.

## Mandatory order in the ordinary package build

1. Re-read canonical manifest + complete bounded/strict UTF-8 package source
   snapshot; validate provenance and imports
2. Bind the nominal declaration to actor-target contracts, external mtds
   and uniquely attached sealed state
3. Build immutable static descriptors and verify SHA-256 source membership
4. **Typecheck** the restricted scalar external method bodies into pre-HIR
5. Unconditionally fail with `DECLARED_ACTOR_CODEGEN_UNSUPPORTED`;
   no codegen, no actor mailboxes, no state handles and no actor activation

An unsafe method body now fails at step 4 with
`ACTOR_TYPED_IR_UNSUPPORTED_BODY` or `ACTOR_TYPED_IR_RETURN_MISMATCH`
rather than being admitted as a runnable method. Any bound private CONTROL/receive hook without a normal method slot fails with `ACTOR_TYPED_IR_CONTROL_UNSUPPORTED` rather than disappearing from a supposedly complete actor plan. `IncrementalCompiler.compile(Map)`
still rejects all declaration-only actor code with
`DECLARED_ACTOR_MANIFEST_GATE_REQUIRED` before ABI cache mutation.

## Executable follow-up gates

- Teach the full typed HIR/MIR pass to analyze arbitrary external method
  bodies, effects, permissions, captures, ownership and borrow scopes.
- Emit actor-owned callable slots only from a complete compiler-issued
  manifest/source + method body + state layout proof. Avoid raw Java
  function dispatch or public `Foo` receiver values.
- Lower state loan/read-write admission as an actor-owned capability with
  release on all exceptional/cancellation paths and no escaping borrows.
- Lower checked arithmetic and return semantics identically on all backends.
- Wire mailbox channel dispatch/backpressure, supervisor CONTROL notices,
  one-shot init, generation fencing and teardown into the runtime.
- Verify real JVM and Graal Native Image execution, then LLVM, .NET AOT,
  Wasm and Forge conformance. Current stage has **no executed proof**.

## Verification

`ActorTypedMethodIRTest` has 21 newly authored tests, comprising positive
scalar typing, actor descriptor binding, typed operand shape, invalid-body
diagnostics, source changes, immutable plan/result, method removal,
and mandatory rejection before codegen. All are included in
`.github/workflows/cross-file-actor-mtd-junit.yml`, requiring actual
JDK25 GraalVM Maven and unskipped Surefire reports.

Previous exact-head Actions runs on #2913 failed with **zero steps and no
assigned runner**. That is a runner admission failure, not a compiler
pass/failure. No local full-repository compiler execution has occurred.

Do not merge or disable rejection gates until an exact-head real test run,
independent review, and the ownership/runtime proof requirements are met.
