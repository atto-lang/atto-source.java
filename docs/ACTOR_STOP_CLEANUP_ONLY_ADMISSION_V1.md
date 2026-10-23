# Actor STOP cleanup-only SourceTask lane (Java draft)

Date: 2026-10-22. **Status: draft; exact-head JDK25/JUnit execution NOT verified.**

Stack: Java SourceTask cancellation #1887, mutex grant retirement #1926/#1933,
this draft. Companion native handoff substrate #869 is not yet connected to
the actor STOP implementation here.

## Lifecycle contract staged in this branch

1. STOP/cancel/failure seals the ordinary actor mailbox and generic Future
   continuation channel. Already-admitted source scheduler commands are held
   in an independent, bounded, runtime-only control mailbox.
2. Under the actor lifecycle monitor STOP atomically seals SourceTask
   admission using the scheduler admission lock and captures the currently
   admitted Task snapshot; this phase invokes NO Future callbacks.
   A cancellation-snapshot barrier blocks actor finalization. STOP then
   requests cancellation of the snapshot only AFTER the actor lock is released.
3. Only after cancellation requests are issued may the actor dispatch saved
   scheduler SourceTask commands in STOP cleanup mode. Ordinary messages and
   generic Future completion callbacks remain denied. The preserved source
   commands are tied to the existing ActorCell/ActorId and never transferred
   to a replacement incarnation. A source turn dequeued BEFORE STOP is
   already admitted and must not be discarded as a stale queued command.
4. CleanupAwareTask cancellation returns to its saved lexical continuation.
   Deferred cleanup can itself await an asynchronous future and execute actor
   scheduler safepoints only while carrying the authenticated cleanup-turn
   marker. The actor done barrier remains pending across that suspension.
5. An authenticated cancellation cleanup Task may register a bounded
   `ownCurrentActorFuture` for an asynchronous resource/iterator close even
   after STOP. Other callers cannot: generic Future completion callbacks do
   not receive cleanup authority. These cleanup-owned Future registrations
   keep actor DONE pending even after the SourceTask itself has exited.
6. The scheduler reports physical quiescence only after its live Task set and
   all in-flight task-retirement callbacks have settled. The actor additionally
   joins the UNTRUSTED task count and timed terminal-subscriber admissions.
   Pending work defers actor registry removal, memory-slice close, and done.
7. Any rejected source cleanup admission, cleanup-owned Future quota overflow,
   scheduler incomplete cleanup, or failed physical retirement callback must
   quarantine rather than publish
   a clean actor-done or reclaim its arena.

The source continuation lane does not introduce a new worker pool, OS thread,
guest-visible supervisor, mutable sharing mechanism, or memory layout.
It preserves existing per-actor single-writer execution; Task identity and
lease/handoff receipts remain independent of AoS/SoA/AoSoA/SoAoS/ECS layout.

## Regression matrix committed (NOT YET EXECUTED)

`ActorStopCleanupAdmissionTest`:
- STOP/cancel a shared actor while its SourceTask holds a mutex and awaits a
  borrowed producer, then block its cleanup on a SECOND asynchronous future.
- Assert the actor remains non-finalized, the guard remains physically held,
  ordinary Future continuation admission and fresh Task admission are denied.
- Settle the async cleanup, require exactly-one guard release, no spurious
  poison, correct cancellation cause, and a successor actor with a new ID.
  Keep a separately registered async-close Future pending after Task unwind:
  actor done must remain blocked until that receipt is settled.
- Cancel a queued SourceTask before first dispatch while the enclosing actor
  turn is blocked: no guest start, no lost queued command, actor eventual DONE.
- Crash a running actor after a SourceTask suspends with a mutex guard. Require
  original failure preservation, post-crash asynchronous cleanup, physical
  release before done, and no unintended guard poison.

The existing task/mutex and actor structured-cancellation suites are included
in the focused exact-head matrix, for Java and GraalVM-native carrier options.

## Remaining merge gates / limitations

- Complete JDK25/GraalVM compilation and run JUnit on an **authorized** runner.
  Previous Actions submissions produced zero job steps; such failures do not
  establish either test pass or test failure.
