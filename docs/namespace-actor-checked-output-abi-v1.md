# Namespace actor checked scalar output ABI v1 (draft)

**Stack:** this branch is based on Java PR #2976 at `8f3a9fdc41b8d4138a85bc9de438481a69059fa1` (which itself depends on #2972 → #2959 → #2947 → #2892).

## What this patch actually adds

The already-verified, production-parser-to-compiler `NamespaceActorCriticalIrCompiler` can now admit a **source-written, private critical scalar getter** and an actor-local output statement:

```atto
critical mtd (Probe p) current(): int {
    return p.state.received;
}

external mtd (Probe p) receive(ActorMail<int> mail): void {
    p.add(mail.value);
    p.send(p.current());
    return;
}
```

The compiler admits a zero-argument critical reader **only** when its body is one return of an owned signed-i64 state field. An external receive may send this read through `p.send(p.current())` **only** after 1..8 verified critical writes and immediately before `return;`. Both `p` references must match the same declared actor receiver. The compiler resolves the field slot statically, so there is no dynamic method dispatch, reflection, guest-visible state pointer, transferable loan or host receipt access. All prior write-only sources remain valid.

During each matching, physical actor mailbox turn, the existing checked critical writer updates the actor's private `Owned.long[]`; the compiler then copies the read slot into `ActorRuntime.emitCurrentActorOutput`. This uses the existing bounded, immutable actor output transport, which verifies the execution identity and rejects output capacity exhaustion rather than blocking a carrier.

The positive JUnit fixture verifies source-computed outputs 3 then 7 from one actor and 11 from a separate actor, checked private-state totals and retirement. Negative cases reject direct state output from `receive`, noncritical getters, unknown/foreign receivers, dynamic expressions, forged output calls, reordered/multiple outputs and state-mutating getters.

## What this does NOT prove

- This is **not** a general Graal source `spawn/send/await` lowering or general `.atto` guest request/reply language API. The scoped output consumer in these tests is a Java test observer of the actor's production `ActorOutputStream`, not a source-level guest consumer.
- This is **not** correlated request/reply with multiple concurrent in-flight requests. There is one final immutable output per processed message, and it can fail on bounded backpressure.
- This is **not** source-level multiple-reader RW-lock parity, suspension-safe loans, Native Image AOT, or an actor heap implementation for LLVM/.NET.
- No backend or broker parity claim. The dependent #2976 closed-world source `main()` still returns a literal. Its synchronous caller-side waits remain unchanged.
- **CI execution status: NOT_EXECUTED for this branch until a funded, fully pinned runner produces a stepful receipt.** Earlier #2976 carrier 86/86 JUnit successes prove an exact-file subset of the *parent*, not this commit, nor the new tests.

## Mandatory gates

1. Whole-tree exact-head identity, JDK25/GraalVM25.3.4.1 and full relevant JUnit with zero skipped required cases; independent review.
2. Real Truffle AST guest nodes for typed actor `spawn`, `send`, async output stream reads, scheduler-aware `await`/continuations, and guest-correlated immutable replies.
3. Test guest-to-guest requests with actual values (including accumulated 1000) and rejection of borrowed state across suspension/cancellation/hot upgrades.
4. Execute a common canonical actor/source/output fixture in LLVM AOT and .NET NativeAOT before parity or broker claims.

Keep this PR draft until those independently reported gates are addressed; do not label it a general native actor compiler.
