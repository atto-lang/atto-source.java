# `oreslang test --race` and `--fuzz`: runtime contract (proposal)

> **October 18, 2026 implementation update:** the stacked test-mode dispatch
> PR now supports Oreslang-native `fuzz_int_cases` generated integer inputs,
> bounded CriticalGate cancellation/handoff stress, a finite two-lock
> reference model, explicit `--deadlock` runtime checks, and SHA-verified
> replay of the generated CriticalGate test inputs. See
> `docs/TESTING_COMMAND_MODES.md` for accepted commands and current coverage.
> Full guest shadow-memory `--race`, coverage-guided fuzzing, deterministic
> actor/Task schedule replay, and general source-level model checking remain
> **unimplemented**; no clean general-concurrency result is implied.

Status (2026-10-19): **guest race detection and true guest schedule fuzzing remain unavailable**.
The Java launcher executes Oreslang-native `TestSuite` programs.
`--race` deliberately exits **2** until guest accesses are instrumented.
`--fuzz` can check real seeded native `fuzz_int_cases` integer properties;
`--seed` and `--runs` validate their reported input parameters, and
`--replay` verifies only bounded CriticalGate input exploration (v2 source-
closure fingerprint), **not** guest scheduling. The `--deadlock` and
`--model-check` modes cover only a bounded CriticalGate graph/reference model.
See `docs/TESTING_COMMAND_MODES.md` for the exact supported combinations.
Do **not** mark an ordinary green test as race-free or deadlock-free.

## Target CLI

```sh
oreslang test examples/native-testing.ores
oreslang test --race path/to/test.ores
oreslang test --fuzz --runs=10000 --seed=42 path/to/test.ores
oreslang test --race --fuzz --seed=42 path/to/test.ores
oreslang test --replay=failures/cycle-abc.orestrace path/to/test.ores
```

The test runner uses Oreslang's own `stdlib/testing.ores` and
`ORES_TEST|...` protocol. Explicit `TestOptions(machine=true)` and a
positive, consistent summary are required; a missing or failing summary is
never success. File discovery and automatic test functions are future work.

## Race detector: shadow memory and happens-before

For every instrumented guest read, write and atomic access, OresVM records:

- `(runtime incarnation, allocation domain, object generation, field/index)`
  as the memory identity. Never treat a recycled pointer or old actor ID as
  the same object after destruction/hot reload.
- Logical **Task/actor continuation** as the execution identity, not the
  physical Java/C++ worker thread or actor authorization domain.
- Source file, source span, effect row, current critical ticket, RW loan
  generation, and relevant allocation provenance.
- Epoch/vector-clock or FastTrack-like last-write/last-read metadata.
  An unordered pair of conflicting accesses to the same reachable mutable
  location is a race. Plain atomic accesses are not data races but can still
  participate in algorithmic races/liveness failures.

Happens-before includes sequenced guest operations, release/acquire on
CriticalGate/SharedMutex/RWLock, channel send/receive *commit*, Future result
publication and its actual await-resumption edge, Task spawn/join, and actor
mailbox delivery. **Cancellation requests are not physical-exit edges.**
Public task cancellation cannot confer ownership of a critical ticket; only
the scheduler's physical retirement barrier can.

Actor-owned private heaps should be compiler-enforced disjoint, but a race
mode must still validate trusted shared memory, FFI aliases, native buffers,
per-actor state with physical max>1, and accidental escapes. Do not promise
race detection for uninstrumented FFI, GPU, device memory, other processes,
or opaque host callbacks. Those must be explicitly reported as coverage gaps
and, in strict race mode, rejected where the operation may mutate shared state.

Reports must provide both source locations, owners/continuations, allocation
generation, last synchronization edges, and actionable `critical`/borrow
checker remediation. Race mode is **not** just a JVM thread race detector.

## Deadlock detection: logical resource dependency graph

OresVM tracks `waiter -> resource -> owner` dependencies for CriticalGate,
SharedMutex, RW state gate, Task join, Future producers, actor request/reply,
channel admission and `select`. Graph edges are generation-authenticated and
removed **before** a timeout/cancel result is externally published. Edge
creation and removal are atomic against handoff; never publish callbacks while
coordinator locks are held.

