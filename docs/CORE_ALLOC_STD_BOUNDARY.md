# Oreslang core / alloc / actor / std library boundary (proposal v1, SUPERSEDED)

> **Superseded on 2026-10-26.** Do not implement the four public tiers below.
> Oreslang has exactly two public library layers, `ores.core` and `ores.std`.
> Allocation, actor execution, mailboxes, trusted control and clocks are
> explicit *providers* beneath those layers, not separate `alloc`/`actor`
> libraries. `alloc`, `actor` and `otp` are forbidden public profile names.
>
> Current authorities:
> - `docs/CORE_STD_BOUNDARY.md` in this repository
> - `oreslang-common-source` `contracts/library/v1/library-profiles.json` (normative)
> - `oreslang-target-profiles` `docs/FREESTANDING_TIER_ADMISSION.md`
>
> This file is kept for its rationale (explicit allocator ABI, bounded static
> mailboxes, no hidden allocation or OS use in core), which now applies to the
> provider model.

Status: **architecture contract proposed 2026-10-16; not yet a compiler, runtime, or backend implementation**.
This document specifies the eventual source-level and runtime dependency policy. It does not claim that the Java/Truffle host is bare-metal capable.

## Motivation

An Oreslang program should be able to target bare metal, a kernel, a capability-constrained WebAssembly guest, or a hosted Linux/Windows/macOS process without importing ambient OS facilities into the portable language foundation. As in Rust, separate the minimal language library from heap-backed collections and platform services; unlike a simplistic hosted/freestanding binary split, actor execution must remain possible with a supplied embedded scheduler and bounded static mailboxes.

## Library namespaces and permitted dependency edges

| Namespace | Role | Required dependencies | Forbidden dependencies |
| --- | --- | --- | --- |
| `core/*` | Language-defined primitives, types, traits, static algorithms, fixed-size storage, pure numeric operations | Compiler/backend scalar intrinsics only | `alloc/*`, `actor/*`, `std/*`, OS/runtime/process services, mandatory heap |
| `alloc/*` | Owned strings/buffers, dynamic collections, box/reference abstractions, heap-backed graph and codec algorithms | `core/*` and an explicit allocator provider | `std/*`, implicit system malloc, ambient OS services |
| `actor/*` | Actor/message/supervision API and capability-neutral scheduler contracts | `core/*`; optionally `alloc/*` where declared | `std/*`, implicit OS thread/clock/network requirement |
| `std/*` | Hosted/platform-adapted I/O, clocks, process/env, filesystem, network and convenience facades | `core/*`; `alloc/*` and/or `actor/*` as needed; capability-checked platform adapter | Unrestricted host reflection/FFI or capabilities not in the caller's grant |

The **dependency DAG** is `core <- alloc`, `core <- actor`, `alloc <- actor` only for allocator-using actor modules, and `core/alloc/actor <- std`. An implementation must reject *transitive* forbidden imports, including dependencies introduced by compiler intrinsics. A library module's permission is never inferred from a Java classpath, Node host, C runtime, .NET assembly or system headers.

Not every `actor/*` module must depend on `alloc`: a fixed-capacity actor executor can use statically provisioned mailboxes. Likewise, `core` does not promise all atomics on all targets. Target support is explicit and feature-gated; unsupported operations fail compilation rather than emulating unsafe behavior.

## Precise boundaries

### core: no allocator, no OS

- Primitive scalars (with Oreslang-defined integer/float behavior); `bool`, units, tuples, fixed-size arrays, slices/views and bounded iterators that do not allocate.
- Generic `Option<T>`, `Result<T,E>`, `Ordering`, type/ownership/borrow contracts, `Sendable` admission traits, deterministic pure algorithms, fixed-size numerical matrices, and portable arithmetic.
- Pure future/poll *types* may live here; actual executor, wakers, timers, wait queues and scheduler ownership do not.
- Borrowed UTF-8/string views may live here. Owned growing strings and Unicode tables requiring dynamic memory belong in `alloc` or a separate data package.
- No system allocator, malloc/free, filesystem, network, environment, stdout, implicit current time, random source, process exit, thread creation, host FFI, reflection, or TLS dependency.
- Panic/trap behavior must be implemented through a target-defined failure hook or a documented trap strategy; no assumption of OS unwinding, stderr or process abort. Core imports cannot grant capabilities.

