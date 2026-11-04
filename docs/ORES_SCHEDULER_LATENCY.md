# OresScheduler per-turn cost

Status: host runtime change in `OresScheduler` and `MixedInteropBridge`. No
language or Future semantics change. Not a real-time claim: on the JVM, GC,
safepoints and JIT dominate tail latency regardless of scheduler cost. The
hard-RT Java path is `oreslang-runtime.realtime-java`.

Native counterpart: `oreslang-source.llvm` `docs/NATIVE_SCHEDULER_LATENCY.md`.

## Benchmark

`src/test/java/dev/oreslang/runtime/OresSchedulerLatencyBench.java` is an
opt-in main (not a JUnit gate). It prints two JVM floors next to the scheduler:

- `floor_park`: two platform threads ping-pong via `LockSupport` park/unpark,
  i.e. one kernel sleep + wake per hop.
- `floor_spin`: the same through one volatile field (cache-line transfer).
- `sched_pingpong`: two Tasks alternately complete each other's await Future;
  measured from `completeFromRuntime` to the start of the resumed turn.
- `sched_cooperate`: N Tasks each `rt cooperate` K times; ns per turn.

```sh
mvn -o -DskipTests test-compile
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp target/classes:target/test-classes:$(cat cp.txt) \
    dev.oreslang.runtime.OresSchedulerLatencyBench
```

## Audit findings: per-turn costs that were not the guest's

1. Every turn took the **VM-wide `AWAIT_GRAPH_LOCK`** at least twice
   (`runTurn` → `detachActiveAwaitRegistration`, and `finish`), even when the
   task had no await edge. All carriers of all schedulers serialized on it.
2. Every turn incremented one **global `AtomicLong`** for its dispatch id (a
   cache line bouncing between every carrier) and boxed it into a
   `ThreadLocal<Long>`.
3. Every turn `set()` and then `remove()`d **six `ThreadLocal`s**. `remove()`
   deletes the map entry, so the next turn's `set()` probes and re-inserts it.
   In JFR this was the largest self-time frame (`ThreadLocalMap.set`).
4. Every turn allocated a fresh watchdog-overrun lambda.
5. Every turn copied the mixed-interop scope twice
   (`Map.copyOf(new LinkedHashMap<>(m))`), usually for an empty map.

## Changes

- `awaitEdgeRegistered` per task, written only under `AWAIT_GRAPH_LOCK`
  together with the graph map. Removing an edge that does not exist no longer
  takes the lock. Reading `false` is equivalent to having taken the lock before
  any concurrent registration, so cycle detection is unchanged.
- Dispatch ids come from per-thread blocks of 1024 taken from the global
  counter. They are still unique and nonzero; tests only rely on that.
- One `ThreadLocal<ThreadState>` replaces seven `ThreadLocal`s. A null field
  means exactly what an absent value meant; save/restore nesting is unchanged.
  Reads use `peek()`, which never allocates (a shared all-null stand-in), so a
  thread that only asks `OresScheduler.current()` does not get a holder.
- The watchdog-overrun callback is built once per scheduler.
- `MixedInteropBridge.open` uses `Map.copyOf` only, which returns immutable
  maps unchanged and still rejects nulls. Opening over no scope shares one
  close action. The empty map is still installed, because `invoke()` reports
  "not active" and "no linked unit" differently.

## Race fixed in FutureCombinators.map / flatMap

Faster turns exposed a race already on main. `startObservingCancellation`
assumed that registering its cancellation witness first lets it "win the
pending-source notification order". But `OresFuture.settle` makes the
terminal state visible (state CAS) *before* it runs waiters, and the
scheduler's await fast path reads that state directly. So the mapped Task
could resume, throw the source's `CancellationException`, and settle its
result as a failure before the witness cancelled it: `isCancelled()` false.
`cancelledSourceCancelsSchedulerMappedResultButNotOtherObservers` hit it
0/25 on main and 13/25 with only the scheduler changes.

Fix: the witness completes a `witnessed` Future only after cancelling the
dependent. A map/flatMap Task that sees its *source* cancelled awaits
`witnessed` instead of settling itself, so the dependent ends cancelled.
The existing `upstreamCancelled`/`taskRef` handshake (each side writes before
reading the other) guarantees one side cancels and completes `witnessed`, so
the Task cannot hang. flatMap's inner-Future failures are unchanged.
Afterwards: 0/25.

## Results

macOS 26, Apple M4 Max, OpenJDK 21.0.12, after JIT warm-up. Same bench
against `main` and this branch, two runs each (ranges are run-to-run).

| scenario | main | this branch |
|---|---:|---:|
| floor_park (kernel), mean | 3,011–3,353 | 2,889–3,115 |
| sched_pingpong, 2 carriers, p50 / mean | 583 / 980–985 | 292–333 / 551–607 |
| sched_pingpong, 8 carriers, p50 / mean | 709–791 / 1,635–1,674 | 292–333 / 860–1,000 |
| sched_cooperate, 1 carrier, ns/turn | 507–518 | 212–216 |
| sched_cooperate, 4 carriers, ns/turn | 438–491 | 383–391 |
| sched_cooperate, 8 carriers, ns/turn | 570–678 | 289–379 |

A carrier handoff now costs about a fifth of one kernel park/unpark. p99
(~19 µs) is unchanged: at that tail the JVM, not the scheduler, dominates.

## Not done / next

- The remaining shared hotspot is the JDK `ThreadPoolExecutor` queue
  (`LinkedBlockingQueue`: two locks plus a node allocation per turn, and a
  kernel park on an idle carrier). Per-carrier queues need a custom executor
  that keeps bounded admission (`AbortPolicy`), exactly `parallelism` carriers,
  and `shutdownNow`/`awaitTermination` semantics.
- `ActorTurnWatchdog` still adds and removes one lease per turn in a global
  `ConcurrentHashMap`.
