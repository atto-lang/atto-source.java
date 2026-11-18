# First-actor lazy pthread activation — Nov 3, 2026 hardening audit

Status: draft-only. This is not evidence of production two-thread process startup, isolated heap execution, or full five-backend guest parity.

## Review baseline and intended semantics

Reviewed Java PR #2493 beginning at `351dc4759132e588e7d6c155c760c5b68b38ab60` on current Nov 3 main `5c3d5414a15f887a078b64d6aed3bafb7132427f`. Source actor pools start with zero pthread carriers; the first actor admitted to a particular lane starts that lane's pthread *before* publishing ActorRef. More idle actors reuse started lanes; runnable pressure grows within `DispatcherConfig`. No carrier creation is triggered solely by the number of actors after first admission.

PRIVATE, SHARED and UNTRUSTED presently use distinct host dispatchers. Merely placing UNTRUSTED on a different pthread is **not** an OS/process/isolate security boundary. Strongly isolated source execution remains separately gated. OresVM prestarted CONTROL workers and reactor integration are not consolidated into the proposed two-boot-worker pool by this PR.

## Findings and fixes on this draft

1. **Accepted task stranded during shutdown:** `NativeCarrierExecutor.execute()` could enqueue after `beginShutdown()` drained pending tasks; because pthreads then exit, a successful `execute()` could be neither run nor returned as abandoned. Submission and shutdown queue drain now share `nativeLifecycleLock`, and new admissions reject after shutdown wins.
2. **Half-started native pool admitted work:** publishing `nativeHandle` is needed before the JNI callback enters, but cannot certify `nativeStart()` succeeded. An explicit `startedSuccessfully` state is published only after native start; first task admission waits for initialization. Partial JNI startup failure poisons the executor and asks the native reaper to retire any already attached carriers.
3. **Retired handle reused:** `ensureStarted()` previously checked `nativeHandle` before shutdown. It now checks shutdown under the lifecycle lock, so a closed pool rejects a second start.
4. **False Java fallback prestart:** `ThreadPoolExecutor.prestartCoreThread()` returning false with no worker was incorrectly treated as successful actor admission. The dispatcher now rejects that lane. Startup and `shutdownNow()` share `growLock`, fencing close against first-worker activation.
5. Added focused regression tests for failed prestart, deterministic first-worker-start-versus-close arbitration, native accepted-work accounting during racing submit/shutdown, and refused restart after retirement.

## Evidence and gaps

- The PR author reports focused Java/native tests passing on macOS JDK21 and 10/10 repeats of the original `ActorPoolLazyStartTest`. These are PR-authored results and do **not** certify the additional hardening until executed on the new exact head.
- A standalone **source-free model** of the revised Java enqueue/shutdown locking protocol compiled with JDK 21.0.12.1 `javac -Xlint:all -Werror` and passed 120 concurrent rounds, accounting for 17,034 accepted tasks. A second source-free modeled prestart/shutdown arbitration test compiled with the same flags and passed 1,000 iterations, including a dead prestart. **Models only**: neither test is an exact-source compiler test, JNI test, or AttoLang guest test.
- Extended the real JUnit first-admission test to check that the first UNTRUSTED actor activates only its distinct carrier pool after PRIVATE and SHARED have populated theirs. This tests pool segregation; it does not establish a process/isolate sandbox.
- Java source-org workflow failures on the new head currently have `steps=[]` and no assigned runner. No `mvn test`, JDK25, GraalVM Native Image, JNI fault injection or compiled `.atto` actor program has been executed against this updated PR in this audit.
- Do not mirror private Java source to another GitHub organization until the independently reviewed destination, exact snapshot and protected-source governance gates authorize it. Working source-free alternate-org runners are not transfer approval.
- Full physical retirement after partial JNI attach, exact JNI reaper barrier, independent UNTRUSTED isolate semantics, saturation/compensation behavior, and compatibility with VM NIO drafts #2354/#2359/#2367 remain merge-blocking work.

## November 3 JNI physical retirement hardening

The audit identified a lifecycle gap after `nativeStart()`: the native barrier
waited for `AttachCurrentThreadAsDaemon`, but did not wait for the Java
`nativeCarrierLoop` callback to register its worker. As a result, an
immediate `awaitTermination()` could observe all-null thread slots while
native pthreads were still attaching. A stronger barrier and physical join
receipt are now implemented:

- `NativeCarrierExecutor.ensureStarted()` waits for a bounded, once-only
  Java-loop-entry latch (all configured native slots), before reporting
  successful startup. If callbacks never register, startup fails closed,
  requests native retirement, and does not publish a live actor reference.