### alloc: heap without OS

- An explicit `Allocator` ABI with checked allocation/deallocation/layout/alignment and deterministic failure; bump, arena, static-pool and actor-owned heaps may implement it. No implicit global heap is required.
- `Vector<T>`, `Box<T>`, owned `String`, hash/ordered maps, heap-backed immutable structures, owned byte buffers, variable-precision `BigInt`/`BigDecimal`, and pure in-memory JSON/MessagePack/Protobuf codecs when implemented.
- Quota exhaustion returns a typed allocation error or follows an explicitly declared `try_alloc`/panic policy; never silently request host memory.
- Actor ownership transfer, `rt borrow/take/copy/share` and cycle/weak-reference semantics are preserved. A memory provider cannot bypass actor isolation or `Sendable` checks.

### actor/async: capability-neutral contract

- `ActorRef`, message typing, `ActorMail`, ActorGroup, supervision CONTROL contracts, cancellation, channels, and executor interfaces belong in a portable actor/async layer, not behind `std`.
- A target may provide a cooperative, interrupt-driven, or native-threaded executor. Declared serial/concurrent actor behavior, synchronized actor-state access, message admission, lifetime completion, actor-state loan nonescape and quiescent retirement are source semantics independent of executor. This does not require a dedicated native thread or isolate for each actor.
- Embedded configurations may use fixed-capacity mailboxes and user-provided clock/tick source; no OS services are imported solely by `spawn` or `receive`.
- `Sendable` applies to in-process actor transfer; network serialization is a separate `Serializable`/codec contract. Wire and network transport live outside core. Neither transfer path may leak pointers or untrusted host capabilities.

### std: OS/platform adapters

- `std/fs`, `std/net`, `std/process`, `std/env`, `std/time`, `std/io`, `std/thread`, and platform-aware random services can depend on a target-specific host/OS/device adapter.
- Target adapters must declare capability grants, quotas, error categories, cancellation, handle generations and any blocking behavior. `std` is **not Linux-only**: Windows, macOS and hosted WASI can provide supported APIs; unsupported modules fail closed.
- A kernel or embedded HAL may expose separate `platform/*` drivers to an application without redefining `core` as OS-dependent or claiming complete `std` support.
- The std library remains Oreslang-first for user-visible policy; Java/Truffle, JNI, .NET, C/C++, WASI and native syscalls are backend adaptation, not competing language semantics.

## Target library profiles (proposed policy, not current parser syntax)

| Profile | Allowed libraries | Runtime provider | Examples |
| --- | --- | --- | --- |
| `freestanding-core` | `core` | target traps/intrinsics only | boot code, kernels, tiny MCU scalar functions |
| `freestanding-alloc` | `core + alloc` | explicit allocator, target traps | embedded collections/firmware |
| `freestanding-actor` | `core + actor`, optional `alloc` | explicit executor, bounded mailboxes, optional allocator/clock | embedded actor scheduler |
| `hosted` | `core + alloc + actor + std` (target supported subset) | capability-checked platform adapter | Linux/Windows/macOS server, CLI |
| `wasm-no-imports` | `core`, optional `alloc`, only supported `actor` subset | guest-local memory; no required imports | wasm32-unknown-unknown |
| `wasi-hosted` | `core + alloc` and supported `std` subset | explicit WASI host capabilities | wasm32-wasi |

Suggested manifest/CLI shape **for future design only**:

```toml
[build]
target = "thumbv7m-none-eabi"
library_profile = "freestanding-core"
```

The compiler must not accept this setting as a no-op. Until implementation, reject unrecognized profile entries. A future `--library-profile` switch may override or verify the manifest. A *target triple is not by itself a proof* that runtime libraries are implemented.

## Compatibility and packaging plan

