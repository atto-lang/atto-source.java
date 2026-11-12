# Actor-local task carrier ownership (Java runtime)

An AttoLang `task { ... }` created during an actor's admitted source turn
must use **that actor's existing mailbox scheduler**, not the root task
scheduler, supervisor CONTROL coordinator, a detached executor, or an I/O
producer's thread. A nested `task { ... }` inherits the same actor scheduler;
each logical task still has its own task identity.

Physical carriers follow the **actor's kind**:
- SHARED actor: shared actor dispatcher.
- PRIVATE actor: private/isolated actor dispatcher.
- UNTRUSTED actor: untrusted actor dispatcher and the same inherited logical
  untrusted fuel/wall quota for nested tasks.

The actor scheduler has logical parallelism one and enqueues every initial
turn and continuation into `ActorCell.sourceTaskMailbox`. A suspended task
may resume on a **different OS thread from the same actor pool**, but cannot
bypass `ActorCell.runBatchEntered()` or its physical single-writer lease.
Neither a root Task nor a supervisor may acquire actor state or supervisor
authority merely by observing a Future.

Every guest source-task turn now checks the runtime, actor ID, actor kind,
incarnation execution-domain identity, actor carrier marker, and active physical
turn lease **immediately before** executing the task. A misrouted turn fails
closed. This guard remains applicable to nested tasks and await continuations,
including ones awoken by foreign I/O completion threads. In addition, `SourceActorTaskExecutionTest` exercises real checked actor
source with nested `task { }` / `await` under shared/private actors and rejects
unsafe captures of mailbox `self` / `ActorMail`. Those authored tests still
require executed receipts before any PASS claim.

## Logical scheduler admission

A physical actor-carrier thread can run a synchronous nested scheduler
callback without leaving its actor lease. That does **not** grant the nested
scheduler authority to mint new actor Tasks. Both ordinary
`startActorTask` and tracked `startTrackedActorTask` admission now reject
any non-null `OresScheduler.current()` which differs from the actor cell's
mailbox-backed `sourceScheduler()`. Ordinary actor receive turns can admit
actor roots with no current Task scheduler. An executing actor Task can admit
children only through its exact owning scheduler.

`ActorForeignSchedulerAdmissionTest` uses a foreign inline scheduler nested
within a real SHARED actor physical carrier and asserts that both admission
entry points reject it without invoking CONTROL. Passing source-level code
review is not a substitute for executed JVM tests.

## Nested task ownership and physical lifetime

A `task { ... }` started from another **tracked** actor Task inherits the
currently authenticated `TaskCancellation` token. The parent therefore
cancels descendants with the same reason, and its physical `getDone()`
barrier remains pending until descendants physically retire, even when the
parent's result Future is already terminal. A parent with a settled result
can still request cancellation of physically live descendants; the original
terminal result and the eventual parent success/failure outcome remain immutable. The parent token is not
exposed to guest code; ownership is authenticated against the logical
`OresScheduler.currentTaskDomain()` on every admission.

A new actor task started from an ordinary (untracked) actor receive is
instead an actor-owned root: it remains subject to the actor STOP admission
seal and physical actor cleanup. This does **not** enable an actor
`task scope { }` or transfer `self` into detached tasks.

## STOP/admission linearization

The actor runtime now checks STOP/finalization and registers each Task with
the actor-owned `OresScheduler` while holding the same ActorCell lifecycle
monitor that STOP holds when it seals `sealActorStopAdmission()`. This closes
the time-of-check/time-of-use window in which a previously initialized
`sourceScheduler()` could remain reachable during STOP's cleanup lane.
Untrusted quota-slot reservations are provisional until the same atomic
scheduler-registration gate succeeds; failure unwinds those reservations.

A cancellation cleanup continuation is allowed to execute its *existing*
saved defer/finally state under the authenticated actor carrier, but neither
tracked nor untracked actor tasks can start fresh after STOP, even when the
old sourceScheduler object still exists. Cleanup never gains supervisor CONTROL
execution authority.

`ActorTaskStopCleanupAdmissionTest` exercises real source-code actor runtime
dispatch in PRIVATE, SHARED and UNTRUSTED kinds. It verifies one suspended
cleanup-aware task, STOP, the authenticated cancellation cleanup turn, failure
of BOTH task-admission paths in that turn, physical actor DONE, and late
external Future completion without guest reentry. All waits are on the host
test thread, never inside actor guest turns.

## Verification

`ActorNestedTaskLifetimeTest` specifically tests parent-result vs
nested-child physical retirement, parent cancellation cascading to a
suspended child, post-result cancellation of still-live child tasks without
rewriting parent results, actor STOP sealing admission before reporting DONE,
and late foreign Future completions not resurrecting guest turns.
No test performs a blocking join on an actor carrier.

`ActorSourceTaskPoolIsolationTest` exercises PRIVATE/SHARED/UNTRUSTED
actor-code callbacks, initial execution, foreign-producer `await` completion,
nested task creation, scheduler identity, host producer isolation, and a
CONTROL dispatcher configured to reject any task dispatch. It additionally
verifies actor stop and absence of actor failure.

`ActorTrackedTaskCancellationTest` exercises the tracked-task admission bridge,
its physical `getDone()` cancellation barrier, late foreign Future completion
without guest reentry, and external-thread admission denial. It is test
coverage, not evidence of execution before a real runner produces a receipt.

Run from the Java repo, with the required JDK/Graal/Maven native prerequisites:

```sh
mvn -B -ntp -Dtest=ActorSourceTaskPoolIsolationTest,ActorForeignSchedulerAdmissionTest,ActorTrackedTaskCancellationTest,ActorNestedTaskLifetimeTest,ActorTaskStopCleanupAdmissionTest,SourceActorTaskExecutionTest,ActorTaskBlockGuestExecutionTest test
```

Release gate: exact-head executed JUnit results, explicit job steps and XML
receipts; separately run compiled guest-source `task { }` + `await` actor
fixtures and native-image tests. Passing host-only assertions is insufficient
to claim guest-source support or five-backend parity. This Java change does not claim Wasm/LLVM/.NET/Forge parity. Actor `task
scope { }` remains deliberately rejected pending a separately proved
structured-lifetime contract; actor-local `task { }` is supported only for
compiler-checked detached, Send-safe captures.

## Private-source CI destination policy

The repository policy in `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`
is fail-closed: a target must be **private**, independently approved in
`.github/private-ci-approved-repositories.txt`, pinned in
`.github/private-ci-carrier-contracts.json` with its exact executable
workflow and test plan, and the protected source-main governance must pass.
An arbitrary funded GitHub organization is **not** sufficient approval.
A public destination is prohibited even for dangling, unreferenced Git blobs.

The current carrier contracts pin historical PRs #1482 and #616 only; neither
authorizes PR #2999. Do not copy this private source tree into an unapproved
destination. GitHub jobs with `steps=null` are NOT_EXECUTED, not a passing
compiler or guest receipt. The actor task PR remains draft until a valid
executed exact-head source test and independent approval exist.
