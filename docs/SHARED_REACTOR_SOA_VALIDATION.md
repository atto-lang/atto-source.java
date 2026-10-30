# Shared reactor / SoA validation — 2026-10-29

PASS locally: JDK 17.0.20 compiler module, strict -Xlint:all -Werror; real Selector/Pipe checks. Five additional repeated runs passed. JDK25/GraalVM/full Maven and Native Image are NOT_EXECUTED here.

Host components remain opt-in. Existing runtime/CLI defaults and guest feature admission are not switched to these pools. No compiler-backed five-backend guest parity or hard-real-time guarantee is claimed. The native and Java tests cover bounded saturation, 8,000 concurrent submissions, one polling owner, actual readiness, same-thread polling/execution in one-worker mode, hot-ready-queue I/O fairness, explicit growth, ordinary task failure, self-join rejection and accepted-work draining.

.NET uses the same scenario set but still needs actual SDK10 execution. WASM has admission parity only. Production actor/CONTROL/isolate integration, cancellation/kernel retirement, flags-2-env CLI integration, full backend regression and hardware performance gates remain outstanding.

## Exact component Git blobs

- `src/main/java/dev/oreslang/runtime/SharedReactorPool.java`: `000663b4d85fe41d6790b3b88c4a723f8d82afd9`
- `src/test/java/dev/oreslang/runtime/SharedReactorPoolCheck.java`: `8e084393ca4dbcd029320bfd054f66f5dac520e4`
- `src/test/java/dev/oreslang/runtime/SharedReactorPoolTest.java`: `8c3ead8b34f9bad414ec97188caf3f19d94e1653`
