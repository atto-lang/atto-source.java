# Explicit `define supervisor` next to an ordinary actor — proof and admission contract

**Status: proposed source syntax; host-level JVM proof in `ExplicitSupervisorDeclarationProofTest`. NOT a claim that the guest parser, on-start lowering, on-panic lowering, LLVM lowering or actual restart transactions work.**

## Decision

**No:** when actor A spawns B, A does **not** automatically become a supervisor.

A supervisor S is a separate runtime-managed control entity. S can be declared in the
same source file as ordinary actor A, but **file proximity does not grant
supervision authority or access to another actor's private state**. Top-level
type-name resolution is allowed; capability acquisition, source references and
lifecycle attachment must remain explicit.

There are two independent relationships:

1. **Spawn ancestry:** the identity of the immediate spawner (A -> B), used
   for structured descendant cancellation, observability and quotas.
2. **Supervision ownership:** the supervisor whose child registry and reserved
   authenticated CONTROL lane own B (S -> B). The owner does not change merely
   because A spawned B.

This extends the flat inherited-group semantics in
`SUPERVISOR_RECOVERY_AND_NESTED_ACTOR_FAMILIES.md`: ordinary recursive spawn
inherits the *current* supervisor; it does not create an implicit supervisor.

## Proposed source sketch (not yet accepted by either compiler)

```ores
define actor B as
    state { }

    receive(ActorMail<Work> mail) {
        // Process mail; a fatal panic will become CONTROL for S.
    }
end

define actor A as
    state { }

    receive(ActorMail<Start> mail) {
        // If A was admitted under S, this ordinary spawn inherits S.
        const ActorRef<B> child = await spawn B();
        child.send(Work());
    }
end

// S is not 'actor A with extra privileges'.
// It is a separately declared runtime-owned supervisor, even in this same file.
define supervisor S as
    state { }

    on_start() {
        // Supervisor-authored launch, with S as B's owner.
        const ActorRef<B> child = await spawn B();
    }

    on_panic(ChildFailure failure) {
        // Reserved authenticated CONTROL: choose a bounded policy.
        // Real restart must be an acknowledged runtime transaction.
    }
end

// A trusted program/bootstrap context explicitly starts S.
// The following syntax is a proposal, not executable Oreslang today.
const SupervisorRef<S> supervisor = await spawn S();
```

`on_start`, `SupervisorRef` and `spawn S()` are **proposed** surface forms.
A future normal `SupervisorRef.request(...)` endpoint, if exported, must be
separate from reserved CONTROL and must never expose `on_panic` to ordinary
mailbox senders. A must not learn S's private registry just because S is in
the same file.

### Ownership matrix

| Operation | Immediate spawner | Supervisor of B | Is A a supervisor? |
| --- | --- | --- | --- |
| A under S executes ordinary `spawn B()` | A | S (inherited) | No |
| S executes `spawn B()` in its privileged start turn | S | S | No |
| Host/bootstrap explicitly creates S and launches B through S | Host/S | S | No |
| A explicitly instantiates a **new authorized** supervisor T and T launches B | T | T | No; A created a distinct entity |
| A knows the textual name S but has no capability and tries `S.spawn B` | Rejected | No new B | No |

S may have a bounded single logical CONTROL turn at a time, including while
awaiting a child readiness barrier. No child-carrier inline re-entry. The same
single `state { ... }` field rule applies to any persistent guest-managed
supervisor state, guarded by compiler-verified critical loans; no ad-hoc private
fields and no direct access to actor B's heap.

### Lowering target

- `define supervisor S as ... end` introduces a distinct nominal supervisor
  declaration in the compiler symbol table, **not** an ordinary actor class.
- The embedder/compiler bootstraps a supervisor process and allocates bounded
  CONTROL capacity, an authenticated ID, group ownership, a single-turn
  dispatcher and explicit escalation parent/root before running `on_start`.
- On the S start/CONTROL turn, `spawn B()` passes S's unforgeable *owning*
  group handle into spawn admission. Publish B only after all ownership,
  mailbox/quota and lifecycle reservations succeed; rollback failures.
- Ordinary A -> B spawn uses A's existing supervisor identity. If A is
  unmanaged, either require explicit root admission or fail closed; do not
  invent ambient S from source locality.
- B panic completes B's own defers and finalization, then publishes immutable
  runtime-minted CONTROL to S. Neither A nor B may fabricate notices or
  synchronously execute S callbacks.
- Restarts, timeouts and escalation need a bounded retry window, generation
  fencing, root fallback and a durable/fail-closed undelivered-notice policy.
  `on_panic` decides policy, not host heap writes into B.
- Stopping S revokes new child admission; B's DONE is after termination/
  physical cleanup. A's cancellation retains *spawn ancestry* semantics if
  A is the spawner, even when a separate supervision boundary is involved.
- Reuse the existing single-flight `SupervisorProcess` and
  `ActorGroup.spawnWorker` substrate first; preserve isolation between
  reserved CONTROL and ordinary `ActorRef.request` application calls.

## What the host proof actually establishes

`ExplicitSupervisorDeclarationProofTest` runs against the JVM runtime on
the existing #631 supervisor substrate:

1. A and S co-exist but hold **different** runtime supervisor identities;
   possession of S's host group handle within a hostile *trusted test*
   actor A cannot mint B or install a CONTROL process.
2. A trusted S launch admits B into S, never A's group. B's real terminal
   exception reaches exactly S's separately scheduled CONTROL decision; A's
   parent group gets no failure notice.
3. A's ordinary nested spawn B inherits one existing S and preserves A -> B
   lineage without creating any new group or event-bus membership.

These are **JVM host** invariants only. The test deliberately uses a trusted
host factory for adversarial authority checks; it is not an allowed guest
capture or a way to bypass source lexical isolation.

## Still required for source-level proof

1. Parser/AST/typechecker for distinct `define supervisor S` and duplicate
   declaration, nesting, import and lexical-isolation rejection cases.
2. Compiler-lowered S bootstrap and `on_start` into a genuine scheduler-owned
   supervisor turn; a source `spawn B` must execute from that turn.
3. Source-level `on_panic`, graceful `on_done`, escalation and cancellation,
   with controlled failure injection and one-shot ordered CONTROL receipts.
4. Explicit supervisor capability checks, bounded supervisor depth/fanout,
   self-/mutual-recursive supervisor-spawn amplification diagnostics.
5. Shared fixtures compiling and running on exact Java/GraalVM and LLVM
   heads, including source-level A -> B, S -> B, B -> C, forged CONTROL,
   restart generations, concurrent stop/spawn and supervisor failure.
6. Exact-tree CI with native-image and LLVM sanitizer targets. A green
   host JUnit alone does not satisfy source execution or native parity.

**Integration plan:** keep this separate draft stacked on #631, without
silently claiming #631 or its recovery/panic prerequisites have merged.
