# Actor FFM retirement and GC boundary: audit contract

This document is scoped to the host-level actor arena prototype in the stacked
drafts #580 and #699. It is **not** evidence of completed Oreslang guest
allocation lowering, per-actor physical collectors, or hostile isolate security.

## Separate the identities

1. **Semantic owner**: one stable `ActorId`, independent of the current
   scheduler carrier thread. Every guest-visible i64 slot operation checks it.
2. **Actor allocation domain**: bounded `ActorMemorySlice` with reservations,
   persistent storage and turn-local scratch. Its quota is *logical*.
3. **Physical FFM arena**: native bytes owned by a Java 25
   `java.lang.foreign.Arena.ofShared()`, explicitly closed after last loan.
4. **Physical JVM/Native Image GC heap**: Java wrappers, continuations,
   Truffle objects, mailbox data and other managed allocations. Ordinary
   actors on the same JVM share its tracing collector even if their payload
   bytes are off-heap. A separate Graal isolate/Engine is an additional
   physical heap policy, not an automatic property of actor arenas.

## Required lifecycle invariants

- **Admission:** native allocations and the composite `ActorArena`
  registration are serialized against the owning actor's shutdown.
- **Scratch:** a turn-local scope cannot cross await, return or carrier
  suspension. Stale generations and foreign actors must be rejected.
- **Borrow:** a persistent loan may pin native bytes beyond the source turn.
  While borrowed, a slot cannot be mutated or deleted.
- **Logical retirement:** reject guest reads, writes and fresh allocations
  immediately. Repeat shutdown/retirement requests are safe.
- **Physical retirement:** never close the FFM scope while its loans remain.
  Run the close once; if bookkeeping/callback subsequently throws, resume
  accounting on retry without attempting the native close twice.
- **Last completion:** one loan decrements its counters once; if its last
  completion encounters a release failure, calling `Loan.close()` again
  retries the unfinished physical cleanup.
- **Quota:** do not refund native bytes on logical retirement. Refund exactly
  once after native physical close has succeeded, and preflight actor-local
  and global counters atomically. If destruction of ordinary actor storage
  fails, retain its accounting until a successful retry.
- **Best effort:** one failed native region must not prevent attempts to
  revoke sibling regions, graph regions or direct-memory blocks. Aggregate
  errors and propagate the failure.
- **Safety after failure:** never call a failed release `physicallyClosed`.
  Keep retained region references so supervisor cleanup can retry.

## Verification performed

- Native JDK 25 FFM component checks: real `Arena` allocation, scratch
  expiry, pinned loans, cross-thread completion, quota and injected callback
  failure, 64 rounds of eight competing closers. Test harness uses a
  lightweight `ActorRuntime` stub; it does not compile all of Oreslang.
- Exact Git-blob syntax parse of the modified production `ActorRuntime`
  and real JUnit files on a funded runner.
- Real-runtime JUnit regressions added for sibling-region shutdown, late loan
  quota release, callback failure and same-loan retry. These require executed
  full-source Maven verification before merge.

## Blocking gates for integration

Full Maven + JDK 25 suite, GraalVM Native Image AOT/hybrid and FFM reachability,
compiler-lowered source actor slots, cross-carrier continuation/cancellation
quiescence, cross-backend source conformance, GC pause/allocation telemetry,
and negative sandbox/host-access coverage remain unproven by the component run.
**Keep draft until those gates pass.**
