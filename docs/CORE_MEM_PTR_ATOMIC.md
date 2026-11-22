# Java-owned `core/mem`, `core/ptr`, and `core/atomic` (.atto)

**Status: compiler-packaged Atto core-policy source; native guest execution not yet verified.**
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

## 2026-11-22 Java core import integration

Java's `CoreLibrarySources` closed registry now explicitly admits the three
`ores.core/mem`, `ores.core/ptr`, and `ores.core/atomic` modules from
`src/main/resources/dev/oreslang/core/*.atto`. Each packaged resource's
Git blob SHA was independently re-fetched and verified identical to its
corresponding backend-owned `stdlib/core/*.atto` source. Unapproved .ores
aliases and traversal paths must remain rejected.

`CoreMemoryPolicyGuestTest` adds compiler parsing/typechecking,
`LinkedProgramRunner.validate`, and real guest exit-status tests for a
checked `mem` result (42), `ptr` offset (42), and an imported atomic-order
helper used with the already implemented Java local `Atomic<int>` runtime
(42). It also tests exact resource/source identity and closed import paths.
These are executable *test definitions*, not a claim that they have run.

**CI gate:** Source Actions at Java PR #1771 head
`66b7b2be57238ebd0d5168a40f2f338cedd08252` reported failures, with
sampled `compiler-and-guest`, `prohibit-python`, and `host` jobs recording
**zero steps**. Classify as NOT_EXECUTED / runner-admission blocked, not
compiler failures. Run `mvn -Dtest=CoreMemoryPolicyGuestTest test` with an
approved full JDK/Graal toolchain on an admitted runner. Any private
cross-organization mirror requires separately authenticated source export
authorization, a verified destination and full-tree/blob proof; do not mirror
without those controls.

Java `Atomic<int>` currently has existing local guest coverage. This core
module validates memory-order parameters only; no newly ordered atomic load,
store, CAS, hardware fence, untrusted cross-actor sharing, or fully sealed
AOT-lowered guest capability is implemented by this change.

## 2026-11-22 ordered AtomicI64Cell runtime hardening

The existing real Java `AtomicI64Cell` guest backing primitive now has
validated overloads for memory orders encoded by `core/atomic.atto`:
0 relaxed, 1 acquire, 2 release, 3 acq_rel and 4 seq_cst.
`load(order)` uses `getOpaque/getAcquire/get` for admitted orders;
`store(value,order)` uses `setOpaque/setRelease/set`. RMW and
strong compare-exchange conservatively strengthen all admitted modes
to sequential consistency and still use checked arithmetic with no
overflow publication. Illegal order/arity is rejected before an atomic.

Six new `AtomicI64CellTest` JUnit methods cover invalid loads/stores,
RMW result values, illegal CAS failure orderings, checked overflow,
release/acquire publication and contended increments. The
`CoreMemoryPolicyGuestTest` also has additional real linked-guest
boundary test *definitions* for mem overflow, pointer one-past-end,
and CAS-order policy. Java guest `Atomic<T>` methods still accept
only their existing default sequentially consistent arities: explicit
order arguments are intentionally rejected by the TypeChecker pending
real typed/evaluated guest lowering.

**Local separate JDK21 algorithm smoke test** (not a reconstruction of
the repository's exact source blobs) executed successfully: ordered
load/store/CAS validation, failed-mode no publication, overflow trap,
release/acquire publication and 80,000 contended increments. This is
not compiled Atto guest, GraalVM Native Image or exact-head JUnit
evidence. Current Java PR Actions on intermediate heads have
runner-admission/zero-step failures; the exact newest head still needs
real executed JDK25/Graal compiler and guest CI.

**Open overlapping import PR:** [Java #1416](https://github.com/atto-lang/atto-source.java/pull/1416)
implements `atto.core/*` and `atto.std/*` identity-preserving aliases
and touches `CoreLibrarySources.java`. This branch currently registers
the new resources under `ores.core/*`. Incorporate #1416's unique
alias behavior without discarding newer main changes before any merge;
do not assert `atto.core/mem` has been compiler-verified yet.

This library work must not enable raw `Pointer<T>` guest operations
until the contradictory pointer specs #18 and #154 are reconciled and
backend ownership/tenant/generation/lifetime proofs execute.
