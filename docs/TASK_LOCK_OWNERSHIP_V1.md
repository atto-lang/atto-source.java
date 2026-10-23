# Task-owned mutex permit lifecycle (Java runtime stage 1)

Status: **draft** (2026-10-22). Runtime-host implementation only; guest-source, exact-JDK25 build, and LLVM parity are not verified.

## Creation versus acquisition

A Task which constructs a `Mutex<T>` does not automatically own the lifetime of every subsequent lock acquisition. A mutex may be published to a longer-lived actor, shared arena, or authorized Arc capability. **A guard belongs to its acquiring logical Task**, not to the mutex creator or an OS worker thread. Acquisitions explicitly transferred to actor lifetime need a compiler-checked transfer; Task-to-Task transfer is forbidden by default.

Each tracked TaskHandle owns an implicit `TaskOwnedLockLedger` in trusted runtime metadata. The compiler does not insert a user-visible `defer`; it records per-Task permit authority in the Task's scheduler identity and emits normal lexical guard-drop instructions. The runtime backup activates on Task terminal quiescence even if guest finally/recover/tear_down never executes.

## Stage 1 host behavior

- `TaskHandle` binds an opaque ledger only for the actively executing tracked scheduler Task. Nested/untracked tasks do not inherit the caller's ledger.
- A synchronous `OresMutex.Local.lock/tryLock` or `OresMutex.Shared.lock/tryLock/lockFor` acquisition registers a guard. Normal guard release removes the reverse index entry in O(1).
- A `SharedMutex.lockAsync/lockAsyncFor` request made inside a tracked Task registers the *pending waiter* with that Task before it becomes observable. Handoff registers the eventual guard with the originator's Task ledger, not the releaser's Thread. A cancelled/terminal Task revokes its queued waiter; an unpublished in-flight handoff loses to owner-quiescence cancellation and discards its grant. Successful held guards remain owned until explicit release or Task retirement.
- Public queued timeout/cancellation physically unlinks the waiter and its wait-for edge and returns async quota before completing its Future. If dequeue already claimed the handoff, public cancellation/timeout cannot forge a win. This integrates the essential settlement invariant from Java draft #754, **not** its entire callback and resource-admission hardening.
- `SharedMutex` handoff now retains a private non-cancellable `physicalSettlement` receipt through publication, including blocking non-Async Java Future subscribers. The Task ledger retains the pending request until that receipt completes; `TaskHandle.getDone` does not report quiescence while a dequeued handoff is still physically executing. The zero-copy guarded payload is not safe to regrant merely because `GuardFuture.isDone()` is true.
- **Queued cancellation receipt ordering:** `revokeQueuedWaiter` physically unlinks the queue entry, releases wait-for/quota accounting and marks the waiter revoked, but does not complete its Task retirement receipt. The receipt completes only after `GuardFuture.notificationComplete()` runs following the synchronous Future subscriber drain. Thus `TaskHandle.getDone` cannot publish while an existing cancellation subscriber is still executing. Claimed handoffs retain their separate grant settlement receipts.
- Queued grant, timeout and Task retirement public Future notifications use **bounded-admission virtual threads**, never a fixed-worker pool or `CallerRunsPolicy`. Each accepted async request reserves one global notification slot before publication; a one-shot claim prevents duplicate terminal submissions and the slot is released after callback drain. If host notification scheduling fails after a handoff has been claimed, its private retirement receipt completes exceptionally and the Task must not publish DONE; an unpublished guard remains quarantined instead of being unsafely granted to a successor. Blocked subscribers cannot silently turn the releasing worker into the callback executor or starve all unrelated handoffs.

- Cancellation **fences new registration immediately**; if a permit was physically acquired before the fence, its constructor rolls back the grant. Never free a live Task's held lock on cancellation.
- After scheduler-confirmed final carrier quiescence, the runtime revokes still-held guards in reverse acquisition order, outside the ledger monitor, before `getDone` may publish. A leaked shared writer guard poisons the protected value even on Task success, since it escaped its lexical critical section.
- If revocation fails, retain its registry reference, attempt later independent revocations, and do **not** report safe Task completion. A supervisor needs an explicit quarantine/diagnostic path in a later integration stage.
- Task metadata is quota-bounded (4096 simultaneously held permits in this host prototype); no per-Task new OS threads or mutexes.

`SharedMutex` owner identity uses an opaque OresScheduler ExecutionContext when available. This distinguishes concurrent logical Tasks in the same actor and survives scheduler carrier migration. The fallback remains the actor's execution domain or host Thread only when there is no Task context.

## P0 unimplemented / do not release source API

