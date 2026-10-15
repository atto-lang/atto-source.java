# Pointer<T>, borrow checking, and zero-copy ownership — integration contract

Status: **design + fail-closed admission gate**, 2026-10-15. These operations
are **not implemented on current main**. Historical draft PR #443 has a managed
`OresPointer` implementation, but is over 1,000 commits behind main; neither
its prior tests nor its semantic choices may be treated as current-main proof.

## Roles and surface language

`Pointer<T>` is a compiler-owned **typed, non-null, opaque, affine owner handle**.
It is not `int`, a C `T*`, a borrow, an actor reference, or an FFI capability.
User classes/type aliases/interfaces may not shadow the reserved name `Pointer`.
No pointer integer cast, arithmetic, unchecked indexing, or fabricated handles.

The canonical operations proposed for the integrated implementation are:

| Operation | Input / result | Ownership and alias effects |
| --- | --- | --- |
| `rt ref value` | owned `T` → `Pointer<T>` | **Move** the existing value into a strong handle. The source is uninitialized/moved. No implicit clone. The target must be reference-backed and have proven storage/region provenance. |
| `rt deref pointer` | live `Pointer<T>` → scoped `borrow T` | **Read-only reborrow**, not a copy of `T`. Hold a shared loan on the exact pointer root until the view's last use / lexical release. |
| `rt unref pointer` | owned `Pointer<T>` → `void` | **Proposed explicit release** of the owning handle, after all derived loans have ended. This is not dereferencing, not a `T` result, and not a promise that Java GC immediately frees physical memory. |
| `rt take pointer` | owned `Pointer<T>` → `Pointer<T>` | Move the handle itself (including its region/epoch/provenance), not the pointee. Source becomes unusable. |
| `rt borrow pointer` | `Pointer<T>` → `borrow Pointer<T>` | Scoped shared borrow of the handle; it does not acquire ownership or permit `rt unref`. |
| `rt borrow mut pointer` | mutable `Pointer<T>` → `borrow mut Pointer<T>` | Exclusive loan, with no concurrent reads/writes/moves. Cannot launder a pointee borrow into ownership. |
| `rt copy pointer` | rejected | A unique pointer has no implicit copy operation. Even an identical address would create a second owner. |
| `rt share pointer` | rejected pending `Shared<T>` | Must not silently increment an unimplemented reference counter, manufacture shared mutation, or evade actor isolation. |

**Important unresolved compatibility decision:** older PR #443 defines
`rt unref` as an alias of `rt deref`. This draft explicitly recommends a
distinct release operation; do **not** merge that old behavior unnoticed.
A different naming decision (e.g., `rt drop` for release and reserving
`rt unref` for an owning extraction) would require updating this table,
compiler diagnostics, tests, and both backends **atomically**.

`rt deref` returns a *borrow*, never an owned `T` with an erased lifetime.
To extract an owned payload in the future, define a separate compiler-checked
`into_inner`/unwrap operation that **consumes** the sole handle and checks
outstanding borrows and drop flags. Do not implement this by casting a borrow.

## Static invariants (compiler obligations)

1. Exactly one unique owner. Every `rt ref`, `rt take`, `rt unref`, return,
   field move, and scope exit must update path-sensitive moved/drop state.
2. Many shared loans OR one exclusive loan. A dereferenced view tracks its
   **root owner**, projection path, generation, and region; aliases/reborrows
   never reset lifetime. Reject use-after-move, use-after-release, double
   release, owner rebind while borrowed, mutable aliasing, and borrowed
   return/field/closure/aggregate escapes without a proven lifetime.
3. Last use and cleanup are control-flow-sensitive. Release loans on every
   exit: normal, `return`, `throw`, `raise`, `panic`, cancellation,
   exception unwinding, and `defer`/finally. If proof is incomplete, fail
   compilation rather than approximate with a Java reference count.
4. Ordinary borrow/pointer views may not survive `await`, `select`,
   blocking channel reads, actor-turn release, callbacks, or actor state-loan
   revocation. No guest pointer in an actor message, channel, observable,
   stream, public ABI, shared/untrusted heap, or RPC payload.
