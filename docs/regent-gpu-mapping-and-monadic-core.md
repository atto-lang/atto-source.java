# Regent-inspired GPU mapping and monadic core

This increment deliberately takes a small number of high-value ideas rather than
copying Regent's surface language.

Primary reference:

- Elliott Slaughter, *Regent: A High-Productivity Programming Language for HPC
  with Logical Regions*, Stanford University dissertation.
  https://theory.stanford.edu/~aiken/publications/theses/slaughter.pdf
- In particular, see the Legion execution model discussion of **Mapping**
  (section 4.1.3) and **Variants** (section 4.1.5).

## 1. One logical task, multiple physical variants

A logical Oreslang compute task may have multiple implementations that preserve
the same logical effects while targeting different processors or layouts.

The runtime planning model represents those implementations as task variants:

- CPU scalar;
- CPU parallel/SIMD;
- GPU;
- later architecture-specific variants where useful.

Variant choice is a physical scheduling decision. It must never change the
logical task's ownership, aliasing, actor-isolation, or effect contract.

The mapper rejects duplicate variant names and chooses eligible variants
deterministically. Explicit GPU placement fails closed if a usable GPU variant
or backend is unavailable.

## 2. Placement accounts for data, not only FLOPs

GPU placement is not "send this function to a GPU." Data placement is part of
the scheduling problem.

Before physical placement, the planner conservatively checks task
non-interference using logical region identities plus read/write/discard/
reduce/atomic footprints. Only then may the physical mapper choose CPU or GPU.

For AUTO placement, the initial cost model considers:

- work-item count;
- variant minimum useful work size;
- whether the current logical version of each touched region root is proven
  GPU-coherent;
- device-memory budget;
- an explicit crossover threshold representing transfer/setup cost.

Each root that is not proven GPU-coherent raises the GPU crossover threshold. A known
working set that exceeds the device budget forces AUTO back to CPU; explicit
GPU placement fails closed instead of silently changing semantics.

This is intentionally backend-neutral. The Java class is planning/compiler
glue; production storage, transfers, kernels, and device handles remain behind
the native/Oreslang runtime boundary.

`GpuResidency` is a **coherence proof snapshot**, not an allocation inventory.
A device allocation that became stale after a CPU write is non-coherent and
still pays the transfer crossover. The transfer/coherence manager must make
every accessed field current before GPU execution.

A variant's `requiredLayout` is a physical materialization requirement, not an
ignorable hint. A backend selecting that variant must provide or materialize the
layout before launch. An unknown planning working-set estimate likewise never
waives executor-side device-memory admission.

## Functional composition: Option and Result

Oreslang's initial monadic-style core is intentionally concrete rather than
introducing higher-kinded-type machinery.

### Option<T>

- `map<U>(T => U) => Option<U>`
- `flat_map<U>(T => Option<U>) => Option<U>`
- `and_then<U>(...)` as the readable alias for `flat_map`
- `or_else(() => Option<T>) => Option<T>`
- `flatten() => Option<T>` for `Option<Option<T>>`

### Result<T,E>

- `map<U>(T => U) => Result<U,E>`
- `flat_map<U>(T => Result<U,E>) => Result<U,E>`
- `and_then<U>(...)`
- `map_err<F>(E => F) => Result<T,F>`
- `or_else<F>(E => Result<T,F>) => Result<T,F>`
- `flatten() => Result<T,E>` for `Result<Result<T,E>,E>`

The type checker requires an explicit output type argument for transforming
operations in this first cut. That keeps generic inference conservative while
the language's callable inference is still being hardened.

The implementation preserves short-circuit behavior: callbacks are not invoked
for `None` on success-side composition or for `Err` on success-side Result
composition.

Composition callbacks execute through Oreslang's ordinary evaluator/trampoline,
so callback tail calls complete before their values are wrapped in
`Option`/`Result`. Generic composition members are direct-call-only in this
first cut: extracting `value.map` or `value.flat_map` as a polymorphic
function value is rejected until first-class generic callable values are sound.

## Non-goals in this PR

This PR does not:

- add a universal `Monad<M<_>>` source-level abstraction;
- add higher-kinded types;
- add postfix `?` propagation;
- add Future/Iterator/Stream composition;
- add GPU kernel code generation or a physical residency manager;
- expose device pointers, native handles, or mapper authority to guest code.

Those can build on these contracts without expanding this change's semantic
surface.
