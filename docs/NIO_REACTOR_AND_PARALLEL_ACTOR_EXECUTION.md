# Dedicated NIO reactors and opt-in parallel actors (Java/GraalVM)

Status: **staged implementation contract**, not a claim of feature parity or production readiness.
Native LLVM companion: `oreslang-source.llvm/docs/NIO_REACTOR_AND_PARALLEL_ACTOR_EXECUTION.md`.

## Findings from Java main (2026-10-16)

- `OresVM` prestarts 2–4 CONTROL carriers by default; the root scheduler admits work to CONTROL (or an explicitly supplied root admission executor). These are separate from PRIVATE, SHARED and UNTRUSTED actor dispatchers.
- `ActorRuntime.ActorCell` permits one physical carrier, one active turn and one shared mutable `activeMail`/source continuation slot. `sourceScheduler()` uses a mailbox-backed single-flight scheduler. Merely changing a thread-count setting cannot make this concurrent.
- `newActorDispatcher` constructs fixed-size lane executors or a fixed-core JVM fallback. Adaptive 2-to-20 growth has **not** been implemented; changing `ores.actor.affinity=lane` also requires a lane-aware growth algorithm.
- The legacy `AsyncNativeIo` uses offloaded blocking socket operations and `NativeHttp` uses the JDK HTTP server and streams. These **do not qualify** for the new strict NIO-only runtime policy. They must be replaced, disabled in a strict-NIO profile, or reported explicitly as legacy incompatibilities; virtual-thread offloading is not an acceptable substitute.
- Draft Java PR #790 provides an experimental `NativeNioFutureReactor`: a dedicated selector for a loopback listener, nonblocking channels, bounded in-flight futures, and scheduler-only guest continuation handoff. It does **not** implement a process-wide adaptive 1–4-reactor manager, actor-transferable socket capabilities or production cancellation semantics.

## Required execution topology

```text
              VM CONTROL pool (min 2, max 20)
                     root tasks / supervisors / mailmen
                                ^
                                | task-specific continuation
     kernel readiness           |
            |                   |                         independent actor pools
    NIO manager (min 1)         +------------------------> PRIVATE / SHARED / UNTRUSTED
      reactors <= 4                                       min 0 max 20 per domain
            |                                                       ^
            +--> bounded completion --> owning Future --> scheduler+
```

- A reactor waits in `Selector.select()`, performs bounded nonblocking socket operations, and publishes immutable completion records. It cannot execute guest closures, host stream conversions that invoke arbitrary code, or unbounded `OresFuture` subscriber chains.
- A producer registers its request without blocking a CONTROL or actor carrier. `await` suspends a logical task and releases the carrier. Completing five pending reads does not start five OS threads; only *independent runnable turns* justify worker expansion.
- A manager initially owns one reactor, default max four. Add reactors only on sustained readiness-processing utilization/queue latency with idle CPU, not because `select()` is sleeping. New channels can be assigned to a less-loaded reactor. Channel migration requires a separate generation-fenced protocol.
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

- Startup: baseline CONTROL carriers present; no actor carriers until work; one NIO reactor initialized. Five and 10,000 outstanding idle sockets must not cause linear thread creation.
- Reactor: concurrent socket accept/read/write, partial writes, EOF/half-close, cancel vs completion vs close, fd reuse/generation fencing, reactor saturation, fairness, queue-full backpressure, idle/active reactor growth and shutdown.
- Future: producer callback runs on reactor only for bounded host processing; Oreslang resumption executes on owning scheduler; no inline reentrancy and no guest code on reactor.
- Pool: correct dynamic thread 2→3→20 growth under independent runnable demand; fixed bounds and idle retirement; no growth merely because 1,000 reads are pending. Separate CONTROL progress under saturated PRIVATE/UNTRUSTED pools.
- Actor: max=1 remains physically serialized; opted-in max=3 runs three *independent* DATA turns simultaneously, never four; lexical state independence, shared immutable reads, contended critical write, cancellation/panic during three turns, per-turn watchdog quotas, actor group aggregate caps.
- Native Image/JDK25 guest end-to-end and LLVM parity, including sanitizer and source-level execution. Do not transfer evidence from old commits or standalone host tests to the complete runtime.

## Hard real-time and native parity

The LLVM backend must remain zero tracing-GC and use bounded preallocated operations, generation-tagged completion records, actor-owned arenas, and deterministic cleanup. There is no honest hard-real-time guarantee for arbitrary general-purpose OS scheduler/allocator/kernel paths. A dedicated RT profile should preallocate reactors, carriers, sockets/queues/arenas where practical and reject unbounded actions, contrasting with the default throughput profile's adaptive startup and pool growth.

### Relevant existing work

Java: PR #790 experimental NIO TCP; #519 single-flight ROOT and supervisor domains; #1045 ActorGroup-wide capacity (not actor-local); main `docs/NATIVE_NETWORK_IO_AND_TRACING.md`.
LLVM: merged PR #280 nonblocking Linux socket/epoll **substrate only**; merged #204 lazy host pthreads/zero-GC startup; open #463 host-level shared-domain arena leases. None currently proves end-to-end 3-carrier source actor execution.
