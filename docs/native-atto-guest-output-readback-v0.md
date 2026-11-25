# Native Atto guest output result, closed-world v0

**Status:** experimental compiler-backed guest result proof. This extends
`NamespaceActorUnleasedSpawnV0` but does **not** provide general Truffle
expression lowering, suspended `await`, `ServiceActor` request/reply
ownership, native `SupervisorActor`, or backend parity.

The canonical `.atto` fixture is
`contracts/namespace-actor-state-v1/unleased-source-output-readback.atto`.
A normal Polyglot `Context.eval` of its native Atto `main()` now returns a
`Long` that originated in an actual ActorRuntime output channel rather than
the old literal-return expression. The admitted source shape is:

- One checked namespace actor with sealed scalar state and private
  `external mtd init/receive`.
- An exactly verified `p.send(mail.value)` emitted after all state-critical
  writes, using the existing bounded runtime `ActorOutput` channel.
- Up to eight immutable actor-ref bindings and exactly one statically checked
  message per actor, never an arbitrary loop or output flood.
- A terminal `return actor_ref.output_value();` where the actor ref has
  exactly one admitted message. This is **temporary experimental syntax**:
  do not canonize it as the final ordinary asynchronous Atto API.

At parse and execution, compiler-owned code-image descriptors attest that
the native actor `receive` emits a bounded scalar. The root drains the actual
mailbox, proves immutable processed counts and actor-generation identity, then
reads the runtime-minted output channel and verifies source actor identity,
sequence zero, and owned integer payload. The root physically retires all actors
before revoking the compiled code image.

This closed-world proof executes on the embedding caller thread and
deliberately rejects actor/Mailman-carrier entry. It still invokes temporary
bounded host waits for ready/drain/retirement. It must **not** be construed as
a generally nonblocking guest actor API; the future proper spelling should
use typed `ActorRef` output streams and asynchronous task suspension.

## Required follow-on implementation

1. Compile real typed Truffle `spawn`, `send_async`, `next_async` and
   `await` expression nodes; remove the special output_value projection.
2. Represent value/result/close/backpressure as explicit Option/Result and
   typed terminal futures, with linearizable bounded admission.
3. Compile native `SupervisorActor` private CONTROL and checked restart
   policies; never derive CONTROL from source inheritance alone.
4. Compile `ServiceActor<Request,Reply>` owned reply endpoints, cancellation
   and once-only settlement.
5. Execute **full source-tree** JDK25/GraalVM, Native Image and LLVM/Wasm/Forge/
   .NET backends on independent exact trees and conformance fixtures.

Source-only or partial mirrored subsets are not complete source-tree proof.
