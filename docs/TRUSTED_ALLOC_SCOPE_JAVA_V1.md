# Trusted allocation-scope adapter (Java backend)

This milestone builds on the already-existing `ActorLinearArena` runtime:
`TrustedAllocationScopeV1` is an **internal** JVM adapter whose `Owner`
and `Loan` objects have private constructors. No raw or serializable arena
handles are exported to Atto source; the source namespaces remain defined
in `.atto` files.

The adapter executes real `ActorLinearArena.allocateOwned`,
`putLong/getLong`, and `dropOwned` operations; the underlying arena
provides generation-checked handles and scrubs native buffer bytes at drop.
This is real actor-owned native storage, but `ByteBuffer.allocateDirect` is
JVM-managed and does **not** imply immediate OS deallocation or zero GC.

The adapter enforces one-carrier scope access, read/exclusive-write loan
gates, no drop while borrowed, reverse-order destructor dispatch, double
release rejection and exception-unwind cleanup. Failed destructor
execution poisons the allocation, prevents handle recycling, and surfaces
the error after attempting other cleanup. A poisoned allocation remains
live until the arena is shut down; it must not be reported as a completed
destructor. OOME during owner publication rolls back native allocation.

## What remains unimplemented

- No compiler lowerer inserts `trusted_open` or destructor calls into
  arbitrary Atto guest functions yet. Host JDK21 execution is **not**
  evidence of emitted guest drop glue.
- `ReservationLedger` and `DropStack` are Atto-owned policy sources
  with forgeable integer tickets; the physical adapter must not trust
  those tickets. It does not call `.atto` functions yet.
- No `Arc<T>` cross-actor sharing, Send/Shareability proof, Native Image,
  suspension/FFI quiescence or per-backend parity is asserted.

`.github/workflows/atto-trusted-alloc-host-v1.yml` checks out the exact
PR-head SHA and compiles the **real** `ActorLinearArena` alongside the new
adapter and its independent test probe on JDK21. The test marker counts
only when an actual runnable job executes.

## Additional hardening: recursive destruction

The scope now rejects allocation, borrowing and nested drop during an active
destructor callback. A failed native arena drop (including an exception) marks
the owner **POISONED**, rather than leaving a misleading DROPPING state.
A real-actor-arena host probe checks recursive callback rejection, cleanup
ordering, rejected poison replay and the absence of an illicit native refund.

## 2026-11-22: lexical cleanup and bounded tracking

Native ownership scopes now issue opaque, one-shot `ScopeMark` checkpoint
objects. `begin_lexical_scope`/`end_lexical_scope` provide a runtime
target for future compiler-inserted `finally`/exception/early-return drop
lowering. Checkpoints are identity-checked, LIFO and replay-resistant.
Cleanup preflights all inner-owner borrows before running any destructor,
then drops strictly in reverse acquisition order and aggregates failures.
A failed destructor remains poisoned and unreclaimable pending arena
shutdown.

Successful early-drop owner entries are removed immediately instead of
accumulating per-drop metadata in a long-lived actor. The host probe
includes 4,000 serial allocate/drop cycles with constant owner tracking,
nested checkpoints, borrow-blocked cleanup, stale/replayed checkpoints,
and simulated exceptional unwind. This is still **host runtime machinery**,
not automatic compiled Atto guest lowering or full GC-freedom.
