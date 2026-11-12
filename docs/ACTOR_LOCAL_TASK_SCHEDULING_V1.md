# Actor-local Task blocks — Java/GraalVM implementation slice

Status: **candidate, not yet exact-head CI verified**. Canonical language/source names are
AttoLang and `.atto`; Oreslang remains the compatibility name used by the
existing Java/GraalVM runtime.

## Execution decision

A `task { ... }` (or `cpu task { ... }`) evaluated from an authenticated
ordinary actor mailbox turn inherits the **actor's mailbox-backed source
scheduler**. It is a logical child of that actor incarnation, NOT a new actor,
thread, ActorGroup, supervisor, or scheduler carrier pool.

- PRIVATE actors: continuations run on the bounded PRIVATE actor carrier pool.
- SHARED actors: continuations run on the bounded SHARED actor carrier pool.
- UNTRUSTED actors: `task {}` currently fails closed until tracked-child
  admission accounts for the same logical fuel, wall time, cancellation,
  physical cleanup and isolate quotas already used by `startActorTask`.
- Supervisor and Mailman CONTROL work remains on the VM-owned CONTROL pool;
  neither schedules an ordinary actor child nor gains its execution context.
- Actor `sourceScheduler()` uses its existing bounded SOURCE_TASK continuation
  mailbox and single-writer actor turn lease. A Future completion on an I/O,
  timer or foreign thread **only enqueues** a resume, never runs guest code.
- Await/cooperate may migrate physical carriers within the actor kind's
  pool while keeping actor incarnation, source scheduler and task context.
- STOP seals task admission and cancels descendants; `get_done()` and actor
  termination require physical cleanup/quiescence. A cancelled Future alone
  is not a memory-retirement witness.

## Source example

```atto
define actor Worker as
  receive(ActorMail<String> mail): void {
    const int snapshot = 40;
    let Future<int> child = task {
      return snapshot + 2;
    };
    self.send(await child);
    self.end();
    return;
  }
end
```

Captures remain independently ownership-checked: the compiler publishes an
identity-bound `Ast.TaskCaptureAdmission`; the interpreter only transfers
the permitted COPY/MOVE snapshot. Actor `self`, direct actor-owned mutable
fields and arbitrary borrowed/unsafe capabilities cannot be captured into
the child. Running on an actor carrier is NOT a general state-access permit;
only the existing sealed actor-state critical gates may expose mutable state.
Ordinary immutable owned data is safe to pass.

`task scope` inside an actor is not enabled by this patch; physical
structured-scope and reentrant quota integration require separate proof.
Likewise, no new native `thread.spawn` privilege is granted. Detached tasks
do not transfer raw actor state or CONTROL authority.

## Tests and evidence requirements

1. Compiler positive: owned scalar snapshot into a task in actor receive,
   child awaited, output emitted, shutdown completed.
2. Compiler negative: `self` capture, mutable state escape, untrusted actor
   tracked-child admission, cross-scope/foreign task authority.
3. Host scheduling: PRIVATE and SHARED actor worker thread names/provenance;
   same actor ID and `OresScheduler` across await resumed after producer
   completion from a foreign thread; never Mailman/CONTROL context.
4. STOP/cancellation race: pending child cannot outlive actor DONE, guest
   cleanup runs on actor lane, and no stale actor incarnation is revived.
5. Cross-backend: LLVM/Forge/.NET/Wasm must either implement the same actor
   scheduler admission and physical retirement proof or reject unsupported
   source forms. The separate native single-i64 `SourceTaskRuntime.c` is
   **not** by itself evidence for actor-owned TaskBlock parity.

Executable receipts must name exact source commit/tree and compiler binary
digest, include nonempty executed steps, and distinguish compiler/guest
execution from host reference tests. This document alone is not evidence of
passing tests or backend parity.
