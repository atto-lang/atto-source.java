# Native Atto checked receive → bounded actor output (experimental v0)

**Implementation status:** narrow native `.atto` actor-method lowering, not general
Atto expression execution, `SupervisorActor`, `ServiceActor`, or multi-backend parity.

Source fixture: `contracts/namespace-actor-state-v1/critical-output-receive.atto`.

The compiler parses a real native Atto actor, binds sealed fields and checks
all critical methods. Its receive body may contain 1–8 checked critical calls,
then **at most one** exact `p.send(mail.value);`, then `return;`.
`p` must be the verified receiver and `mail` its declared owned
`ActorMail<int>` parameter; no receiver/argument aliases, guest-supplied
callbacks, alternate senders, state reads or reordering are admitted.

Runtime lowerer `NamespaceActorCriticalIrCompiler` calls
`ActorRuntime.emitCurrentActorOutput(input)` only *after* each critical
write loan closes. The runtime validates the current physical actor turn,
copies/freezes data-only output, enqueues it in the actor's bounded channel,
and increments an immutable sequence. A full or closed output channel fails
the actor turn rather than parking a carrier. Output delivery is not a
persistent storage acknowledgment and cannot mint `ActorRef`, supervisor
CONTROL, actor state aliases, or reply capabilities.

The compiler-provided output contract must not be mistaken for an ordinary
Java actor implementation. Actor behavior is described in the native `.atto`
fixture; Java is the backend compiler/runtime substrate. The private test
asserts outputs from two independent, code-image-bound actors and verifies
correct sequences, payloads, actor-owned state, and post-retirement fences.

The existing experimental `NamespaceActorUnleasedSpawnV0` root still has
host-bound synchronous readiness/drain/retirement waits; this change
explicitly denies its invocation on an actor or ActorGroup Mailman carrier.
It still returns a source literal, and **does not** yet expose actor outputs
through general user-authored `main()` / `await` lowering.

## Required future gates

1. Actual JVM JUnit results from exact source blobs, including all negative
   output and nested-carrier tests (zero-step Actions are NOT_EXECUTED).
2. General typed guest `spawn`, `ActorRef` and output consumption nodes,
   including `await` continuations rather than the experimental host root.
3. Native Atto `SupervisorActor` CONTROL, typed request/reply ownership,
   cancellation, quota-limited output and backpressure under load.
4. Full exact-tree CI and independent security review.
5. Independent GraalVM Native Image, LLVM, Wasm, Forge, and .NET proofs.

No production-ready actor base class, native AOT or five-backend parity is
claimed by this narrow compiler slice.