1. **Notification and physical-handoff evidence**: A claimed async handoff now keeps an independent physical settlement receipt owned by the Task ledger. `getDone` is withheld until it settles; queued handoffs/timeout/cancel notifications run on a quota-bounded host notifier executor rather than the releasing/scheduler carrier. These are draft runtime mechanisms, **not a demonstrated JVM/Graal source-level guarantee**. Real JDK25 concurrency, blocked-subscriber, queue saturation, notifier rejection, and unexpected host exception fault-injection tests still must execute. The CompletableFuture guest-host boundary from issue #769 must remain fail closed until validated.
2. **All actor handlers**: ActorRuntime's direct turns without tracked Task handles need an actor-incarnation ledger. Do not call arbitrary guard/Future cleanup while `ActorCell.lifecycleLock` is held; stage post-monitor notification (issue #717).
3. **Task child/actor ownership transfer**: Only explicit, authenticated transfer can change scope; atomic transfer must not race cancellation, STOP, or reincarnation. Do not capture a guard through unverified `rt share`.
4. **Dirty state recovery**: Arbitrary mutable shared payloads are poisoned on leaked writer until `recover` repairs invariants. Transactional updates may avoid poisoning only if they prove rollback. Readers and immutable data can have a different recovery rule.
5. **Backend parity**: native LLVM must integrate generation-fenced continuation tickets, nonparking admission and quiescence before lock release. Distributed lock ownership is separate and requires consensus leases + fencing enforcement at the resource.
6. **Exact-head evidence**: Run `TaskOwnedLockLedgerTest`, `MutexRuntimeTest` and TaskHandle/TaskScope suites on JDK25, then Java AOT and LLVM compiled-guest tests plus targeted model checking and race stress. A CI job with zero executed steps is not evidence.

**New tests added, not yet executed in exact-head JDK25 CI:** blocking a non-Async Future subscriber while proving Task DONE stays behind physical handoff; four blocked subscribers versus a fifth independent grant to detect notifier starvation;  Task-owned queued waiter retirement, async grant authority across a releasing host Thread, timeout physical cleanup before publication, 100 cancel/handoff races, and cancellation with an active Task carrier. Source-org CI still reports failing jobs with zero steps; these are not code test failures or passing evidence.

Cross-repo reference: platform-conformance PR #158; native substrate LLVM PR #863. This stage must remain draft/unmerged until the async and actor-lifecycle gaps are closed.

- **Immediate async acquisition hardening:** `SharedMutex.lockAsync()` now submits the immediately acquired guard (and immediate failure/poison notifications) through the same bounded host notifier as queued grants. It checks that a guard is still live before Future publication and rejects a capability released by a terminated Task. If notifier submission itself fails after the physical grant, the private handoff receipt fails exceptionally and the permit is quarantined; Task `getDone` must not report success.
- **Losing handoff / stale terminal waiters:** If Task-death cancellation already claimed a Future, a dequeued grant or failure cannot settle its physical handoff until the winning cancellation's non-Async completion subscribers finish. Likewise, a lock releaser skipping a terminal Future still found in the queue waits for that Future's private notification-drain receipt; it does not infer safe cleanup from `isDone()`. The skip path schedules receipt completion off `asyncQueueLock`. Deterministic reflective JUnit fault-injection regressions cover both cases. Two independent source-free Java 21 concurrency models completed 4,000 losing-handoff and 1,000 terminal-queue iterations, respectively. **These are not project JUnit passes.**
- **Residual host API caveat:** Java `CompletableFuture` invokes a non-Async dependent inline on the *registering* thread when registration happens after the Future is already completed. Off-carrier completion does **not** make arbitrary late `whenComplete` calls safe on a guest actor carrier. Guest-language await/continuation lowering must schedule onto an authenticated Task domain, or use a Future facade with strictly asynchronous guest callbacks. This remains a release gate.


- **Task terminal callback receipts:** `TaskHandle.getDone()` commits its
  public terminal state before runtime subscribers return. A separate
  `doneCallbacksDrained()` receipt completes only from the Future's final
  notification hook. It is exposed as a read-only minimal CompletionStage,
  so a cancelled observer copy cannot forge Task physical quiescence.
  Actor STOP must join this drain receipt before releasing Task tracking or
  retiring actor backing storage. This is distinct from the lock permit's
  own physical-settlement receipt.

- **Two independent Task notification drains:** A Task's `getDone()` terminal CAS and callback drain do not imply the scheduler's separate source-result Future has drained all its subscribers. `TaskHandle.terminalCallbacksDrained()` now joins both source-result and Task-DONE completion drains into one read-only, unforgeable observation stage. Actor STOP retains the Task until this *combined* receipt settles. This applies to ordinary tracked Tasks and UNTRUSTED wall-deadline Tasks, preserving the original quota callback and propagating its exceptional failure as an unsafe receipt. The new JUnit regressions block source-result subscribers after `getDone()` is already terminal; these project tests still require JDK25 CI.

- **Structured TaskScope lifetime:** Each TaskScope child now releases its active join slot only after the combined source-result and Task-DONE callback-drain witness succeeds. A public child `getDone()` CAS alone is insufficient to reclaim scope storage. A failed drain leaves the scope fail-closed with a host-only retirement diagnostic, not a false scope `getDone`. Deterministic regressions block a child's source-result subscriber after public Task DONE and require the sealed scope to remain pending.
