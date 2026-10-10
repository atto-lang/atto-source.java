# Async stdout and deferred cleanup: staged contract

## Implemented on this branch (runtime integration candidate)

- `stdio.stdout.log(...)` remains synchronous and preserves old behavior.
- `stdio.stdout.log_async(...)` formats arguments before enqueue and returns a settled `Future<void>` for *admission*, **not durability/delivery**.
- `await stdio.flush_async_logs()` waits for the FIFO barrier representing all accepted records before that barrier. It does not call `fsync`, wait for an external pipe reader, or guarantee remote collector delivery.
- One bounded context-owned queue (8192 events), one dedicated stdout writer thread, explicit rejection at full capacity. No stdout write or queue-capacity wait on the submitting actor carrier.
- Context shutdown drains queued stdout after async producers and actors stop and before VM shutdown. Failure/timeout is propagated to the embedding host instead of silently lost.
- Existing direct synchronous writes and queued async writes share a PrintWriter; they are individually synchronized but **not globally ordered** across APIs. The explicit barrier orders only queued records. A follow-up can establish a single sequencer for mixed sync/async writes.

## Still required (not implemented by this branch)

1. Parse and lower `defer { await stdio.flush_async_logs(); }` as a deferred async body; correctly await its completion before scope exit, including exceptional unwinding.
2. Admit the parenthesized immediately invoked lambda form `defer (|x| -> { await ... })(x)` and distinguish **defer-time evaluation/capture of callee and arguments** from **exit-time invocation**. Plainly storing an unevaluated expression is insufficient, since `x` may be moved, mutated or leave scope.
3. Borrow checking must reserve loans until execution or forbid holding ephemeral borrows across suspension; owned captures move at registration, `Copy` captures copy, immutable borrow captures cannot outlive the referent, and mutable/guarded borrows must be rejected if they would cross `await`. Enforce LIFO execution and exactly-once cleanup under normal return, trap, cancellation, process exit and panic.
4. Ensure shutdown works for explicit `std.process.exit` and linked CLI exits, not only ordinary context closure; decide a bounded flush deadline and nonzero exit on drain failure.
5. Measure slow stdout pipes (seconds), bounded-queue saturation, cancellation, race with context close, telemetry JSONL framing, and actor carrier liveness. Test Graal JVM/AOT/hybrid/isolate modes on an actual runner.
6. Compare OTel userland logging migration and define `log_blocking` / `log_async` vs legacy `log` naming consistently with the native I/O suffix contract.

This PR is **draft** until executable regression tests and the syntax/borrow-checking work are independently verified.
