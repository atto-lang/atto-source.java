# Runtime-minted namespace actor state-turn authority (draft)

## Why this hardening exists

The earlier [Java #2948](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2948)
physical read/write-loan adapter allocated its own logical `ExecutionContext`
and execution slot for each scalar access. That is not sufficient evidence
that a permitted state loan belongs to a **runtime-issued logical turn**;
an adapter-generated identity cannot certify scheduler identity or lifetime.

This follow-up moves the authority issuer into the existing `ActorRuntime`,
without a second actor scheduler or host-owned Atto broker state machine.

## Issuance and scope

- `ActorRuntime` opens an immutable, runtime-owned source-state turn **inside
  the real `ActorCodeExecutor.initializeActor`, `receiveActor`, and
  `tearDownActor` invocation boundaries**, after normal actor admission.
  The turn is revoked in `try-with-resources` before control returns.
- Every source callback gets a unique monotonic physical slot, even when
  multiple mailbox messages execute in the same carrier batch.
- The internal permit binds **exact instance identity** of the runtime-supplied
  `ActorContext`, actor ID, `CodeImage`, callback phase and carrier.
  Copying the public `ActorContext` getters into a fake object cannot
  manufacture a matching permit, even while the actor is executing.
- Only INIT can mint a region allocation identity, **at most once per INIT callback**; a second source state allocation in the same activation is rejected. Only RECEIVE can produce
  a state RW-loan `ExecutionContext`. Only RECEIVE or TEARDOWN can retire
  an actor's physical region. All off-turn callers fail closed.
- The underlying `ExecutionContext.actorBound` is built by
  `ActorRuntime.sourceStateExecution` from the currently live runtime
  slot; it is no longer independently fabricated by the scalar adapter.
- The same RECEIVE callback reuses the same identity across its successive
  scalar read/write operations. The next RECEIVE gets a different slot.
  Reusing a STOPped actor or copying an earlier execution slot cannot
  create a new state permit after completion.

Slot and allocation counters fail closed at exhaustion; no wraparound.
Actor incarnations are fenced by the real `ActorId`, exact code image,
allocation identity, and runtime-issued per-callback identity. An
allocation-only handle or integer is **never** sufficient authorization.

## Security and ownership boundaries

This work deliberately retains `ActorStateRwLoans` from [#2796](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2796)
unchanged: physical reader/writer fairness, cancellation notification
drain, writer exclusivity and reclamation quiescence continue to be
checked there. The scalar compiler from [#2939](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2939)
continues to admit only exact, closed `int` and `bool` critical bodies;
no arbitrary state expression or caller-selected source execution identity
is enabled. `TypeChecker` still rejects general namespace actor execution.

These are Java VM actor-owned scalars with bounded physical-loan accounting,
not proof of native arena allocation, real guest ActorRef dispatch, cross-file
namespace linking, cancellation across suspended continuations, or AMQP.
The internal issuance methods are **not** guest syntax and are not included
in host-service/public foreign access.

## Negative executable tests (authored; execution pending)

`NamespaceActorScalarRegionTest` tests:
- exact context getter cloning from within an actual receive turn;
- rejection of writes during INIT and new allocations during RECEIVE/TEARDOWN;
- stable identity within a callback and different execution handle on a
  subsequent mailbox callback, including across STOP and respawn;
- off-turn issuance and state access denial;
- sibling isolation, stale actor references and state manager retirement.

Imported #2796 loan tests continue to cover overlapping readers/writers,
writer fairness, cancellation, ABA/slot reuse and retirement. The exact-head
JDK25 workflow is wired to run both suites with mandatory non-skipped Surefire
receipts. **A zero-step Actions failure is runner-admission failure, not
executed JUnit or guest proof.**

Until JDK25 compilation and the real actor tests execute successfully,
retain draft status and keep AttoRMQ native startup fail-closed.
