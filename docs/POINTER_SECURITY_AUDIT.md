# Managed Pointer<T>: audit and hardening ledger (draft PR #443)

> **Integration candidate, 2026-10-15:** This ledger originally recorded the
> read-only `rt unref` alias in PR #443. The new merge candidate explicitly
> changes `rt unref` to a *consuming unique-owner move* and retains `rt deref`
> as a read-only borrowed view. Earlier descriptions of `unref` as a loan
> are historical, not the integrated implementation contract. The merge
> remains draft until exact-head full CI verifies parent capability guards.
>

Audit performed 2026-10-15 against the draft pointer branch; **not** a completed safety certification. Shared design: [oreslang-common-source PR #39](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/39). LLVM admission gate: [oreslang-source.llvm PR #415](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/415).

## Current implemented behavior (on this draft branch, not main)

- `rt ref value` moves a reference-backed owned class/struct/array/list value into a strong, move-only, **managed** `Pointer<T>`.
- `rt deref pointer` produces a read-only compiler-rooted borrow. `rt unref pointer` is different: it consumes a unique named `Pointer<T>`, returns owned `T`, and tombstones the Java handle. Neither operation clones the pointee.
- `rt borrow` / `rt borrow mut` create ordinary checked loans. `rt take` moves ownership. `rt copy` and `rt share` of move-only pointers reject.
- `rt deref` views cannot be upgraded to owners by casts, assignment annotations, projected fields or collection children. `rt unref` returns ownership only after the ownership checker proves the pointer is unique and has no live borrowed views.

## Immediate hardening

- The runtime Java `OresPointer` constructor now rejects nested `OresPointer` values even when called from host code, rather than depending on the source type checker alone.
- Tests assert target **identity** on dereference, fail-closed null/nested handles, and explicit parser rejection of `rt ref mut`, `rt unref mut`, `rt deref mut` until exclusive pointer loans and writeback exist.
- Java's `OresPointer` has a mutable tombstone-backed `Object target`, an immutable execution-domain token, synchronized `target()`/`unref()`, and a constructor that strongly retains the live target. Wrapper construction is still an allocation unless the runtime/compiler proves and eliminates it.

## Open P0/P1 blockers

1. **Allocation claim:** `rt ref` executes `new OresPointer(value)`, so at least a wrapper allocation is present in the evaluator unless optimized away. Benchmark JVM interpreter, JIT, and Native Image, and distinguish *zero-copy referent* from *allocation-free hot path*. Keep nonescaping borrow/deref paths alloc-free as a measured target.
2. **Lifetime/retirement:** `OresPointer` now records an execution-domain identity, but it still has no lease registry, allocator generation, or native-region generation. Hot-reload teardown, native FFI, cancellation, and host interop still need explicit lifetime and revocation proofs before broader pointer admission.
3. **Capability boundary:** `OresPointer(Object)` and `Object target()` remain public host-side APIs. The wrapper enforces creator execution-domain identity and denies known runtime/resource capabilities, but that is defense in depth rather than a complete provenance/authorization proof. Never serialize raw pointer authority or treat arbitrary host-created referents as guest-safe.
4. **Mutable zero-copy:** no `rt deref mut` support. Do not use reflection/interior mutation to bypass the read-only borrow. Introduce unique mutable pointer authority only after tracked overlapping loans, projected place/array realloc checks, stable owner and writeback lowering.
5. **Release/drop proof:** `rt unref` is now the consuming unique-owner operation and stale/double unwraps are rejected. Native lowering still needs deterministic cleanup/drop proofs for exceptional control flow, descendant native views, and allocator generations; Java handle tombstoning alone is not a native lifetime proof.
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

## 2026-10-15 follow-up: runtime admission hardening

Additional Java changes on this draft branch:

- `OresPointer` denies scalar/Copy targets, nested `OresPointer` values, generic `AutoCloseable` resources, and directly supplied actor/mailbox/control/shared-memory/channel/mutex/future/runtime capabilities even when a host bypasses the guest compiler.
- `ActorRuntime.validateMessageGraph` refuses `OresPointer` in incoming payloads, including lists, maps, sets and arrays, before copying/freeze or mailbox message-byte reservations. Failure still occurs after the bounded mailbox slot pre-reservation, which the existing `finally` releases.
- `requireSharedMutexPayloadSafe` rejects pointer capabilities inside a host-created `SharedMutex` graph. Do not rely on language-level `Send` checks to protect these host-facing entry points.
- New regression suite `ActorPointerTransportTest` exercises freeze, private/shared mailbox, nested container payloads, and SharedMutex wrapping; `RuntimePointerTest` covers host-created scalar, async/channel/mutex, nested and null targets.

**Residual risk:** The Java wrapper's `public OresPointer(Object)` and `public Object target()` remain host-visible. Execution-domain fencing and the capability denylist are defense in depth, **not** a complete provenance, allocator-generation, recursive host-graph, or borrow-lease proof. Trusted or hostile host code can still retain aliases to an accepted raw referent; production host interop needs a capability-scoped API with explicit provenance and lifetime enforcement.

**Verification boundary:** Targeted isolated Java 21 constructor smoke compiled and executed against representative runtime-type stubs. This is *not* a full Graal/Java 25 build, not an actor integration test, and not a zero-allocation benchmark. The new JUnit/LLVM matrices are checked in but have not been executed successfully in current source-repo CI.
# Transport follow-up, 2026-10-15

Local `Channel<Pointer<T>>` is distinct from actor mailbox transport. The
compiler now permits an owning pointer through a local channel and consumes
the sender binding; borrowed views remain forbidden in buffered payloads.
Runtime dereference is fenced to the pointer's original execution domain
(actor identity, or its creating host thread). No cross-domain rehoming exists.

The full runtime scalar admission path rejects arbitrary host enums and
subclasses of extensible numeric classes: these can contain hidden mutable
state or pointer authority. Only exact BigInteger/BigDecimal representations
and explicitly known data-only ActorKind values qualify. This is not general
guest enum/ADT lowering. This branch has no OresSymbol runtime type; a stale
reference to that nonexistent class was removed to restore compilation.

`rt copy Pointer<T>` and `rt share Pointer<T>` remain fail-closed. There is no
compiler-proven recursive copier, immutable shared-owner conversion, lifetime
pinning or atomic allocator-domain handoff on this branch. Neither scalar
string transport nor same-domain pointee identity proves those features.
Isolates/processes have no raw-pointer transport path. Java host aliases to
the pointee remain outside guest ownership guarantees; the domain fence does
not revoke aliases already retained by trusted host code.

Added compiler tests cover local take, use-after-send, rejected copy/share,
and Pointer nested in Option/Result/records/arrays at actor boundaries.
Runtime tests cover local channel pointee identity, cross-thread dereference,
hidden pointers in host enum/numeric subclasses and recovery after rejection.
These tests do not measure allocations or prove cancellation/drop behavior.

