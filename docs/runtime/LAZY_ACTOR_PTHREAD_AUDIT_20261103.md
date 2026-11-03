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