- Inspect scheduler-vs-STOP races under deterministic dequeue barriers and
  sanitizers (where applicable); exercise repeated STOP, actor crash, async
  defer exceptions, forced quota expiry, oversized cleanup queues, and
  fault-injected physical-receipt callbacks.
- Make arena loan receipts and TaskHandle-owned mutex physical settlement
  first-class supervisor joins, including poisoned state on unrecoverable
  abandoned mutable locks. This branch only joins scheduler and untrusted
  admission receipts, not all native I/O/FFI/arena loans.
- Add real *source-syntax* `defer`/async iterator closing with actor STOP
  and compare Java/LLVM traces; host-level CleanupAwareTask tests do not prove
  parser/lowering parity. Typed `raise`/`recover` is also pending.
- Define STOP cleanup wall/fuel budgets and failure reporting without ever
  treating a timeout/quarantine as a successful cleanup receipt.
- Resolve actor `self.endWithCleanup` ordering relative to suspended
  SourceTask lexical cleanup in guest conformance tests.
- Do not use cross-organization private source Git-tree mirroring without
  independent security approval and a protected destination allowlist.
- Keep the PR draft/unmerged until those gates have passing evidence.

## Adversarial audit follow-up (2026-10-22)

This stacked draft also closes additional premature-finalization windows
detected by reviewing the exact actor STOP and TaskRunner code:

- **Existing actor-owned Future revocation:** STOP sets
  `pendingOperationRevocationPending` while holding lifecycleLock, snapshots
  existing pending operations and revokes them OUTSIDE the lock. No arena
  release or actor DONE can win before every revocation attempt returns.
  Revoker failures quarantine and do not prevent later revocation attempts.
  **This is not an I/O/FFI physical loan receipt**: an asynchronous revoker
  may return before a driver closes a native handle.
- **Terminal subscriber drain:** an admitted SourceTask increments a
  `pendingTerminalNotifications` receipt; terminal completion is not
  sufficient until `OresFuture.onNotificationDrained` returns. This closes
  a gap where the Task left its scheduler live set but synchronous terminal
  subscribers were still executing, potentially outside the actor carrier.
- **Physical retirement callback:** a separate bounded in-flight callback
  counter prevents arena release after live-set removal but before
  `onQuiescent` returns. Callback exceptions mark incomplete cleanup.
- **Quarantine latch:** an asynchronous cleanup Future quota failure can set
  `sourceStopCleanupAborted` before STOP publishes the cancellation snapshot.
  STOP now ORs snapshot failure into that state, rather than erasing it.
- **Atomic scheduler-creation check:** an already-running guest actor turn
  cannot construct a new unsealed source scheduler after STOP.

New deterministic regression coverage was committed for blocked old-Future
revocation, blocked Task terminal subscriber, blocked physical retirement
callback, and failed physical callback. These are source tests **not yet
executed** by an authorized JDK25 runner.

A read-only structural audit against the branch source checked 13 critical
presence/order invariants; all 13 matched. Static checks do not establish
compilation, scheduler fairness, race freedom, or runtime correctness.

**Private CI policy gate:** `.github/private-ci-approved-repositories.txt` on
protected `main` contains no approved external repository IDs. Policy PR
#1538 remains open and unmerged. Therefore no source Git objects were copied
to another organization, regardless of remote Actions minute availability.
The latest source-org workflow jobs fail at runner admission with zero steps.
The mirror publisher must remain disabled until an independently reviewed
exact destination is approved on the protected branch.

Additional mandatory regression: fault-inject a pending-operation revoker that
returns before its asynchronous driver teardown. Supervisor/arena release
must await a separate physical loan receipt (not merely `Future.cancel`),
including success/failure, duplicate cancellation and incarnation rollover.
The existing code **does not yet provide** that universal receipt aggregation.

## Owner Future STOP receipts and child cancellation ordering (2026-10-22)

Further adversarial review of this draft found two distinct early-retirement
windows and one structured-cancellation delay:

