# AttoLang allocation policy modules (Java backend)

The files in this directory are **AttoLang .atto implementations**, not Java
allocator implementations. Each compiler is free to maintain independent
source and runtime integration.

- `heap.atto`: overflow-safe reservations, release accounting, retirement.
- `arena.atto`: aligned bump cursor, rewind/reset policy, epoch fencing.
- `region.atto`: owner/incarnation checks, quiescence and retirement policy.

**Security/safety boundary:** None of these classes grants physical allocation
or deallocation authority. Offset integers are never guest pointers. Trusted
allocators must authenticate receipts and exact drop completion, maintain
physical loan/turn/continuation/I/O/producer counters, prevent escape, and
scrub/free only after independently proven quiescence. Compiler-inserted Drop
and borrowed-lifetime validation are required for actual deterministic
memory management. The public `account_release`, `try_reset`, and
`try_mark_reclaimed` methods only update policy/accounting state; treating
those calls alone as authorization is unsafe.

`tests/fixtures/alloc-heap-arena-region-policy.atto` is an intended guest
conformance fixture returning exit code 42 on successful evaluation. It is
**not compiled or executed** by adding it to the repository. There are no
claims here of zero-GC, native AOT, leak freedom, or runtime parity.

# alloc/slab, alloc/deque, alloc/actor_heap (.atto)

These **AttoLang-authored** modules provide a conservative scalar/word-storage
baseline. They do not invoke Java, C, .NET, JS, FFI, or OS APIs.

- `slab.atto`: bounded `int` storage; free-list reuse, generation-checked
  handles and permanent quarantine at generation exhaustion.
- `deque.atto`: `int` double-ended ring, including explicit transactional
  `reserve` and signed-zero-safe pop operations.
- `actor_heap.atto`: bounded logical 32-bit word arena with first-fit freed
  span reuse, checked word access, generation fencing, zero-on-free, quotas and
  idempotent logical retirement.

**Scope & limitations:** This v1 intentionally does not fake generic
`Slab<T>`/`Deque<T>` storage, transitive Drop, or native off-heap ownership.
`Array<int>` and metadata objects are backend-managed. Numeric handle
domains/incarnations are *not* authorization. The compiler/runtime must prove
the heap object's actor owner, exclusive mutation, lifetime, cross-actor
non-escape, cancellation/turn quiescence and physical resource cleanup. A
trusted runtime must also provide unique incarnation provenance.

Positive guest source probes are under `tests/fixtures/alloc/*.atto`; an
executed source-to-guest compilation and runtime check is required before
reporting these as passing. Public `atto.alloc/...` resolution is also
backend-dependent. A future generic implementation should use typed move-only
slots with compiler-checked borrows and deterministic drop, never an
unverified `Array<T>` alias or unchecked host pointer.

Overlaps: Java #1724 implements a **runtime** actor slab heap; this `.atto`
logical allocator is complementary, not a replacement for its runtime fences.

## Instance-identity audit (hardening v2)

Numeric domain/incarnation identifiers and generations are *not* globally
unique. Each Slab/ActorHeap now creates a fresh private object-identity token.
Handles retain that token, and `contains` first checks reference identity
(`is`) before touching slots. Even two independently created containers with
identical numeric identifiers, slot indices and generations reject cross-use.
A fabricated handle holding an unrelated public OwnerToken is also rejected.
The OwnerToken is not a backend scheduler/actor/tenant authorization capability:
the compiler/runtime must still enforce private-field access, identity,
non-escape, actor affinity, borrow lifetimes and source/physical memory fences.
Runtime/guest tests must prove those checks; no guest passes are asserted here.

# Atto-owned allocator policy modules

These are implementation files **in AttoLang**. Each compiler repository owns
its own copy; cross-backend source does not have to be byte-identical.

* `alloc/aligned`: checked power-of-two padding, over-allocation plans and
  page counts. Requires an authenticated allocator to produce *physically*
  aligned pinned storage; integer offsets alone do not establish that fact.
* `alloc/stats`: bounded per-owner accounting ledger and fragmentation inputs.
  Real heap usage, leak findings, frees and allocation identities must originate
  in authenticated allocator receipts, never caller-supplied counters.
* `alloc/secure`: logical in-place clearing and closed-buffer state, **not**
  secret-key-safe erasure. `secure_clear` deliberately rejects until the
  backend implements guaranteed non-elidable zeroization and ownership/lifetime
  controls, including on failure and cancellation paths.

The fixture in `tests/fixtures/alloc_foundations_smoke.atto` specifies the
intended public behavior. It is a guest execution *candidate* and must not be
reported as passing until source module resolution, compiler lowering and
actual execution are verified on the exact head. Java, LLVM and Rust/Wasm may
have different runtime providers and implementation details.

Use `atto.alloc/<module>` for unambiguous imports. Unsupported backend
capabilities must fail closed; do not silently lower secure memory to ordinary
array clearing or treat logical padding as a physical alignment proof.