Reject a definite single-resource dependency cycle before admission when
possible. For alternatives (`select`, timeout, remote producer, external
input), model OR dependencies and report `possible cycle` or `stalled`
rather than a false definitive deadlock. No periodic thread polling is
required for basic cycles: check at wait-edge insertions.

`suspend critical` is the recommended source form for contended work.
Its lock ticket can outlive `await` while the physical carrier is free;
an unvalidated mutable `StateAccess` loan cannot outlive that await.
CONTROL STOP/CANCEL/wakeups always have a reserved, non-user queue and cannot
wait on ordinary critical admission.

## Fuzzer: deterministic interleaving exploration

On an explicit seed, choose scheduling decisions at compiler safepoints,
`await`/resume, Task spawn/join, `critical` admission/release, channel
select, actor mailbox dequeue, cancellation, timeout and supervisor restart.
Record each choice with a stable resource ID and code-generation epoch.

- No implicit `Thread.sleep` based exploration. Use bounded scheduling
  choices, virtual time, reproducible PRNG, and optional preemption bounds.
- Use seed + action trace + source/runtime hashes; replay fails closed if
  source differs. Preserve failure trace artifacts and minimize/shrink them.
- Assertions: exactly-once critical handoff, no simultaneous write loans,
  no stale wait edges/permits, cancellation-vs-completion barriers, bounded
  admission/CONTROL progress under stated fairness assumptions.
- Watchdog/stall findings must distinguish deadlock, livelock, long I/O,
  idle external-input waits and starvation. Neither a timeout nor a test-run
  budget by itself proves deadlock.
- Race and fuzz modes compose: schedule fuzzing explores more
  happens-before interleavings while shadow memory detects conflicts.

## Delivery milestones

1. Physical-retirement gate and deterministic cancellation tests on Java
   and LLVM, with no public-cancel-as-exit shortcut.
2. Unify CriticalGate and SharedMutex logical continuation IDs and wait graph;
   include Task joins, actor replies, RW loans and channels incrementally.
3. Java reference shadow memory for a narrow shared-memory subset, plus known
   good/bad .ores fixtures proving races are actually detected.
4. Scheduler choice capture/replay for a narrow Task/CriticalGate subset and
   deterministic synthetic deadlock fixtures. Prove seed replay identical.
5. Widen to actors, channels, select, timeouts, restarts, FFI boundaries and
   native LLVM/TSan-assisted backend verification.
6. Only then enable general guest `oreslang test --race` and true
   scheduler/coverage-guided `--schedule-fuzz` modes with machine-readable
   `ORES_TEST|RACE` / `ORES_TEST|DEADLOCK` / `ORES_TEST|FUZZ` reports.
   Native bounded integer-property `--fuzz` is a separate, limited feature
   and must never imply full guest scheduling or memory-race coverage.

Interim Java host tests include `CriticalPhysicalRetirementTest`,
`CriticalLogicalDeadlockTest`, `CriticalSeededTransitionFuzzTest`,
`CriticalCarrierNonparkingTest` and `NativeTestCommandTest`. Their tests
must execute on the exact PR head. A GitHub Actions failure with zero job
steps is **no execution evidence**.


## October 18 follow-up: critical cancellation publication

A follow-up review of the existing `CriticalGate` found two additional
admission/publish windows, hardened on the same draft branch:

1. A synchronous call on an actor or ROOT/CONTROL carrier must either claim an
   idle gate **atomically without queuing** or reject immediately. Previously
   the call could enqueue then retract; a concurrent owner release could grant
   it in the gap. The corrected path leaves the FIFO and wait graph untouched
   on contention.
2. Cancelling a queued `CriticalGate` admission must remove its FIFO entry
   and `WAITING_ON` edge **before** the cancelled `OresFuture` becomes
   observable. The cancellation-admission callback now retracts only a queued
   ticket. Once physically granted, public cancellation loses and the owning
   continuation must perform its normal physical-release protocol.