- `src/main/c/oresthread.c` caches `nativeCarriersJoined()` and invokes
  it from the dedicated reaper **after all `pthread_join()` calls**,
  while the executor JNI global reference remains valid.
- `awaitTermination()` / `isTerminated()` now require this reaper receipt
  for a started pool, rather than inferring physical exit from Java thread
  slots. A never-started pool still terminates without launching a reaper.
  Failure to create/attach a reaper fails conservatively at timeout.
- GraalVM Native Image JNI reachability metadata includes the reaper callback;
  `NativeCarrierExecutorTest` covers max=4/core=1 callback entry, reaper
  retirement, and no-start shutdown.

**Independent local protocol smoke test:** a **source-free** JNI/pthread probe
was compiled under OpenJDK 21.0.12.1 with `javac -Xlint:all -Werror`.
Both GCC and Clang built its native shared library with
`-std=c11 -O2 -fPIC -shared -Wall -Wextra -Werror -pthread`.
Both completed 25 independent rounds × 4 pthreads, with the JNI reaper
callback running only after all four native workers exited. This demonstrates
the *callback protocol*, **not compilation or execution of the modified
AttoLang source tree**.

**Remaining gates:** compile the exact `NativeCarrierExecutor.java` and
`oresthread.c` together with JDK25 and GraalVM Native Image, then execute
the new JUnit tests and fault-inject `pthread_create` and JNI attachment
failures; verify the reaper timeout/quarantine contract before merging.
Private source mirroring remains prohibited without reviewed V3 governance
approval and protected refs.

## Merge gate

Keep PR #2493 draft. Require exact-head focused `ActorPoolLazyStartTest`, `NativeCarrierExecutorTest`, `ActorAffinityDispatcherTest`, `ActorRuntimeNativeCarrierTest`, ActorRuntime lifecycle and JNI shutdown suites on JDK25 and GraalVM Native Image. For future backends match the *observable* lazy-pool contract while retaining each backend's actual isolation and scheduler entry requirements. Do not count source-free canaries or host-only models as guest execution evidence.

## November 3/4 follow-on: bounded native attach and exact-head lifecycle workflow

The previous Java `carrierLoopsEntered.await(30s)` did not bound the
earlier native `nativeStart()` pthread attachment barrier: native code used
`pthread_cond_wait` without a deadline. A stalled
`AttachCurrentThreadAsDaemon` could block startup indefinitely before
Java's timeout began. `src/main/c/oresthread.c` now uses
`pthread_cond_timedwait` against an absolute `CLOCK_REALTIME` deadline
of 30 seconds, checks all carrier attachments and shutdown/failure, and
throws before actor admission on timeout. Java's existing failure path
then seals this executor and invokes asynchronous native retirement.

Additional real-source JUnit cases added to `NativeCarrierExecutorTest`
race `ensureStarted()` with `shutdownNow()` across 12 independent
instances and keep a physical actor turn intentionally active after
shutdown interruption to verify that `awaitTermination()` cannot
succeed until the JNI reaper has joined its pthread.

The new source-org-only workflow
`.github/workflows/lazy-native-carrier-lifecycle.yml` pins the exact
checkout SHA, uses read-only permissions and no persisted checkout
credentials, compiles the actual repository JNI library with GCC and
Clang in separate JDK25/GraalVM jobs, and invokes focused actor/native
lifecycle JUnit tests. No private source is transferred to another
organization. Initial workflow run
[41747485003](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41747485003)
was **UNOBSERVED**: both compiler matrix jobs failed before runner
allocation (`runner_id=0`, `steps=[]`). A workflow being discovered
and scheduled is not evidence its commands executed.

An independent **source-free** POSIX timed-condition-variable model
was locally built under GCC and Clang and passed 100 repeats each
of complete attachment, timeout and shutdown-wakeup cases per
compiler. These 600 modeled scenarios do **not** replace an exact
`oresthread.c` JNI build, fault-injected JVM attach stall or GraalVM
Native Image execution.

**Still mandatory before merge:** obtain funded, authorized source-org
execution; compile and execute the new JNI/JUnit tests and actual
compiled `.atto` actor fixture; inject failures at pthread_create,
JVM attachment, and reaper creation; demonstrate shutdown/physical
retirement under JDK25/GraalVM Native Image. The source-org Actions
runner-admission failure remains distinct from test failures.

## November 3/4 JVM fallback first-worker admission and CI governance

