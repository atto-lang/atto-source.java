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
