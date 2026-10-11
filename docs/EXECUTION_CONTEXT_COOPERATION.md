# Execution-context cooperation quantum (Java/GraalVM draft)

**Implemented in this stacked draft:**
- One `SourceCooperationQuantum` object belongs to one heap-backed `SourceTask`, and survives `await`, `rt cooperate`, and physical carrier migration.
- `SourceTask.resumeSource()` starts a fresh 2ms/512-backedge *dispatch* quantum. Waiting on a Future does not cause parked wall time to consume the new dispatch's quantum.
- On a committed source-loop backedge, a cheap integer counter checks only whether a 64-iteration batch boundary was reached. The monotonic clock is never read on the fast path; slow-path time sampling occurs only at a batch boundary and **not at all** if the reduction limit is already exhausted.
- An actual cooperate records its own monotonic timestamp. `timeSinceLastCooperateNanos()` is distinct from generator `yield value` and from `await`. Neither `await` nor a generator item publication resets the logical cooperation clock.
- The loop's next operation is already in a heap-owned `SourceValueCont`. Suspending here preserves lexical defers, pending finally, locals, nested loop state and side-effect order; nothing is re-executed from the start of the loop.
- UNTRUSTED actor backedges still call `schedulerSafepoint()` at every iteration for fuel/quota validation; automatic cooperation remains disabled pending task-lifetime quota continuity. A live mutex guard prohibits automatic cooperation and cannot be forcibly interrupted.

**The words `yield` and `cooperate` must not be conflated.** A generator's `yield value` is a one-element consumer/backpressure protocol, not a scheduler fairness handoff. `timeSinceLastCooperate` is the scheduling term, not `timeSinceLastYield`.

## Safety boundary

`SourceTask` currently owns its lexical defer/finally continuations, but they have **not** been unified with `OresScheduler.TaskRunner` cancellation. `cancelFromFuture()` can still terminalize and skip pending suspended cleanup; see `docs/SOURCE_TASK_CANCELLATION_RELEASE_GATE.md` on parent PR #567. This patch deliberately does **not** claim cancellation-safe cleanup.

`recover` remains reserved/non-executable in `docs/EXCEPTION_EFFECT_ADMISSION.md`; no recovery semantics are attached to scheduling handoffs.

The hot-loop cycle-proof pass covering interprocedural recursion, callback effects, dynamic calls, native frames and unbounded guarded cycles is **not implemented** by this Java change. Synchronous actor receive loops use the existing loop-backed SourceTask path, but ordinary synchronous methods and general recursion are not automatically resumable. Compile-time rejection or explicit isolation of unbounded no-yield cycles is still required before universal threadpool starvation resistance can be claimed.

## How to validate

1. Run `mvn -q -Dtest=SourceCooperationQuantumTest,SourceLoopSoftPreemptionTest test` on the exact branch source using GraalVM 25.
2. Run full Maven `verify`, actor single-writer / serial HTTP admission tests, JIT/AOT/hybrid tests and forced peer-fairness stress.
3. Confirm the exact source-tree (or enumerated Git blob) equality for any private funded CI mirror.
4. Benchmark 32 versus 64/128/256 batch backedges and 2ms/512 versus 5ms/500, reporting clock reads per million iterations, CPU time, throughput and p95/p99 peer wait latency.
5. Prove suspended-finally cancellation/cleanup through the owning actor scheduler lane before removing the release gate.

## Go pre-emption comparison

Go's runtime can pre-empt goroutines at compiler-approved safe locations, including signal-assisted asynchronous safe points. This is significantly more complex on the Java/GraalVM and LLVM mixed stack because heap continuation materialization, GC maps, FFI/native frames, and actor ownership must agree. Adopt the *principle* of compiler-verified safe points, not signal-based arbitrary host stack capture in this phase. A lightweight counter on a known lowered loop backedge is portable and does not require Go-style runtime stack scanning.

References: `docs/ACTOR_PREEMPTION.md`, `docs/SUSPENSION_CONTRACT.md`, and `oreslang-platform-conformance` draft PR #14.