1. A second STOP-side `Future.cancel(false)` may lose to an already-winning
   cancellation on a different producer thread. That cancellation's terminal
   state CAS can precede a blocked physical cancellation hook or synchronous
   terminal subscriber. STOP now reserves a separate
   `pendingStopFutureNotifications` count while holding `lifecycleLock`,
   registers `whenNotificationsDrainedRuntime` on **every** owned Future in the
   snapshot, and joins those receipts before arena/actor DONE. The receipt
   registration precedes STOP's revocation loop. A failed registration or
   receipt accounting underflow quarantines instead of manufacturing success.
2. An actor-owned Future previously left `pendingOperations` when its own
   first ordinary waiter ran, **before** other terminal waiters completed. It
   now leaves only on the Future's host notification-drain receipt. This keeps
   cleanup-owned Future counts charged across blocked terminal subscribers and
   lets STOP snapshot Futures that are already terminal but not physically
   notification-quiescent.
3. Internal `OresFuture` cancellation hooks remain nonthrowing to consumers,
   but a thrown `RuntimeException`/`Error` is now saved as a runtime-only
   `cancellationHookFailureFromRuntime` receipt. Actor ownership and STOP
   receipt paths mark `sourceStopCleanupAborted` on such a failure. A
   cancelled Future is **not** proof that its physical revoker succeeded.
4. Parent STOP requests structured child cancellation **before** parent-owned
   revokers and SourceTask cancellation callbacks may block. Each child is
   attempted even if an earlier child throws. Failed child cancellation
   quarantines the parent; parent DONE still joins child finalization.

Focused tests added/extended:

- A foreign thread wins cancellation and stalls its physical revoker while
  actor STOP attempts another cancellation: DONE stays pending until drain.
- A later synchronous terminal subscriber blocks after the actor's own
  observer: the Future remains actor-owned until all ordinary waiters drain.
- Child STOP progresses ahead of one blocked parent revoker.
- A revocation hook throws: the Future stays cancelled, independent listeners
  are notified, and the physical failure remains inspectable by the runtime.
- Repeated STOP and a late producer during two successive asynchronous
  cleanup awaits cannot replay the Task, resurrect ordinary mail, or retire
  the actor early.

These are staged regression tests, **not passing JUnit evidence**. The focused
JDK25 workflow now selects `OresFutureCallbackTest` as well as the actor and
scheduler suites; exact-head runner execution is required.

**Not solved by these changes:** one child cancellation can itself block on a
synchronous child revoker, delaying cancellation requests to later siblings.
Likewise, notification-drain receipts alone do not prove asynchronous native
I/O, FFI, DMA or kernel handle closure. Full supervisor loan joins, independent
nonblocking CONTROL cancellation fan-out, bounded stalled-cleanup diagnostics,
and Java/LLVM source-level conformance remain mandatory release gates.

## Drain observer STOP ordering and physical notification receipts

Further source review identified an ordering hazard in `ActorCell.terminateTree`:
although child STOP requests and parent-owned resource cancellation had been
prioritized, the implementation still failed each pending public `drain()`
observer **before** those operations. `OresFuture.failFromRuntime` runs
subscribers synchronously, and a blocked host subscriber could therefore stop
child cancellation and source lexical cleanup from even being requested.

This draft now:

1. Detaches public drain observers and reserves one
   `pendingStopDrainNotifications` receipt per observer under `lifecycleLock`.
2. Seals the mailboxes, requests STOP for all children, registers and joins
   existing owned-Future drains, and requests SourceTask cancellation before
   delivering public drain failure.
3. Registers `whenNotificationsDrainedRuntime` on each pending drain Future
   before terminal settlement; every receipt must drain before `finalizeStopLocked`
   can reclaim the arena or publish DONE.
4. Marks an observer registration failure, underflow, or terminal-publication
   failure as incomplete cleanup; no false success or arena reclamation.
