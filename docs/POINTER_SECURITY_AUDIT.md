# Managed Pointer<T>: audit and hardening ledger (draft PR #443)

Audit performed 2026-10-15 against the draft pointer branch; **not** a completed safety certification. Shared design: [oreslang-common-source PR #39](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/39). LLVM admission gate: [oreslang-source.llvm PR #415](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/415).

## Current implemented behavior (on this draft branch, not main)

- `rt ref value` moves a reference-backed owned class/struct/array/list value into a strong, move-only, **managed** `Pointer<T>`.
- `rt deref pointer` and `rt unref pointer` both produce a read-only, compiler-rooted borrow of the pointee. Neither releases memory or consumes the owner. The aliasing name `unref` should be explicitly reconciled with future release semantics before graduation.
- `rt borrow` / `rt borrow mut` create ordinary checked loans. `rt take` moves ownership. `rt copy` and `rt share` of move-only pointers reject.
- Pointer views cannot be upgraded to owner by casts, assignment annotations, projected fields or collection children; existing `RuntimePointerTest` covers live-loan moves, escapes, suspension, first-class calls, structural calls, and target restrictions.

## Immediate hardening

- The runtime Java `OresPointer` constructor now rejects nested `OresPointer` values even when called from host code, rather than depending on the source type checker alone.
- Tests assert target **identity** on dereference, fail-closed null/nested handles, and explicit parser rejection of `rt ref mut`, `rt unref mut`, `rt deref mut` until exclusive pointer loans and writeback exist.
- Java's `OresPointer` currently has a `final Object target`, a constructor, and `target()`; it is a wrapper that strongly retains the target. Neither wrapper construction nor target allocation is intrinsically zero-allocation.

## Open P0/P1 blockers

1. **Allocation claim:** `rt ref` executes `new OresPointer(value)`, so at least a wrapper allocation is present in the evaluator unless optimized away. Benchmark JVM interpreter, JIT, and Native Image, and distinguish *zero-copy referent* from *allocation-free hot path*. Keep nonescaping borrow/deref paths alloc-free as a measured target.
2. **Lifetime/retirement:** no runtime pointer lease registry, actor-heap domain ID or generation is encoded in `OresPointer` today. The compiler excludes actor transport, but hot-reload teardown, native FFI, cancellation, and host interop need an explicit lifetime and revocation proof before broader pointer admission.
3. **Capability boundary:** `OresPointer(Object)` and `Object target()` are public host-side APIs. They are **not** authorization or actor-domain checks. Never expose them as guest interop or serialize raw pointer authority. A compiler check cannot validate arbitrary host-created pointers.
4. **Mutable zero-copy:** no `rt deref mut` support. Do not use reflection/interior mutation to bypass the read-only borrow. Introduce unique mutable pointer authority only after tracked overlapping loans, projected place/array realloc checks, stable owner and writeback lowering.
5. **Release naming:** `rt unref` is currently a read alias, not a `drop` or loan-ending operator. Design a migration separately, with double release and descendant-view detection, without silently changing this draft's behavior.
6. **Error paths:** add generated cleanup/drop proofs across `return`, `throw`, `raise`, `panic`, defer/recover, cancellation and supervisor teardown before native ownership moves and pinned/suspended pointers are enabled.
7. **Concurrency/ABA:** generational native regions, indexed slices and buffers require stale generation, out-of-bounds, reallocation, conflicting loan, and wrong actor/tenant rejection. Existing Java managed identity alone does not solve them.
8. **Cross-backend:** the LLVM parser currently rejects all `rt ref/unref/deref`; Wasm/Forge/.NET must also reject or implement equivalent ownership semantics. Do not count a Java PR as compiler parity.

## Release-test checklist

- Java: `mvn -B -ntp -Dtest=RuntimePointerTest test` plus ownership, actor, hot-reload and suspension suites, then full `verify` against an exact source tree.
- LLVM: execute `tests/native_ownership_boundary_matrix.py <compiler>`; test four explicit pointer operations reject and **no IR output** is produced.
- Use ASan/UBSan/TSan where applicable on native region/pointer lowering, run negative fuzz tests and cancellation/unwind stress once native pointer admission is added.
- Measure allocation bytes, copy count, and p99 latency for `rt ref`, `rt deref`, `rt borrow`, `rt take` and preallocated buffer loops. `rt copy` may allocate by contract; `rt share` needs an independently proved ownership protocol.
- Current org Actions failures have reported zero executed steps on inspected runs; do not present these as passing compiler tests. Use an authorized private CI runner or local exact-tree builds for executable evidence.

Keep this PR draft until the release gates pass; these changes alone do not establish safe native pointers or zero-allocation pointer creation.
