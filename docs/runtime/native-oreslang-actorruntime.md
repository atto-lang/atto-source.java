# Native Oreslang actor runtime migration

Status: **implementation in progress — NOT yet the live actor runtime**.

## 2026-10-14 continued audit: native-retirement does not mean callback completion

**Finding A — zero retry count while cleanup still runs.**
The experimental scheduler's `retryNativeQuiescentTasks()`
previously skipped an already-native-retired SourceTask, even when
its trusted `onQuiescent` callback had not yet completed. A return
value of zero could therefore be misread by the VM owner as a
fully quiescent actor. The retry method now checks both the native
retirement and the Java trusted-callback acknowledgement; if the
native task is retired but its callback is still in flight, it
reports an unresolved busy/failure state rather than reporting idle.

A deterministic opt-in JUnit test blocks `onQuiescent` on a latch,
confirms a concurrent retry rejects the incomplete callback, and
asserts normal DONE remains unpublished. It also checks that
the retry never reenters the callback and that a later completed
callback leads to exactly one successful terminal result.

**Finding B — shutdown starvation on a Java callback exception.**
Previous native-witness shutdown caught `NativeStatusFailure` but a
trusted quiescence callback could throw an ordinary
`IllegalStateException` or `Error`. Such an error could abort the
revocation sweep before another admitted task was canceled. With
native witness enabled, shutdown now aggregates these exceptions,
continues revoking and notifying all admitted tasks, shuts down
the executor, and rethrows the original error with any suppressed
failures. Ordinary non-native `OresScheduler` behavior remains
unchanged. An opt-in regression creates two *physically parked*
SourceTasks, makes one trusted callback throw during shutdown, and
checks that the other source is still canceled. Tests wait for the
actual TaskRunner `WAITING` phase and `executing=false`, not merely
for a latch reached while guest code is still on the carrier.

**Finding C — JNI workflow JDK mismatch.**
The native Actions job selected the JDK 21 toolchain in one run step,
then invoked Maven in another run step, where step-local
`JAVA_HOME`/`PATH` were not preserved. The workflow now publishes
the selected JVM via `GITHUB_ENV` and `GITHUB_PATH`, prints both
`java -version` and `javac -version` before the smoke and Maven
steps, and gives the JNI/Maven verification job a 20-minute limit.

**Evidence:** 11/11 exact-file source-structure checks passed on the
new OresScheduler, JUnit and workflow blobs. These checks prove the
expected guard/test/workflow edits are committed, **not** runtime
correctness or Java/JNI execution. The new JUnit regressions still
require an authorized Maven/JDK21+ runner. The source organization
continues failing its Actions jobs before any job steps; the
protected-branch cross-organization private-source allowlist
is still empty. Keep production native actor enforcement OFF and
PR #917 DRAFT until the JNI, compiled Oreslang guest STOP/cleanup/DONE,
GraalVM Native Image, and LLVM parity tests execute successfully.



## Additional 2026-10-14 JNI/JVM hardening: strict compile and non-replayable cleanup failure

**Confirmed Java build blocker fixed.** The new
`NativeActorTurnKernel.NativeStatusFailure` extends the serializable
`IllegalStateException`, but originally had no `serialVersionUID`.
The standalone JNI build uses `javac -Xlint:all -Werror`, which rejects
that omission. Independently reproduced the warning-as-error behavior
with OpenJDK 21 and added an explicit `serialVersionUID = 1L` to the
committed exception class. This reproducer confirms the compiler rule;
it is NOT a complete recompilation of all JNI/guest source on the
latest head.

**Non-idempotent trusted callback failure now reported.** Previously,
if `TaskRunner.onQuiescent` threw after successful native SourceTask
retirement, `nativeRetirementConfirmed` remained true but
`nativeQuiescenceCallbackFinished` was false. The task deliberately
remained live and normal terminal publication stayed blocked, but the
trusted `retryNativeQuiescentTasks()` method silently skipped it
as already retired. A subsequent scheduler close could also succeed
without surfacing this unresolved failure.

The optional scheduler now:

- Records the original trusted quiescence callback exception, counts
  failures via `nativeQuiescenceFailures()`, and leaves the failed
  native-backed task in the scheduler's live set.
- Reports that original cause through a descriptive error from the
  trusted `retryNativeQuiescentTasks()` path. It does NOT invoke
  `onQuiescent` a second time: callbacks may have already released
  some actor-owned resources before throwing.
- After revoking and notifying all admitted tasks, `close()` reports
  outstanding trusted callback failures rather than silently claiming
  shutdown success.
- Keeps normal terminal Future publication blocked after failed
  cleanup. Shutdown Future cancellation remains a separate immediate
  notification and MUST NOT be confused with actor DONE or native
  SourceTask physical quiescence.

An opt-in JUnit regression verifies a successful guest step followed
by a throwing trusted quiescence callback: the normal terminal Future
is not published; retry and close report failure; callback invocation
count remains exactly one; and the outstanding task stays visible.
The JNI smoke still requires the same explicit experimental flag.

**Verification boundary:** new JUnit tests are COMMITTED, **NOT YET
EXECUTED** on this exact head. The source GitHub Actions budget/runner
blocker has not been resolved; no cross-org private-source transfer is
authorized. The production ActorRuntime/native STOP and cleanup-only
continuation bridge, compiled Oreslang guest scenarios, JDK25/GraalVM
AOT/hybrid, and LLVM parity remain unverified and disabled.



## Terminal-publication fail-closed audit — 2026-10-14 (later hardening)

The *optional* JNI-enabled `OresScheduler.TaskRunner` had a concrete
one-shot barrier error: `quiescenceNotified` was set **before** the
native SourceTask settlement/retirement calls had succeeded. If an
unclosed cleanup ticket, an active physical carrier, or a native
capability error made retirement fail, the scheduler could subsequently
publish a normal terminal Future despite the native task still being
live; the consumed quiescence latch also prevented any retry.

**Hardening committed:**

