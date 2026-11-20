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
- Future continuation handles are **static nested** runtime handles with
  separate **weak** references to the exact originating ActorRuntime and
  ActorCell. An implicit non-static outer-class pointer or an explicit strong
  runtime field must not pin a closed runtime through a foreign pending Future.
  An id reused after actor hot reload cannot redirect a stale callback into
  a new actor's heap. Caller-supplied callback closures may independently
  retain data: weak handle storage does not sanitize arbitrary capture graphs.

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
- The follow-up matrix https://github.com/honeypot-r-us-test/hnpt-lib-core/actions/runs/40773114902
  ran actual steps independently on Temurin JDK21 (job 123115257939)
  and Temurin JDK25.0.5 (job 123115257699). Both report 9 smoke checks
  and 6/6 JUnit tests passed with BUILD SUCCESS.
- Source integration's foreign-runtime target rejection is covered by
  an authored actor-dispatch integration test, but it has NOT executed
  because the complete ActorRuntime dependency graph is not mirrored.

The mirror proves only the exact standalone ledger class and unit tests,
NOT ActorRuntime integration, Graal Native Image, actual guest .ores
execution, or exact JDK25 runtime behavior. Source-org test jobs on the
current branch have failed before executing steps and are not passing tests.

## Local exact-blob callback-retention hardening (2026-10-29)

A continuation admission may remain reachable through an envelope or
diagnostic handle after it completes or is dropped. Its receipt no longer
retains the captured guest callback once the callback is claimed for
execution, discarded by STOP, or explicitly closed before execution.
The executing Java stack temporarily owns the callback; the receipt
remains RUNNING until its physical `run()` finally returns.
No guest callback is executed while holding the ledger monitor.

- New source blob: `97f86086dc9c89a4255dd540388144cd691c5d0b`
- New standalone JUnit test blob: `901d8bb67a00c81ef5b32d5ecc823c5008a3b558`
- Compiled **the exact 5,822-byte source blob** with local OpenJDK 21
  `javac`; its local `git hash-object` matched the connected GitHub blob.
- Ran a standalone Java 21 smoke harness: **17/17 passed**, including
  normal callback release, close/discard, STOP fencing, replay rejection,
  SOURCE_TASK cleanup after STOP, failed callbacks, and concurrent STOP
  while a callback is physically RUNNING.
- Three added JUnit methods check terminal callback retention, the
  RUNNING/retirement distinction, and 128 run/close/STOP interleavings.
  The complete ledger class now has **9 JUnit methods**, committed but
  **not yet executed with JUnit** on this changed PR head.
- An additional local Java 21 stress harness compiled the same exact ledger
  blob and completed **512/512** concurrent run/close/STOP race iterations
  with no duplicate callback, leaked receipt or terminal callback closure.

This local smoke execution proves only the isolated actor continuation ledger.
It does **not** prove `ActorRuntime` integration, full Maven/JDK 25, JVM
Native Image, task arena reclamation, or guest code behavior. Source `main`
must satisfy live branch protection and approved carrier governance before
any private Oreslang source is exported into a different GitHub organization.
Existing private mirror receipts above cover the **previous** ledger blob,
not this modified code.

## Continuation target retention boundary (2026-10-29)

An opaque `ContinuationTarget` was previously a **non-static** inner class,
which carries a synthetic strong `ActorRuntime.this` reference. The explicit
`originatingRuntime` field also held the runtime strongly. Weak capture of the
`ActorCell` alone did not prevent a long-lived foreign Future from retaining
the entire runtime object graph. The target is now a static nested class
holding weak references to both runtime and cell; it resolves both live owner
and exact ActorCell identity before every continuation enqueue.

- ActorRuntime integration blob: `2750b589e3d4fafa9a5f5cb98302c2552e2fc2eb`
- Integration JUnit test blob: `bfac3fd3200dcbaeeeebdcf88a63f3be1a630ae9`
- A local Java 21 isolated compilation of the new target shape passed 11
  smoke assertions for owner identity, ordinary enqueue, foreign owner
  distinction, stale actor-generation rejection, static nested layout, and
  absence of strong runtime fields. **This is a standalone type/semantics
  characterization, not an executed ActorRuntime integration test.**