5. Adds deterministic JUnit regression
   `blockedDrainSubscriberCannotDelayChildCancellationOrGuestStop` in
   `ActorStopCleanupAdmissionTest`. It holds an in-flight mailbox message,
   blocks a drain subscriber after STOP, checks the child can terminate,
   then releases the guest turn **while leaving the subscriber blocked**
   and confirms parent DONE remains pending.

**Limitations**: this reordering does not make host callbacks nonblocking.
One hostile subscriber can still delay *later* drain-observer notifications and
the original caller's return. Stronger independent CONTROL fan-out is required
for bounded STOP progress, along with native I/O/FFI loan receipts. All tests
remain unexecuted on the exact source head until authorized runners are
available; successful source-free canaries in unrelated organizations do not
authorize a private-source mirror.

## Fail-closed sibling admission pre-fence

Parent STOP formerly invoked each direct child's full `cancel()` in a serial
loop. A synchronous cancellation hook on child A can block before child B's
STOP begins. During that window B previously continued admitting new user mail
(and could spawn new grandchildren from an already-running guest turn).

The parent now snapshots children while sealing itself, then under **each
child's independent lifecycle lock** sets `child.sealing = true` for all
non-finalized direct children **before** invoking any child's full cancellation.
This does not hold the parent lock while taking a child lock and does not call
Future listeners under those locks. The ordinary send admission checks the
sealing bit both before and after message preparation. Structured child spawn
now also rejects `parent.sealing`, preventing post-fence grandchildren.
An existing graceful-stop seal also denies new child spawns.

Deterministic regressions in `ActorStopCleanupAdmissionTest`:

- `siblingMailboxesAreSealedBeforeAnyChildRevokerCanBlock` arms two
  independently revocable child operations, blocks the first STOP revoker,
  then requires both sibling mailboxes to reject new messages. No
  post-fence message may reach either child.
- `inFlightChildCannotSpawnGrandchildAfterParentStopPreFence` holds an
  executing child turn and a different child's STOP revoker, then resumes
  the first child's attempted spawn. Spawn must fail with cancellation and
  no new grandchild may be created.

This is an **admission safety fence**, NOT synchronous or parallel physical
STOP completion. Already-running child turns and previously admitted work
remain subject to normal cancellation checkpoints. A blocked revoker can
still delay the next sibling's full teardown. Proper nonblocking CONTROL
cancellation fan-out needs a separately reviewed design and physical receipt
accounting; it should not put blocking hooks on the zero-thread CONTROL
coordinator. No extra threads or worker pools were added by this patch.

These tests are staged but cannot be called passing without an executed
JDK25 exact-head workflow. The protected source-repo private carrier allowlist
remains empty; private source has not been exported to any candidate test org.

## Two-phase sibling STOP with VM-owned physical workers

The previous sibling pre-fence now has an **opt-in asynchronous physical STOP
dispatch stage**, wired into real Oreslang execution through `OresContext` and
`OresVM`:

1. Parent STOP first marks itself stopped, captures its actor/scheduler/Future
   snapshots, and atomically reserves `pendingChildStopDispatches` for every
   direct child. All immediate children are admission-sealed before the first
   full child cancellation call.
2. If `ActorRuntime.PhysicalStopDispatcher` is injected, every child receives
   an independent physical teardown submission. The host dispatcher must be
   bounded, must reject overflow, and must **not** execute teardown inline or
   on a CONTROL carrier. The runtime rejects inline execution on the caller
   thread as a fail-closed implementation check.
3. The child stop task runs `child.cancel` on an independent physical worker.
   Its exactly-once `completeChildStopDispatch` receipt decrements the parent
   reservation after synchronous cancellation hooks return. The parent also
   awaits child `finalized`, source Task cleanup, owned-Future notification
   drains, and drain-observer receipts before DONE or arena release.
4. Each rejected submission, thrown child revoker, or duplicate/missing
   dispatch receipt marks incomplete cleanup/quarantine. One bad child cannot
   suppress submissions of later children.
5. Standalone `ActorRuntime` instances with no injected physical dispatcher
   retain the legacy synchronous stop fallback and still pre-seal all children.