1. The JNI owner now provides `settleAndRetire(source)`, preserving
   the one-shot successful settlement witness across a retirement retry.
   It does **not** call `note_source_settled` twice when settlement
   already succeeded.
2. `TaskRunner` keeps native settlement/retirement and completion of
   the trusted `onQuiescent` callback as distinct acknowledgement
   barriers. **Normal terminal success/failure publication is blocked**
   until both have completed. Failed native retirement resets its
   one-shot quiescence claim and retains the task in the scheduler live
   set; it does not claim native DONE or run guest code again.
3. The trusted, package-private `retryNativeQuiescentTasks()` may be
   invoked **only after** a blocking native cleanup-ticket barrier is
   resolved. It retries quiescent terminal records, never restarts
   `Task.resume`, and reports any remaining native failures. An
   audit counter `nativeRetirementFailures()` is exposed only to
   trusted code/tests for diagnosing this fail-closed condition.
4. During scheduler close, a native status failure from one task cannot
   skip revocation/cancel-notification of other admitted tasks. Native
   failures are accumulated and surfaced **after** shutdown, not
   swallowed. Other non-native Java failures retain their original
   behavior.
5. The JNI adapter distinguishes a native-status failure from arbitrary
   guest Java exceptions. Java-native capability failures still throw
   and do **not** install fallback guest admission or change the
   production scheduler, which has its native witness disabled.

Added opt-in regressions check pending cleanup preventing Future
publication, later ticket cancellation plus idempotent retirement,
other SourceTasks being canceled despite a failed native witness, and
a blocked `onQuiescent` callback preventing normal terminal result
publication. Native-backed tasks now remain in the scheduler's **live
set** until that trusted callback completes and the deadline barrier
has been released; the earlier non-native task order is unchanged.
The standalone JNI smoke also exercises direct ticket-closure
settlement retry. The opt-in native actor witness rejects
`parallelism != 1`: multiple logical SourceTasks are allowed, but
one native actor can have only one physical execution lane.

**Verification boundary:** exact GitHub source blobs passed 13/13
source-structure/invariant checks during this audit. **These are
static guards, not JVM execution.** The new JNI/JUnit cases and
full Maven/GraalVM/LLVM compatibility on this revised source remain
unexecuted because source-organization Actions has not run its job
steps and the local runtime lacks the full Maven project toolchain.
Previously reported complete native C and Java21 standalone smoke
results apply to their then-pinned source hashes; do not extrapolate
them to this newer Java revision.

Native `ores_turn_force_revoke` remains a revocation checkpoint, not
instantaneous preemption of an already-running guest carrier or an
atomic authorization barrier for subsequent effects. The production
actor cleanup/STOP/DONE integration remains **disabled and unproven**.



## Experimental JVM-to-native JNI boundary — 2026-10-14

The source branch now contains a real, *opt-in* JNI bridge to the existing
native actor-turn capability kernel:

- `src/main/java/dev/oreslang/runtime/NativeActorTurnKernel.java` is a
  package-private trusted-JVM owner. It opens a per-actor native VM, mints
  scheduler-owned SourceTask handles, claims and releases native physical
  turns, observes STOP, and allows explicit one-shot terminal/quiescent
  settlement and retirement. The opt-in property
  `-Dores.native.actor.turn.experimental=true` is required before it
  loads `liboresactorturnjni.so`; production `ActorRuntime` and
  `OresScheduler` do **not** load or activate it by default.
- `src/main/c/oresactorturnjni.c` binds these methods to the already
  verified native C kernel. JNI pointers remain private to trusted Java.
  Native lease entry on Java virtual threads is rejected: JNI uses OS-carrier
  TLS identity, and virtual threads may migrate between enter and exit.
  Each platform-thread physical claim must be released from its original
  Java/native carrier.
- Separate `CleanupTicket` and `CleanupTurn` types bind the cleanup-only
  claim, terminal-injection acknowledgement, cleanup-effect check,
  completion, cancellation, and release. Ordinary `SourceTask` leases
  have no Java API to impersonate a claimed terminal cleanup lease.
  Cleanup-effects checking remains a **point-in-time query**; it is NOT
  an atomic authorization for future guest effects after FORCE revocation.
- `scripts/test-native-actor-jni.sh` builds the real JNI shared library and
  compiles/runs Java 21 standalone smoke without Maven dependencies. The
  native workflow now triggers on these files and has a separate JNI job.
  The script currently supports Linux/JDK 21+.
- `NativeActorTurnSchedulerJniTest` is an additional **opt-in** JUnit
  experiment exercising genuine `OresScheduler.TaskRunner` native
  admission, physical-carrier exit and terminal/quiescence callbacks,
  testing await/resume and STOP. It deliberately does not activate any
  production actor runtime and has not yet been run under Maven because
  the local environment lacks the full project dependency/runtime toolchain.

**Local exact-source proof:** Java/JNI source blobs
`d70f1044721af8015148b6b6151263826b7aab05`,
`9e7abc37ba89b1b0702a0ed19878621d82bd77c8`,
`b467c913a2e768656e8b97a3b7688b88cc40668c`
and runner `2e0387754574965e0eca655b038e8ca15d0dba84`
match the committed Git tree. On Linux/OpenJDK 21, the smoke compiled with
`javac -Xlint:all -Werror` and GCC 14 / Clang 17
`-std=c11 -Wall -Wextra -Werror -pedantic -O2`. Each compiler variant
passed **13/13 Java-to-native program executions** (26 total). The
executable assertions include native VM allocation, source admission,
two-source single-flight, wrong-carrier refusal, suspended-task resumption
on another platform thread, STOP, cleanup-ticket admission, injection
before completion, post-completion effect revocation, and retirement.
These are Java/JNI smoke tests, **not compiled Oreslang guest executions**.

