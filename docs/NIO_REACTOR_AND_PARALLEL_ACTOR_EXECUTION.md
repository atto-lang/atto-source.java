# Dedicated NIO reactors and opt-in parallel actors (Java/GraalVM)

Status: **staged implementation contract**, not a claim of feature parity or production readiness.
Native LLVM companion: `oreslang-source.llvm/docs/NIO_REACTOR_AND_PARALLEL_ACTOR_EXECUTION.md`.

## New guest std/net migration slice (October 17, 2026)

The guest-facing `network.listen_nonblocking("127.0.0.1", ...)` entry point
now constructs `VmNioTcpListener`, which owns only a caller-context-scoped
IPv4 socket and borrows the already-started VM's `NioReadinessGroup`.
The `net_core.accept/read/write/close` guest facade dispatches through the
adapter's `Connection`, not `NativeNioFutureReactor`. Multiple listeners
therefore share the VM readiness selector without a per-listener selector
thread. The older `NativeNioFutureReactor` remains for legacy host tests
and is not a permitted fallback in the new guest path.

This initial migration keeps conservative one-operation-per-channel leases:
accepted connections are supervisor-owned, cannot be transferred to an actor,
and accept/read/write Future cancellations arbitrate against kernel work.
Nonblocking reads and writes are capped at 64 KiB. Writes snapshot caller
data and support short kernel writes; TCP reads return a possibly short
byte-array and an empty array on EOF. Each listener enforces its own bounded
`maxPending` operation budget, including accept/read/write and cancelled
Future notification drains, with explicit capacity denial. Internal producer
cancellation notifications cannot settle an exported Future before its
cancellation CAS. Permission checks and context teardown still gate listener
and connection ownership. The full `OresContext` now binds terminal
notifications to its already-budgeted PRIVATE actor-dispatcher carrier domain
before the first readiness watch. Pending I/O alone still starts no actor
carrier. When the first terminal notification becomes runnable, one short-lived
lazy NIO bootstrap submitter may start solely to submit that first delivery
through the cold bound executor off the SELECTOR stack. In full OresContext the
delivery itself executes on the bound actor carrier, not the helper, which then
retires after handoff. Slow synchronous Future subscribers remain off SELECTOR
and CONTROL and execute on the bound actor carrier. The standalone
`OresVM`/`NioReadinessGroup` host API still retains its transitional lazy
0..2 completion pool for compatibility.

The `OresVM` substrate now cold-starts with **zero VM-owned threads**:
zero readiness reactors, zero CONTROL carriers, and zero completion carriers.
The first accepted readiness watch lazily starts reactor #0. The enclosing
`OresContext` also constructs ActorRuntime/AsyncRuntime/GC scheduler objects,
but the staged native affinity lanes now allocate only bounded metadata during
construction: they create no pthread on runtime construction or lane
reservation, and first runnable work starts only the selected lane's bounded
carrier set. Async virtual-thread executors, source-deadline timers, GC sweep
timers and the actor watchdog likewise create workers only after their
corresponding work is submitted. This is an Ores-owned-thread accounting
statement; JVM/GraalVM/JIT/GC/embedder implementation threads are outside it.
An exact full-context GraalVM/Native Image run remains a release gate.

Tests staged on the Java branch include `VmNioTcpListenerTest` for two
listeners sharing a selector, echo, cancellation, ownership and closed
connections, and the existing compiled-source `NativeNetStdlibTest` echo.
**These new guest/runtime changes have not been validated by an executing
JDK25/GraalVM full-tree CI run.** Keep PR #1100 draft until exact-head guest
tests, partial-write/cancel/STOP races, and thread accounting are verified.

The historical October 16 notes below describe the earlier staged state;
in particular, statements that guest `std/net` still uses a per-listener
selector are superseded by this section.

## October 17 follow-up: bounded VM carrier admission and shutdown fence

- Java full-context NIO now uses scheduler-targeted host notification delivery:
  `NioReadinessGroup.bindVmCompletionExecutor()` is a one-shot
  pre-admission binding to `ActorRuntime.executeHostIoCompletion()`. The
  actor dispatcher is already part of runtime carrier accounting. A cold
  bound executor is bootstrapped only after a terminal notification is
  runnable, using one short-lived lazy submitter so native/JVM carrier startup
  cannot block the selector. It submits the first real delivery into
  ActorRuntime; that delivery executes on the actor carrier, and the helper
  retires after successful handoff. Pending I/O alone
  does not start it. Notification reservations are bounded. Under a full actor
  ready queue, deliveries remain staged until an actor turn or host completion
  retires.
