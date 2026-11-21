# AttoLang actor-owned guest invocation frames — Java M2 slice

**Status:** authored compiler/interpreter integration, **NOT certified process
stack isolation**. Stacked on Java actor byte-frame storage PR #2627,
logical-budget PR #2624, and carrier recursion guard PR #2505.

## Implemented runtime boundary

`OresEvalRootNode.Evaluator.invoke` is the interpreter's common invocation
trampoline for functions, methods, static functions, function bodies and
invokable callables. This slice extends the already actor-frame-aware guard
with a `GuestInvocationFrame` try-with-resources metadata lease:

1. `GuestCallDepthGuard.enter` reserves the carrier recursion budget and
   exactly ONE existing 32-byte actor-owned managed frame per non-tail call.
2. The evaluator writes a 16-byte primitive descriptor **inside that same
   frame** (call kind, arity, tail-transfer count); it does not allocate a
   second 16-byte frame or reduce the 64-call frame budget to 32 calls.
3. Proper tail transfers rewrite the same descriptor, not the allocation.
4. On return or exception the guard releases the single actor frame and
   carrier depth in LIFO order. If descriptor initialization fails, the new
   lease rolls back the successful guard admission without double release.
5. Reject foreign-carrier access, scope-depth mismatch, invalid metadata
   and double close. Reads return defensive copies; no guest object, pointer
   or hosted callback crosses the transport.

`GuestCallDepthGuard` mints a fresh `ExecutionContext` for an outermost
synchronous call chain outside an actor-bound source task. **Integrated
with Java PR #2631:** inside an actor-owned SourceTask it instead reuses
that task's exact generation-fenced execution identity. Its call frames
nest above the persistent 64-byte task root and unwind before await;
the SourceTask root remains charged through suspension and carrier migration.
Actor retirement still waits for physical STOP quiescence.

## Evidence and tests

Five new JUnit host tests in `GuestInvocationFrameTest` check actor
descriptor allocation, 100,000 tail-transfer rewrites with bounded memory,
nested non-tail calls, carrier migration denial while a frame is live,
transactional rollback on byte quota exhaustion, sibling-actor survival,
single-use leases and invalid descriptor denial.

Three new `.atto` **source files**, plus
`ActorGuestInvocationLanguageTest` (3 JUnit methods), require the real
source parser/type checker/evaluator and source actors to execute:

- `non-tail-actor-frame.atto`: direct recursion result `8`.
- `proper-tail-actor-frame.atto`: 100,000 proper tail transfers result
  `100000`, without increasing actor frame count.
- `sibling-after-budget-failure.atto`: an actor enters deep non-tail
  recursion while its sibling returns `alive`.

These JUnit files and fixtures are **authored, not executed** on the
new PR head. They are intended for the real `.atto` admission lane. An
isolated source-level check or source-free canary does not prove guest
execution. The sibling fixture proves source-level progress *only if it
runs*, and does not yet prove supervisor CONTROL acknowledgement,
post-retirement failure evidence or actor restart generation fencing.

Run on a nonzero-step authorized source runner:

```sh
mvn -Dtest=GuestInvocationFrameTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test
```

Capture exact head/tree, compiler version, fixture Git blobs, classpath,
stepful Maven/JUnit results, guest output and process exit. CI `steps=[]`
or `steps=null` means runner admission failure, not compiler failure.

## Deliberate limitations — do NOT promote to native stack isolation

This is **real actor-owned allocation of 32-byte guarded frames with a
16-byte evaluator descriptor**, not full
lowering of Java interpreter locals, operand stacks, return addresses or
source `SourceTask` continuation frames. The interpreter still uses
the JVM native carrier stack, and a deep expression tree, recursive
statement/AST walker, Truffle interop or host/JNI callback can still
consume untracked native frames.

Outside a SourceTask, a guarded call chain has its own minted execution
identity. Inside an actor-owned SourceTask, guarded calls join the scheduler
identity but still unwind descriptor frames before any await. This does
**not** mean actual source locals or return continuations are stored in
the managed frame. A complete implementation must:

- Compile guest functions/methods/closures and expressions into a
  heap-frame instruction stream with explicit return PCs, operand slots,
  type-safe locals, and cleanup edges; replace recursive `eval` dispatch.
- Lower actual SourceTask locals, return and cleanup state into the already
  stable SourceTask execution identity across await/carrier migration,
  and prove non-reentrant mailbox turns and generation-fenced cancellation.

- Charge all frames and root metadata to one actor stack/heap quota,
  reserve carrier-native host headroom, and enforce FFI call boundaries.
- Prove actor A's actual compiled `.atto` failure, actor B response,
  typed supervisor CONTROL, physical quiescence, restart with stale
  continuation rejection, low-native-stack process survival and
  five-backend native parity.

Do not recover by catching JVM `StackOverflowError`, CLR
`StackOverflowException`, SIGSEGV/SIGBUS, or unchecked native faults.
Unverifiable native/JIT guest code needs a killable OS-isolated worker.
