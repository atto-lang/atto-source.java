# alloc/pool, alloc/arc and alloc/weak — source-first slice

**Status: SOURCE IMPLEMENTATION OF SERIAL CONTROL-PLANE POLICIES. Native guest execution and actual allocator-backed `Arc<T>`/`Weak<T>`/`Pool<T>` are NOT VERIFIED.**

atto-source.java owns its own `.atto` sources. These modules contain real checked policy transitions, not a replacement for compiler-inserted ownership, atomic memory management, or type-safe allocation.

- `pool.SlotPool` provides bounded generation-stamped slot leasing, reuse, retirement on generation exhaustion, stale/double release rejection, and a non-closing gate while any lease remains. A backend-owned payload store must enforce exclusive lease provenance, run the destructor and scrub before `release`. PoolLease is metadata, **not** a transferable security token.
- `arc.ArcLedger` models bounded strong/weak transitions and reserves the implicit weak sentinel while the last strong owner is being destroyed. `release_strong()==2` requires the trusted adapter to destroy the payload **exactly once**, then call `finish_drop`; `can_reclaim_control` remains false beforehand. This mutable ledger must not itself be shared between threads.
- `weak.WeakKey` and `may_upgrade` define domain/generation/stale-key admission and non-resurrection. The function is only a preflight; atomic increment-if-nonzero plus authenticated allocation identity must be supplied by the runtime.

## Required actual APIs (not shipped by this slice)

```atto
// TARGET ONLY; constructors, type identities and effects require spec approval.
val owned: Arc<T> = Arc.new(take! value);
val other: Arc<T> = owned.clone(); // explicit strong owner, frozen contents
val weak: Weak<T> = owned.downgrade();
val maybe: Option<Arc<T>> = weak.upgrade();
drop! other;
drop! owned; // exactly once T destructor, weak.upgrade() becomes None
// Unique owned Pool<T> must tie each checked lease to the actual payload.
```

`share!` stays the canonical explicit sharing operation; `weak!` is the proposed ownership operation. An `Arc<T>` nominal wrapper cannot silently manufacture sharing from a scoped borrow. This feature is separate from Java's actor-local mutable `Ref<T>`/`WeakRef<T>` (PR #565).

## Integration and security requirements

1. **Compiler/type checker:** `Arc<T>`, `Weak<T>` and `Pool<T>` must own opaque, provenance-checked runtime handles. Reject direct shared mutation, live borrows across drop/suspend/actor boundary, use-after-move, unproved freeze, fake generation/foreign actor/isolate, and unsupported backend operations without emitting guest code.
2. **Runtime:** checked atomic strong/weak updates with explicit acquire/release memory ordering; no strong resurrection after zero; exactly-once drop on normal, exception, panic and cancellation exits; weak control remains allocated until final weak retirement; reject counter overflow.
3. **Pooling:** memory reuse only after destruction, zeroization where required, loan and continuation quiescence, generation advance; do not recycle exhausted generations; pool lease and allocation domain must be authenticated independently of numerical slot receipts.
4. **Cycles:** strong cycles need explicit weak back-edges, cycle prevention, or a separate validated reclamation strategy. Reference counting alone does not collect cycles.
5. **Evidence:** exact source SHA and compiler SHA; real source-to-target positive and negative guest tests; no artifact for invalid programs; JDK25/Graal AOT, LLVM Clang/GCC ASan/UBSan, .NET NativeAOT, browser/Wasm/isolate as available. Count source checks separately and mark unrun as NOT_EXECUTED.

## Test matrix (pending actual guest wiring)

- Pool: capacity=1/full, release/reuse new generation, stale/double return, reject close with live leases, end-generation retires slot, closed refuses acquisition.
- Arc: two strong/one weak, clone overflow, last-strong pending destructor, no upgrade during or after last drop, finish_drop idempotence, implicit weak sentinel, final weak reclamation.
- Weak: matching generation upgrades only while strong>0, cross-domain/slot mismatch denial, stale/recycled generation denial, generation exhaustion, no forged-key authority.
- Concurrency: CAS/upgrade versus last drop races; count underflow/overflow; cancellation/unwind; no actor-local or cross-worker sharing without explicit domain proofs.

**Verification classification for this change:** `.atto` source authored; real backend compile/guest execution = **NOT_EXECUTED**. Do not mark Ready based only on source review or a host-side transition oracle.

## Extended alloc/allocator, alloc/set, alloc/drop source slice

- `allocator.Layout` performs alignment, checked array span and logical
  allocation-bound arithmetic in native `.atto`. No pointer or allocator
  receipt is exposed. Physical allocation and failure propagation are still
  compiler/runtime responsibilities. Zero-sized layouts carry no fabricated
  address.
- `set.BoundedIntSet` is an **implemented integer-domain set** backed by an
  owner-local `Array<int>`: duplicate insert/no-op remove, membership,
  deterministic ascending enumeration, union/intersection/difference and
  subset checks. Key domain must be 1..65536. This is not a generic hash set,
  not thread-safe, and does not synthesize unproved Hash/Eq/ownership.
- `drop.DropStack` tracks LIFO destructor obligations and blocks duplicate,
  out-of-order, or unfinished transitions. Its numeric tickets are advisory
  bookkeeping, not trusted destructor receipts. The compiler must still
  emit exact-once drop glue and perform every destructor on all exit paths.
- `arc.ArcLedger.is_consistent()` adds an owner-local state invariant check.
  The serial ledger does not become a native atomic `Arc<T>`.

### Guest checks to execute when module linking is available

```atto
// Source-owned module entry points. Call through authenticated alloc imports.
allocator.self_test();
set.self_test();
drop.self_test();
arc.self_test();
```

Also check bad alignment, overflowing spans, out-of-range/duplicate set
keys, domain mismatches (no mutation), LIFO/double drop, last-strong
destructor pending, weak upgrades during destruction and stale generations.

**Evidence:** files are authored on the draft PR branch. Native `.atto`
parsing/import linking/guest execution, compiler-generated drop glue, atomic
race tests, ASan/UBSan, and AOT are **NOT_EXECUTED** on this increment.
This source-only implementation must not be represented as safe release-grade
physical allocation, generic Set<T>, or shared Arc<T>.
