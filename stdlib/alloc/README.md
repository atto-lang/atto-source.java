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