- `OresContext.close()` now closes and drains the VM NIO readiness domain
  **before** calling `ActorRuntime.close()`; otherwise a bound notification
  might wait forever on workers already shut down.
- The optional `-Dores.runtime.carriers.global.max=N` property accepts
  4..256. It enforces a static upper bound over ordinary CONTROL + actor
  carriers: CONTROL reserve=1, and each of PRIVATE, SHARED, UNTRUSTED gets
  `floor((N-1)/3)`. This includes native-affinity lane capacity and caps
  Java non-affine growth. CONTROL can therefore still start while every
  actor-domain quota is occupied.
- This is **not** a dynamic, work-conserving global permit scheduler: idle
  domain quotas cannot yet be borrowed. By default (property absent), legacy
  independent per-domain limits remain. Reactors and explicit user-owned
  `OresScheduler(N)` threads are excluded from this execution-carrier cap.
- A direct byte-identical `OresCarrierBudget` source blob was compiled
  locally with JDK 21, and 261 positive/negative budget smoke cases passed.
  Source-repository JDK25/Graal CI still cannot execute runner steps; the
  complete new runtime integration and NIO tests remain unqualified.

## October 17 follow-up: warm-lane host completion placement

The full-context bound NIO path first uses a one-shot **off-reactor
submission helper** to bootstrap a cold actor carrier without waiting for
native/JNI carrier publication on the selector. The helper never executes a
Future subscriber; it only submits the already-reserved completion record.
It is a transitional runtime-owned helper thread, not the canonical
zero-extra-thread implementation.

After first successful bootstrap, NIO terminal notifications that share an
`ActorAffinityDispatcher` now prefer **already-started** actor lanes, choosing
the least loaded warm lane by host-only `active + queued` diagnostics and
round-robin tie breaking. This prevents later selector callbacks from
synchronously cold-starting a different JNI/pthread lane and avoids parking
all notifications behind one busy warm lane if another warm lane is idle.
When no lane is started, host submission deterministically chooses lane 0;
the integrated bootstrap helper handles its initial native startup off the
selector. This changes neither the actor's reserved lane nor the physical
single-flight invariant.

The production `ActorAffinityDispatcher.java` Git blob
`22a21ba29849891aafb855f819ff7f573dae4484` was reproduced byte-for-byte,
compiled with JDK 21 strict lint and exercised locally through 50 warm-lane
rounds plus 50 slow-subscriber saturation rounds (3,550 host assertions).
The JDK 25 checked-in JUnit tests for warm-lane reuse, deterministic cold
placement and idle-warm-lane preference are new and **not yet executed** on
an authorized full source-tree runner.

**Outstanding safety boundaries:** a slow/untrusted synchronous host
callback can still monopolize a PRIVATE actor carrier when all warm lanes
are busy. Thread scarcity and inline host `Executor` behavior cannot be
solved purely by lane selection. Canonical completion delivery needs typed
scheduler-only records and a nonblocking executor admission API, with
arbitrary host callbacks isolated behind an explicitly supplied bounded host
executor (or a separately budgeted execution domain). The transient
bootstrap submitter also needs elimination before claiming no additional
runtime-owned physical threads under NIO activity. Reactor-selector and
CONTROL callbacks must never run guest code.

## October 17 hardening: no-bootstrap admission and termination fence

The fully published Java native startup state and the visibility of an
individual attached pthread are different facts. JNI publishes each
`carrierThreads[slot]` while `nativeStart()` is still waiting under
`nativeLifecycleLock` for the entire requested carrier set. Using
`getStartedCarrierCount() > 0` to select a warm lane could therefore park an
NIO selector behind native bootstrap.

- `NativeCarrierExecutor.readyForNoBootstrapAdmission()` observes the final
  volatile `nativeStarted` publication and shutdown state, not a thread census.
  `executeWithoutBootstrap()` uses a try-lock over bounded queue admission,
  never acquires the JNI startup lock, and rejects cold, retiring, contended,
  or full carriers without allocating pthreads.
