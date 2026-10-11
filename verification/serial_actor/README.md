# Serial-actor lifecycle verification

This directory defines a **bounded TLA+ safety model** for a single persistent
serial actor, two queued handler identities, two runtime task identities,
one restart, and at most three handler generations.

Run with TLC:

```bash
java -jar tla2tools.jar -workers 1 -deadlock \
  -config verification/serial_actor/SerialActorLifecycle.cfg \
  verification/serial_actor/SerialActorLifecycle.tla
```

The checked safety properties are:

- **SingleHandlerLifetime** — zero or one admitted handler; suspension keeps
  its admission; stop has no active owner.
- **FifoAndOwnership** — waiting handlers are unique, in FIFO order, and
  distinct from the active owner.
- **NoForgedExecution** — recorded task execution has precisely the ticket's
  captured actor-owner, incarnation and generation.
- **NoDuplicateTaskExecution** — each modeled task performs at most one
  recorded guest step.
- **TypeOK** — finite-state domain and lifecycle invariants.

The model treats task identity as runtime-issued and unforgeable. It deliberately
rejects an old ticket after a restart because incarnation differs, even if
generation values are reused. `Resume(t)` is allowed only for the currently
admitted ticket, and no unrelated task can resume the suspended handler.

**Scope and assumptions:** this is a finite abstraction of
`SerialActorAdmission`, `SerialExecutionTicket`, and the actor mailbox
scheduler. It does **not** prove the entire Java/LLVM implementations, absence
of Java data races, physical quiescence during `defer/recover/finally`, HTTP
transport, timeout fairness, or liveness under unfair scheduling. The model
allows `Stop` at any nonterminal state, including while suspended. A complete
liveness proof would additionally require explicit fairness assumptions for
scheduler dequeue and wakeups and constraints on cancellation.

**Refinement obligation:** every production state transition must correspond
to a modeled transition, including a task completion callback on a foreign
thread, cancellation racing resume, and restart. If the runtime adds lifecycle
states or new admission paths, revise this model and its TLC check *before*
marking the integration PR ready for review. Keep CI required on the complete
source tree in addition to TLC.
