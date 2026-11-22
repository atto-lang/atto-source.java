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