6. Production `OresContext` injects `OresVM.executePhysicalStop`, whose
   **single VM-wide bounded virtual-worker ThreadPoolExecutor** is separate
   from the prestarted CONTROL scheduler. Its parallelism and queue size are
   configurable via `ores.runtime.physical-stop.parallelism` (1–128) and
   `ores.runtime.physical-stop.queue-capacity` (1–65536), with bounded
   defaults. No executor is allocated per actor.

Focused proof targets now include `OresVMTest` (a blocked physical STOP
worker must not starve a root CONTROL turn), an actor test requiring both
sibling revokers to enter before either finishes, and
`FormalActorStopFanoutModelCheckTest`, a two-child exhaustive state model
covering accepted/rejected dispatches, callback failures, and finalization
gates. An independent model enumerator visited 34 states and upheld the
same safety conditions; that is **model-level evidence, not JUnit execution**.

Open safety gaps: the physical STOP executor can saturate if many native
revokers hang; queue overflow must quarantine, not silently skip a child.
Cancellation of deeper descendants is still recursively initiated by each
worker, so a finite worker pool is not a proof of arbitrary-depth liveness.
I/O/FFI/native completion is not proven by a revoker returning unless the
device/loan integration emits a separate trusted physical receipt. Add
dedicated cancellation-generation fencing and adversarial stress before
releasing actors from quarantine. Source-level Java/LLVM cancellation parity,
exact-head JDK25/GraalVM execution, and approved private CI mirroring remain
blocking merge gates.

## Full-tree admission sealing before physical workers

Phase-one admission sealing now walks the *entire reachable descendant tree*,
not only the parent's immediate children. The implementation uses an iterative
identity-set walk. It snapshots each actor's children while holding only that
child's own lifecycle lock, sets `sealing`, and releases the lock before
visiting descendants. A racing spawn either linearizes before the snapshot and
becomes reachable or observes its sealed parent and is rejected.

The new `allGrandchildAdmissionsAreSealedBeforeAnyPhysicalStopDispatchRuns`
regression holds every physical STOP worker behind a test gate while a child
and grandchild are alive. Before releasing any worker, the grandchild's
mailbox must reject newly sent guest messages, and parent DONE must remain
blocked on outstanding physical STOP receipts. The test then releases both
physical workers and the test resource revoker and joins the complete tree.
The source and protocol changes remain subject to exact-head JVM/native
execution and formal verification.

This is a **safety** fence; it does not guarantee progress if a host teardown
worker hangs or cannot obtain capacity. Physical loan receipts and bounded
quarantine remain separate integration requirements.

## Physical STOP dispatch shutdownNow accounting (2026-10-23)

`OresVM` now wraps every physical STOP submission in an atomic
`PhysicalStopJob` state machine:

- `ADMITTED → RUNNING → RETIRED`: a worker won execution and the actor
  callback runs only on that worker. VM shutdown can request interruption but
  cannot claim the running revoker physically retired.
- `ADMITTED → CANCELLED_BEFORE_START`: `shutdownNow()` returned a job that
  never started. Exactly one failed `neverStarted` callback is delivered to
  the owning `ActorRuntime`; no STOP guest revoker executes.
- Rejected admission throws synchronously. ActorRuntime quarantines its
  parent tree. A buggy dispatcher that sends null or duplicate failed
  callbacks is also quarantined, never treated as cleanup success.

`PhysicalStopDispatcher` requires both an admitted `Runnable` and a
`Consumer<Throwable>` for never-started jobs. `OresVM.executePhysicalStop`
uses the wrapper to arbitrate worker entry against shutdown. The VM reports
each discarded queued task even when physical worker shutdown interrupts
other tasks, and continues closing CONTROL if a discard callback fails.

A successful callback requires the worker's original `child.cancel()` to
return from its synchronous host revocation path; child finalization and
native/FFI physical-loan retirement remain **independent** actor-done gates.
A never-started task is **not** counted as successfully retired: its failed
receipt sets `sourceStopCleanupAborted`, and the parent arena remains
quarantined even if the receipt counter itself reaches zero.

The focused JUnit matrix now includes:

- `OresVMTest.shutdownNowReportsQueuedPhysicalStopNeverStartedExactlyOnce`
- `OresVMTest.saturatedPhysicalStopQueueRejectsNewSubmissionWithoutFabricatingReceipt`
- `OresVMTest.concurrentWorkerStartAndShutdownClaimOneExclusiveReceipt`
- `ActorStopCleanupAdmissionTest.vmShutdownDiscardsQueuedSiblingStopAndQuarantinesParent`
- `ActorStopCleanupAdmissionTest.missingOrDuplicatedPhysicalStopDiscardReceiptCannotAuthorizeReclamation`
- `FormalPhysicalStopJobModelCheckTest.shutdownAndWorkerStartupNeverForgeSuccessfulPhysicalRetirement`

The model uses **three** jobs with one running worker and one queue slot,
including possible worker-start lag. An independent enumerator checked
150 states covering queue saturation, cancellation-before-start and running
during shutdown; no early-finalization violation appeared in that
bounded abstraction. This is not actual JDK25/JUnit/native or LLVM execution.

**Still open:** native I/O / FFI loan completion must supply an independent
physical lease receipt, since a synchronous revocation hook returning does
not prove an asynchronous kernel/native operation has fully exited. A
long-running native callback may outlive VM `close()` and must not be treated
as released by interruption or timeout. Formal modeling should extend to
actor generations, native loans and supervisor restart. Real CI is blocked by
the private-source mirror authorization gate.

## AsyncNativeIo context-wide physical loan barrier (2026-10-23)

The former `AsyncNativeIo.close()` used `shutdownNow()`, immediately
cleared its pending Futures/resources, failed all Futures and closed registered
sockets even while a virtual host I/O worker was still executing. The
replacement has bounded data and independently reserved close/control workers
and tracks each admitted job through:

- `ADMITTED → RUNNING → RETIRING → RETIRED`. The worker is the sole
  publisher of the operation Future and must complete physical `work.call()`
  *and the terminal Future callback drain* before releasing the context's
  physical-operation reservation. A terminal Future alone is not a receipt.
- `ADMITTED → CANCELLED_BEFORE_START`. A queued entry returned by the
  executor's `shutdownNow()` never executes `work.call()`; a bounded-count
  independent host notifier fails its Future. The context retains its receipt
  until **all** terminal subscribers return. This avoids executing arbitrary
  host callbacks synchronously on the shutdown/control caller.
- Admission rejection reserves no operation, returns a failed Future and
  never runs rejected work. Actor/supervisor callers cannot cancel a
  filesystem mutation as if rollback had been guaranteed.

On shutdown, the context seals data/control admissions, requests interruption,
reports every discarded queued job, waits for both executors' actual worker
termination, and joins outstanding never-started notification receipts within
a finite deadline. Only then may it close registered resources. On deadline,
notification exception, or resource-close failure it throws an incomplete
cleanup error and keeps strong ownership in `QUARANTINED_IO`. Another explicit
`close()` can join and release once the physical receipts exist.

An `unregister(resource)` racing active operations retains the resource
strongly until context loans have quiesced. A resource registered by an
already-running worker *after* shutdown admission is sealed is also retained
for the post-join close sweep, not prematurely closed on the worker stack.

The enclosing `OresContext.close()` now independently joins `nativeIo`
and actor runtime shutdown before tearing down the VM, mutable actor heaps,
shared code images, HTTP/async services and runtime collector. An incomplete
physical lifetime quarantines the context in `QUARANTINED_CONTEXTS` for a
later explicit retry instead of clearing those shared heaps and code images.

Added `AsyncNativeIoTest` coverage for discarded queued work, a native
worker deliberately ignoring interruption, terminal callbacks blocking
physical retirement, a never-started notification subscriber blocking,
and self-close deadlock prevention. `FormalNativeIoLoanModelCheckTest`
models two physical loans and their queued callback branches. A separate
bounded enumeration explored **83 states** without allowing resource
reclamation ahead of physical worker plus notification receipts. **These
regressions still need actual exact-head Java/GraalVM execution.**

