# CPU and GPU execution-domain contract (proposal, v0 safety gate)

Status (2026-10-29): **synchronous source blocks remain fail-closed on Java/Graal `main`**.

**Draft branch-only stage v3 exception:** [`CPU_SCALAR_REGION_V3_PREVIEW.md`](CPU_SCALAR_REGION_V3_PREVIEW.md) proposes a separately versioned, *closed integer scalar* `cpu { 40 + 2 }` source path, with its own AST and checked evaluator. It is **not merged into `main`**, has **not run actual compiler/JUnit CI**, and is not a general CPU region, buffer ownership proof, or GPU device integration. The table below continues to describe the production `main` baseline rather than the unverified preview. This document began as the v0 reservation contract; the canonical spelling/result policy is now [CPU/GPU Task v2](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/blob/main/docs/CPU_GPU_TASK_V2.md). The v1 and v2 pinned fixture corpora remain distinct.

| Source spelling | Java/Graal `main` admission | Meaning / release gate |
| --- | --- | --- |
| `cpu { ... }` | Rejected by parser preflight | Synchronous result `T` or `void`; typed CPU region lowering not yet on `main` |
| `gpu { ... }` | Rejected by parser preflight | Synchronous GPU region; no host fallback or device simulation |
| `task { ... }` / `cpu task { ... }` | Parsed, then checked for ownership and effects; runtime source Task path present | Asynchronous CPU task, one `Future<T>` completion, NOT an implicit stream |
| `gpu task { ... }` | Parser admission only; rejected by type checker and evaluator | Requires actual GPU provider, device allocation, ownership/fence proof |
| `nb cpu { ... }` / `nb gpu { ... }` | Legacy parser aliases of CPU/GPU Task; identical placement gates | Not a second streaming primitive |
| `quantum` | Rejected | No unverified accelerator fallback |

The table describes source paths, **not exact-head executed compiler or hardware evidence**. Native LLVM, .NET, Forge and Wasm retain their own fail-closed gates; the Wasm source path depends on the pinned LLVM compiler. Unsupported constructs must not emit executable artifacts. No physical GPU kernel execution, nested typed graph runtime, implicit host/device transfer, CUDA, OpenCL or WebGPU dispatch is certified by this document. No-value expressions have `void` / `Future<void>`, **not `Unit`**. Empty GPU work is invalid until it has a verified device effect.

## Intended syntax

```oreslang
// FUTURE SYNTAX ONLY — intentionally rejected by today's compiler.
suspends fnc pipeline(HostBuffer input): HostSummary {
    let device = await gpu {
        let device_input = await Gpu.upload(rt take input);
        launch_transform(rt take device_input);
    };
    return await cpu {
        let host = await Gpu.download(rt take device);
        summarize(host);
    };
}
```

Both `cpu { ... }` and `gpu { ... }` are expression-capable *execution
regions* with a typed value/result and effect signature. They are not placement
hints, lexical macro substitution, or authorization to execute guest code on
an arbitrary scheduler thread. A domain transition is an asynchronous,
cancellable operation in the runtime execution graph.

## Nested-domain lowering

| Nesting | Required lowering | Forbidden shortcut |
| --- | --- | --- |
| CPU → CPU | Host lexical region; compiler may fold if effects match | Double-publish mutable borrows |
| CPU → GPU | Enqueue validated device launch; await or carry typed future | CPU-evaluate kernel on missing device |
| GPU → GPU | Separate ordered launch, or proven-safe fuse on supported target | Device-side arbitrary kernel launch |
| GPU → CPU | End/split device kernel, satisfy fence, schedule host continuation | Call host code directly from GPU threads |
| GPU → CPU → GPU | Device completion → explicit download/transfer → host continuation → explicit upload/dispatch | Hidden migration, reentrant device callback |

A `cpu` region *nested syntactically* within a `gpu` region is a host
continuation in the graph. A GPU kernel body is still restricted to device-safe
operations. Compositional nesting does not mean arbitrary CPU/GPU recursion or
hardware dynamic parallelism. The scheduler must never synchronously wait on
the device on an actor carrier thread.

## Required compiler safety checks before lifting the v0 rejection

1. Typed `ExecutionDomain` effects: `Host`, `Device(device capability)`,
   `Transfer`, and `Await`. Device kernel admission rejects I/O, heap/host
   pointers, locks, actor mailbox mutation, unwinding/panic, and unsupported
   recursion/dynamic allocation. Domain transitions must be explicit in IR.