- Enqueue and `shutdownNow()` queue drain use the same short admission lock:
  a runnable cannot slip into a queue after it was drained, and no rollback
  using user-defined `Runnable.equals()` is needed.
- `ActorAffinityDispatcher` uses this no-bootstrap method for warm native
  host notifications; a selector with *no* fully started warm lane fails
  closed and retains its exact bounded notification in the readiness manager.
  Its first cold start remains the responsibility of the off-selector helper.
  Even an already-warm generic `ExecutorService`/`ThreadPoolExecutor` is
  rejected on a selector: only built-in native `executeWithoutBootstrap`
  and adaptive JVM `executeWithoutCarrierGrowth` have a verifiable
  no-growth admission contract. The generic host executor can still accept
  that staged work when retried from a trusted external thread.
- JVM `AdaptiveCarrierExecutor.executeWithoutCarrierGrowth()` likewise
  tries its admission lock rather than parking on another worker's startup.
  It forbids `ThreadFactory` growth for these notifications and returns a
  bounded rejection when there is no already-live worker.
- `NativeCarrierExecutor.isTerminated()/awaitTermination()` now wait for
  shutdown **and** all outstanding native-start admissions. A temporarily
  empty `carrierThreads` array while pthread attachment is in flight is
  not proof of quiescence.

Additional checked-in tests cover cold and partially published JNI bootstrap,
native/JVM contended admission locks, native queue saturation, distinct
value-equal Runnable shutdown drains, cold-pool termination observation,
selector-side cold-lane rejection followed by trusted-host retry, and 48
host NIO completions reusing one warm native pthread across three lanes.

**Scope:** queue admission still uses ArrayBlockingQueue's short internal
lock; this is a bounded no-bootstrap/no-monitor-wait submission contract, not
a wait-free kernel-ready reactor or an RT/WCET guarantee. The transitional
one-shot bootstrap submitter remains. New JUnit/JNI tests require executable
source-head CI and are not qualified by older funded component runs.

## Implementation status on this draft branch (October 16)

- `AdaptiveCarrierExecutor` now supports a zero-resident minimum. Normal `OresVM` throughput startup uses CONTROL min=0/max=20; the first independently runnable CONTROL turn creates a carrier. A host may explicitly configure a nonzero warm minimum, which is prestarted before admission.
- The Java-only fallback actor dispatcher can use `AdaptiveCarrierExecutor` when `ores.actor.affinity=none`, honoring `ores.actor.dispatch.max.parallelism`. Pinned/soft-affine lanes retain fixed placement/capacity, but native lane executors now start with **zero pthreads** and create their bounded carrier set on first runnable work. In the default one-carrier-per-lane layout, activating one lane starts one pthread while unused lanes remain cold. A non-affine native pool still creates its bounded maximum together on first start; per-carrier native adaptive growth remains future work. A carrier pool cap is not an actor's per-actor physical limit.
- The experimental loopback TCP `NativeNioFutureReactor` from #790 has been forward-ported along with `std/net`, evaluator/typechecker entries, `OresContext` capability/owner checks, and guest/JUnit tests. It still creates a **dedicated reactor for each listener**, capped at four listeners per context. It does not yet consume the shared selector manager.
- `NioReadinessGroup` is the VM-owned generic selectable-channel substrate. It now constructs with zero reactor threads and lazily starts reactor #0 on the first accepted watch, with an optional four-reactor upper bound. The staged `VmNioTcpListener` path uses this shared manager; older `NativeNioFutureReactor` compatibility paths still require consolidation/removal.
- Reactor work quanta are capped (128 commands/readiness events per turn); listener close quiescence is published before guest close Future completion. Arbitrary `OresFuture` subscriber work and buffer conversion on a poller need a stricter no-guest/no-slow-callback audit.
- `NioOnlyPolicy` introduces `ores.runtime.io.mode=nio-only`. This profile rejects `AsyncNativeIo` legacy offload and `NativeHttp.listen` before work begins; the current default remains `legacy-compat` to keep preexisting FS/HTTP tests running until genuinely nonblocking replacements exist. **Default NIO-only parity is not implemented**; no claim that Java blocking I/O is entirely eliminated.
- Actor physical credits remain **host-only**; `ActorRuntime.ActorCell` still single-flight and opt-in max=3 source actor execution is not available.
- New JUnit tests target bounded worker expansion, NIO-only denial, source `std/net`, the readiness group and TCP reactor. The exact-source readiness-group smoke (minimal Future stub) passed locally; the complete JUnit/Graal suite has not run on an executing CI runner.