**Production activation gate remains CLOSED.** After the standalone JNI
smoke, the scheduler gained an opt-in `managedWithNativeTurnWitness(...)`
constructor. Its `TaskRunner` registers each native SourceTask under the
scheduler admission lock; claims native authority before entering guest
continuations; releases only from `afterCarrierTurn`, AFTER the
`turnExecutor` and interop scope have unwound; and settles/retires only
from the scheduler's TERMINAL + physical-quiescence path. No production
`ActorRuntime` constructor activates this witness, and no guest can
enable it by sending a message. The opt-in JUnit test now drives
this **automatic** TaskRunner lifecycle, not a manually wrapped
`Task.resume` lambda. This code has not yet passed project Maven/JDK25
compilation and the full guest conformance suite: its hook remains
experimental even though the JNI standalone C/Java smoke is executable.

Native actor STOP/cleanup tickets, real cancellation delivery, output
closure, actor DONE barriers, authorization of guest side effects, and
Java/LLVM source parity are **not** wired to the actual ActorCell.
A directly enabled native gate without the future trusted cleanup path
would reject legitimate post-STOP cleanup continuations. Do not activate
it for production actors until those paths are implemented and validated.
The complete Java/GraalVM JDK25, compiled `.ores` actor, Native Image
and LLVM-parity gates remain open.



## Latest verified integration snapshot — 2026-10-14

**Native C regression gate: PASS (local exact Git blobs).**

After the source-branch/mainline reconciliation, the full original
**16-function** `oresactorturn_test.c` regression suite AND the separate
scheduler-boundary fixture were recovered from this PR and executed locally.
The four input files were independently checked against GitHub blob SHA
using `git hash-object`:

| Input file | Exact Git blob SHA |
| --- | --- |
| `src/main/c/oresactorturn.c` | `0c461c22e5466242430a3c38731b0f249b4abdf8` |
| `src/main/c/oresactorturn.h` | `0c28c259ae31590158aee4066d417867d5b9666e` |
| `src/test/c/oresactorturn_test.c` | `cb38654e454b216943995f9d12af70b4c5990787` |
| `src/test/c/oresactorturn_scheduler_boundary_test.c` | `cc494b21a8f15f83007fb4227c645832830ec85b` |

**18/18 full-suite executions passed**: both suites compiled and ran under
GCC/Clang with strict warnings at O0 and O2, without sanitizers and with
AddressSanitizer + UndefinedBehaviorSanitizer (**16/16**), plus each suite
with GCC ThreadSanitizer at O1 (**2/2**). All executions returned the expected
`ORES_NATIVE_ACTOR_TURN_CAPABILITY_OK` or
`ORES_NATIVE_SCHEDULER_BOUNDARY_CONTRACT_OK` marker. Each of the nine
boundary fixture runs includes 1,000 concurrent FORCE/cleanup races;
ten additional back-to-back GCC O2 runs of both suites also passed.
GCC `-fanalyzer` and Clang static analyzer passed on the kernel and both
test units under strict warnings. Local OS/compiler evidence is **not**
a full-tree build or proof of native-image/LLVM or Java guest execution.

**Exact mainline merge**: Main commit
`7020608e9613f2ea0e8ec670766b442ac8670375`
(42 newer commits) was incorporated into the PR through two-parent commit
`18fc1b95da2673cd26415ede8240b8645d03ed22`.
All 13 mainline-changed and 11 native-branch-changed file paths were
disjoint. Independently overlaying the two sides' exact blobs onto each
parent tree produced identical merged tree SHA
`306cc016507378801fb61ff50530732e4bbfd6c9`.
This preserved all newer compiler and ownership changes; no wholesale
Java file replacement and no cross-repository private source transfer.

**Native CI readiness**: `.github/workflows/native-actor-turn.yml` was
updated to trigger on **both** native test files, record Git commit/tree
and blob identities, and run a GCC/Clang × plain/ASan+UBSan matrix plus
GCC ThreadSanitizer. This workflow is **not yet green in source Actions**:
runs have been failing before steps due to runner availability/capacity,
not due to observed test failures. Source-org CI, GraalVM Native Image,
real `OresScheduler.TaskRunner` → native SourceTask binding, compiled actor
STOP/defer/finally/DONE, and LLVM parity remain **unproven**; PR stays DRAFT.
The protected-branch cross-organization CI allowlist remains empty.

Older sections below preserve the progress of earlier audits. Earlier
statements that the original C suite was unexecuted describe those
historical audit snapshots; the local full-suite gate is now complete.


## Ownership decision

The **canonical implementation language** for Oreslang actor semantics is
Oreslang itself. The implementation entrypoint is
[`stdlib/actorruntime.ores`](../../stdlib/actorruntime.ores), not
`src/main/java/dev/oreslang/runtime/ActorRuntime.java`. That Java class is a
temporary bootstrap/reference adapter and must be retired when native Oreslang
can own the full lifecycle.

Do not merely rename Java source to `.ores`, use `java:` imports to call the
old implementation, or introduce a second independent actor scheduler.

The first checked-in source slice is **pure lifecycle policy**, executable as
Oreslang. It does not yet create actors, drive a mailbox, perform STOP cleanup
or implement the dispatcher. No production code should claim native actor
execution solely because these policy functions compile.

## End-state source layout

- `stdlib/actorruntime.ores`: authoritative actor creation, ActorId/incarnation,
  lifecycle, ownership, STOP/force, cleanup-only lane, supervisor CONTROL, DONE,
  state retirement and actor hierarchies
- `stdlib/scheduler.ores`: master scheduler, pool control loops, actor leases,
  runnable queues, fairness, backpressure, admission and park/resume
- `stdlib/future.ores` and `stdlib/channel.ores`: guest semantic ownership
  of Futures, cancellation, select, channel send/receive and stream lifetimes
- `stdlib/mailman.ores`, `stdlib/eventbus.ores`: first-class actor routing
  and event semantics, including pipeline `self.forward()` and
  `self.backward()`
- VM implementations: only sealed low-level intrinsics required to execute the
  language and interact with physical resources; Java/GraalVM and LLVM use
  the **same versioned Oreslang-visible ABI** and shared guest conformance

All source implementation modules above, except `actorruntime.ores`, are
**targets, not a statement that those files already exist**.

## Gate 0 implemented policy, not scheduler authority