1. Introduce compiler-owned canonical `core/*`, `alloc/*`, `actor/*`, `std/*` namespaces and a versioned intrinsic/effect manifest. Import precedence must disallow project or classpath shadowing of reserved library modules.
2. Existing `stdlib/core.ores` currently resolves as `std/core`; keep it as a transitional compatibility facade after `core/*` is executable. Its current `min_int`/`max_int`/`clamp_int` algorithms belong in the real `core`. Do not make a freestanding program import `std/core` to obtain those helpers.
3. Move logical library sources under an explicit split such as `stdlib/core/**`, `stdlib/alloc/**`, `stdlib/actor/**`, `stdlib/std/**`. Keep source origin/provenance and AOT/Native Image resource registry mechanically checked.
4. Do **not** move all collections into `core`: heap-backed `List/Vector/Map/Set` algorithms move to `alloc`, fixed-size arrays and allocation-free iterators stay in `core`.
5. Canonical `core` must not transitively touch Java `OresEvalRootNode` host convenience implementations, JVM collection allocation, OS calls, Node APIs, .NET managed host APIs, or a C runtime `malloc`.
6. Existing `std/*` loader and native-first migration proposals (#218/#237/#135) should be *restacked*, not independently rewritten. Preserve existing linked-program, AOT, and hot-load behavior during migration.
7. Library/core ABI versioning must distinguish source/API compatibility, target-required intrinsics, and actor runtime capability sets. No silent host fallback.

## Required verification before declaring support

**Admission tests** (each backend): compile `core`-only source without `std` references; reject `core -> alloc`, `core -> std`, `alloc -> std`, forbidden transitive imports and denied host intrinsics; reject unsupported target/runtime features before emitting executable artifacts.

**Executable no-OS tests** (native targets): link a `core` scalar program freestanding, no libc, no system allocator, no OS entrypoint wrapper, no dynamic loader. Inspect undefined symbols and program headers. For alloc profile, inject a deterministic bounded allocator and prove success, OOM error, alignment, no implicit heap. For actor profile, inject a static executor and test saturation, ordering, cancellation, supervision and state isolation.

**Wasm tests**: require zero imports for `wasm-no-imports`; reject WASI or JS host calls. Separate WASI capability-granted tests; no conflation of host-side Node workers with in-guest actor support.

**Semantic tests**: common `.ores` source vectors for int overflow, float edge cases, Option/Result, slice bounds, ownership moves/loans, sendability, unwind/trap, heap exhaustion and actor quiescence. Record exact source SHA and executed test steps; an empty Actions job or a standalone C++ mailbox unit test is not guest-language parity.

**Backend status at proposal time**: Java/Graal has the leading language/library implementation but is hosted; LLVM has restricted Cortex-M scalar IR and an experimental fixed actor bootstrap; Wasm uses the LLVM bridge with zero imports; .NET and Forge have arithmetic bootstrap subsets. None is proven to implement this entire split.

## Incremental acceptance order

1. Agree and version the layer DAG, target profiles and intrinsic capability taxonomy.
2. Implement a transitive import/effect admission check in the Java reference compiler and negative tests, without changing guest stdlib behavior.
3. Establish a `core` source resolver/prelude and migrate pure helpers + fixed-size/Option/Result semantics; verify hosted and freestanding code generation where available.
4. Introduce `alloc` with injected allocators and heap quota/OOM proof. Migrate heap-backed collections and pure codecs.
5. Detach actor contracts from `std`; bind to supplied executors/allocators/clocks with fixed-capacity embedded smoke.
6. Reclassify `std` as OS/platform adapters; move filesystem/network/time/process conveniences and retain security capability checks.
7. Expand LLVM, Wasm, .NET and Forge with the **same canonical fixture suite**. Only advertise a profile on a backend after actual compile/link/run validation.

Related source architecture: `docs/NATIVE_FIRST.md`, `docs/CORE_NATIVE_STDLIB_AUDIT_2026-10-09.md`, `docs/PROJECT_MANIFEST.md`, `docs/TARGET_ABI_V1.md`. All historical docs using “core stdlib” should be interpreted as describing the **pre-split** layout until migration is complete.
