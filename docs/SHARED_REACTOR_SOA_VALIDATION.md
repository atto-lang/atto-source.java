# Shared reactor / SoA validation — 2026-10-29

PASS locally: JDK 17.0.20 compiler module, strict -Xlint:all -Werror; real Selector/Pipe checks. Five additional repeated runs passed. JDK25/GraalVM/full Maven and Native Image are NOT_EXECUTED here.

Host components remain opt-in. Existing runtime/CLI defaults and guest feature admission are not switched to these pools. No compiler-backed five-backend guest parity or hard-real-time guarantee is claimed. The native and Java tests cover bounded saturation, 8,000 concurrent submissions, one polling owner, actual readiness, same-thread polling/execution in one-worker mode, hot-ready-queue I/O fairness, explicit growth, ordinary task failure, self-join rejection and accepted-work draining.

.NET uses the same scenario set but still needs actual SDK10 execution. WASM has admission parity only. Production actor/CONTROL/isolate integration, cancellation/kernel retirement, flags-2-env CLI integration, full backend regression and hardware performance gates remain outstanding.

## Exact component Git blobs

- `src/main/java/dev/oreslang/runtime/SharedReactorPool.java`: `cc164ce9685aed80aaf039e9fc12181ab5dd93fd`
- `src/test/java/dev/oreslang/runtime/SharedReactorPoolCheck.java`: `39f3c32fbed8f38222b0bf81b1bf0688e1fd4dae`
- `src/test/java/dev/oreslang/runtime/SharedReactorPoolTest.java`: `8c3ead8b34f9bad414ec97188caf3f19d94e1653`

## Incremental wake-gating hardening — 2026-11-03

Hot-queue submissions avoid calling the reactor wake function when the single polling lease is absent. The polling lease is acquired before the poller's ready-queue check; active-lease submissions still wake it after publication. Added a deterministic saturation regression: while the only executor is blocked in user work, 1024 accepted queue entries do not trigger reactor wakeups. An ordinary shutdown continues to wake an active reactor.

Local exact-file verification on JDK 21.0.12.1: `javac -Xlint:all -Werror` and five host-check executions PASS. Test identity tracking uses `Thread` objects instead of deprecated `Thread.getId()`. JDK 25, full Maven, Native Image, and compiled guest execution are not established.

These results are standalone host-substrate evidence only. The PR remains draft and opt-in; runtime wiring, actor authority, CONTROL priority, cancellation generation fencing, and total-process thread measurements are still required. Remote zero-step workflow failures are not execution evidence.

## Poll failure containment, fail-closed drain and runner capacity — 2026-11-03

A faulting reactor must not leave a worker spinning or lose previously accepted entries: once an exception seals admission, workers drain already-admitted work but do not re-enter polling. C++17 no longer requires `poll` to be `noexcept`; unexpected exceptions are caught at the scheduler boundary while `wake` still must be nonblocking and `noexcept`.

Exact Java source/test blobs verified with `git hash-object`; JDK21.0.12.1 `javac -Xlint:all -Werror` and 5/5 host-check runs PASS. Fault-injected reactor poll exception sealed admission and drained 128 previously accepted tasks, exactly one observed poll failure, no repoll after shutdown. JDK25, GraalVM AOT, actual actor routing and complete process thread accounting are NOT_EXECUTED.

GitHub focused CI jobs in the source organization have previously failed without executed steps; these are not test failures. A separately authorized **source-free** GitHub Actions runner canary succeeded in private alternate-org carrier `opto-sync-test/go-java-validation`, run https://github.com/opto-sync-test/go-java-validation/actions/runs/41589765720, commit `7705a21d567f9637dbe035c69cd9be8dfbd1e8e2`. This demonstrates runner capacity only and does not certify or authorize exporting private source.

The protected-source repository policies have no active approval for cross-organization source mirroring; no source, tree, or runtime test fixture was transferred to the alternate org. Real cross-org source-bearing CI remains BLOCKED by governance. The opt-in pool and draft PR must not be treated as production 2-thread startup or five-backend guest parity proof.