These are focused fixes, **not** a unified graph with `SharedMutex`. Related
actor lifecycle code still closes source schedulers under `lifecycleLock`
during finalization, which may synchronously invoke Future callbacks; issue
#1231 remains a distinct hardening gate. Do not claim full "no callbacks under
coordinator/lifecycle locks" until all those paths are audited and executed.

The standalone Java 21 reference transition model was run locally with
100,000 seeded sequential scenarios, 1,000 concurrent cancellation/grant
races and 1,000 concurrent cancellation/physical-exit races, repeated twice.
This is **model-only evidence**; it is not executed exact-source Oreslang
compiler/JUnit/native-image evidence.

Private cross-organization source testing is blocked by the current approved
destination list at `.github/private-ci-approved-repositories.txt` on
`main`: it contains no approved repositories. Source mirroring requires a
reviewed allowlist entry and destination privacy/permission verification;
availability of GitHub Actions minutes is not authorization to copy source.

## October 19: actor two-phase shutdown and dropped-dispatch retirement

A new audit found two additional runtime hazards beyond cancellation of a
physically executing critical owner:

1. **Callbacks under actor `lifecycleLock`:** finalization previously called
   source scheduler close, mailbox/channel close and drain, output close, exit
   hook, and Future completion under the actor's lifecycle monitor. The
   two-phase path now publishes a lock-protected readiness gate, then runs
   potentially callback-bearing release steps outside that monitor. Queued
   mailbox draining also rejects callers holding the lifecycle monitor.
   Terminal publication follows owned-future/child cancellation requests.
2. **Permanently queued dispatch reservations:** scheduler actor continuation
   envelopes can be dropped when an actor stops. The scheduler must receive a
   single-shot `abandoned()` signal for every queued turn that is discarded
   without execution. A dispatch-local CAS arbitrates `run()` vs
   `abandoned()`, so physical-retirement accounting decrements exactly once.
   The same signal is applied to work returned by `shutdownNow()` on owned
   executors. This matters for `critical` ticket release after cancellation.

Added exact-source JUnit regression classes:
`ActorFinalizationCallbackIsolationTest` and
`SchedulerAbandonedDispatchTest`. They assert that actor exit and terminal
callbacks run outside lifecycleLock, parent/task cancellation precedes
publication, actor turn completion obeys the physical boundary, abandoned
queued source Tasks cannot execute guest code, and retirement runs exactly once.

Independently, a standalone Java 21 state model passed 200,000 sequential
trial orderings and 5,000 three-way concurrent cancel/run/abandon races.
**This is model-only evidence.** It does not mean the exact Oreslang source
compiled or executed. Source CI on this branch was still queued/no confirmed
steps when the changes were recorded. Do not merge until exact-head
compiler/JUnit/GraalVM workflows have actually run and passed.

Private CI transfer is blocked by the unmerged allowlist proposal
[PR #1472](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1472).
Even a private destination with a successfully executed Actions workflow is
not eligible for source mirroring until approved through protected main.


## October 19 supplementary audit

A host task dispatched to a synchronous/inlined executor can exit its guest
turn and subsequently propagate a fatal Error back to the submitter. The
`afterTurn` path and submission `catch` must share one single-shot
reservation retirement flag; otherwise `inFlightDispatches` can go negative.
Added `SchedulerInlineRetirementTest` for fatal guest turns and submission
rejections, inspecting the exact in-flight counter.

Actor mailbox Channel.tryRead is now invoked outside actor lifecycleLock.
Channel selection can publish Future results and invoke completion listeners.
The existing second pre-dispatch stop check prevents delivery after shutdown
wins.

A local Java 21 standalone design model completed 100,000 fatal inline,
100,000 rejected submission, and 5,000 concurrent retirement scenarios,
all passing. This is model-only evidence, not Oreslang Maven/JUnit execution.
Latest exact-source GitHub Actions jobs still had no executed steps. Private
cross-organization source mirroring remains pending the reviewed allowlist
proposal in PR #1472.
