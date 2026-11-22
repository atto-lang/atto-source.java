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

## Trusted lexical-bracket lowering target

`TrustedAllocationScopeV1.with_lexical_scope(Supplier<T>)` now wraps an
authenticated `ScopeMark` in `TrustedLexicalUnwindV1.run`, which executes
the real native owner retirement in all synchronous completion paths.
It preserves the original user/body failure and suppresses an ordinary
cleanup failure (bounded at 64). An `Error` or `SecurityException` in
cleanup wins over a preceding ordinary body failure, retaining that body
failure as a suppressed diagnostic. After a normal return, a cleanup error
fails the operation rather than returning a success value.

Final `TrustedAllocationScopeV1.close()` refuses to silently bypass an
unconsumed lexical checkpoint. Native arena host probes exercise nested
brackets, an ordinary return, primary exception with failed destructor,
poisoned memory remaining live, close preflight, and 1,024-depth admission.
The standalone pure unwind probe was actually compiled and executed using
local JDK 21. This is **not** evidence that the Atto compiler automatically
emits the bracket or has completed suspension/actor cancellation lowering.

## 2026-11-22: bounded multi-destructor failure aggregation

A lexical scope and final owner scope can contain many destructors that
throw. The runtime must continue attempting the other safe native drops, but
must **not** accumulate an unbounded exception-suppression list. Aggregation
now caps attached suppressed failures at 64 and promotes an `Error` or
`SecurityException` above a prior ordinary failure. The old primary is
retained as a diagnostic when the new authority failure wins. Failing
destructors leave their own native allocations poisoned; neither cleanup
continuation nor diagnostic capping implies successful deallocation.

The host regression exercises 75 failing destructors against an actual native
arena, and multiple failures with a later authority error. Until a stepful
exact-head runner actually executes the integration probe, these scenarios
are **authored, not verified**. The standalone pure unwind helper **was**
compiled using local JDK21 with `-Xlint:all` and emitted
`ATTO_TRUSTED_LEXICAL_UNWIND_JDK21_PASS`; its two checked-in file blob SHAs
match the locally compiled source bytes.
