# Reusable long state region — bounded JVM host substrate

Status: **prototype in draft PR #2305, not Oreslang guest compiler lowering**.

## Storage/ownership

`ReusableLongStateRegion` owns preallocated primitive `long[]` data and
parallel bounded slot metadata. All storage and pin ledger arrays are created
once per region. Ordinary `read`, `write`, `addExact` and lifetime
`pin`/`releasePin` calls do not mandate managed object allocation.
A caller-supplied `LongUnaryOperator` or synchronization contention may
still allocate or block. Java arrays and their host reachability **remain
subject to JVM garbage collection** on both HotSpot and Graal Native Image.

Object handles are 64-bit primitive values encoding a nonreused 23-bit
domain, 24-bit generation and 16-bit slot. An unsigned top tag bit is
reserved for lifetime-pin tickets. Exhaustion is an error, not silent handle
reuse. Numeric handles are not cryptographic or guest-issued capabilities.

## Mutate in place

`write(handle, value)` writes the preallocated scalar slot.
`addExact(handle, delta)` computes checked signed addition and commits
only after the overflow check succeeds; signed overflow leaves the previous
value unchanged. Neither operation creates mandatory copy-on-write buffers,
snapshots, boxed numbers or callback guards. These are **host primitives**;
no compiler analysis presently maps guest Oreslang expressions onto them.

The optional callback-based `update` holds a synchronized exclusive
mutation gate. Nested access/retirement reentry cannot free backing storage
until the callback returns, and the gate is reset even on throw. The
callback must not export interior references; source-level lifetime checking
and restrictions on arbitrary guest callbacks are not yet integrated.

## Lifetime pins and cancellation

`pin()` reserves one of 64 preallocated pin slots, returning a generation-
tagged primitive ticket. `releasePin(ticket)` validates the type tag,
owner domain, live slot and exact generation before releasing one pin. A
slot is never reused at exhausted generation. A pin is **not** mutable-state
authority; it is an explicit lifetime hold that trusted scheduling / FFI
adapters must retain through physical completion.

`beginRetire()` monotonically denies new access and pin admission.
`tryClose()` refuses to reclaim state while a callback is active or the
pin count is nonzero. Cancellation requests alone do not release pins.
A trusted task/IO/FFI adapter must fence new producers, await physical
termination/completion acknowledgements, and only then release each ticket.
The host primitive cannot independently authenticate those acknowledgements,
prevent malicious native pointers from escaping or prove arbitrary Java
object lifetimes.

This region does **not** provide concurrent read leases or general actor
state access semantics. Synchronized scalar access is correct for the tested
host API but is not a hard-real-time or non-parking guarantee.

## Optional physical-turn integration

`ActorPhysicalTurnGate.dispatchPinned(region, task, submit, reject)` is a
package-internal, **opt-in** bridge, not yet wired into every actor, source
Task, I/O completion or FFI adapter. It acquires a lifetime pin BEFORE
submitting/queueing a turn. Rejected/closed/queued turns release exactly once;
a started physical turn retains its pin until the Runnable returns through
its synchronous TurnExecutor boundary. The separate per-ticket start claim
serializes with `beginRetire()`: a queued task cannot enter guest execution
if STOP won that admission race, even though it acquired a pin earlier.

Adversarial path: an executor may start an asynchronous physical turn and THEN
throw from its own submission callback. The submitting thread must **not**
release the started turn's pin; the actual task completion owns release.
The adapter records start separately from exactly-once completion and tests
this race. A rejected but already queued runnable is inert on late replay.

The bridge's host wrapper allocates Runnable/Pending/AtomicBoolean objects;
zero per-mutation allocations apply to primitive region methods only, NOT
per-dispatch scheduler cost. It does not establish cross-carrier guest
execution provenance, hard-real-time nonparking scheduling, or authoritative
source compiler borrow checks.

## Production native-I/O-to-actor-reclamation bridge

The existing `OresContext` startup path installs
`AsyncNativeIo.actorPhysicallyRetired` as the ActorRuntime native-I/O
retirement gate and forwards last-loan completion to
`ActorRuntime.onNativeIoLoanRetired`. ActorRuntime checks the gate **before**
it closes the real private `ActorMemorySlice`. This is distinct from the
opt-in `ReusableLongStateRegion` and is the current production route for
I/O physical completion receipts.

The October 30 audit hardened `AsyncNativeIo.admit` in two places:

1. A submitter must atomically change an operation's state from
   `ADMITTED` to `CANCELLED_BEFORE_START` before refunding that actor's
   outstanding I/O count. A worker that already won `RUNNING` exclusively
   owns the final completion notification even if `execute()` subsequently
   throws. A stale dequeued operation whose admission was cancelled is inert.
2. The rejected Future and the last-actor-loan notification are published
   only **after** leaving the AsyncNativeIo admission monitor. A final loan
   refund now wakes the actor's already-stopping finalization gate; a failed
   Future observer cannot suppress that wakeup.

