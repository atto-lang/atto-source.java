# Actor single-writer runtime verification

OresVM needs **one executing native carrier per actor**, not one carrier for the
whole VM. Different actors should run concurrently. Source-level `await`,
`select`, `rt cooperate`, mailbox continuations, shutdown cleanup and
supervision must all obey the same single-writer rule.

## Two independent witnesses

1. `ActorCell.beginTurn()/endTurn()` already enforce a lifecycle-serialized
   single-writer lease. This remains **mandatory** in every build.
2. `ActorTurnContentionProbe` is an **optional**, independent, nonblocking
   atomic CAS ownership witness. It is acquired before the lifecycle lease,
   held across guest code and cleanup, and released before the lifecycle lease.
   A second entrant fails immediately; it is **not** queued or blocked.

There is one probe per ActorCell, scoped to a runtime and discarded at actor
termination. We do not keep an unbounded global string-key mutex registry.
Actor authority is **not** installed on a carrier until both the optional
CAS witness and mandatory lifecycle lease admit a turn. After guest code and
final cleanup unwind, actor `ThreadLocal` identity is cleared **before** the
witness is released. The host-side actor-exit hook runs without guest actor
authority; lifecycle accounting still protects finalization.

`TurnExecutor` may run the actual guest turn on a thread other than the
dispatching thread. `isActorCarrierThread()` therefore marks the **executing**
carrier within the turn, restoring the thread's previous carrier marker on
normal exit, failure, or finalization. The outer dispatcher retains its own
marker only for its own dispatch work. This is tested with delegated platform
threads as well as native carriers.

Each probe records the incumbent and competing Java carrier Thread identities;
for native-pthread-backed dispatchers the Java Thread is the host carrier
representation, not an independent OS-level `pthread_self()` measurement.
`ActorRuntime.coreContentionViolationCount()` counts probe violations for the
runtime. It is not a proof that all possible forbidden accesses were observed.

## flags2env configuration

The source of truth is the repository's `.cli-flags.toml`:

| Name | Environment key | Type | Default |
| --- | --- | --- | --- |
| `--use-core-mutexes` | `ORES_USE_CORE_MUTEXES` | bool | false |

The externally resolved `flags2env` environment map is converted to the
typed `CoreRuntimeFlags` record via `fromFlags2EnvMap(Map<String,String>)`,
rejecting invalid boolean strings. Embedders pass it to the five-argument
`ActorRuntime` constructor.

Default JVM entrypoints read process environment. **The runtime does not yet
invoke the flags2env CLI/JNI parser itself**; the CLI > environment > default
precedence must be applied by the launcher for CLI overrides. The core probe
works today with `ORES_USE_CORE_MUTEXES=1` and with explicit typed settings.
Do not describe this as generated Java type code until the flags2env generator
is wired into the build.

Despite the legacy flag name, this is a **nonblocking contention assertion**,
not `OresMutex`, not an I/O operation and not a blocking function.

## Empirical and deductive verification

- Run with the flag both disabled and enabled and assert **zero diagnostic
  collisions** under parallel actors, mailbox pressure, source reload, guest
  callbacks, native carriers, cancellation, `await`, `select`, and fairness.
- Fault-inject a second entrant while a first carrier is inside guest code:
  the probe must trip and fail-stop, and carrier ThreadLocal identity must
  always clear. A deliberately broken test must fail; otherwise the guard
  provides no meaningful evidence.
- Unit-test the probe itself: contention fails promptly, independent actors
  proceed concurrently, and a released actor may resume on another carrier.
- Pair with `OwnershipChecker` negative compile tests for double mutable
  borrows, mutable-vs-shared conflicts, borrow lifetime escapes and
  use-after-move. Borrows erase at runtime; a per-actor single-writer witness
  **cannot prove the language borrow checker is sound**.
- Pair with finite-state model checks of ownership and actor scheduling.
  Model checks are exhaustive **only within their modeled bounds and
  assumptions**, not of every possible real execution.
- For FFI and guest resource graph guarantees, a separate object-granular
  read/write borrow witness and instrumentation at native entrypoints are
  required. This patch is not that instrument.

A green test run provides evidence for a tested configuration, never a
mathematical proof of the unrestricted implementation. The LLVM backend
requires a separate probe at its actual actor entry boundary; the Java
instrumentation cannot verify compiled LLVM actor threads.
