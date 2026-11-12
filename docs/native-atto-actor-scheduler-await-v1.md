# Native Atto async actor result — scheduler-lowered v1

**Status: experimental, compiler-verified closed-world async subset.** This is
a real native Atto source `pub async fnc main()` proof with scheduler-backed
continuations and bounded actor channels, not general-purpose typechecker
admission, service actor support, or five-backend parity.

## Source and execution

The canonical fixture is
`contracts/namespace-actor-state-v1/async-scheduler-actor-result.atto`.

The parser admits one namespace, one `declare actor` with compiler-authenticated
state/critical/receive code, up to eight `const a = spawn Actor()` bindings,
**exactly one** `await a.send_async(int-literal)` for each bound actor, and
one final `return (await a.outputs().next()).value.value;`.
All additional source statements, aliases, receivers, effects, forged CONTROL
calls, direct state reads, or non-owned scalar outputs fail at admission.

`NamespaceActorAsyncGuestV1` compiles the complete accepted AST into
`OresScheduler.Task<Long>` continuation stages. The task awaits real
`ActorRef.ready()`, `sendAsync()`, bounded actor output stream `next()`,
mailbox `drain()`, and physical actor `getDone()` futures.
Every pending future reschedules the owning root task through the scheduler:
**no pending `Future.get`, `join`, `awaitTermination`, `Thread.sleep`,
or guest carrier parking** occurs inside this compiler-generated state machine.
The context's embedding host may await its root main result, as with normal
Atto async programs; that is not an actor/scheduler carrier.

The returned value is derived from source-native checked receive arithmetic
`p.send(mail.value + 1)` and verified against runtime-minted actor ID,
monotonic output sequence, scalar payload, exact processed mail count,
incarnation generation and code image. Async operation failure enters
retirement; all actor references are stopped, each actor's physical `getDone`
barrier awaited before code-image close, and the failure is propagated.

The exact syntax is an interim strict source subset. In particular:
- This does **not** turn user-defined `ActorRef` methods, arbitrary
  `await` expressions, or general `ServiceActor` into compiled operations.
- TypeChecker without this compiler-owned closed-world verifier continues
  to reject unsupported namespace actor source.
- Trust remains in backend compiler/runtime state, not method spelling,
  inheritance, user trait implementations or host callbacks.
- No native Atto `SupervisorActor` policy, restart or CONTROL ingress
  is being claimed.
- This is Java/GraalVM guest execution only. LLVM, Wasm, Forge, and .NET
  require separate compiler/runtime lowering and conformance.
- A selected-blob mirror is not a complete original Git-tree proof; this
  branch must pass source-head full compiler CI and independent security review.

## Next generalization

After this concrete await proof passes: replace the isolated strict AST
admission with typed HIR/MIR actor expressions, unify normal 
`OresEvalRootNode` / linked compilation and compiler-checked authority,
add non-constant message payloads and statically bounded output iterators,
enable structured cancellation and supervisor CONTROL lifetimes, then
reuse the same canonical `.atto` fixture on LLVM and the other backends.