A new `AsyncNativeIoTest` adversarial JUnit scenario exercises an actual
private actor with one native I/O worker and one queued work slot: the third
request is rejected by the real executor, while two remaining physical
operations must keep the actor's state alive through STOP until they finish.

The source-level actor/critical-state compiler and all alternative native I/O
adapters still need independently executed source and runtime proofs. A pin
is never permission to mutate state after STOP. There is no claim that the
JVM never garbage collects or that the full actor dispatch path allocates
zero bytes.

## Evidence/limits

- Local JDK 21, exact Git-blob-equivalent `ReusableLongStateRegion.java`
  and exact smoke bytes, both compiled and executed successfully.
- 100000 measured `write` calls: 0–208 thread-allocated bytes observed
  across separate local executions; 100000 measured `addExact` calls:
  0 thread-allocated bytes observed. These readings apply only to warmed-up
  primitive-call loops; no full-VM GC or p99.9 proof follows.
- Additional local host harness: 27 assertions, four threads performing
  80000 checked additions; independent pin audit: 18 assertions including
  pin budget, stale/foreign/duplicate release, and concurrent drain.
- The repository's full Maven/JUnit suite, Graal Native Image, compiled guest
  execution and source-level borrow/suspension analysis remain unverified.
  Source-org Actions fail with zero steps; that means NOT_EXECUTED, not a
  test failure.