- The committed JUnit layout test is pending execution with real ActorRuntime.
  Existing runtime-affinity and stale-actor integration tests also remain
  unexecuted on the current head due to zero-step source Actions.

No private source was sent to a different GitHub organization for this change.
Both source-main protection and approved destination policy remain mandatory
for any later private cross-org source export.

## Borrowed suspended-receive Future callbacks (2026-10-29)

`ActorRuntime.suspendCurrentUserMailboxUntil(future)` can observe an
indefinitely pending *borrowed* producer. Its old terminal subscription
implicitly retained both the actor cell and the containing ActorRuntime
through the completion lambda, potentially keeping stopped actor state
reachable until foreign completion.

The subscription now captures only two `WeakReference` values: one for
the exact originating ActorRuntime and one for the exact ActorCell.
On completion it resolves both references, then delegates to a
host-only runtime helper. Missing/collected identities are a no-op.
If both remain live, the helper uses the existing actor identity and
STOP admission checks, and only **enqueues** the continuation.
No source guest code executes inline on a foreign producer thread.

- Updated `ActorRuntime.java` blob:
  `d07da8962c1e6606ae8dbd0d428c7a4dcb091d69`
- `ActorContinuationLedgerIntegrationTest` now includes a reflection
  test that verifies the registered waiter's callback captures exactly
  two weak identities, contains no strong runtime/cell fields, and
  accepts late completion of an independently owned producer after STOP.
- The full actor integration JUnit test is **committed but unexecuted**:
  the source organization still fails runner admission before any steps.
- This is reachability isolation only. It does not prove GC timing,
  weak-reference clearing, native source loan release, Task physical
  retirement or complete host/guest semantics. A producer's callback
  may still hold independent external objects, and strong actor
  ownership in the actor table remains authoritative until retirement.

## Executed source-free CI selection regression (2026-10-29)

The GraalVM 25 `actor-runtime-exclusivity` workflow explicitly includes
both `ActorContinuationLedgerTest` and
`ActorContinuationLedgerIntegrationTest`, plus the original actor carrier
checks, with `-DfailIfNoTests=true`. A dedicated source-free verification
script runs as the first post-checkout job step, **before GraalVM setup**.

- Exact committed workflow blob: `3af46666ff47050acfef53e4a66195e002ec730c`
- Exact committed offline guard blob: `bd0dcfca28cd0f761bb7e0b212dda048f95179ef`
- The local reconstruction of **both** files matched the GitHub blob hashes.
  `bash -n` succeeded, and running
  `bash tools/test-actor-continuation-workflow-selection.sh --self-test`
  against the exact committed workflow passed **1 positive + 12 negative** cases.
  The negative mutations removed either ledger selector, downgraded JDK 25,
  disabled `failIfNoTests`, removed the actor acceptance job, injected
  `continue-on-error: true`, skipped the Maven step with `if: false`,
  disabled JUnit via `-DskipTests`, or replaced Maven with `echo mvn`.
  The guard additionally resolves selectors from the actual named Maven
  `run: >` step rather than trusting unrelated step metadata.
  Further negative controls prohibit ambient `env`/shell-hook overrides,
  a false-green extra shell command, and an appended Maven failure mask.
  Its active Maven command must remain exactly three lines.
- Current exact-head source-org [actor workflow run 40930186407](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40930186407),
  `actor-runtime-exclusivity` job `123646235177`, failed before executing
  any steps. Source-free local shell checks **are not JUnit, Maven, GraalVM
  Native Image, full actor runtime or guest-language execution**.
- Do not promote this PR from draft without independent review, source-org
  runner admission, a real JDK 25/JUnit execution receipt, and physical
  actor/task retirement acceptance on the reviewed integrated tree.

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
