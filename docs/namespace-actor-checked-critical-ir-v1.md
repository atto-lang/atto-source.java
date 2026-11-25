# Namespace actors — first checked critical IR (compiler-internal)

**Status: experimental; stacked on Java PR #2892. Not user-visible guest execution.**

## Source

`contracts/namespace-actor-state-v1/critical-ir-multifield.atto` is the
canonical example. It declares an actor, two actor-owned `int` state fields,
two private `critical mtd` routines and private runtime-only
`external mtd init/receive`. The normal guest frontend intentionally
does **not** execute this form yet.

## Statically checked IR

`NamespaceActorCriticalIrCompiler` validates the actual parser AST, using
`ActorReceiverBinder` and `ActorStateBinder` as prerequisites, then
constructs an immutable program of field slots and checked scalar expressions.
It does not execute source with reflection, evaluation of strings, Java
callbacks, hidden host state, or general Java method dispatch.

The initial supported subset includes:

- One namespace actor and one attached state schema
- One to eight actor-owned signed-i64 state fields with literal initializers
- One to eight private critical methods taking one `int` and returning `void`
- Up to four checked state assignments per critical method
- Checked field reads, signed-i64 literals, the method's parameter,
  and `+` / `-` with overflow trapping
- One to eight statically verified calls to the actor's own critical methods
  from `receive(ActorMail<int>)`, followed by `return;`

Every unsupported operation fails closed, including direct state writes in
receive, external calls, open method names, unsafe aliases, dynamic
expressions, awaits, closures, and unbound fields.

## Mutable loan execution

After the actual `ActorRuntime` admits a mailbox turn, its code-image,
actor identity, runtime, and current physical carrier must match before
the compiler-generated `WriteLoan` becomes live. Its reference is private
to the compiler's trusted implementation and not expressible or capturable
in admitted Atto source. Each critical-method call holds only one mutable
loan, runs serially on the actor's carrier, stages its writes and commits
those writes when every checked expression succeeds. The loan is always
revoked in `finally` when that method exits.

These are **per-method transactions**: a later critical call failing in the
same receive turn does not reverse an earlier completed critical call. A
future general effect system must define multi-call atomicity explicitly.

Two actor instances allocate independent state records and carry distinct
generation-fenced actor references. This stage does **not** claim physical
per-actor VM arenas, inter-VM zero-copy ownership, or a complete Rust-like
borrow checker.

## Evidence gates

Tests in `NamespaceActorCriticalIrCompilerTest` execute the compiler IR
through real `ActorRuntime.spawnCodeActor` lifecycle/mailbox turns:
1,000 messages, isolated sibling state, overflow boundaries, forged ingress,
stale actor generations, legal multiple critical calls and writes, and
fail-closed negative source mutations. The same funded CI job also runs the
existing 56 bootstrap/parser/binder admission tests.

The private funded carrier mirrors specific Git blobs from this stacked
branch into `voxletra/voxletra-e2e`, confirms blob equality, and executes
the tests. The carrier's **whole repository Git tree differs** from this
branch and does not prove exact-head mainline CI. A green carrier cannot
replace independent review or compiler-backed guest/AOT execution.

## Next P0 after this PR

1. Publish typed/ownership-checked IR with manifest-authenticated actor
   descriptors, method/field identity and generation-bound code images.
2. Lower that IR into ordinary Graal guest actor execution; reject direct
   and method-reference access to `external mtd`.
3. Enforce a unified borrow/effect system across suspension, cancellation,
   mutable loans, actor hot upgrades and physical actor-owned memory.
4. Prove Java, LLVM, .NET and other backend parity on executed exact trees.
5. Only then connect the AttoRMQ `ConnectionActor` and `ChannelActor` to
   NIO2, with a real one-message AMQP publish/get/ACK acceptance test.

**No normal guest broker or native AMQP publisher acceptance is claimed.**
