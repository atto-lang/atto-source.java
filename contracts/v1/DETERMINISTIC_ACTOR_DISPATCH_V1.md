# Deterministic actor dispatch v1 — real Java runtime test seam

**Status: implementation candidate, not yet executed on Java/GraalVM compiled guest.**

## Scope

This revision adds a package-private `ActorRuntime.DispatcherFactoryForTesting` constructor seam. Only Java host-runtime tests in `dev.oreslang.runtime` may opt into a bounded, manually driven `ExecutorService`. Production constructors continue constructing the exact same private/shared/untrusted actor dispatchers with no injected overhead. The test does not expose scheduler authority to .atto guest code or accept guest-written trace files.

`DeterministicActorDispatchTest` drives the actual `ActorRuntime` mailbox scheduling/dispatch lifecycle, physical-turn checks, `TurnExecutor` callback and `ActorRef` API. Two shared actors each receive one admitted message, assert real actor context/generation, and terminate from the receiver. The test driver queues accepted physical turns and selects their order explicitly without invoking guest code on producer or scheduler callbacks.

The test proves *bounded host-runtime order controllability*, not end-to-end deterministic guest scheduling, virtual time, scheduler task continuation replay, hot upgrades or cross-backend parity. It does not assert full scheduler identity from arbitrary actor UUIDs. Test-generated ticket `turn-N` identifiers are unique within one isolated run and are stable only when runnable admission repeats in the same deterministic scenario.

## Safety and fail-closed contract

- The injected factory is package-private, never a public language/runtime capability. Normal constructors are unchanged.
- A controlled executor enqueues, never runs work inline from `execute()`. The test driver selects one eligible runnable at a time; a selected runnable passes through the real physical actor turn lease and is never recursively re-entered.
- The test driver bounds per-kind queued callbacks, total steps and runs all pending turns to completion before closing the runtime. The underlying production startup checks are not claimed for this test-only executor.
- Trace decisions record the **complete enabled ticket set**, selected ticket and observed actor-message prefix. Replay rejects mutated enabled sets, nonexistent choices, missing/excess decisions and changed effects rather than silently falling back to a random scheduler.
- Random scheduling is seeded and repeatable; deliberate seed exploration must cover both A/B and B/A actor turn orders. A seed by itself is **not** the eventual portable trace contract.
- Actor source/guest state and payloads are never serialized into this test trace. Production trace recording requires trusted host-owned receipt channels and immutable runtime/build/source fingerprints.

## Reproduction

```bash
mvn -B -ntp -Dtest=DeterministicActorDispatchTest \
  -DfailIfNoTests=true -Dsurefire.failIfNoSpecifiedTests=true test
```

An exact-head CI job must execute all three tests without skips, failures or errors. No `compiled_atto_guest` badge can be given to these tests: the receivers are Java host callbacks, not a compiled `.atto` module.

## Successive gates

1. Validate the exact current Java PR head with stepful test execution and production constructor regression tests (including runtime shutdown, actor control lane and cancellation). Zero-step CI means NOT_EXECUTED.
2. Extend test-only choice capture down to `OresScheduler` task continuations, mailbox DATA/CONTROL, virtual timers and generation-fenced cancellation without blocking carriers or exposing unsafe suspension points.
3. Create an equivalent two-actor `.atto` guest and prove schedule capture + replay on the same compiled artifact; then add cross-backend conformance receipts and AOT verification.

Related: [atto-spec #207](https://github.com/atto-lang/atto-spec/pull/207), [atto-test.cli #16](https://github.com/atto-lang/atto-test.cli/issues/16), [atto-platform-conformance #562](https://github.com/atto-lang/atto-platform-conformance/issues/562). Existing Java [#953](https://github.com/atto-lang/atto-source.java/pull/953) has older concurrency CLI/perturbation code and is thousands of commits behind current main. Do not blindly merge it or claim that this scoped test seam replaces all its unique functionality.
