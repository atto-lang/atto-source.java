# Direct suspends calls: in-place SourceTask CPS v1

Status: **draft, compiler-backed guest acceptance NOT EXECUTED**.

## Why this change

Before this branch, a direct source call to a `suspends fnc` invoked `startSourceFunctionTask`, which minted a new Task and ExecutionContext just to call a function. That makes a suspending call behave like an implicit spawn, instead of retaining the caller's logical execution.

## Implemented reference-evaluator slice

- For a statically resolved, non-actor direct call declared `suspends fnc`, enter `runBlock` on the *existing* SourceTask. It does not allocate a new Task or Future.
- Preserve the lexical Env, return flow, defer/finally/guard cleanup frames and expression callback as the same heap-owned CPS continuation across real `await`/`rt cooperate`.
- Keep `async fnc` and explicit `task {}` on their existing independent scheduler/task pathways. `suspends fnc f(): T` still returns T semantically, not an implicit Future.
- Admit only an already scheduler-authenticated SourceTask (`currentExecutionContext` exists). Bound nested inline call depth to 64 to prevent unbounded same-turn Java stack growth. Detect replay/double return.
- Leave actor scalar bytecode CALL/AWAIT, indirect/instance-method suspension, LLVM/.NET/Wasm/Forge and Native Image as separate unsatisfied gates.

## Mandatory evidence

Run exact branch head with executed JDK25 CI and JUnit XML:

```sh
mvn -B -ntp -Dtest=SuspendsAwaitExecutionContextGuestTest,SuspendsInlineContinuationGuestTest,SuspendsAwaitExecutionContextRuntimeTest test
```

The new canonical `.atto` fixture exercises nested direct `suspends` calls, explicit child Task await, lexical `defer` cleanup and TaskLocal restoration; expected stdout is `defer:42|root`.

Still needed: real call-chain identity instrumentation, typed actor frame/stack lowering, suspension inside instance methods and dynamic callables, cancellation-vs-resume proof, bounded cleanup quotas, Native Image, five-backend identical guest semantics. Host-only canaries or zero-step CI cannot certify this feature.

Keep DRAFT until actual guest execution, exact-head/reevaluation and independent review.
