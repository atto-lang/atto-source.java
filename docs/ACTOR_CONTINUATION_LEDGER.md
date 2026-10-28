# Actor-local continuation receipts (phase 1)

## Authority and default ordering

An Oreslang actor has one physical guest execution lease, even when many
Futures, timers, and SourceTasks are ready. The VM/scheduler decides **when**
the actor is runnable. The actor owns **which admitted event** may execute.

The current runtime already selects sourceTaskMailbox before
continuationMailbox, and those before ordinary user mailbox messages.
A suspended source receive prevents later ordinary messages from overtaking
its logical turn. This change **does not enable arbitrary re-entrancy**, reorder
events, or make actor state concurrently writable.

This implementation makes the existing continuation lane a second,
actor-local accounting authority:

- Each admitted callback receives a monotone sequence and actor-local
  generation, in a fixed-capacity receipt ledger.
- A receipt transitions QUEUED -> RUNNING -> RETIRED exactly once, or QUEUED
  -> DISCARDED. Duplicate invocation fails closed.
- Runtime STOP increments the ordinary continuation generation and revokes
  not-yet-entered ordinary callbacks. Already-running callbacks leave through
  their own finally and the physical actor carrier lease.
- SourceTask cleanup continuations use a separate lane and remain admissible
  under the existing STOP cleanup authority. Finalization seals *both* lanes.
- Closing a drained envelope releases its ledger receipt. A foreign close
  cannot falsely retire an in-flight callback.
- Future continuation handles retain a **weak** reference to the exact
  owning ActorCell. An id reused after actor hot reload cannot redirect a
  stale callback into a new actor's heap.

The ledger is bounded by INTERNAL_CONTINUATION_SLOTS + 1: the one
physically executing callback may coexist with a completely full bounded
continuation queue. This does not increase mailbox admission capacity.

## Lock and heap contract

The ledger is private to ActorCell; its receipt objects are never exposed
to Oreslang guest code or scheduler callers. Ledger operations only mutate
metadata under their own monitor; **no guest callback** executes while the
ledger monitor is held. ActorCell's lifecycle lock is acquired before ledger
methods during admission and STOP, never while running callback bodies.

A callback's retirement receipt is **not** proof that the actor carrier,
source task, native I/O loan, or arena is physically quiescent. The existing
activeTurns, carrierActive, source scheduler retirement, and native
loan gates remain authoritative for physical reclamation. Actor metadata and
closures still require runtime memory accounting.

## Failure and race acceptance

1. Callback completion from a producer is only an enqueue, never guest
   execution on a producer, timer, or I/O thread.
2. A duplicate/cancelled envelope cannot execute its callback twice.
3. A running callback remains recorded until its finally returns, even if
   cancellation or STOP happens concurrently.
4. A queued ordinary callback that loses STOP does not execute.
5. A pre-admitted SourceTask lexical cleanup can proceed after STOP; a
   new ordinary message cannot.
6. A stale actor-incarnation target cannot enqueue into a replacement actor.
7. The normal actor execution lease and continuation-first mailbox ordering
   remain unchanged; an unresolved Future does not consume a carrier.

JUnit coverage: ActorContinuationLedgerTest (bounded receipts, replay,
STOP, cleanup, race and exception retirement) and
ActorContinuationLedgerIntegrationTest (concrete actor carrier/ordering
and late completion). Run with the repo's Java/JDK25 build. Source CI with
zero executed steps cannot establish these tests as passed.

## Executed private exact-blob verification (2026-10-28)

- Source ledger and private mirror blob SHA:
  a153647eea23d15372a19f9fd24b493dc22d6d7c
- Source JUnit ledger test and private mirror blob SHA:
  a0804954fedbf1892aab8e2cd8c1dcdac499656e
- Private mirror PR:
  https://github.com/honeypot-r-us-test/hnpt-lib-core/pull/7
- Executed private test run:
  https://github.com/honeypot-r-us-test/hnpt-lib-core/actions/runs/40772904161
- Job 123114526128 has actual checkout, JDK21 setup, javac + java smoke,
  and Maven test steps. Logs: 9 smoke checks; 6 JUnit tests passed,
  0 failures, 0 errors, BUILD SUCCESS.

The mirror proves only the exact standalone ledger class and unit tests,
NOT ActorRuntime integration, Graal Native Image, actual guest .ores
execution, or exact JDK25 runtime behavior. Source-org test jobs on the
current branch have failed before executing steps and are not passing tests.

## What phase 1 does not claim

This is **Java host/runtime execution accounting**, not a compiler feature
or a completed cross-backend guest-language contract. It does not claim:

- safe opt-in concurrent/re-entrant user mailbox turns;
- deadline-aware scheduling, RT qualification, or bounded WCET on LLVM;
- compiled .ores continuation/frame lowering across Java/LLVM/.NET/Wasm/
  Forge/Cranelift;
- equivalence of actor-owned state-loan lifetimes across suspension;
- full accounting for ordinary user messages, CONTROL notices, or all
  scheduler wake tokens.

The next slice is a shared, serialized acceptance vector format with actor
incarnation, event sequence, lane, suspend/resume, STOP, physical retirement
and rejected stale wake outcomes; then *compiled* guest execution in Java,
LLVM, .NET, Wasm, and Forge. Use the same vectors on each backend, record
unsupported lowering explicitly, and do not treat a host-only test as guest
proof.

Reconcile with Java continuation PR #161 and timer/lifecycle contracts #2203
and #2210 before integrating source-level re-entrancy features.