- A temporary private carrier that previously ran diagnostic checks lacked
  authenticated source-export policy approval. It is **not an approved CI
  evidence route** (security issue #2307). All subsequent private-source
  copying to alternate organizations is blocked pending source security gates.
  Source-free canaries may establish runner capacity but not guest parity.

## Promotion requirements

Compiler-generated ownership/drop/loan proofs; real scheduler task and
native FFI completion integration; exact-head Maven/JDK25 and Native Image
execution, after source-org or an approved private-carrier security review;
quantitative JFR/ThreadMXBean allocation metrics; contention latency;
source-level negative tests; and five-backend conformance receipts.


## Exact-source local JDK21 verification (2026-10-31)

A standalone, package-scoped harness is checked in at
`tests/ExactSourcePhysicalTurnSmoke.java`. It compiles directly against the
**actual** `ActorPhysicalTurnGate` and `ReusableLongStateRegion` Java
implementations, not translated source-free models. It tests post-STOP queued
entry denial, physical completion after executor submission failure, queued
rejection while a different task remains running, stale replay, replay of an
already-admitted physical turn, and 2,000 start-versus-retirement races.

Reproduction from the source repository root with JDK21:

```sh
mkdir -p /tmp/ores-physical-turn-test
javac --release 21 -Xlint:all -Werror \
  -d /tmp/ores-physical-turn-test \
  src/main/java/dev/oreslang/runtime/ReusableLongStateRegion.java \
  src/main/java/dev/oreslang/runtime/ActorPhysicalTurnGate.java \
  tests/ExactSourcePhysicalTurnSmoke.java
java -ea -cp /tmp/ores-physical-turn-test \
  dev.oreslang.runtime.ExactSourcePhysicalTurnSmoke
```

Locally executed on JDK 21, the final exact Git blobs were:
- `ReusableLongStateRegion.java`: `25a38bc89a651fcf6b8374a1713d3489ffc42826`
- `ActorPhysicalTurnGate.java`: `58ff6e255d725f9f28ad9535fabd41cef69b90ff`
- `ExactSourcePhysicalTurnSmoke.java`: `403bbb0994d3a3b462e29bbfac6071704b2a050e`

All three blobs matched GitHub; local `javac` succeeded and the test
reported `EXACT_SOURCE_JDK21_PASS assertions=8022 racing_rounds=2000`.
The duplicate accepted-turn replay check found an idempotency gap in the
physical gate; this was corrected before the final run.

The restricted source-org workflow
`.github/workflows/actor-state-region-physical-turn.yml` is configured to
checkout the source repository's exact PR head with a read-only token and
execute this smoke. Hosted run admission is still separately required.
Neither the local smoke nor that workflow constitutes full GraalVM/Native
Image, Maven/JUnit, complete actor scheduling or compiler guest parity.



## Dual physical owner join regression — 2026-10-31

`AsyncNativeIoTest.privateActorFinalizationRequiresBothPhysicalCarrierAndNativeIoRetirement`
is a **production ActorRuntime integration regression**, distinct from the
dependency-free `ExactSourcePhysicalTurnSmoke` and from a source-free model.
It uses a real trusted private actor, the real `AsyncNativeIo` actor-id
retirement gate, and a `TurnExecutor` that holds the physical carrier after
guest work until a host release signal.

The test covers both independent completion orders:
1. Native I/O worker finishes and sends the exact final-loan callback
   **before** the physical carrier exits.
2. Physical carrier exits while the native worker is still running,
   and the worker completes **afterward**.

Actor DONE must stay unsettled after STOP until the second receipt in
either order, then close the private memory slice and retire the actor.
The test explicitly waits for the actual native loan retirement callback
in the first order, rather than inferring completion from an unrelated worker
or a Future's logical cancellation.

**Status:** the integration JUnit test is checked in, not yet executed
against the complete Java runtime. Exact-source standalone Java gate smoke
passed separately on JDK21; it does not prove this combined path. Current
source-org hosted Actions still report no runner and zero steps, including
the new exact-head JDK21 workflow. Source-only checkout remains restricted to
this repository; no private cross-org mirroring is authorized.


## Reentrant STOP mutation fence — 2026-10-31 (current draft)

`ReusableLongStateRegion.update()` previously allowed a callback to request
`beginRetire()` or `tryClose()` while the reentrant Java monitor was held,
then commit the pre-STOP scalar write after the retirement flag was set. The
callback can legitimately request STOP, so the pending write now checks the
monotonic retirement bit **after** calculating the candidate value but
**before** committing to the backing array. A reentrant STOP fails the
mutation and leaves the logical region retired; physical reclamation still
requires all outstanding Task/FFI lifetime pins to finish.

`ReusableLongStateRegionTest` now expects STOP inside a callback to veto
the write. A second JUnit regression asserts STOP during the mutation never
releases a physical pin. `tests/ExactSourcePhysicalTurnSmoke.java` includes
the same case. Separately, the `AsyncNativeIoTest` dual-owner test now marks
the **exact physical carrier thread** executing the native-I/O-admitting
guest turn using ThreadLocal identity, avoiding a global next-turn gate that
could consume the wrong carrier.

**Evidence boundary:** these newest source/test revisions are **authored
but not yet executed**. The earlier JDK21 8,022-assertion exact-source
smoke applied to earlier Git blobs and must not be attributed to the
current `ReusableLongStateRegion.java` or smoke-test blob. The latest
source-org PR run has no assigned runner and zero executed steps. Keep draft.


## Current-blob JDK21 scalar region execution — 2026-10-31

A dedicated standard-library-only regression is now committed at
`tests/CurrentRegionSafetySmoke.java` (Git blob
`dfcbbf2085d86fd5e5b22714ec4971d95b09e547`), and the source-repository
workflow compiles/runs it alongside the existing physical-turn smoke.

Locally reproduced the **exact current production**
`ReusableLongStateRegion.java` blob
`ec4fbc13ce520b4886fc44fef3688b9146f5aa49` and compiled it with
JDK 21 `javac --release 21 -Xlint:all -Werror`.
The exact committed regression independently compiled and executed:
`EXACT_CURRENT_REGION_JDK21_PASS checks=130000 iterations=10000`.
The same exact class and harness also passed JDK21 `java -Xint -ea` and `java -Xcomp -ea`, each repeating all 130,000 checks; this tests JVM interpreter and eagerly compiled execution modes, not GraalVM Native Image.
Every iteration verifies reentrant STOP aborting the write, physical pin
retention, foreign/stale tickets, denied read/pin after STOP, reclamation
only after the final pin, and overflow transactional rollback.

This is actual source-byte-matching **scalar region class** execution,
not full ActorRuntime/AsyncNativeIo JUnit execution, GraalVM Native Image
or guest compiler evidence. The `ActorPhysicalTurnGate` source is unchanged
from the earlier exact-source smoke, but that older combined smoke does not
cover the current region mutation-fence blob. The new workflow is still
runner-admission-blocked, so hosted exact-head proof remains pending.


## First-worker queue-overflow admission and JDK25 integration gate

`AsyncNativeIoTest.executorQueueOverflowRefundsExactPrivateActorLoanAndPhysicalWorkStillPinsMemory`
now emits two distinct actor messages to make its precondition **deterministic**.
The first message starts an I/O worker and the host waits until
`firstEntered` is signaled; only after actual physical worker entry does
the host send the second message, which submits the bounded queued operation
and then the rejected third operation. A new assertion checks the queued
operation has not begun before the first worker exits. This avoids
the former race where scheduling all three within one actor turn could
reject the intended second operation instead of the third.

The exact source-repository workflow
`.github/workflows/actor-state-region-physical-turn.yml` now includes two
independent jobs:

- `exact-production-host`: JDK 21 source-only physical-turn and scalar
  region smoke tests.
- `integrated-actor-lifetime-junit`: pinned GraalVM 25.3/JDK 25 Maven
  tests for `AsyncNativeIoTest`, `ActorPhysicalTurnGateTest`,
  `ActorAsyncFinalizationBarrierTest` and
  `ReusableLongStateRegionTest`. The checkout verifies the exact PR
  head; `contents: read` and `persist-credentials: false` prevent
  credential persistence. No cross-org source checkout is authorized.

**Verification status:** workflow [run 41230475966](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41230475966)
discovered both jobs, but each failed **before** any runner assignment,
with zero steps. The integrated JUnit regression is **authored but not
executed** on the current head. Older JDK21 standalone local evidence
does not certify this new integration test.
