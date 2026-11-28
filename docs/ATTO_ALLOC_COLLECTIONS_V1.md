# Java/GraalVM AttoLang alloc collections V1

**Scope:** the Java compiler owns the AttoLang source implementations in
`stdlib/alloc/bitset.atto`, `linked_list.atto`, and `map.atto`. These are
not Java collection facades. They use scalar `int` keys/values and owned
`Array<int>` buffers. This V1 does **not** certify general generic,
native-image, actor-send, or hard-real-time capability.

## Public API / behavior

| module | concrete type | API |
| --- | --- | --- |
| `atto.alloc/bitset` | `BitSet` | `new_bitset`, `try_new_bitset`, `len`, `is_empty`, `get`, `try_set`, `clear_all`, `count_ones`, `first_set`, `try_union_with`, `try_intersect_with` |
| `atto.alloc/linked_list` | `IntLinkedList` | `new_linked_list`, `len`, `is_empty`, `allocated_slots`, `push_front`, `push_back`, `try_push_front`, `try_push_back`, `front`, `back`, `pop_front`, `pop_back`, `remove_first`, `contains`, `to_array`, `clear` |
| `atto.alloc/map` | `IntMap` | `new_map`, `try_new_map`, `len`, `capacity`, `is_empty`, `get`, `get_or`, `contains_key`, `try_insert`, `remove`, `clear` |

`BitSet` stores nonnegative 63-bit words, with 63 cached powers of two
to avoid shift tokens not supported by the current Java Atto lexer.
`IntLinkedList` is an index-linked doubly-linked owned array graph with
private recycled slots. `IntMap` is a fixed-capacity open-addressing
integer map with tombstones; negative keys use adjusted signed remainder.
These internals may differ between backend implementations.

Boundaries: `BitSet` size 0..1,048,576 bits, `IntLinkedList` maximum
1,048,576 physical slots, `IntMap` capacity 1..1,048,576.
`try_set` validates index before modifying a word. Bitset union and
intersection validate equal length before mutations. Map full-capacity
new-key insert returns `Err("CapacityExceeded")` without changing state;
updating an existing key is still allowed. `remove` zeros the old scalar
value and marks a tombstone; `clear` resets all scalar slots. Linked list
`to_array()` returns an independently owned snapshot.

**Fallibility boundary:** `try_new_bitset` and `try_new_map` reject
invalid *logical bounds* with `Result`, and `try_push_front`/
`try_push_back` check logical node quota. None of these APIs yet
guarantees typed recovery from physical allocator OOM, runtime traps, or
constructor partial-allocation failures. Full `Allocator` and cleanup
provider integration must precede stronger guarantees.

## Compiler-owned module admission

The closed resource registry reserves `atto.alloc/*`, aliases bare
`alloc/*` to the same unit identity, and admits only three known
`.atto` files. Unknown modules, ambiguous suffixes, traversal, Java source
and ambient project/stdlib imports from alloc fail closed. Maven packages
the original `.atto` bytes as compiler resources; no user-provided
filesystem lookalike may override a registered module.

## Generic ownership milestone (not implemented)

A future `HashMap<K,V>` must require a compiler-verified, deterministic
`Hash<K>` and `Eq<K>` witness with stable hash/equality while a key is
stored. Mutable/borrowed keys cannot be aliased or exposed to mutation.
Values must preserve ownership across `insert`/replacement, `remove`,
snapshot/iterator operations, and deterministic exactly-once destruction.
Disallow unproved `copy!`, `share!`, `take!`, actor sends, lifetime
escapes, and virtual dispatch until typed compiler/runtime gates exist.
Never infer generic safety or no-GC from this int-specialized implementation.

A future owned `LinkedList<T>` requires move-aware node allocation and
destruction, iterator invalidation or generational handles, and verified
rollback on allocation failure. A generic `BitSet` is not needed because
the bit domain is inherently boolean.

## Material evidence

The branch has eight Java JUnit methods and three executable `.atto`
fixtures; exact-head tests in `.github/workflows/atto-alloc-collections-guest.yml`
must run nonzero steps and prove the actual guest returned exit 42.
Queued/zero-step CI is `NOT_EXECUTED`. The LLVM source embedding
and source-free reference oracle are not JVM, Graal Native Image, or cross-
backend guest execution evidence.

No direct merge to protected main is implied by source or model checks.
