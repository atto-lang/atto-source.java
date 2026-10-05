# Oreslang numeric native-acceleration ABI

Oreslang owns numeric semantics. Native libraries are optional accelerators, not the
definition of Matrix, Vector, complex, electrical-engineering, FFT, or signal-processing
behavior.

The portable reference implementation uses immutable row-major primitive storage and is
the parity oracle for optimized implementations.

## Layers

1. **Oreslang semantic layer**
   - numeric types and widening;
   - `@` matrix multiplication;
   - shape rules;
   - complex arithmetic;
   - singular/rank-deficient failure behavior;
   - immutable value and actor-transfer rules.

2. **Portable reference kernels**
   - contiguous row-major f64 storage;
   - separate real/imaginary planes for complex128;
   - portable loops for elementwise operations and matrix multiplication;
   - pivoted reference factorization for solve/inverse;
   - no dependency on EJML or another Java numerical library.

3. **Optional native accelerator**
   - BLAS/LAPACK, Accelerate, OpenBLAS, MKL, CUDA, ROCm, Metal, or another backend;
   - reached through trusted JNI/FFI glue only;
   - never exposed as Java/native objects to guest Oreslang source.

## Buffer ABI

A native CPU accelerator should consume validated POD descriptors rather than host
objects:

```c
typedef struct ores_dense_matrix_f64 {
    const double *real;
    const double *imag;      /* NULL for real matrices */
    uint64_t rows;
    uint64_t cols;
    uint64_t row_stride;     /* elements, not bytes */
} ores_dense_matrix_f64;

typedef struct ores_dense_matrix_f64_mut {
    double *real;
    double *imag;
    uint64_t rows;
    uint64_t cols;
    uint64_t row_stride;
} ores_dense_matrix_f64_mut;
```

The trusted VM/JNI adapter validates multiplication overflow, lengths, strides, overlap
rules and mutability before calling native code. Guest code never supplies raw pointers.

## Required parity

An accelerator is usable only when it matches reference behavior for:

- shape mismatch errors;
- real/complex promotion;
- complex multiplication and conjugation;
- NaN/Infinity policy for the selected operation;
- singular and numerically rank-deficient solves;
- row-major result ordering;
- immutable input semantics;
- deterministic no-alias guarantees where promised.

Floating-point implementations may differ by normal rounding error or FMA use, but cannot
silently change precision families or operation meaning.

## JNI guidance

JNI is acceptable as a trusted VM implementation boundary. Keep it small:

- prefer direct primitive buffers or copied pinned staging buffers;
- never retain guest object references as native numerical state;
- do not call arbitrary Oreslang code from native worker threads;
- convert native error codes to bounded Oreslang errors;
- never load a library based on an untrusted guest path;
- Native Image/AOT builds should statically link or explicitly configure the trusted
  adapter.

CPU numeric operations may always use the portable Oreslang reference path when an
accelerator is unavailable. This differs from explicit `gpu` placement, which must fail
closed rather than fall back to CPU.
