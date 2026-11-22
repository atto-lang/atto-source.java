# Java/GraalVM actor graph loan: failure-atomic charge and measured overhead

**2026-11-14 — host-runtime correction only, not full compiled AttoLang guest-AOT / zero-GC proof.**

`ActorOwnedGraphArena.borrow()` previously incremented `loans[slot]` and
`activeLoans` **before** constructing the lease handle. An allocation or
class-initialization failure during `new Lease(id)` would strand an
uncloseable loan. Since actor graph retirement waits for `activeLoans == 0`,
this could indefinitely postpone deterministic payload destruction and backing
release, especially under memory exhaustion.

The new admission order:
1. Check actor ownership, exact arena identity and live node generation.
2. Reject the counter-overflow boundary before any state mutation.
3. Construct the complete loan handle.
4. Charge the per-node and global loan counters.
5. Return the handle to the actor owner.

`Lease` now uses a pre-resolved, shared `VarHandle` CAS on a volatile boolean
rather than allocating an `AtomicBoolean` per borrow. It still has one Java
`Lease` object and performs thread-safe idempotent close. The logical owner
must still call `close()`; the Java GC is not a resource release mechanism.

## Tests

`ActorOwnedGraphArenaBorrowSafetyTest` asserts one terminal close under
64 instances of concurrent double-close and actor STOP, exactly one payload
drop and backing release, borrow denial after retirement, reverse construction
order even when a destructor fails, owner-guard checks and memory counter
behavior. A 10,000-borrow warm-up and 20,000-borrow observed JDK thread
allocation sample prints the raw measured number or `UNAVAILABLE`. No
universal byte threshold or zero-GC result is asserted.

The independent GraalVM JDK25 workflow compiles the actual changed host
class with `--release 21` and runs both old/new real Maven/JUnit suites at
the exact immutable source head. An Actions job with `steps=[]` is
**NOT_EXECUTED**, not a test pass or failure of source code.

## Remaining compiler-level ownership work

The graph remains Java-managed metadata and Java-owned payload objects. This
patch does not lower native `.atto` values, prove 32-bit arena-relative
addresses, enforce unique ownership at compile time, or generate proper drop
glue for return, throw, raise, panic, STOP, Future cancellation and `await`.
A future compiler-boundary change must derive drop obligations from typed MIR,
retain resources through suspended frames, and prove every physical callback /
loan has retired before native heap reclamation.

Host allocation counters and Java tracing collections cannot establish
process zero-GC. Run a real compiled `.atto` actor-load test with verified
native-image executable identity and sustained deterministic memory budgets.
Do not export this private source to an unapproved alternate runner.