2. Typed, bounded device buffer handles: tenant, device, allocation generation,
   byte length, alignment, element layout, and ownership. No host pointer
   masquerades as device memory. No shared mutable host/device borrow while a
   command is in flight. No use after move, drop, cancellation or device reset.
3. Explicit `upload`, `download`, and device/device transfer. A compiler must
   not infer an implicit transfer or fallback solely because a value appears in
   another domain. `GpuBuffer<T>` and `HostBuffer<T>` are distinct types.
4. Ordered futures and completion fences for dependencies. Cancellation is a
   *request*, not proof that a GPU command has stopped. Memory reclamation and
   actor shutdown wait for verified device quiescence; stale completion tickets
   must never revive a retired allocation.
5. Validate target `arch`, 32/64-bit address width, endian/layout ABI,
   alignment and legal workgroup/buffer limits. Never assume a 64-bit CPU
   implies a 64-bit device. OpenCL/CUDA/Vulkan capabilities are checked before
   creating a command; unknown capabilities reject deterministically.
6. Bound per-tenant queue depth, bytes, outstanding transfers, kernel runtime,
   and concurrency. Report device loss, timeout, and unavailable backend as
   typed errors; do not retry mutations or fall back to CPU implicitly.
7. Prove each actor's host continuation executes on at most one carrier at
   once, including completion/cancellation races, without blocking a scheduler
   worker. CPU and device execution must not share unprotected actor state.
8. Formatter retains explicit domain boundaries, and Java + LLVM conform to
   identical parse/type/effect/IR fixtures for legal and rejected programs.

## Present compiler admission checks

Java/Graal `main` reserves `cpu`, `gpu` and `quantum` as dedicated tokens. The parser rejects synchronous CPU/GPU blocks and quantum before lowering, even inside an otherwise admitted Task or unreachable branch; the lexer does not turn reserved domain words into normal identifiers. Canonical `cpu task` and `gpu task`, plus legacy `nb` forms, are parsed separately. **Parsing `gpu task` does not make it executable**: the Java type checker forbids it in the absence of physical GPU dispatch, and the evaluator retains a fail-closed guard. Java CPU Task execution is also subject to capture/actor-state ownership gates.

LLVM, .NET and Forge must independently prove their own real source-compiler rejection/no-artifact behavior rather than inherit a Java parse result. Wasm must pin the underlying compiler and independently show any host-device capability. When lifting a gate, require typed host/device effects, ownership and transfer checks, exact-head source execution, and true device evidence for GPU; never enable a blanket identifier fallback.

## Data-oriented programming integration (follow-on, still fail-closed)

The nested-domain contract is now connected to the **typed DOP GPU
preflight**: AoS, SoA and AoSoA kernels bind to device buffers only when their
full layout metadata (kind, rows, fields, scalar width, alignment and lanes)
matches. Equal padded byte lengths alone are insufficient. Each launch plan
requires distinct device ownership generations, same tenant and device,
idle fences, nonzero GPU work and checked signed-64-bit memory reservations.

- Java DOP implementation: [PR #703](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/703),
  stacked on Java DOP kernel [PR #579](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/579).
- Native DOP implementation: [LLVM PR #229](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/229),
  using `DopLayout.hpp`, `DopKernelIR.hpp`, `GpuBufferFence.hpp` and
  `DopGpuAdmission.hpp`.

See `docs/DOP_GPU_EXECUTION_BOUNDARY.md` in those DOP hardening branches.
These are runtime **preflight data contracts**, not a compiler-approved
`cpu {}`/`gpu {}` AST, host/device transfer implementation, or GPU driver.
A trusted provider must atomically claim buffers, authenticate device
allocation provenance, enforce no host/device aliasing and wait for real
device fences; preflight snapshots alone do not authorize launching work.


## G2 source-neutral kernel reference

The runtime now carries a source-neutral `PortableKernelIR` used only for
backend conformance. It supports checked AoS/SoA/AoSoA layouts, homogeneous
`f32` elementwise scale/bias/scale-bias kernels, and an explicit deterministic
CPU reference provider.

This does **not** relax synchronous `gpu {}` / `cpu {}` rejection or the GPU Task type-checker gate. CPU Task source admission does not confer a kernel backend; there is no source `kernel` GPU device lowering yet. The CPU reference provider is never an implicit fallback for a
GPU request and is not hard-realtime evidence.

Java/Graal and LLVM/native now both lower this subset to the same OpenCL C artifact. Generated OpenCL
source is an artifact only; real device execution still requires an
authenticated provider, physical buffer provenance, queue admission, a
submitted-state witness, device-event completion evidence, and verified
retirement.