The new receipt is conservatively **context-wide**, not per-socket or
per-actor-incarnation. Per-resource reference accounting, native callback
exit receipts beyond Java worker return, generation/fencing proofs for
actor restarts, more exhaustive exhaustion testing, and LLVM parity are
still required before this can certify arbitrary FFI or native memory safety.

## Native I/O post-join resource-close callback audit

The physical worker join and terminal Future drain are **necessary but not
sufficient** to prove a native I/O context's resource sweep completed.
Registered `AutoCloseable.close()` callbacks are arbitrary host operations,
and a Java `synchronized` monitor does not reject recursive calls on the
same thread.

The latest draft therefore adds three fail-closed resource ownership rules:

1. **Reentrance fence:** while `AsyncNativeIo.close()` invokes registered
   resources' `close()`, a recursive call to close the same context is
   rejected, before modifying ownership. If the resource callback propagates
   the error, the resource remains in the set and in `QUARANTINED_IO` for an
   explicit retry; a callback that safely catches the rejection may finish
   normally. This prevents recursive repeated closes or stack exhaustion.
2. **No incomplete-sweep success:** a registered resource can spawn or expose
   a second native handle during its own close callback, and catch the
   expected `register()` rejection after shutdown was sealed. The newly
   retained handle was not in the sweep's initial snapshot. The post-sweep
   `resources` set must be **empty** before publishing `resourcesRetired`
   or removing static quarantine; otherwise close throws and retains the
   context for a subsequent sweep.
3. **No ownership after final retirement:** once `resourcesRetired` is true,
   `register()` rejects new handles *without adding them* to the retired
   context. The rejected handle remains caller-owned; its creator must close
   it. During a *nonretired* closing context, the prior rule still retains a
   late worker-created handle until the physical join and resource sweep.

New deterministic `AsyncNativeIoTest` cases cover caught/uncaught resource
close recursion, late resource registration during a close callback, the
explicit retry sweep, and rejected post-retirement handoff. These are
committed source tests, **not yet executed JUnit proof**.

The protected GitHub private-carrier allowlist currently contains no approved
destination repository IDs. External capacity-only canaries do not authorize
copying private source; testing still needs source-org runner admission or
independent approval of a pinned external carrier.

## Resource-generation and actor-incarnation I/O loan gating (2026-10-23)

A separate per-resource loan ledger has been added on top of the existing
context-wide fail-closed shutdown barrier:

- `AsyncNativeIo.register(resource)` mints a monotonic **resource generation**.
  Registered handles are keyed by JVM object identity (not overridable
  `equals`/`hashCode`). Re-registering a handle while its old-generation
  loans are active is rejected. `unregister` retains a loaned handle until
  its final receipt, rather than removing its ownership prematurely.
- `AsyncNativeIo.borrow(resource)` reserves an `IoLoan` identified by
  resource generation, unique loan generation and the **admitting actor's
  ActorId** (or null for host/root work). Duplicate or stale receipts fail
  closed; a released loan cannot affect a later resource generation.
- All accepted I/O operations now also reserve an actor-incarnation count
  *at admission*, even before a queued worker starts. This prevents an actor
  from publishing DONE while its native work is still queued. Accepted
  operations release ownership after work completion and terminal Future
  notification delivery; never-started operations release only after their
  failure subscribers drain. Direct native resource borrows reserve the same
  actor-incarnation counter.
- `OresContext` installs a lock-free actor loan count gate into
  `ActorRuntime.finalizeStopLocked()`, **before** `memorySlice.close()`.
  The final per-actor I/O receipt notifies the stopped ActorCell to retry
  physical finalization. The predicate also requires the context's recorded
  physical receipt failure to be absent, not merely an empty numeric count.
  It is read without taking the I/O admission monitor while ActorCell holds
  its lifecycle lock, avoiding callback-induced lock-order inversion.
  This is not the best-effort `actorExitHook`, which runs after memory
  reclamation and therefore cannot implement this safety invariant.
