# Gate A: scalar guest Tasks, proof provenance and compiler-frame cleanup

Status: **draft hardening**, stacked on Java `feat/guest-scalar-task-await-20261019`
([#1484](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1484)).
This document is not a claim of tested guest execution or general Task support.

## Narrow, implemented changes

1. The `await task { return scalar; }` frontend now requires the same
   compiler-resolved `ThreadSafetyTraits.SEND` check used by the scoped
   Task proof kernel, after collapsing a string-literal value to owned
   `string`. Only resolved `int`, `bool`, `float`, and `string` values
   may proceed. The JVM frame separately copies the scalar and checks its
   exact host class. No nominal proof callback is permitted.
2. A readonly structural `record` *view* does **not** gain `Sync` merely
   from its view flag. A writable alias can mutate the backing value while
   another Task holds a shared reference. Deny `Sync` until the compiler
   proves immutable/frozen allocation identity. Owned `Send` is retained
   only when all fields are `Send`. This copies the safety decision tracked
   independently in Java #1467; reconcile rather than overwrite during merge.
3. A compiler-frame submission rejected before scheduler registration has
   no live guest Task to retire. Its supplied resource/compiled-code release
   callback is now invoked **after** the `OresTaskScope` monitor is released.
   Preserve the original rejection and attach release failures as suppressed.
   Source and test regressions verify scope rejection and closed-scheduler
   rejection both release exactly once outside the monitor.

## Explicit nonclaims

- The successful dispatch path still invokes `OresScheduler.start` under
  `OresTaskScope`'s monitor. A same-thread inline executor and callbacks
  require a separate *provisional child admission* and cancellation/join
  protocol to avoid executing callbacks under locks without introducing
  untracked in-flight children. Do not lift host-only scheduler gates yet.
- The root main scalar Task child is context-owned. It is **not** linked to
  root Task cancellation; cancellation currently does not propagate into
  a general Task ancestry tree. Context teardown must still join physical
  quiescence before resources/code are released.
- No guest-level scoped borrows, `Arc`/`Weak`, child `get_done`, actor
  inheritance, detached Task, nested Task or LLVM continuation lowering
  is enabled by this patch.
- A generic `readonly record` is not automatically frozen, `Sendable`,
  deep-shareable or translatable across an actor boundary.
- No assertion of zero-GC Java or general zero-GC LLVM guest execution.

## Required exact-head evidence

Run on **this stacked PR head** under Java 21+ with the repository's
normal GraalVM dependencies:

```sh
mvn -B -ntp -Dores.runtime.carriers=java \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dtest=TaskTraitAdmissionHardeningTest,OresTaskScopeFrameTest,GuestScalarTaskAwaitTest,TaskBlockSyntaxTest,TaskSpawnSendAdmissionTest,ScopedTaskTypeBridgeTest,OresScalarTaskFrameTest test
```

Also run a full Maven/guest test suite, cancellation-while-running tests,
two-carrier continuation migration and stress race/provenance checks. All
GitHub Actions runs must report executed steps; a runner-unstarted failure
is neither source-test success nor source-test failure.

The LLVM companion remains [#668](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/668)
for static traits and
[issue #651](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/651)
for Task cancellation/continuation semantics. Do not claim cross-backend
parity without equivalent executable `.ores` tests.

## Next integration proof

Publish one compiler-minted typed Task ownership/loan ticket only after
the scheduler atomically reserves a child owner and establishes an authentic
physical retirement callback. Ensure cancellation requests never release
loans; only scheduler-confirmed quiescence + destructor/FFI/code-lease
retirement can revoke ownership tickets. Explicitly block pthread
asynchronous interruption/forceful mutex release as cancellation mechanisms.
