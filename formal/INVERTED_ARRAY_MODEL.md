# Bounded InvertedArray row-handle and field-loan safety model

This is a bounded executable abstract state machine for the proposed inverted class / InvertedArray SoA storage in Java issue #1424. It checks two stable handle slots, up to three distinct logical rows, swap-delete physical compaction via stable slot-to-dense indirection, row generation increments on slot retirement, owner identity fencing, and disjoint field-sensitive read/write loans. Each loan has a distinct identity, so two concurrent readers of the same slot and field can be modeled without collapsing into one set element.

The model exhaustively explores its finite reachable state space and reports counts at runtime; avoid treating an unverified hardcoded state count as evidence. Model checks include handle identity (no wrong logical row after reuse), no reuse ABA, physical-dense/handle-slot bijection, unique live rows, foreign-owner rejection, read/write conflicts on the same field, and no structural mutation while any column view is borrowed. Field loans on disjoint fields are permitted.

Negative mutations MUST produce counterexamples: missing generation bump (HandleIdentityABA), swapping logical handle identities instead of only dense physical rows (DenseSlotBijection), read/write overlap on the same field (FieldBorrowConflict), and insertion/deletion while views remain live (NoStructuralMutationDuringBorrow). Further negative mutations detect absent owner fencing and negative/out-of-bounds slots (Python negative indexing is not an acceptable runtime handle semantics).

Run with Python 3.10+ (no dependencies):

    cd formal
    python3 -m unittest -v test_inverted_array_model
    python3 inverted_array_model.py
    python3 inverted_array_model.py --mutant foreign-owner
    python3 inverted_array_model.py --mutant slot-bounds

## Proof boundary

This is bounded checking of an ABSTRACT storage/borrow protocol; it is NOT proof that the parser accepts inverted class, that the Java backend lowers it to SoA memory, that the borrow checker rejects real guest source, that a physical column remains pinned across await, that AoSoA/SoA/AoS conversions are correct, or that LLVM provides zero-GC and AOT. The model does not simulate actual column payloads, thread memory ordering, reallocations, nb Task capture, hot-reload reclamation, or ABI layout. There is no unbounded safety claim.

To promote to runtime evidence, issue #1424 requires exact-head executable guest tests for push/read/update/erase_swap, old-handle ABA, moved-survivor stable handle, foreign owner, field disjointness, overlapping alias failure, active borrow+insert/delete failure, suspend/Task borrow rejection, actor arena retirement and hot-reload revocation. Cross-backend parity requires real compiled code and CI step evidence. An abstract model cannot certify an unimplemented feature.
