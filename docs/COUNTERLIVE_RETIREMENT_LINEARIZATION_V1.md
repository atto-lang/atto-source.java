# CounterLive v1: independent retirement and serial-turn commit

**Scope:** JDK 21+ **test-only host model**, stacked on Java WebSocket transport PR #2061 and LiveView host tests PR #2410. This does **not** implement a guest-facing WebSocket API, compile `counter_live.atto`, authenticate a WebSocket guest, or prove GraalVM Native Image/LLVM/Wasm/Forge/.NET execution.

## Defect reproduced

Previously `CounterLiveTurnModel.increment` and `retire` were both synchronized on the same model monitor. An event callback could block while holding that monitor, preventing a different thread's supervisor retirement from returning. A previously admitted event then committed and emitted patch/ACK after retirement was requested. The stock-JDK race regression was red before the correction.

## Linearization contract

- Event turns remain **serialized** by the model's intrinsic lock. No concurrent reducer may commit or reorder revisions; the existing 100-racer test and 256-entry dedup bound are preserved.
- The potentially slow reducer/renderer callback does **not** hold the short `commitLock`. `retire()` may acquire `commitLock` while the callback is stalled and revoke logical admission immediately.
- Each increment prepares frames but must then acquire `commitLock`, check retirement, and commit revision/count/dedup **as one critical section**. If retirement won, throw without publishing a state transition, patch, or ACK. If the commit won, retirement is ordered after the committed revision.
- `retired` is volatile for the initial admission check. The final check and state publication occur under the same lock as retirement, so a volatile-only check-then-publish race is not introduced.
- Replay ACK and stale-revision rejection plans also acquire `commitLock` through `replyIfActive`; they cannot skip the terminal gate.
- External WebSocket output remains separately generation-fenced by the real transport. A successful *state commit* does not license a late socket send after physical connection revocation. The host model returns an output plan, not a native I/O completion receipt.
- The existing synchronous reentrancy guard and exception cleanup remain active. Actor panic, restart and scheduler guest ownership are separate integration gates.

## Tests

`bash verification/liveview-counter-host/run.sh` runs under stock JDK 21+, compiling `-Xlint:all -Werror`. It includes the original **332** host assertions and a new **64-attempt** cross-thread retire-while-callback-stalled regression. A JUnit 5 test (`CounterLiveTurnModelConcurrencyTest`) adds 32 corresponding adversarial attempts plus commit-first/retire-later ordering. It is added to the existing exact-head WebSocket test selector; hosted Maven execution has not been verified.

This repo's source-organization Actions has previously failed before running job steps. Do not treat these local host tests as Graal/JDK25, actor guest, or five-backend parity evidence. Do not mirror private source into an alternate organization without independently approved private-source export authorization, regardless of available Actions minutes.