The first executable functions are deliberately pure Oreslang. Their inputs are
scalar snapshots of VM-owned state, **not guest permissions**. They cannot
perform mailbox reservation, authenticate a continuation, obtain a physical
carrier lease or retire memory. None of these functions is a replacement for a
VM intrinsic or a security capability.

The initial policy is exercised in
[`examples/native-actorruntime-contract.ores`](../../examples/native-actorruntime-contract.ores)
and in a host-side guest-source execution regression. It covers:

- user-mail refusal after STOP, graceful sealing, force revocation, finalization
  or VM closure;
- bounded admission decisions for one lane plus the aggregate mailbox budget,
  rejecting negative/incoherent counters; **the actual atomic reservation
  remains VM-owned**;
- scheduler continuations preceding URGENT and NORMAL mail, urgent burst
  fairness after eight user messages, and suspension preserving only the
  internal continuation lane;
- authenticated pre-queued SourceTask retention and single-flight terminal
  delivery/cleanup decisions;
- state-retirement barriers including detached STOP releases and outstanding
  source task lifetimes, and ordered DONE publication.

**Lane 1 in the pure arbitration policy is the scheduler continuation lane,
not the supervisor CONTROL channel.** Supervisor CONTROL is a distinct
VM-reserved authenticated route and must never be writable from a guest
`actorruntime.ores` invocation.

### STOP arbitration hardening

Selecting a mailbox lane and admitting a physical actor turn are different
operations. The pure `choose_mailbox_lane` now accepts lifecycle state and
a purported retained-cleanup permit so its decision table can be tested:

- **LIVE:** authenticated scheduler continuations have precedence; after eight
  urgent user messages, an already-queued normal message gets an opportunity.
- **GRACEFULLY SEALED:** old user messages remain drainable; sealing is a
  sender-admission condition, *not* a physical STOP.
- **STOPPED:** no queued user message is selected. An internally scheduled
  cleanup continuation is selected only if it is present **and** its retained
  identity/cleanup permit has been attested by the VM.
- **FINALIZED / FORCE REVOKED / VM CLOSED:** no guest turn is selected, even
  when a purported cleanup continuation is present.
- **CORRUPT URGENT BURST COUNT:** a negative value yields an invalid decision
  rather than silently falling through to user execution.

The scalar `retained_cleanup_continuation = true` argument is **not** a
capability. Normal guest callers can supply it themselves; they gain no
privileged actor access. Gate 1 MUST replace these attestations with a sealed
generation-specific handle, validate it again at the atomic dispatch commit,
and reject stale actor/restart/queue identities. A policy result without
that VM check is advisory and never authorizes guest execution.

Admission likewise rejects impossible queue snapshots (negative counts,
lane count greater than aggregate count, or lane capacity greater than
aggregate capacity). This is **not** an atomic quota implementation: both
precheck and credit reservation must occur in one VM-owned synchronization
transaction. If the VM exposes a separate control lane with reserved capacity,
its accounting budget must explicitly include that reserved capacity; no
application can mint CONTROL work by choosing a lane number.

The Java/GraalVM backend still executes the live scheduling and cancellation
runtime. A passing Gate 0 means Oreslang-owned policy can be linked/executed;
it does not establish that the live runtime invokes this module or that LLVM
can compile full actors. Gate 1 requires sealed intrinsics with generation-safe
handles and actual .ores-owned actor state transitions.

## Gate 1 prototype: native actor-turn ownership kernel

The portable native **prototype** under `src/main/c/oresactorturn.h` and
`src/main/c/oresactorturn.c` establishes a VM-private admission and
incarnation boundary, independently of Java runtime class identity. The native
kernel supplies opaque VM-owned actor handles, one-shot cleanup tickets, and
carrier-bound leases, and offers atomic operations for register, claim, STOP,
cleanup claim, cancellation, force revoke, release, and retirement.

This is deliberately **not a public Oreslang FFI**. The C pointers MUST stay
inside the trusted VM. A future compiler intrinsic returns a sealed typed Ores
capability instead of an address or an integer supplied by guest code. The
kernel validates membership before dereferencing a supplied handle, fences
old actor incarnations and foreign registries, binds physical leases to the
claiming native carrier, and prevents the same cleanup ticket from being used
twice. A STOP decision and a new user-turn claim serialize under one lock, so
neither can race an unguarded check. Existing leases must physically exit
before final retirement.

The prototype intentionally uses one VM-global mutex and a lifetime object
budget, which is **correctness scaffolding, not the intended scalable
scheduler**. Allocated handles are not reused until the VM is destroyed to
prevent pointer ABA; therefore admission can fail when the bounded lifetime
budget is exhausted even if many old turns have finished. The host must quiesce
and fence every possible caller before destroying the native VM.

### Extended native admission and teardown stress audit

The native admission regression suite additionally races **200 STOP/user-claim**
interleavings and **200 force-revoke/cleanup-claim** interleavings, with
carrier-owned release, plus forged/cross-VM stack-pointer guesses and malformed
input checks. The number of interleavings is a stress sample, not a formal
exhaustiveness proof; the linked VM-wide mutex is the native linearization
point.

The VM destructor now fails closed until **every actor is STOPPED,
physically quiescent, free of outstanding cleanup tickets, and RETIRED**.
A no-active-lease check alone would have permitted dropping an unretired
actor and its cleanup obligations. The host must independently fence all
threads that can call into this VM before teardown. Opaque pointer addresses
are never reused *within the lifetime of one VM*, but after destroying a VM
the allocator can recycle addresses; old host pointers must never be used
against a new VM. A production handle should additionally carry VM-instance
identity and a generation-scoped secret/nonce independent of allocation
address.

### Physical carrier identity hardening (2026-10-14)

A raw `pthread_t` is not a durable turn ownership identity: operating systems
may recycle thread identifiers once a carrier exits, and `pthread_equal`
with an expired identifier is not a safe authentication strategy. Turn leases
now capture a **process-monotonic token** assigned to each native thread via
C11 thread-local storage. A new thread cannot inherit a prior thread's token
even if the OS reuses its thread ID. A process-wide atomic counter refuses
to wrap; token exhaustion fails closed rather than granting a recycled lease.