- `NativeIo.connect`, `TcpConnection.readLine`, and
  `TcpConnection.writeText` acquire/release registered socket loans
  around their actual host operations. `requestShutdown()` first seals new
  admission, interrupts queued/running workers and closes registered Java
  sockets as a **wakeup/revocation action only**. `close()` separately
  joins workers, callback drains, and resource-loan counters before
  releasing bookkeeping and context-owned handles. Generic
  `AutoCloseable` resources are not forcibly closed before loans retire.

Added `AsyncNativeIoTest` coverage: identity-stamped unique receipts,
double release, re-register/ABA rejection, two actor-incarnations sharing a
resource while stopping independently, admitted but unfinished actor work
blocking DONE, and transport wakeup for a blocked real TCP read.
`FormalNativeResourceGenerationModelCheckTest` explores old actor loans,
generation replacement and stale replay. An independent abstract enumerator
explored **208 states** without allowing a completed actor to retain an
active modeled loan. These are *model/static* checks only, not compiled JUnit
evidence.

Scope limits: A Java `Socket.close()` or virtual-worker exit does not prove
a separate JNI library, kernel callback, io_uring, GPU, or FFI operation has
retired. Those require externally attested per-operation physical receipts,
including real resource/actor-generation fencing at the native boundary.
The actor gate covers I/O operations admitted from Oreslang actor execution
and direct resource borrows; arbitrary unrelated host code with captured
guest/native pointers remains outside the authority of this ledger. Real
JDK25 and LLVM source-level conformance, stress testing and independent
private CI carrier approval are required before merge.

## Context close: two-phase admission seal, I/O revoke, physical joins (2026-10-23)

`OresContext.joinActorsAndNativeIo(actors, nativeIo)` is now the actual
production orchestration boundary:

1. `ActorRuntime.sealShutdownAdmission()` seals runtime creation/send
   admission and pre-seals all existing actor cells **without executing
   guest cancellation callbacks**.
2. `AsyncNativeIo.requestShutdown()` seals native I/O data/control
   admission, fails never-started queued work, requests worker interruption,
   and closes registered Java sockets **to wake blocking reads only**.
3. `ActorRuntime.close()` requests structured actor STOP and joins actor
   execution/physical loan barriers and dispatcher exit. A separate
   `dispatchersShutdown` CAS means earlier phase-one sealing does not
   prevent the first physical dispatcher stop on this call.
4. `AsyncNativeIo.close()` joins actual native workers, pending terminal
   notification drains, and outstanding registered resource loans before
   the resource-close sweep.
5. The context releases shared code images, VM, collector and runtime state
   **only after both joins succeed**; errors preserve strongly reachable
   quarantined ownership for explicit retry. Both joins are attempted even
   if the other returns an incomplete-physical-retirement error.

`ActorRuntime.close()` no longer clears ActorGroup/mailbox/shared cell
registries or zeros shared-memory bookkeeping **before** verifying all actor
cells are finalized and all carriers physically terminated. This prevents
failed close/retry from erasing ownership of an unretired actor.

`AsyncNativeIoTest` now exercises the **same package-visible production
sequence**: actor mail must already be sealed when a registered socket's
wakeup callback executes; a worker ignoring interruption must keep resources
quarantined and permit a later successful explicit retry. A separate test
retains a stopped actor across an unretired synthetic native gate, then
publishes DONE on the last receipt and retries close.

`FormalContextShutdownOrderModelCheckTest` explores both physical join
orders, failed close/retry, and the no-reclaim-before-both-joins invariant.
The independently executed abstract enumerator explored **20 states**;
this is only a state-space model, not Java/GraalVM/LLVM execution.

Remaining risk: `ActorCell.stop()` may itself block in a misbehaving native
revocation hook on the closing host thread. Bounded physical cancellation
dispatch of **root** actors (not only descendants) and explicit terminal
receipt joins are still needed for guaranteed host-close liveness. IO worker
exit cannot certify external JNI/io_uring/FFI callbacks; those require
independent native generation-fenced physical completion acknowledgments.
The existing source CI remains blocked before runner assignment; no
compilation or JUnit passing claim is made.
