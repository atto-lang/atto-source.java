# Java source timer v0: await time.sleep / after — staged guest slice

**Status:** draft compiler/runtime implementation; exact-head executable tests
and GraalVM guest AOT evidence still required. Built on Java timer substrate PR
#2202 at `7173d9e1ae0d97702892f343949375b93d73146c`.
This document is **not** a claim of working cross-backend source parity.

## Intended source API

```oreslang
pub async fnc main(): void {
    val Duration delay = 1ms;
    val Future<void> wake = time.after(delay);
    await wake;
    await time.sleep(0ms);
    stdio.println(42);
    return;
}
```

Both operations return `Future<void>`, not `Task<void>`. The existing
async callable already owns a resumable source execution frame, and
`await` suspends this frame through `OresScheduler`. Registering a timer
does not schedule another guest Task; the shared monotonic clock settles the
Future and the scheduler queues the original continuation.

The lexer accepts whole, nonnegative checked Duration literals:
`ns`, `us`, `ms`, `s`, `m`, `h`.
Every literal is converted using exact, checked multiplication to a signed
64-bit nanosecond count. Fractional literals, unknown units, malformed
separators, negative values, and overflowing counts are rejected.
`Duration` is nominal: an ordinary `int` is not an implicit timer
argument or a Duration return value.

`OresContext` owns exactly one `OresTimers` resource. It closes/revokes
pending timers before the actor/scheduler physical shutdown process. Timer
revocation is **not** physical continuation quiescence, which remains gated
by the owning runtime.

## Fail-closed boundaries

- `time.timeout`, `time.interval`, `time.set_timer`,
  `time.set_timeout`, `time.set_interval`, and `sleep_blocking` are
  **not** admitted as guest-language timer APIs by this slice.
- Actor-local `time.*` registration is rejected pending actor-owned CONTROL
  and actor-generation/heap-lifetime admission. Ordinary non-actor async
  callables are admitted. An actor cannot use the context timer to bypass its
  sealed heap or single-writer turn.
- `await` remains legal only under existing async/suspends type rules.
- Source-owned cancellation/TaskScope deadline policy is not implicitly
  added by `time.sleep` or `time.after`.
- No blocking `Thread.sleep` or virtual-thread-per-timer fallback is used
  in these two guest paths.

## Required verification

1. Exact branch/head snapshot, JDK 25 Maven compile and
   `SourceTimerLanguageTest`, `OresTimersTest`,
   `AsyncAwaitLanguageTest` execution with real job steps.
2. Verify later scheduler turn for zero Duration, callback-free clock
   execution, actor and timeout negative compiler cases, and global quotas.
3. Native Image **compiled guest code**, not only an interpreter image.
4. LLVM native source/guest parity then .NET/WASM/Forge independent evidence.
5. Cross-backend acceptance candidates:
   [platform-conformance #207](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/207).
6. If private source CI lacks minutes, use only an **approved**
   destination; mere availability of a private test repo does not authorize
   mirroring. Zero-step failed/queued jobs are not executable test proof.

This is a deliberately narrow first guest slice, not the final timer API.
