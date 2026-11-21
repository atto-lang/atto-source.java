# Internal namespace-actor mailbox bootstrap v0

**Stage:** compiler-internal, return-only, no source-level admission.
**Release status:** DRAFT — not production namespace actor lowering.

## Executable v0

`NamespaceActorBootstrapCompiler` recognizes exactly one top-level flat
`use namespace` unit with a single `declare actor Probe;` and two private
runtime-owned methods:

```atto
use namespace foo;

declare actor Probe;

external mtd (Probe p) init(): void {
    return;
}

external mtd (Probe p) receive(ActorMail<int> mail): void {
    return;
}
```

Both bodies must be **exactly** `return;`. The class is package-private:
the normal `TypeChecker` and Graal/Atto evaluator still reject
`StandaloneActorDecl` and `ExternalActorMethodDecl`. It is a narrowly
bounded compiler/runtime proof, not a guest callable API.

The v0 bridge binds the parsed AST with `ActorReceiverBinder`, is pinned
to an immutable `SharedCodeImageStore.CodeImage`, and installs an
`ActorRuntime.ActorCodeExecutor` on a real SHARED actor. The existing
`ActorRuntime` owns actor startup, mailbox turns, generation IDs, per-turn
context, cancellation and finalization. The bootstrap checks the code-image
identity, exact actor identity and current actor execution context at both
runtime entrypoints. Only `ActorMail<int>` with Java `Long` payload is
admitted to `receive`.

## Explicit nonclaims

- No arbitrary guest method body, source `spawn`, member dispatch,
  closure captures, `super` calls, dynamic actor type or contract witness.
- No compiled `state` schema, ownership loans, `critical` method,
  actor-owned numeric mutation, async suspension, or on-disk state.
- No namespace actor source-level execution through normal
  `TypeChecker`/`OresEvalRootNode`; this is a standalone trusted compiler
  prototype using `ActorRuntime`, not a fully compiled Graal guest.
- No public AMQP listener, authenticated ConnectionActor, channel
  multiplexing, published messages, ACKs, WAL or quorum queues.
- The CodeImage store does **not** prove package-manifest provenance and
  the carried source subset is not the whole mainline head tree.

## Gate and proof

The focused JUnit suite `NamespaceActorBootstrapCompilerTest` must
execute with zero skips:

1. Actual `ActorRuntime` ready -> two typed mail turns -> drain -> stop
2. Rejection of wrong `ActorMail<int>` payload during runtime dispatch
3. Normal frontend `TypeChecker` remains fail-closed
4. Direct/fabricated host ingress without an active actor turn is rejected
5. State, extra methods, borrowed mail, contracts and non-return bodies rejected
6. Retired `ActorRef` cannot send into a new actor incarnation

Run this test through the private funded carrier
`voxletra/voxletra-e2e@validation/atto-rmq-namespace-bootstrap-20261111`
with direct Git blob equality checks for every mirrored file.
The carrier's **full Git tree is not identical to this source PR tree**;
the resulting receipt is an executed, exact-file-subset test, not a
complete exact-head acceptance proof.

Previous 5-test non-skipped [bootstrap receipt #43146681634](https://github.com/voxletra/voxletra-e2e/actions/runs/43146681634)
proved initialization/mailbox turns and invalid input. Rerun with the sixth
actor-generation test and separately require exact full-PR-head tests before
merge.

## Next safe increments

1. Integrate manifest-authenticated and generation-fenced actor type
   descriptors before making the compiler bootstrap callable from the
   production guest compilation path. Avoid exposing any host-side factories.
2. Implement one actor-owned `int` field from a statically bound state
   schema, and a checked `critical` write proof whose physical lease
   expires on turn exit. Reject noncritical writes and all awaits inside
   mutable state loans.
3. Replace the return-only interpreter with proven IR for strictly typed
   `init`/`receive`, still runtime-only ingress. Negative tests must
   reject direct/super/captured lifecycle calls, forged mail, stale
   generations and forged state loans.
4. After that, integrate the async NIO2 TCP capability and the AMQP
   connection predicates from AttoRMQ #13, then the one-message and
   1,000-message tests from AttoRMQ #14.
5. Require a matching full-head tree, nonzero executed CI steps,
   independent APPROVED review and JVM/LLVM/AOT parity receipts before
   relaxing general frontend fail-closed diagnostics.
