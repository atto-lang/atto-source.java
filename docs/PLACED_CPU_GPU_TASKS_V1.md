# Placed CPU/GPU Tasks — Java source implementation and remaining device gates

Status: **CPU Task source lowering implemented on stacked Java Task draft; GPU placement admitted to AST only, always rejected at type check and runtime.** Not a GPU execution implementation. This branch is stacked on Java PR #1468, not merged to main.

## Canonical source surface

```ores
let Task<int> ordinary = task { return 42; };
let Task<int> cpu = cpu task { return 42; };
let Future<int> compatible = cpu task { return 42; };
let int result = await cpu;

// Reserved/parsed but rejected by type checker until device backend release:
let Task<GpuBuffer<float>> device = gpu task { return kernel(); };
```

- `cpu task { ... }` reuses the existing owned and scheduler-admitted Task path in ordinary/root execution; there is **no new parallel threadpool** and no promise of a particular physical CPU core. Explicit CPU task offload from actors is rejected until isolated captures and off-actor placement are proven, rather than silently executing the work as an actor turn.
- `task { ... }` remains the canonical default CPU placement. The new `cpu` prefix is useful in heterogeneous graphs and makes intent explicit.
- `Task<T>` and `Future<T>` both lower to `OresFuture<T>` at runtime; an explicit, contextual `Future<T>` binding is accepted. Inferred handles remain `Task<T>`.
- Submission enqueues initial work (it does **not** suspend the caller). `await` suspends the *awaiting* logical continuation and schedules its resumption on its owner scheduler, including for already-settled Futures.
- `rt cooperate` and safe source loop cooperation checkpoints are the CPU yielding operations besides await. Yielding does not change Task/actor ownership or run guest continuation on the Future-settling thread.
- Captures require compiler joined ownership admission proof and physical-lifetime retirement; moving owned captures into an asynchronous Task is not equivalent to borrowing locals across suspension.

## Preserved fail-closed regions

`cpu { ... }`, `gpu { ... }`, `nb cpu { ... }`, `nb gpu { ... }` and `quantum` remain deliberately rejected at source preflight until compatible typed execution-region AST, effects and backend lowering are landed. `gpu task { ... }` is parsed as an explicit GPU Task AST but type checking rejects it with an exact no-fallback diagnostic. Runtime rejects the same AST before consuming Task capture ownership, independent of compile-time checking. No CPU emulation silently satisfies GPU placement.

`gpu { ... }` previously meant a zero-argument anonymous GPU callable in an older, unmerged branch; the CPU/GPU v1 proposal chooses eager regions and instead requires `gpu () { ... }` for callable kernels. Do not enable both interpretations without migrating the prior fixtures.

## GPU device implementation gates (not yet met)

1. **Syntax/AST/typing:** distinct typed synchronous execution regions and their expression return semantics, device-effect restrictions, full device-call graph validation, and explicit geometry/SIMT mapping.
2. **Executable device backend:** approved OpenCL/target backend capable of launching real kernels, with opaque `GpuBuffer<T>`, owned transfers, layouts, alias admission, resource lifetime and device capability discovery. Missing devices fail explicitly.
3. **Nonblocking completion:** a real GPU stream/fence/event drives one `OresFuture<T>` settlement from a host completion reactor; do not block actor or CPU scheduler carriers while waiting. Completion never runs arbitrary guest code directly.
4. **Safe teardown:** cancellation is intent, not device quiescence. All buffers/transfers/permits stay alive until the GPU fence is physically complete, including device-loss and canceled/late-completion paths. Keep credits and TaskScope lifetime bound to actual completion.
5. **Cross-domain graph:** nested CPU/GPU segments lower to ordered host/device continuation and transfer nodes. No device-to-host pointer escape, callback into a host actor from a GPU kernel, or implicit fallback.
6. **Native parity:** explicit LLVM/.NET/Forge/Wasm gates; accepted syntax must not lead to silently scalar-executed GPU tasks in a backend without kernels.
7. **Verification:** full exact-head Java/GraalVM/native-image regression suites, real device kernel and error-path integration tests, sanitizer/ownership stress and parity conformance. Do not mark this PR release-ready until these execute on authorized infrastructure.

Related existing drafts: Java Task #1468, CPU/GPU contract #1096, CPU host graph #1125, native owned graph #507. Do not export private repository trees to outside organizations for CI without the source export policy's explicit approvals.

## Java tests in this branch

`PlacedTaskLanguageTest`: real GraalVM guest CPU `Task<T>` value; inferred `Task<T>`; explicit `Future<T>` result; incorrect return failure; GPU placement parser accepts but checker refuses; GPU no-await denial; legacy unsupported CPU/GPU/quantum region refusal; nested GPU task cannot hide in a CPU Task. These are checked-in tests, **not claims of executed CI**.
