# Source ActorStream linear topology — authenticated routing v1

Status: **DRAFT / Java-Graal implementation candidate**.

This layer composes the authenticated `self.forward*`, `self.backward` and
`self.reply` turn authority from the parent change into a source-created
linear topology.

## Source syntax

Two equivalent source forms are admitted:

```oreslang
val route = ActorUtils.pipe(
    spawn Source(),
    spawn Transform(),
    spawn Sink()
);
```

and:

```oreslang
val route = spawn Source()
    pipe spawn Transform()
    pipe spawn Sink();
```

A route contains 2..16 distinct SHARED source actors. All stages must have the
**exact same** statically known `ActorMail<T>` payload domain (type identity,
not merely bidirectional assignability or structural coercion). The result type
is `ActorStream<T>`. An `ActorStream<any>` return annotation is not a safe way
to erase a concrete graph's message type; use the concrete `T`. This initial
single-flow stream has no dynamic heterogeneous-port routing contract.

`ActorStream<T>` exposes only:

- `send(T)` — one root-flow admission;
- `get_done(): Future<void>` — physical DONE barrier for every bound actor.

The handle is a local runtime capability. It is explicitly rejected as actor
mailbox data and as shared-safe state.

## Runtime lowering

The compiler evaluates stage expressions once, in source order, then calls the
compiler-only root binding operation. Runtime validation independently checks:

- 2..16 stages;
- same ActorRuntime;
- distinct ActorIds;
- SHARED actor kind;
- live compiler-owned source actors;
- no actor or Mailman CONTROL registration context.

The runtime constructs a checked linear `ActorPipelineRoutePlan`:

```text
stage-0 -> stage-1 -> ... -> stage-N
```

and binds it to `ActorPipelineCorrelatedMailboxRouter` in a dedicated
compiler-root registration mode. The existing host-only registration mode
continues to reject scheduler continuations; it was not weakened.

Root `send` installs the first immutable route frame and matching
`PipelineTurnAuthority` on the real mailbox envelope. Every guest `self.*`
hop thereafter uses the same authenticated-turn machinery. Ordinary
`ActorRef.send` mail carries neither value and cannot join the route.

## Deliberate bounds

This first source topology is linear and single-flow. A successful first
`ActorStream.send` permanently consumes root admission; replay is rejected.
A synchronous failed admission releases that one credit.

Reverse `self.backward` and `self.reply` now reserve a second bounded
correlation frame while the source DATA turn is still live. The source frame
is retired only after successful destination-mailbox admission; if a capacity
check or destination admission fails, the attempted child is retired and the
source remains authenticated. A full frame ledger rejects reverse selection
rather than violating the bound or silently dropping the source's authority.
This is a staging/receipt rule, **not** a guarantee that the destination will
eventually execute after mailbox admission. ActorStream physical DONE remains
a separate barrier. Continue to test post-publication scheduling failures
and terminal supervisor shutdown on an actual source-local runner.


The generic type `ActorStream<T>` records the common mailbox domain, so
`route.send(value)` is checked against `T`.

This change does **not** yet enable:

- DAG/fan-out topology syntax;
- source `self.forward_all` (typed per-edge admission receipts remain required);
- heterogeneous forward/backward port domains;
- PRIVATE/UNTRUSTED ActorStream stages;
- LLVM/.NET/WASM/Forge guest topology lowering.

## Executable fixture

`examples/actorstream-authenticated-linear.ores` creates:

```text
Source -> Transform -> Sink
```

Positive integers travel forward. The sink converts the payload to a negative
result and sends one authenticated reverse hop; the transform sends the next
reverse hop; the source prints the terminal value and ends. The associated
JUnit suite also covers infix syntax, mismatched mailbox domains, isolated
stages, nested actor-side topology construction, oversized stage lists, route
replay and reserved builtin names.

Promotion requires exact-head Maven/JUnit execution and GraalVM Native Image
coverage. A zero-step Actions failure is not execution evidence.