This is **physical release ownership only**. The trusted VM must still
authenticate a real SourceTask and continuation, prove the guest frame has
physically exited, and provide a controlled abandoned-carrier recovery design.
A replacement thread may never release an abandoned lease simply because its
OS thread ID matches an old one. This prototype intentionally refuses to
retire an actor while such a lease remains outstanding.

The regression suite adds **512 carrier create/claim/release/join cycles** per
test executable, supplementing the 200 STOP-vs-user and 200
force-revoke-vs-cleanup iterations. Private/private validation mirrored exactly
four source blobs, with root tree
`2585bd1f9a239534ba6bdbd16239dab77befa8f5`, and checked them against
source PR head `9a2dec270b28175003c6a222edf3022b27ac5f7a`.
[Funded Actions run #38657233362](https://github.com/canonical-cloud/canonical-e2e/actions/runs/38657233362)
executed successfully with GCC/Clang, -O0/-O2 and ASan/UBSan (eight
executions). This is **exact-native-blob evidence only**, not proof that the
entire Git tree, Java guest loader or LLVM compiler passed.

An additional security regression isolates an **abandoned lease** in a child
process after its original carrier thread exits. It starts 256 new native
threads and verifies all release attempts fail with WRONG_CARRIER, even if
the OS reuses its old pthread ID; it also checks retirement and VM destruction
remain blocked. The isolated child exits via `_exit` because intentionally
abandoned leases cannot be repaired through the normal safe public API.
[Funded Actions run #38657443729](https://github.com/canonical-cloud/canonical-e2e/actions/runs/38657443729)
verified the revised native test blob at source head
`f8792c3cfc9ea302a5378ebe16fb959249315465` against private
four-file mirror tree `a746d10c5bcd8a608c50e43818ca69801de21ead`;
all eight GCC/Clang/optimization/sanitizer configurations passed.
This proves rejected stale carrier release in a regression, **not**
a production-grade recovery protocol for a legitimately abandoned lease.

### Forked-process VM isolation

The native VM is now explicitly bound to the process that created it.
`fork()` copies the in-memory actor registry, mutex, lease pointer and
calling thread's TLS carrier token. Without process fencing, the child
could wrongly appear to own an inherited lease. Every public native
status operation now checks the creator PID **before locking** a VM mutex.
Inherited-VM operations reject with `ORES_TURN_INVALID`, including lease
release, registration, STOP and VM destruction. A forked child that needs
native actors must create a new VM. This also avoids deadlock on a mutex
copied in its locked state from a disappeared parent thread.

The fork regression copies a VM while the parent owns an active physical
turn lease, attempts native operations in the child, and then verifies
that the parent's original lease and actor can complete and retire.
This regression, like the current SourceTask/terminal-completion changes,
is **committed but not yet compiled/executed**.

### Registered SourceTask cleanup provenance (implementation in progress)

The prototype no longer accepts an arbitrary actor handle as sufficient proof
to mint a retained-cleanup ticket. The trusted VM adapter must first call
`ores_turn_register_source_task(vm, actor, task_id, task_generation, out)`.
This allocates a **VM-owned opaque SourceTask handle**, rejects forged/foreign
handles, fences stale incarnations, and disallows duplicate live task IDs and
non-monotonic generations for the same actor. Ticket issuance now requires
that exact source handle and is one-shot per SourceTask. Outstanding source
registrations block actor retirement even when every ticket has been consumed
or canceled. Source retirement is separately gated on ticket settlement and
physical carrier quiescence.

Claiming a cleanup turn is also **not** proof that the terminal signal was
injected. `ores_turn_note_terminal_injected` requires the original physical
carrier, matching cleanup lease and source handle, an active STOP and a
non-revoked actor. The VM may acknowledge injection exactly once, only after
it truly injected the cancellation signal. **Injection does not prove cleanup
completion.** A separate one-shot `ores_turn_complete_cleanup` call must be
made by the original carrier after all guest cleanup has actually finished.
It refuses un-injected, stale, mismatched, revoked, already completed or
released leases. The private `ores_turn_check_cleanup_effects` returns
DENIED before injection, after completion, after lease release, and after
force revocation. It is a point-in-time check, **not** an atomic authorization
for effects that run afterward; the live VM adapter must hold its physical
turn ownership/epoch throughout any permitted guest work.

If a claimed cleanup lease releases **without successfully completing**
the guest cleanup, the registered SourceTask remains a retirement barrier,
regardless of whether cancellation injection occurred. Only an explicit
VM force-revocation transition can record a terminal abort and discharge
that logical barrier. Even on force abort, physical carrier leases must
exit before actor retirement. Guest code may never invoke these intrinsics
directly or pass naked pointers as handles.

**Important trust limitation:** the prototype can authenticate membership in
*its own registry*, but it cannot prove that the caller registered an actual
scheduler SourceTask or that terminal injection physically occurred. The VM
adapter is still trusted to register genuine SourceTasks and acknowledge
real injections. These checks are not effective production controls until
wired to backend-managed task objects, cancellation and scheduler dispatch.

The expanded C adversarial fixture includes forged/cross-VM SourceTask
handles, duplicate or decreasing task generations, double issue, retirement
barriers, replay after actor restart, wrong-carrier acknowledgement,
pre-injection completion refusal, injection-without-completion retirement
refusal, repeated completion denial, no effects after completion and
200 completion-vs-FORCE race iterations, and inherited-VM rejection
after `fork()`. **These new tests have not yet executed on a runner.** The most recent funded private CI attempt
[#38658876999](https://github.com/canonical-cloud/canonical-e2e/actions/runs/38658876999)
failed before executing any steps, so neither compilation nor test failure
can be inferred from that run.

### Suspended SourceTask settlement barrier (subsequent hardening)

The original native retirement check relied on an empty actor physical turn,
a resolved/cancelled cleanup ticket and a completed-or-forced cleanup claim.
That condition is **not sufficient** for a SourceTask suspended on `await`,
`select`, a channel, I/O or cooperation: it may own no physical lease while
its logical lifetime remains in progress. An early `retire_source_task`
could thereby let actor retirement or DONE proceed without the suspended
SourceTask's terminal callback.

The kernel now requires a separate, one-shot
`ores_turn_note_source_settled(vm, source)` witness. The trusted scheduler
must deliver this only after the real SourceTask reaches its terminal and
quiescent state, independently of Future notification or an idle actor.
Source retirement refuses with `ORES_TURN_BUSY` before that witness.
The witness itself refuses while an actor carrier is executing, while
its cleanup ticket remains open, or while claimed cleanup has neither
actually completed nor been force-aborted. A settled SourceTask cannot mint
another cleanup ticket. Second settlement is rejected; retired handles
remain stale and task IDs require strictly increasing generation on reuse.

**Adapter caveat:** this VM-private C kernel cannot itself inspect or prove
that `OresScheduler.TaskRunner` truly settled. A privileged native caller
could still falsely acknowledge settlement. Bind this operation exclusively
to real scheduler terminal/quiescence events before granting production
authority. Do not expose the C pointers or witness APIs to guest code.

The regression fixtures now cover physically idle but still-live SourceTasks,
double settlement, closed cleanup obligations, immediate retirement refusal,
and stale/recycled task IDs. The original 15-function C regression suite
was updated to explicitly supply the new witness at each successful source
retirement. That original full suite has **not** been executed in the local
audit yet; these passing results apply to the scheduler-boundary fixture.

Verified local exact-blob matrix for the revised kernel and new fixture:
GCC and Clang at `-O0` and `-O2`, with and without ASan/UBSan (8/8),
plus GCC `-O1` ThreadSanitizer (1/1), for **9/9 passing native fixture
executions**, 1,000 FORCE-versus-cleanup races per run, and generation/
suspension/fork regressions. Hashes:
`oresactorturn.c` `b760870dcab0d635ce62b7d84c7794d57ebdc2c9`,
`oresactorturn.h` `27f94d5a600882967d878d79d79cd18b3d3e2ddf`,
`oresactorturn_scheduler_boundary_test.c`
`3d562d31481d984df83e81ff5788a61484b10e21`.
These results establish native prototype behavior only, **not** Java
SourceTask integration or LLVM guest parity.

### Exact-blob local native execution (2026-10-14)

The experimental native kernel was compiled and run **locally** against
GitHub-verified byte-identical blobs, without any cross-repository source
transfer. Three local file hashes were checked with `git hash-object`:

- `src/main/c/oresactorturn.c`:
  `874c57d640542228497d253a272b772f8235e8b8`
- `src/main/c/oresactorturn.h`:
  `284d33d7186a066051e1f4ff951827fee327e5ad`
- `src/test/c/oresactorturn_scheduler_boundary_test.c`:
  `990b6b9e8eb9334deb993f8ddb213f01cfcceddf`

The newly committed fixture exercises a **synthetic trusted native driver**:
registration, STOP admission, terminal-injection acknowledgement, cleanup
completion, physical carrier release, state/source retirement, force-abort
with incomplete cleanup, inherited-VM refusal after `fork()`, and
**1,000 concurrent FORCE-vs-cleanup races per execution**.

Executed local native matrix: GCC 14 and Clang 17, each at `-O0` and
`-O2`, with and without AddressSanitizer/UndefinedBehaviorSanitizer:
**8/8 passing executions**. GCC 14 at `-O1` with ThreadSanitizer:
**1/1 passing**. Combined **9/9 focused native executions** and
**9,000 race iterations**. Clang 17 ThreadSanitizer could not link
because of missing libdispatch/Blocks runtime symbols in the local toolchain;
no Clang TSan pass is claimed.

`scripts/test-native-actor-turn.sh` now builds and runs BOTH the existing
`oresactorturn_test.c` and this new fixture for each compiler configuration.
The **existing full C suite has not been executed locally in this audit**;
these passing figures describe the new focused fixture only. This is not a
full-Git-tree Actions result, not a real compiler-lowered Oreslang actor,
not JDK 25/AOT verification, and not Java/LLVM guest parity. Keep PR draft.

### SourceTask-tagged physical lease admission (2026-10-14)

The native kernel now has a trusted-scheduler-only
`ores_turn_claim_source(vm, source, out_lease)` operation. The lease
records an opaque SourceTask identity for its entire physical turn, rather
than appearing as a generic actor user turn. This still observes the
**actor-wide single-flight lane**: a second SourceTask cannot enter guest
code while another actor turn is executing. STOP, FORCE, stale or settled
SourceTasks cannot claim new ordinary SourceTask turns; the original
carrier must release its physical lease before any suspension/resumption.

The SourceTask records its own `active_lease`, allowing a task whose
turn already unwound to receive its trusted terminal-settlement witness
and retire **while a different SourceTask on the same actor owns the
single physical lane**. Legacy *untagged* actor user leases remain a
conservative actor-wide retirement barrier, because they may be executing
code belonging to an otherwise unidentifiable task. Cleanup tickets also
record their SourceTask-specific active lease. No actor executes more
than one physical guest turn concurrently.

This is still the **C admission kernel and test contract**, not a
production Java-to-native bridge. `OresScheduler.TaskRunner` must invoke
this claim before actual guest `resume` and release it only when the
guest frame has completely exited; it must retain SourceTask identity
across awaits and issue the distinct logical settlement witness only
after real terminal quiescence. The native registry cannot independently
attest scheduler provenance. Hooking a Java Future callback directly to
`ores_turn_note_source_settled` would be incorrect.

**Executable proof (local exact-blob source, no other GitHub organization):**
the C kernel `ff4d95f72594b34e36b3918f7b24bcc6dbc030cf`,
header `ff81a5e7da92a855893d0a8b129b6be824927a63`, and
boundary fixture `0fb15b4aa1027d97623d44fee9653df486e37919`
pass GCC 14 and Clang 17 at O0/O2 with and without ASan/UBSan
(8/8), plus GCC O1 ThreadSanitizer (1/1). Each executable runs
1,000 concurrent FORCE/cleanup races. The fixture additionally asserts
two-source single-flight admission, settlement and retirement of the
inactive source while another task owns the lane, STOP denial of resumed
ordinary turns, and refusal of a stolen physical lease by a foreign
carrier. Repeated GCC O2 runs added 20,000 earlier-variant stress
iterations. These are **native fixture executions only**; they are
not full-suite C, JVM/JDK25, GraalVM Native Image or LLVM parity evidence.

`scripts/test-native-actor-turn.sh` now supports
`ORES_NATIVE_TSAN=1` as an alternative to
`ORES_NATIVE_SANITIZE=1` and fails closed when both are enabled.
The script runs the original native suite plus the new fixture.
The original suite's latest source still needs to be obtained and
executed through an authorized exact-tree test environment.

### Cleanup-turn capability separation (2026-10-14 audit)

**Security finding (reproduced before the fix):** A normal
`ores_turn_claim_source` lease for a SourceTask could remain physically
active across actor STOP. Because terminal-injection and completion checks
previously authenticated only the SourceTask identity, actor STOP and current
carrier, the ordinary lease could call
`ores_turn_note_terminal_injected` and `ores_turn_complete_cleanup`
successfully **without ever consuming a retained-cleanup ticket**.
The local reproducer observed `ORES_TURN_OK` for both acknowledgements.
That incorrectly promoted a normal execution lease to terminal cleanup
authority.

**Fix:** Native leases now record a separate, non-upgradable
`cleanup_turn` capability bit set only by successful
`ores_turn_claim_cleanup`. Terminal signal injection,
`ores_turn_check_cleanup_effects`, and cleanup completion all require
both this lease kind **and** a consumed/claimed SourceTask cleanup ticket.
STOP does not grant cleanup authority to already-issued user or source
leases. The cleanup-bit remains false on `claim_source` and
`claim_user`, regardless of which SourceTask ID is carried.

The boundary regression exercises an ordinary source lease both with an
unclaimed pending cleanup ticket and with **no cleanup ticket at all**,
verifies all terminal cleanup operations refuse it after STOP, then
checks that the legitimately claimed cleanup lease can finish exactly
once. It also preserves carrier ownership, source settlement, and
actor retirement assertions.

**Local exact-blob validation:** C kernel
`0c461c22e5466242430a3c38731b0f249b4abdf8`
and boundary fixture
`cc494b21a8f15f83007fb4227c645832830ec85b`
passed GCC/Clang O0/O2, normal and ASan+UBSan (**8/8**),
plus GCC O1 ThreadSanitizer (**1/1**); each executable also
runs the fixture's 1,000 FORCE/cleanup stress iterations.
The original `oresactorturn_test.c` full regression suite and compiled
Java/LLVM actor runtime remain separate, unverified gates on this head.

### Next native/Java scheduler integration seam (not yet implemented)

Inspect the existing `OresScheduler.TaskRunner` and
`ActorRuntime.startActorTask`, rather than creating a second native
scheduler. `TaskRunner` already has a physical turn admission edge
(`runTurn`), an after-carrier-exit edge (`afterCarrierTurn`), and a
quiescence callback (`onQuiescent`). The eventual trusted VM adapter must:

1. Register a real scheduler-owned SourceTask after actor task admission,
   with actor-incarnation-scoped identity; never accept guest task IDs.
2. Atomically claim a sealed native turn **before** entering the actor's
   compiled guest frame. A non-actor task must not be accidentally registered.
3. Release that same physical turn only after guest execution unwinds, and
   preserve SourceTask lifetime across every `await`/cooperate/resumption.
4. On STOP, retain only authenticated source-task terminal continuations;
   acknowledge injection **only after actual VM cancellation delivery**.
5. Acknowledge cleanup completion **only after** the guest's `defer`,
   `finally`, loan release and output cleanup have genuinely finished.
6. Retire the source task from a quiescent and cancellation-settled path,
   not from Future completion; publish actor DONE only after all state,
   child, output and CONTROL barriers.

The C effect-check API is currently a **point-in-time advisory check**:
there is no safe atomic permission to execute arbitrary guest code after it
returns. FORCE and guest-effect execution must share a real VM-enforced
epoch/admission barrier before native authority is used for production.

### Mainline integration and private-CI policy

PR #917 was brought forward by an exact three-way Git-object merge with
main at `3f1643c0cd88a766308bb44b9705c55f8b3cf518`. No blob-level
collisions occurred. Mainline ownership/select code and the CI security
controls were retained, while the ten native-runtime PR file changes
were overlaid. The combined source tree must still pass JDK 25, guest and
native-image validation before any production merge.

Mainline `.github/private-ci-approved-repositories.txt` has **no approved
destination**. The reviewed security policy explicitly prohibits all
cross-repository private-source transfers until an exact destination has
been approved through protected-branch maintainer/security review. Private
visibility and successful runner canaries are not sufficient permission.
Do not send any further source to `canonical-cloud`, `3FA-app`, or another
organization without that review. Earlier native-only private mirror objects
require security-owner review and retention/disclosure assessment; deleting
refs does not remove Git object bytes. Use a source-org runner if available,
or approved full-tree export only after authorization.

Native compiler-and-test evidence is required on the exact Git tree; successful
registration of a GitHub Actions workflow without executed job steps is not
validation. Cross-org CI mirrors require a maintainer/security-reviewed destination
on the protected-branch allowlist, private/private pinned repository IDs,
and exact tree equality before any conformance claim.

**Critical limitations:** Native `force_revoke` fences *future* claims; it
does not asynchronously terminate an already-running native carrier. Cleanup
tickets now require a registered VM-private SourceTask handle, but the
prototype cannot independently attest scheduler provenance or actual terminal
signal delivery until integrated with live backend task objects.
Physical turn admission and actor retirement still require the rest of the
runtime's capability, child, cleanup and output barriers. C pointers are not
safe to export into user code, IPC, Java interop or ordinary Ores values.
This kernel is not linked into the current Java/Truffle actor dispatch and is
not an LLVM guest actor implementation yet.

`stdlib/actorruntime.ores` now separately owns the deterministic
`next_phase` transition policy (REGISTERED, READY, STOPPING, FINALIZED).
It rejects skipping STOP, rejects stale READY after finalization, and requires
quiescence for FINALIZE. Returning a phase integer is an advisory policy
result, **never** permission to commit an actor transition. The VM must
atomically bind a generation-safe authenticated transition lease to that
policy result before changing its private actor state.

Native correctness smoke tests run at `-O0` and `-O2` through
`sh scripts/test-native-actor-turn.sh`; optional ASan/UBSan execution
uses `ORES_NATIVE_SANITIZE=1`. These cover forged and foreign handles,
generation fencing across restart, wrong-carrier release, concurrent one-shot
ticket claims, STOP and force revocation, resource exhaustion, and delayed
physical retirement. The Oreslang guest fixture independently tests source
lifecycle transitions. Both independent suites are necessary; neither by
itself establishes that the live actor scheduler is using the new kernel.

## VM and trust boundary

To bootstrap an Oreslang program, the VM must exist before any Oreslang module
runs. The VM/backend necessarily performs low-level operations that guest code
cannot safely self-authorize:

1. Verify and enter a VM-controlled actor/heap/execution domain with an opaque
   unforgeable ActorId + incarnation; allocate and revoke physical isolates.
2. Provide atomic, memory-ordered ownership of one actor turn and its bounded
   mailbox and scheduler queue; expose opaque handles, not raw host objects.
3. Save/reinstate heap-owned continuations at compiler-defined suspension
   points; wake a carrier without running guest code on a foreign thread.
4. Perform kernel-facing OS I/O, clock and wake operations with VM-enforced
   capabilities, quotas, and resource ownership.
5. Enter and leave a Truffle/LLVM guest frame and perform final physical
   memory cleanup only when actor leases and owned loans are gone.

These are **intrinsics**, not Java implementations of actor policy. Oreslang
must decide scheduling and lifecycle behavior above this boundary. The current
Java/GraalVM bootstrap is temporary and must eventually call the exact same
intrinsic contracts as LLVM or be reduced to compiler/VM entry glue.

The VM must authenticate provenance before giving `actorruntime.ores` a
`scheduler_provenance_valid` or identity attestation. Passing a guest-supplied
`true` does not grant authority. The current pure policy functions are
suitable for conformance but are **not** privileged security gates.

## Core actor guarantees

- At most **one native carrier** executes an actor at any instant, including
  ordinary receive, terminal cleanup, timers and CONTROL handlers. Multiple
  outstanding logical messages do not imply simultaneous actor code.
- One fixed-key actor-owned `state { ... }` map, never ordinary mutable
  actor instance fields. Read via `self.handle_state(|s| -> ...)` or
  `using state.read(|s| -> ...)`; exclusive writes via
  `self.handle_state(critical |s| -> ...)` or
  `using state.write(critical |s| -> ...)`.
- Signature `with` is the upper bound on effects; `using` acquires a
  lexical state/resource capability. Loans must not escape or survive
  `await`, `select`, cancellation or suspension.
- Source-level `spawn` constructs actor state after the VM installs its
  execution domain. ActorRef interactions are message-only.
- STOP is monotonic: reject user mail, permit only source-scheduler-owned
  terminal continuations, do not replay unstarted frames, and do not resurrect
  stale generation/actor identities.
- A cancellation **request** may authorize an authenticated terminal delivery
  turn; only successful one-shot injection authorizes guest cleanup effects.
  Ordinary guest `catch` cannot swallow terminal unwind. `defer`, `finally`,
  iterator closure, state loans and guards must run exactly once where permitted.
- A bounded cleanup deadline does not license guest execution off-lane or
  physical retirement while a carrier is still in a guest frame. Forced
  physical revocation requires a real VM isolation boundary.
- `get_done()` only settles after lexical cleanup, descendants, final cleanup,
  state retirement, output closure and supervisor terminal publication.
  Host-fatal and untrusted quota failures retain defined dominance.
- Master scheduler plus per-pool dedicated scheduler/control loops. Pool worker
  capacity is 0–5 and carriers activate lazily. All guest scheduling behavior
  is implemented in Oreslang over the sealed host wake/park primitives.

## Incremental acceptance gates

| Gate | Required executable evidence |
| --- | --- |
| 0 — Native policy | `std/actorruntime` links, runs and passes the pure Oreslang lifecycle policy guest fixture; no `java:` host imports |
| 1 — Actor state machine | Implement the actor-owned lifecycle map and STOP transitions in `.ores`; authoritative VM-verified identity, incarnation, cleanup permits; no duplicate Java policy authority |
| 2 — Scheduler and continuations | Source-written actor queues, cleanup-only CONTROL lane, async cancellation and physical turn barriers run through opaque intrinsics; adversarial capacity/races |
| 3 — Structured semantics | Native `spawn`, messages, actor groups, supervisor CONTROL, `with` / `using` critical state loans, `self.forward()` / `self.backward()`, select and channels |
| 4 — Backend parity | Compile and execute **the same** `.ores` guest scenarios on GraalVM JIT, Native Image AOT/hybrid and LLVM; compare traces and terminal failures |
| 5 — Java retirement | Remove Java lifecycle/scheduler policy only after the native path is the one invoked in every supported backend. Retain only minimum VM/compiler adapters and backend tests |

For every gate, compare source root Git tree SHAs and require executed CI steps
instead of accepting zero-step GitHub Action failures as test evidence. Failure
to link/compile a required `.ores` feature must fail closed, never quietly
fall back to a Java policy implementation.

## Relationship to Java PR #823

PR #823 currently exercises the Java bootstrap implementation. Its cancellation,
single-flight and DONE regressions are **migration conformance inputs**, not a
reason to make Java `ActorRuntime` the permanent semantic owner. Keep that
branch draft until the native actor runtime can execute equivalent end-to-end
source scenarios. Any temporary security fix to Java is defensive compatibility
work and does not count toward implementation of the native runtime.
