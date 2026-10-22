# Task-owned mutex permit lifecycle (Java runtime stage 1)

Status: **draft** (2026-10-22). Runtime-host implementation only; guest-source, exact-JDK25 build, and LLVM parity are not verified.

## Creation versus acquisition

A Task which constructs a `Mutex<T>` does not automatically own the lifetime of every subsequent lock acquisition. A mutex may be published to a longer-lived actor, shared arena, or authorized Arc capability. **A guard belongs to its acquiring logical Task**, not to the mutex creator or an OS worker thread. Acquisitions explicitly transferred to actor lifetime need a compiler-checked transfer; Task-to-Task transfer is forbidden by default.

Each tracked TaskHandle owns an implicit `TaskOwnedLockLedger` in trusted runtime metadata. The compiler does not insert a user-visible `defer`; it records per-Task permit authority in the Task's scheduler identity and emits normal lexical guard-drop instructions. The runtime backup activates on Task terminal quiescence even if guest finally/recover/tear_down never executes.

## Stage 1 host behavior

- `TaskHandle` binds an opaque ledger only for the actively executing tracked scheduler Task. Nested/untracked tasks do not inherit the caller's ledger.
- A synchronous `OresMutex.Local.lock/tryLock` or `OresMutex.Shared.lock/tryLock/lockFor` acquisition registers a guard. Normal guard release removes the reverse index entry in O(1).
- Cancellation **fences new registration immediately**; if a permit was physically acquired before the fence, its constructor rolls back the grant. Never free a live Task's held lock on cancellation.
- After scheduler-confirmed final carrier quiescence, the runtime revokes still-held guards in reverse acquisition order, outside the ledger monitor, before `getDone` may publish. A leaked shared writer guard poisons the protected value even on Task success, since it escaped its lexical critical section.
- If revocation fails, retain its registry reference, attempt later independent revocations, and do **not** report safe Task completion. A supervisor needs an explicit quarantine/diagnostic path in a later integration stage.
- Task metadata is quota-bounded (4096 simultaneously held permits in this host prototype); no per-Task new OS threads or mutexes.

`SharedMutex` owner identity uses an opaque OresScheduler ExecutionContext when available. This distinguishes concurrent logical Tasks in the same actor and survives scheduler carrier migration. The fallback remains the actor's execution domain or host Thread only when there is no Task context.

## P0 unimplemented / do not release source API

1. **Async waiter ownership**: `SharedMutex.lockAsync/lockAsyncFor` currently delivers guards on the releaser's carrier and does not bind pending requests or eventual grants to the originating Task ledger. It can leave an orphaned granted permit after Task death. Must atomically link queue registration, waiter cancellation, handoff and Task ledger ownership; implement before guest use. Reconcile Java PR #754 and issue #769.
2. **All actor handlers**: ActorRuntime's direct turns without tracked Task handles need an actor-incarnation ledger. Do not call arbitrary guard/Future cleanup while `ActorCell.lifecycleLock` is held; stage post-monitor notification (issue #717).
3. **Task child/actor ownership transfer**: Only explicit, authenticated transfer can change scope; atomic transfer must not race cancellation, STOP, or reincarnation. Do not capture a guard through unverified `rt share`.
4. **Dirty state recovery**: Arbitrary mutable shared payloads are poisoned on leaked writer until `recover` repairs invariants. Transactional updates may avoid poisoning only if they prove rollback. Readers and immutable data can have a different recovery rule.
5. **Backend parity**: native LLVM must integrate generation-fenced continuation tickets, nonparking admission and quiescence before lock release. Distributed lock ownership is separate and requires consensus leases + fencing enforcement at the resource.
6. **Exact-head evidence**: Run `TaskOwnedLockLedgerTest`, `MutexRuntimeTest` and TaskHandle/TaskScope suites on JDK25, then Java AOT and LLVM compiled-guest tests plus targeted model checking and race stress. A CI job with zero executed steps is not evidence.

Cross-repo reference: platform-conformance PR #158; native substrate LLVM PR #863. This stage must remain draft/unmerged until the async and actor-lifecycle gaps are closed.
