# AttoLang M3.3a — compiler-lowered scalar fnc call/return dispatcher

**Status:** source implementation and test suite AUTHORED, NOT_EXECUTED on
a Java/GraalVM compiler runner. This is a child of
[Java #2665](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2665);
all parent/agent branches remain unchanged.

## What changed in real source execution

`OresEvalRootNode.Evaluator.callFunctionBodyUnchecked` now attempts
`ActorScalarSourceLowering.tryCompile` **only on admitted actor turns**
after the existing async/suspension fast gate. This is an explicit,
fail-closed **whole-direct-call-graph** admission check before any guest
instruction or new actor frame is executed. All argument values must
already be exact Java `Long` wrappers for checked AttoLang `int`
parameters; this path does not coerce object, string, bool or other types.

The lowered subset is:
- synchronous, effect-free, non-generic `fnc` (never `routine` or actor
  method), returning `int` with `int` parameters
- literal signed 64-bit `int` constants, scalar lexical declarations and
  reassignment, local reads, `+`, `-`, `*`, signed `<`, `<=`, `==`
- direct unambiguous calls and mutually recursive `fnc` graphs, including
  non-tail calls inside arithmetic and proper terminal tail calls
- explicit `return` and simple `if/else` paths; every reachable end
  must have an explicit return. Any compiler control-flow invariant
  violation reaches a fail-closed terminal TRAP, not an implicit zero
- no reference roots, dynamic calls, named arguments/type arguments,
  arithmetic division, %/^/^^, overload dispatch by value, closures,
  strings, object values, guards, defer/recover/try, loops, async,
  suspension, channels, or unsafe implicit conversion. The unsupported
  full graph runs on the **existing** evaluator rather than a partial
  machine; actual fallback execution is not guest-stack-safe.

`ActorScalarCallMachine` runs a **single Java while loop**, not a
recursive Java evaluator per guest function call. Every supported
activation pushes one frame into its generation-fenced `ActorFrameArena`,
using a single unique logical execution ID. Frame bytes contain:
- a 16-byte `magic, program counter, operand SP, function index` header
- 9-byte pointer-free, tagged scalar cells for locals and operand values
- a compiler-approved static function ID instead of a JVM call target

At `CALL`, the parent PC and operand depth are retained in arena bytes;
the child gets a new charged frame. At `RET`, the child frame is popped
and zeroized and its scalar result pushed to the parent's actor-owned
operand stack. At `TAIL_CALL`, the current frame is popped before the
callee is pushed, maintaining **constant actor frame depth** for
recursive tail calls. `ActorFrameArena` retains the 64-frame/64-KiB
per-incarnation defaults and poisons only the over-budget actor
incarnation. The Java `ArrayDeque` carries **opaque frame handles and
immutable compiled function IDs only**—not guest scalar lexical data or
operand state. Exit, quota, exception, and safepoint cancellation use
`arena.cancel(execution)` for exact-once frame reclamation.

Every **128th** VM instruction invokes the existing
`context.schedulerSafepoint` callback; the other 127 iterations use a
cheap masked counter. Native scheduling and untrusted killability are
separate controls; no pure software safepoint can protect arbitrary
unsafe/JNI native code.

This is a **real bounded scalar non-tail call/return machine**, not yet a
universal actor stack, off-heap allocation or native physical stack
switch. Non-scalar guest calls continue to use JVM recursion; source
closures, actor methods, exception cleanup, suspension and cancellation
PCs are NOT lowered here. The earlier 48-byte
`ActorGuestReturnRecord` is an ABI precursor, **not** consumed by this
new runtime. The new activation header itself stores its authenticated
return PC and function index.

## Test acceptance — AUTHORED_NOT_EXECUTED

- `ActorScalarCallMachineTest`: **33** JUnit host/runtime methods
  covering arithmetic, bool branches, 100,000 tail calls, 100,000
  non-tail quota failure, nested sibling calls, multiple saved returns,
  indirect/mutual tail calls, actor sibling survival, actor-owned lexical
  slots, one-per-128 safepoints and exception cleanup, program verifier
  negatives, unsupported host admission, program-image immutability,
  live return PC and function ID corruption mutations.
- `ActorScalarSourceLoweringTest`: **16** parser-derived AST tests
  covering genuine `fnc` lowering, mutable scalar locals, exhaustive
  `if/else`, non-tail CALL instruction presence, mutual TAIL_CALL
  presence, and fail-closed fallback for non-scalar/side-effecting AST.
- `ActorScalarIterativeLanguageTest`: **five** canonical compiled
  `.atto` guest tests. These use real Graal polyglot execution with
  actor handlers and expected exact stdout:
    - `non-tail-actor-frame.atto` -> `8`
    - `proper-tail-actor-frame.atto` -> `100000`
    - `sibling-after-budget-failure.atto` -> `starting|alive`
    - `iterative-multi-fnc-returns.atto` -> `46`
    - `iterative-mutual-tail.atto` -> `0`

These counts are authored methods, **not** observed execution. An
isolated Java parser static analysis or source-free harness is not a
substitute for a real compiler-backed `.atto` result. The local
workspace has JDK 21 but no Maven or cached GraalVM/JUnit dependencies;
GitHub Actions has repeatedly failed with `steps=null` and
`runner_id=null`.

## Required exact-head execution / review

```sh
mvn -B -ntp -Dtest=ActorScalarCallMachineTest,ActorScalarSourceLoweringTest,ActorScalarIterativeLanguageTest,ActorLexicalEnvSnapshotExecutionFenceTest,ActorSourceTaskLexicalFrameTest,ActorGuestReturnRecordTest,ActorLexicalTaskCaptureFenceTest,GuestCallDepthGuardTest,ProperTailCallTest test
```

Record Git commit SHA, tree and touched blobs, runner ID and attempt,
nonzero executed job steps, GraalVM/JDK version, JUnit XML, exact source
fixtures/stdout and negative mutation outcomes. CI with `steps=null`
is runner admission failure, **not** a failed/matched Java execution.
Independent approval is required on the current head before draft
promotion or merge.

## Remaining M3.3b/M3.4 work

General lowering must include reference roots with ownership and heap
barriers, typed arbitrary expression operands, methods and closures,
`defer`/exception stack unwinds, suspension and resumption in the
iterative dispatcher, supervisor typed CONTROL overflow errors with
generation restart, low-native-stack process survival, and equivalent
LLVM/.NET NativeAOT/Wasm/Forge guest receipts. In particular, **synchronous
non-tail calls now avoid recursive JVM call frames only inside the
accepted pure scalar subset**. A source fixture success alone cannot
certify full physical per-actor stack isolation.