5. No implicit pointer-to-pointer, raw/foreign pointer, or nullable pointer
   construction. Dynamic narrowing, `as` casts, generics, union/Option/
   Result members, and pattern bindings must preserve provenance.
6. Mutating the pointee requires a separately checked exclusive capability;
   a shared `rt deref` must not expose methods or fields that move or
   mutate the pointee through a plain annotation.
7. Admission to a native arena requires authenticated actor/region/epoch,
   live slot+generation, bounds, alignment, and active loan validation. ABA,
   exhausted generation, foreign-region, and retired-actor handles fail closed.
   An integer/host pointer is never a valid token.
8. All compiler-only checks must have defensive native/runtime gates where
   unsafe interop or cancellation could bypass them. A failed operation
   cannot partially release or resurrect a handle.

## Zero-copy versus zero-allocation

A zero-copy pointer points to **already owned** backing storage: it does not
clone, serialize, or `memcpy` the payload. A zero-allocation operation must
also introduce **no new heap wrapper, queue node, pin record, refcount cell,
or host proxy**; track the pointer/loan in SSA/stack or preallocated arena
metadata. These are different proofs. `rt ref` moving an existing object can
be zero-copy without being zero-allocation.

- Java/Graal main still uses JVM-managed objects, and draft #443's
  `new OresPointer(value)` **allocates a wrapper per `rt ref`**.
  JVM allocation/GC claims must be measured, not inferred from source syntax.
- LLVM may lower a handle as authenticated scalar bits or a stack/SSA pair,
  but only with live generation, lifetime/drop, and relocation-safe semantics.
  A raw payload address is never sufficient across region recycling.
- Zero-copy actor handoff uses `rt take` of an *owned transferable buffer*
  with atomic admission/rollback on send failure, **not** a borrowed pointer.
  Physical zero-copy further requires compatible domains/lifetime, not merely
  absence of a language `rt copy`.
- `Stream<T>`, `Observable<T>`, and `Channel<T>` may transport an **owned
  `T`/buffer**, or a future explicit pinned lease with producer-completion
  acknowledgment. They may not smuggle an ordinary `Pointer<T>` or borrowed
  slice across an asynchronous boundary. Queue machinery may still allocate.
- `rt share` on ordinary move-only buffers remains fail-closed until a
  separate immutable `Shared<T>` refcount/epoch contract proves ownership,
  cycle policy, memory ordering and isolation. No concurrent mutation by
  shared aliases.

## Intended synchronous shape (not yet compilable on main)

```ores
val Buffer bytes = make_buffer();
val Pointer<Buffer> owner = rt ref (rt take bytes);
block {
  val borrow Buffer view = rt deref owner;
  checksum(view);             // read without cloning the Buffer
}
rt unref owner;               // proposed: release owner once loans finish
```

To use a buffer in an actor pipeline, send `rt take bytes` directly instead
of retaining a `view` or `Pointer<Buffer>` through `send`/`forward`.

## Merge/verification gates

- Rebase/reconcile historical Java PR #443 on exact **current main** and
  preserve current borrow, Future cancellation, actor-state, cleanup, and
  no-Zalgo behavior; never select old files wholesale.
- Java and LLVM must parse identical source and reject identical negative
  cases for forged and stale handles, double unref, deref-after-unref,
  borrow-then-unref, raw cast, borrow alias escape, reentrant callbacks,
  actor-send/suspension, failed move, and cancellation cleanup.
- Test active loans through every normal and exceptional CFG exit; check drop
  exactly once and no resurrection during destructor/finally.
- Run Graal/JDK25 tests and Native Image; LLVM Release CTest, native source
  execution and ASan/UBSan/TSan where applicable on exact matching Git trees.
- Measure per-operation copies **and** allocations independently, including
  AOT/JIT warmup, pinned async buffers, actor messages, refcount ops and
  p50/p99 GC pauses. Only label the **proven profile** allocation-free.
- Do not merge a source feature before both backends fail closed on unsupported
  cases; Java-only availability must be explicitly gated if LLVM lags.

Related foundation: `docs/HYBRID_MEMORY_MANAGEMENT.md`,
`docs/CIRCULAR_REFERENCES_AND_GC.md` and LLVM
`docs/JVM_LLVM_OWNERSHIP_PARITY_GATES.md`.