## Findings from Java main (2026-10-16)

- The staged `OresVM` branch now defaults to zero resident CONTROL carriers and zero readiness threads. The root scheduler admits work to CONTROL (or an explicitly supplied root admission executor), creating carriers only for runnable demand; the first accepted NIO watch separately starts reactor #0. ActorRuntime native affinity executors also remain at zero physical carriers through construction and lane reservation; first runnable DATA work starts the selected lane. Full-context exact-head Graal integration is still required before treating this as a production startup guarantee.
- `ActorRuntime.ActorCell` permits one physical carrier, one active turn and one shared mutable `activeMail`/source continuation slot. `sourceScheduler()` uses a mailbox-backed single-flight scheduler. Merely changing a thread-count setting cannot make this concurrent.
- `newActorDispatcher` constructs fixed-size lane executors or a fixed-core JVM fallback. Adaptive 2-to-20 growth has **not** been implemented; changing `ores.actor.affinity=lane` also requires a lane-aware growth algorithm.
- The legacy `AsyncNativeIo` uses offloaded blocking socket operations and `NativeHttp` uses the JDK HTTP server and streams. These **do not qualify** for the new strict NIO-only runtime policy. They must be replaced, disabled in a strict-NIO profile, or reported explicitly as legacy incompatibilities; virtual-thread offloading is not an acceptable substitute.
- Draft Java PR #790 provides an experimental `NativeNioFutureReactor`: a dedicated selector for a loopback listener, nonblocking channels, bounded in-flight futures, and scheduler-only guest continuation handoff. It does **not** implement a process-wide adaptive 1–4-reactor manager, actor-transferable socket capabilities or production cancellation semantics.

## Required execution topology

```text
              VM CONTROL pool (min 0, max 20)
                     root tasks / supervisors / mailmen
                                ^
                                | task-specific continuation
     kernel readiness           |
            |                   |                         independent actor pools
    NIO manager (min 0)         +------------------------> PRIVATE / SHARED / UNTRUSTED
      reactors 0..4                                        target min 0 per domain
            |                                                       ^
            +--> transitional completion (0..2) -> Future -> scheduler
                canonical target: direct bounded record handoff, 0 workers
```

- A reactor waits in `Selector.select()`, performs bounded nonblocking socket operations, and publishes immutable completion records. It cannot execute guest closures, host stream conversions that invoke arbitrary code, or unbounded `OresFuture` subscriber chains.
- A producer registers its request without blocking a CONTROL or actor carrier. `await` suspends a logical task and releases the carrier. Completing five pending reads does not start five OS threads; only *independent runnable turns* justify worker expansion.
- A manager initially owns zero reactor threads, default max four. The first valid watch starts reactor #0. Add further reactors only on sustained readiness-processing utilization/queue latency with idle CPU, not because `select()` is sleeping or because many descriptors are registered. Channel migration requires a separate generation-fenced protocol.
- Pool growth is independently driven by runnable demand. Exhaustion must have bounded queue admission, backpressure and explicit rejection. A Java `ThreadPoolExecutor` with a large `ArrayBlockingQueue` normally does not expand beyond its core count until the queue fills; customize admission instead of assuming it behaves as desired.
- Keep I/O waiting isolated. No `Future.get()`, `join()`, blocking `Socket` streams, blocking I/O FFI, `AsynchronousFileChannel` provider thread offloads, or hidden blocking fallback on actor/CONTROL carriers or in a strict NIO-only backend.

## Opt-in physical parallelism per actor

Proposed canonical per-actor policy name: **`maxActiveTurns`**, default 1; initial supported upper bound 3. The spelling and source lowering must be decided by the compiler/language contract; this document does not create new guest syntax.

Three limits are independent:

