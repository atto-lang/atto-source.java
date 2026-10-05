# Oreslang GPU native ABI

The Oreslang language owns GPU semantics. A physical driver is an implementation
detail behind a narrow native ABI; Java objects, JVM collections, host pointers,
OpenCL objects, CUDA objects, and JNI references are not part of the language ABI.

## Resource identity

Device resources use opaque 64-bit IDs:

- `0` is always invalid.
- IDs are capabilities/identities, not guest-dereferenceable addresses.
- A native backend SHOULD allocate IDs from a generation-checked resource table.
- Reusing a stale ID for a different allocation without a generation change is forbidden.
- Guest Oreslang code never sees the numeric ID.
- The trusted backend adapter receives it only in `NativeResourceArg`.

A JNI implementation may internally associate an ID with `cl_mem`, a CUDA
allocation, a Metal buffer, or another driver object. It SHOULD NOT expose the raw
native pointer value as the Oreslang resource ID.

## Backend argument descriptor

The trusted runtime lowers an Oreslang resident value to the logical descriptor:

```text
NativeResourceArg {
  u64 handle;
  ResourceKind kind;     // ARRAY | MATRIX | STREAM
  ScalarAbi element;     // bool/i8/u8/.../f64/complex64/complex128
  u64 length;            // element count, or stream length hint
  u64 rows;              // MATRIX only
  u64 cols;              // MATRIX only
}
```

Matrix storage is dense row-major and must satisfy `length == rows * cols` without
overflow. GPU ABI v3 currently requires positive matrix dimensions.

## JNI / native-driver boundary

JNI is permitted as a **trusted implementation boundary**, not as an Oreslang
language feature. Prefer a small C-compatible/POD surface such as:

```c
typedef uint64_t ores_gpu_handle_t;

int ores_gpu_buffer_upload(
    uint32_t scalar_abi,
    const void *src,
    uint64_t element_count,
    ores_gpu_handle_t *out_handle);

int ores_gpu_buffer_download(
    ores_gpu_handle_t handle,
    uint32_t scalar_abi,
    void *dst,
    uint64_t element_count);

int ores_gpu_kernel_launch(
    const char *kernel_symbol,
    const void *arg_descriptors,
    uint64_t arg_count,
    uint64_t global_work_items,
    uint64_t error_slots,
    uint64_t result_slots);

int ores_gpu_resource_release(ores_gpu_handle_t handle);
```

The exact C ABI can evolve independently of Oreslang source syntax, but these
invariants are mandatory:

1. validate scalar ABI, byte counts, dimensions and overflow before native calls;
2. never pass guest-controlled raw addresses;
3. native handles are backend/device scoped and stale handles fail deterministically;
4. no callback from a native kernel/driver thread into arbitrary guest code;
5. no CPU fallback for a `gpu` placement failure;
6. explicit release is supported; double release/use-after-release is rejected;
7. native errors are converted to bounded Oreslang runtime errors, not unchecked
   driver strings or host exceptions;
8. JNI local/global references must not be retained as device-resource identity;
9. Native Image builds should statically link or explicitly whitelist the trusted
   native adapter rather than allowing guest-driven dynamic library loading.

## Language-native math

`GpuMatrix<T>`, matrix shape rules, `@`, complex arithmetic semantics, alias
rules, work-item mapping, and error behavior belong to Oreslang/compiler code.
They must not be defined by Java, EJML, CUDA, OpenCL, BLAS, or a JNI library.

A backend may accelerate an operation only if it preserves the same Oreslang
semantics. The portable compiler-generated kernel is the semantic reference.