A further source audit of `ActorRuntime.startCarrierForAdmission` found
that the `ores.actor.affinity=none` JVM ThreadPoolExecutor fallback
previously ignored `prestartCoreThread() == false`. A failed first
worker creation could allow actor construction to proceed without a live
carrier. The branch now invokes `startUnlanedJvmCarrier(pool)` and
fails closed when the pool is shut down, no worker is present after
prestart, or shutdown wins before admission returns.

`ActorJvmFirstCarrierAdmissionTest` adds four focused regression
tests: false prestart, closed pool, repeated prestart reusing one worker,
and 32 idle SHARED actors with one JVM fallback worker. The
`lazy-native-carrier-lifecycle.yml` source-org GCC/Clang matrix now
selects this class alongside the JNI and affinity classes.

**Independent isolated local verification (not actual source-tree
compilation):** the extracted first-carrier gate behavior compiled on
OpenJDK 21.0.12.1 with `javac -Xlint:all -Werror` and passed
worker-start failure, already-closed rejection, worker reuse and 400
first-admission-versus-shutdown rounds (all outcomes accounted for).
The in-repository JUnit test class has not executed on this exact head.

**Source-org CI still blocked before execution:** focused workflow run
[41750097771](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41750097771)
failed with `runner_id=0` and `steps=[]` in both GCC and Clang
jobs; ordinary `ci` and `fast-ci` did likewise. These are
infrastructure/no-run findings, not source test failures.

**Cross-organization private-source export is still prohibited:**
`.github/private-ci-approved-repositories.txt` has zero positive
destination entries, source `main` currently reports
`protected=false`, and V3 requires independently reviewed
source/destination governance and exact-head authorization.
`opto-sync-test/go-java-validation` (numeric repository ID 1323598177,
private at last inspection) recently completed source-free Actions
capacity and security-verifier jobs, but those successes **do not**
authorize moving the private Java tree. No cross-organization Git
blob/tree/branch containing source was created in this audit.

The next executable merge gate is a supported private runner for the
source repository or a properly reviewed V3 carrier authorization;
then execute the focused JDK25 JNI/GCC/Clang tests, JUnit, full source
checks and GraalVM Native Image verification at the exact PR head.


## November 3 additional native self-shutdown and synchronization hardening

Further source review found two definite correctness gaps:

1. `ActorAffinityDispatcher.shutdownNow()` called from a native lane's own
   executing command previously invoked `NativeCarrierExecutor.shutdownNow()`,
   which deliberately throws to prevent self-interruption. The dispatcher had
   already set `closed=true`, and could exit without draining pending lane
   commands or returning their admission slots. The dispatcher now detects a
   caller executing on its own native lane and uses the cooperative
   `requestShutdownFromCarrier()` path, which returns unstarted queued tasks.
   The dispatcher calls `rejectAdmission()` on each and returns the original
   commands to its caller, preserving one terminal outcome per admission.
2. Native `pthread_mutex_init` / `pthread_cond_init` return values were
   ignored in `oresthread.c`; failures could propagate uninitialized
   synchronization primitives to worker code or invoke unconditional
   `pthread_cond_destroy` on uninitialized storage. Native pool construction
   now checks both results, fails closed, and records initialized flags so
   `free_pool()` destroys only primitives actually created.

Added actual in-repository tests:
`ActorAffinityDispatcherTest.nativeLaneMayRequestOwnDispatcherShutdownWithoutLosingQueuedWork`
and `NativeCarrierExecutorTest.nativeCarrierSelfShutdownReturnsPendingWithoutSelfInterrupt`.
Both require running the actual JNI carrier and asserting the native reaper
receipt. These test methods have been **authored, not executed**.

Additional independent source-free model verification:
- JDK 21 `javac -Xlint:all -Werror`: 600 carrier self-shutdown rounds,
  1,200 admitted tasks accounted for, 600 unstarted queued tasks returned,
  every modeled worker exited.
- GCC and Clang `-std=c11 -Wall -Wextra -Werror
  -fsanitize=address,undefined`: each passed 5,000 repeats of each
  initialization outcome (mutex failure, condition failure, success),
  validating conditional cleanup behavior under instrumentation.

**Evidence limit:** These standalone models do not compile or execute
`NativeCarrierExecutor.java`, `ActorAffinityDispatcher.java` or
`oresthread.c` from the exact PR source tree. The latest focused
[run #41751650278](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41751650278)
failed before runner allocation (GCC and Clang: `runner_id=0`, `steps=[]`).
The next gate is real JDK25/GraalVM JNI/JUnit compilation and executable
`.atto` actor tests on an authorized runner. Do not merge the draft based
on these local source-free protocol models.