1. Number of logically in-flight messages in one actor (including suspended handlers).
2. Number of physically executing turns on the *same* actor, `maxActiveTurns` (1..3).
3. ActorGroup-wide physical turn capacity, `maxParallelTurns`, across all members.

`ActorPhysicalTurnCredits` is a **host-only, nonparking preliminary credit gate** with min=1/max=3, single-attempt CAS, idempotent permits and close-fencing. **It is not wired to ActorRuntime.** A value of three must NOT be exposed to source actors until the following blockers are resolved:

- Per-message/turn execution frames must replace actor-wide `activeMail`, `sourceReceiveSuspended`, `sourceHandoffRequested`, watchdog, quotas, and continuation mutable fields.
- Guest source state must have independent per-turn lexical stacks, compiler-enforced Sendable/isolated message ownership, and no shared mutable alias through a captured receiver, class fields or closures.
- A fixed-key actor `state { ... }` map may be concurrently **read** only under certified read loans; write access requires an exclusive `critical` loan. No loan may cross `await`, `select`, cancellation, or suspension. Contended admission must suspend/requeue, not block a carrier.
- Graal/Truffle Context and actor heap integration must explicitly support simultaneous access, or use independent safe execution contexts and a transferable serializable actor state protocol. Do not assume one Truffle context is safe for three concurrent guest executions.
- CONTROL/lifecycle/failure handling needs exclusive barriers: stop, panic, finalizers and immutable code-revision swaps must fence new turns and quiesce every active one. Preserve message ordering where promised; no callback may run after generation retirement.
- Parallel execution is opt-in. Existing serial actors, untrusted actors, and unsupported isolation backends stay max=1 until proven otherwise. No fallback that silently executes with unsafe ownership or bypasses actor state rights.

## Tests and release gates

- Startup: zero OresVM readiness reactors, zero resident CONTROL/completion carriers, and zero native actor-lane pthreads through runtime construction/lane reservation. Invalid I/O admission stays at zero; the first valid watch starts exactly one reactor. First runnable CONTROL starts CONTROL only; first runnable actor DATA starts its selected lane only. Five and 10,000 outstanding idle sockets, many actors, and many ActorGroups must not cause linear thread creation. Exact full-OresContext/GraalVM/Native Image thread census remains a release gate.
- Reactor: concurrent socket accept/read/write, partial writes, EOF/half-close, cancel vs completion vs close, fd reuse/generation fencing, reactor saturation, fairness, queue-full backpressure, idle/active reactor growth and shutdown.
- Future: producer callback runs on reactor only for bounded host processing; Oreslang resumption executes on owning scheduler; no inline reentrancy and no guest code on reactor.
- Pool: correct dynamic thread 0→1→N growth under independent runnable demand; fixed bounds and idle retirement; no growth merely because 1,000 reads are pending. Separate CONTROL progress under saturated PRIVATE/UNTRUSTED pools.
- Actor: max=1 remains physically serialized; opted-in max=3 runs three *independent* DATA turns simultaneously, never four; lexical state independence, shared immutable reads, contended critical write, cancellation/panic during three turns, per-turn watchdog quotas, actor group aggregate caps.
- Native Image/JDK25 guest end-to-end and LLVM parity, including sanitizer and source-level execution. Do not transfer evidence from old commits or standalone host tests to the complete runtime.

## Hard real-time and native parity

The LLVM backend must remain zero tracing-GC and use bounded preallocated operations, generation-tagged completion records, actor-owned arenas, and deterministic cleanup. There is no honest hard-real-time guarantee for arbitrary general-purpose OS scheduler/allocator/kernel paths. A dedicated RT profile should preallocate reactors, carriers, sockets/queues/arenas where practical and reject unbounded actions, contrasting with the default throughput profile's adaptive startup and pool growth.

### Relevant existing work

Java: PR #790 experimental NIO TCP; #519 single-flight ROOT and supervisor domains; #1045 ActorGroup-wide capacity (not actor-local); main `docs/NATIVE_NETWORK_IO_AND_TRACING.md`.
LLVM: merged PR #280 nonblocking Linux socket/epoll **substrate only**; merged #204 lazy host pthreads/zero-GC startup; open #463 host-level shared-domain arena leases. None currently proves end-to-end 3-carrier source actor execution.
