# Oreslang HAT standard-library contract

Status: **portable stdlib foundation; execution remains fail-closed**.

Oreslang calls its heterogeneous accelerator layer **HAT** (Heterogeneous
Accelerator Toolkit). The stable source-level entry point is:

```oreslang
import module hat from "std/hat";
```

The stdlib owns portable contracts. Hardware-specific backends remain modular
and may live in userland. Compiler/runtime code owns the parts that cannot be
implemented safely as ordinary guest objects: opaque device handles, device
allocation generations, ownership/fence tracking, kernel admission, transfer,
and accelerator dispatch.

## v1 portable surface

`std/hat` currently provides:

- `hat.api_version()`
- `DeviceInfo` descriptors and `hat.device_info(...)`
- `BufferLayout` descriptors for `scalar`, `aos`, `soa`, and `aosoa`
- `WorkShape` plus validated 1D/2D/3D work-shape factories
- `Requirements` plus `hat.device_satisfies(...)` for deterministic
  capability admission

These values contain metadata only. They are not device capabilities and cannot
authorize memory access or execution.

## Deliberately absent

The v1 stdlib does **not** expose:

- dispatch or launch
- upload/download
- raw host/device pointers
- user-constructible device buffers
- implicit CPU fallback
- backend-specific CUDA/ROCm/Metal/OpenCL/Vulkan APIs

Adding a guest `dispatch()` function before the compiler/runtime can prove
effects, ownership, transfers, and fence lifetime would create a false security
boundary. Execution therefore stays rejected by the existing execution-domain
gate.

## Future compiler/runtime-owned types

When the execution gate is lifted, HAT should add opaque, non-forgeable runtime
types approximately equivalent to:

```text
Device
DeviceBuffer<T>
DeviceView<T>
DeviceViewMut<T>
Kernel<A, R>
Submission<R>
Fence
```

The exact source syntax is intentionally not frozen by this document.

A submission must retain every borrowed/moved device allocation until its real
completion fence reaches a terminal state. Cancellation is only a request; it
does not release memory. Device reset/loss must retire allocation generations so
late completions cannot revive stale handles.

## Backend boundary

The stable stdlib should remain backend-neutral. A provider may implement CUDA,
ROCm/HIP, Metal, OpenCL, Vulkan, an NPU runtime, or a CPU reference executor,
but provider-specific objects must not leak through the portable `std/hat`
contract.

Backends must report capabilities and be rejected deterministically when they
cannot satisfy a requested layout, work shape, address width, allocation size,
or required feature. No provider may silently execute accelerator work on the
host.

See `GPU_CPU_EXECUTION_DOMAINS.md` for the compiler execution-domain safety
gate and nested-domain lowering contract.
