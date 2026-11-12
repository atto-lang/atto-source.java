# Namespace actor Graal `Context.eval` admission probe v0

**Status: experimental trusted-host-only vertical slice, stacked on Java #2947.**
This is not general namespace-actor guest lowering and not an AttoRMQ broker.

## What this implements

`CheckedNamespaceActorGuestProof.compile(codeUnitId, sourceText)` runs the
production parser and `NamespaceActorCriticalIrCompiler` static IR admission
over the complete source text. The checked IR accepts only the restricted
multi-field `int` actor subset previously proven on ActorRuntime: one
declared shared actor, one attached schema, private runtime `external mtd
init/receive`, and compiler-verified `critical mtd` bodies with checked
arithmetic and nonescaping write loans. No caller-supplied AST or reflection
is trusted.

The trusted Java host then opens a one-shot
`OresLanguage.openCheckedActorGuest(proof)` scope **on the same thread as
`Context.eval`**. `OresLanguage.parse` consumes the lease before its
ordinary TypeChecker path and requires *exact* source text and code-unit
identity. Wrong bytes, wrong file ID, nested leases, replay or legacy
prechecked-source overlaps fail closed. Without this scope,
`Context.eval` still rejects namespace actor source normally.

The dedicated `CheckedActorGuestRootNode` is a genuine Truffle
`RootCallTarget`, entered by the Graal Polyglot `Context.eval` boundary.
It uses the context's `ActorRuntime` and the compiled, immutable checked
IR to instantiate two actors, process 1,000 typed mail turns on the first
and one on the sibling, drain the mailboxes and stop both actors.
The returned scalar 1,000 reports *messages drained*, **not guest-readable
actor state**. No `ActorRef` or write loan escapes to user source.

## Trust/ownership limitations

- This is an **opt-in trusted-host lease**, not normal Atto source
  compilation or a source-level `spawn`, `suspends`, `await`, method
  reference, or general `receive` implementation.
- Source critical bodies execute through the already checked Java
  `ActorCodeExecutor` IR, not as general Graal expression nodes. Being
  entered from a Truffle root does not prove general guest AST lowering.
- Runtime state remains per-actor and mailbox-owned, with physical-turn
  carrier identity checked by private write loans. This does not prove
  physical isolated per-actor heaps across backends or global borrow-checker
  completeness.
- The independent code image store is scoped to the reviewed test proof.
  Manifest-authenticated VM code-image identity and hot-upgrade fencing
  must still be wired into normal compilation before releasing actors.
- No NIO2 connection, AMQP handshake, RabbitMQ broker, ACK, WAL or
  quorum-queue behavior is exercised here.

## Evidence required

`CheckedNamespaceActorContextEvalTest` covers a real Graal `Context.eval`
actor execution, ordinary unleased source rejection, source-content
mismatch, code-unit mismatch, nested/overlapping leases, one-shot replay
rejection and malformed critical source denial.

The funded CI carrier mirrors exact individual source/test/compiler/runtime
Git blobs to `voxletra/voxletra-e2e` and runs the focused Graal test suite
with the existing 64 critical IR and namespace parser/binder tests. The
carrier's **entire Git tree is different** from this PR head, so success is
an exact-blob, stepful focused proof, not exact-PR-tree CI certification.

## Required next increment

1. Replace the privileged one-shot proof with compile-time verified actor
   descriptors tied to the normal linked package manifest, code image,
   declared actor and method/field identities.
2. Lower actual `init`, `receive`, `critical mtd` into Graal guest
   expression/dispatch nodes and compile `spawn`/mail APIs from Atto source,
   with no host-only probe logic.
3. Prove direct/super/captured ingress rejection, field authority, mutable
   borrow escape denial, suspension fairness and cancellation on exact heads.
4. Run Java Graal, NativeAOT/LLVM/backend parity and independent review
   before merging enabling general namespace actors.
5. Finally connect AttoRMQ's `ConnectionActor` to NIO2 and verify a genuine
   publish -> get -> ACK in an unmodified official AMQP client.

**Do not interpret a green Context.eval probe as a working native broker.**
