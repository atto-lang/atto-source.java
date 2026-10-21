# Oreslang HAT

Status: **v0 contract + reserved syntax; execution remains fail-closed**.

Oreslang HAT is the standard heterogeneous-compute boundary. The language and
compiler own kernel syntax, typed kernel IR, ownership/effect checking and
portable lowering contracts. The standard library owns backend-neutral host
APIs. CUDA, ROCm/HIP, Metal, Vulkan, OpenCL and future accelerator providers are
userland/runtime plugins.

## Reserved declaration

```oreslang
define pub kernel VectorAdd as
  // future typed kernel body
end
```

`kernel` is a reserved keyword. In v0 the compiler rejects every occurrence
before execution. This prevents an unsupported kernel declaration from being
reinterpreted as an ordinary CPU declaration. There is no implicit CPU fallback.

## Boundary split

| Layer | Responsibility |
| --- | --- |
| Language/compiler | `kernel` syntax, typed portable kernel IR, ownership, effects, layout/ABI verification |
| `std/hat` | stable backend-neutral HAT contract and host-side policy |
| Core runtime | CPU reference executor, opaque device capabilities, queue/fence lifetime integration |
| Userland providers | CUDA, ROCm/HIP, Metal, Vulkan, OpenCL, NPU backends |
| Userland frameworks | tensors, ML, graph optimizers, placement/cost policies |

The standard rule is: **standardize accelerator semantics, not accelerator implementations**.

## Required kernel admission

Before v0 syntax can execute, a kernel must be proven to:

1. access only device-safe values and operations;
2. carry no ordinary host/actor heap pointer into device code;
3. obey `rt borrow`, `rt borrow mut`, `rt take` and `rt share` lifetimes;
4. keep buffer loans alive through the real device completion fence;
5. reject I/O, actor mailbox mutation, arbitrary suspension, host locks,
   unsupported recursion/dynamic allocation and host unwinding;
6. validate address width, layout, alignment, workgroup limits and backend
   capabilities before submission;
7. perform host/device and device/device transfers explicitly;
8. report backend/device loss and unsupported capabilities as typed failures;
9. never retry mutating work or silently execute it on CPU.

## Actor integration

Actors orchestrate accelerator work; GPU/SIMT lanes are not actors. An actor may
own opaque device/queue/buffer handles in its single `state` schema, but a
critical state loan must not remain held for the duration of a long-running GPU
operation. Submission creates a versioned runtime lease, releases actor state,
and completion is delivered after the fence signals.

## Data layout

HAT kernels may consume compiler-verified AoS, SoA and AoSoA layouts. Layout is
part of the typed kernel contract and includes field shape, scalar widths,
alignment, lanes, address width and ownership generation. Equal byte length is
not sufficient evidence of layout compatibility.

## v0 stdlib surface

`std/hat` currently exposes contract introspection only:

- `hat.abi_version()`
- `hat.allows_implicit_cpu_fallback()`
- `hat.requires_explicit_transfer()`
- `hat.requires_completion_fence()`

Opaque `Device`, `DeviceBuffer<T>`, `DeviceView<T>`, `Queue`, `Fence`
and `Kernel` capabilities will be added only together with compiler/runtime
enforcement. They must not be forgeable as ordinary structs/classes.

## Follow-on implementation order

1. kernel AST + versioned portable IR;
2. CPU reference executor and differential tests;
3. opaque typed buffers/views + ownership/fence admission;
4. asynchronous queue/dispatch primitives;
5. explicit upload/download/device transfer;
6. one real provider backend;
7. batching/fusion and optional userland placement policy.
