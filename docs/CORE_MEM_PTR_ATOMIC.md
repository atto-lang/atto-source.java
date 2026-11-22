# Java-owned `core/mem`, `core/ptr`, and `core/atomic` (.atto)

**Status: source-only policy helpers, not installed/import-linked Atto core APIs.**
Each compiler owns and may implement its `.atto` modules independently.
These Java-local helpers intentionally do not depend on `alloc`, `std`,
host Java, a scheduler, an OS, or a garbage collector.

## Modules

- `stdlib/core/mem.atto`: power-of-two alignment, checked align-up and
  stride, byte-count multiplication under an explicit bound, and span fitting.
  Accepts zero-sized values; all sizes/offsets are nonnegative signed i64.
  Invalid integer results are `-1`, and invalid predicates are `false`.
  `array_bytes_checked` treats a zero stride as an empty byte footprint.
- `stdlib/core/ptr.atto`: checked byte-span end, typed indexed byte offset,
  and split preflight. Index is strictly less than element count. The
  index helper rejects zero-sized elements, invalid alignment, unaligned bases
  or strides, and overflow/bounds failures before computing addresses.
- `stdlib/core/atomic.atto`: validates the order codes relaxed (0),
  acquire (1), release (2), acq_rel (3), seq_cst (4). The portable default
  is sequential consistency. Reject acquire or acq_rel on store, release or
  acq_rel on load, relaxed fence, release or acq_rel as CAS failure, and
  any CAS failure stronger than its success ordering.

## Security boundary

**These helpers are arithmetic policy, not authority.** They cannot attest
region provenance, actor/tenant ownership, incarnation, generation, a live
shared/exclusive loan, initialization, memory residency, or a valid pointer.
Caller-provided sizes and order codes are not authenticated capability grants.
No raw addresses, guest dereference, `Pointer<T>` constructors, `rt ref`,
`rt deref`, `rt unref`, `drop`, heap allocation, or atomics are exported.
Trusted compiler/runtime code must prove all rights and lifetime invariants
at every actual memory access or ownership transfer.

`core/atomic` has **no load/store/CAS/fetch_add/fence implementation**:
using ordinary `.atto` variables or locks as an atomic fallback would
misrepresent lock-free and memory-order semantics. An Atomic<T> intrinsic must
have a genuinely typed/AOT-lowered backend implementation, a supported-type
gate, race/ordering tests and fail-closed unsupported-platform diagnostics.

The Atto spec currently has competing pointer-admission drafts:
[atto-spec #18](https://github.com/atto-lang/atto-spec/pull/18) proposes
checked opaque Pointer<T> capabilities, while
[atto-spec #154](https://github.com/atto-lang/atto-spec/pull/154) disallows
public pointer intrinsics. This PR chooses neither side: it exposes *only*
checked arithmetic, preserving the existing compiler gates.

## Verification and integration gates

Source-only policy tests run via
`node --test tests/core-memory-policy-source.test.mjs`.
They check the exact documented scalar subset of these three `.atto` files,
including i64 boundaries. A pass is **NOT** native Atto parsing/import,
compiled-guest execution, threading, native AOT, atomic linearizability,
pointer safety, destructors or cross-backend parity.

Before marking these modules usable in an actual Atto build:
1. Resolve `core/` import and declaration visibility against real compiler.
2. Compile and run real positive and negative `.atto` guest fixtures in
   the Java/Graal compiler and native-image mode.
3. Wire authenticated compiler intrinsics for size_of, align_of, move/drop,
   checked pointer capabilities and real atomics; prove single ownership and
   no cross-actor raw-pointer transport.
4. Test on the exact source SHA with genuine executed runner steps. A
   zero-step CI failure is NOT_EXECUTED, not test success or failure.
5. Reconcile LLVM [#801](https://github.com/atto-lang/atto-source.llvm/pull/801),
   Rust [#419](https://github.com/atto-lang/atto-source.rs/pull/419),
   atomic spec [#175](https://github.com/atto-lang/atto-spec/pull/175)
   and pointer spec [#18](https://github.com/atto-lang/atto-spec/pull/18).
