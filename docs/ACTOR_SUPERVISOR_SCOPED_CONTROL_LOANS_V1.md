# AttoLang actor/supervisor scoped CONTROL loan v1 (Java)

**Status: draft, runtime host bridge only.** This change is stacked on
[Java #2820](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2820)
(under #2816 → #2758 → #2296). It must **not** be merged or described as
full compiler-backed actor-state borrow safety without executed evidence.

## Security defect addressed

The previous Java adapter could return an
`OresFuture<ActorStateSupervisorLoans.Loan>` whose result survived the
end of the physical supervisor CONTROL callback. A later CONTROL callback
with the same supervisor identity could continue reading or writing via
that loan, and a privileged caller could release the physical loan
before a guest continuation using the memory had completed.

That design could not safely represent an AttoLang guest borrow.

## New non-suspending, lexical supervisor access

`ActorStateSupervisorLoans` now exposes a trusted runtime-package
compiler bridge:

- `withSupervisorRead(grant, supervisor, Consumer<ScopedScalar>)`
- `withSupervisorWrite(grant, supervisor, Consumer<ScopedScalar>)`

The operation must enter through
`ActorRuntime.dispatchSupervisorStateControl`. It captures a **private
object identity** for the current authenticated physical CONTROL turn:
runtime, exact supervisor object, and carrier thread. Capturing a
stable supervisor ID or reusing the same CONTROL thread later cannot
reuse this identity.

Admission consumes exactly one one-use grant with the matching READ/WRITE
mode. A contended request cancels its waiter and fails fast rather than
parking a CONTROL carrier. If the waiter completes concurrently with
cancellation, its internal physical loan is reclaimed and no loan is
handed to the guest.

After immediate admission, a short-lived `ScopedScalar` view is passed
to the synchronous callback. On every primitive access it verifies:

1. Current authenticated CONTROL turn is **the same captured object**.
2. It is physically executing on the expected carrier and supervisor.
3. The view has not expired, the region has not been STOP-revoked, and
   the underlying loan remains physically held by its minted execution.
4. A write has an exclusive WRITE loan.

A view can be captured in a Java variable, but cannot be used after the
callback, on a different thread, on another runtime, or from another
CONTROL turn. The API returns `void`, not a loan or raw mutable
reference. In `finally`, the view is invalidated **before** the host
releases the physical loan. Callback exceptions therefore cannot
strand the writer or expose old state to a new owner.

The scoped view exposes only a primitive `long` read/write, not an
`ActorOwnedGuestState`, heap pointer, `ByteBuffer` or mutable projection.

## Transitional adapter and residual risk

The old `redeem(grant, supervisor) -> OresFuture<Loan>` is now
**package-private**, documented as a trusted *legacy host/test adapter*.
It still permits host-owned loan handles across CONTROL callbacks.
It is intentionally retained so existing FIFO/cancellation tests and
the initial Java host substrate remain inspectable, but **MUST NOT** be
used by source-level AttoLang lowering or exposed through FFI.
Removing it after the scoped and runtime lifecycle tests execute is a
required follow-up; package privacy alone is not a verified runtime
capability fence against all code in the trusted Java package.

This change establishes **a narrow physical lexical borrowing contract
for primitive state**, not arbitrary asynchronous guest operations. An
AttoLang supervisor method that suspends must not hold `ScopedScalar`
across `await`; future compiler lowering needs a separate explicit
suspension/continuation loan protocol. The trusted Java wrapper cannot
by itself prove the guest compiler does not smuggle a mutable pointer
through another path.

## Adversarial JUnit coverage

The existing 27-case `ActorStateRwLoansTest` is unchanged. Ten
`ActorStateSupervisorLoansTest` cases are added, bringing that class to
**32 authored tests** (59 focused JUnit cases total):

1. Scoped exclusive writer shares state with owner reads and releases
   before the next CONTROL turn.
2. Escaped `ScopedScalar` rejects read/write in later authenticated
   CONTROL turns.
3. A different physical thread cannot use a live view even while its
   originating callback is active.
4. READ scope cannot mutate, and failure releases its loan.
5. Thrown writer callback still closes the physical writer.
6. Borrow mode mismatch does not consume the original valid grant.
7. Contended read scope fails immediately and removes its queued waiter.
8. STOP during a scoped write revokes further reads/writes, while the
   physical writer remains pinned until `finally` releases it.
9. A different simultaneous CONTROL turn with the very same supervisor
   cannot use a scope minted for the first turn.
10. Reflection checks that the legacy raw-Future adapter and the scoped
    compiler bridge are not public Java methods.

The dedicated exact-head CI workflow requires Surefire reports for both
classes: **27+32=59 real tests, zero failed/errored/skipped**.
An unstarted job with `steps=null` or `runner_id=0` is **NOT_EXECUTED**
even if GitHub shows a failure or red X. Do not report the authored
tests as passes.

## Remaining production P0 gates

- Restore runner admission and execute all 57 JUnit tests at the exact
  head/tree, then actual Graal compiled guests and Native Image AOT.
- Remove or formally isolate the free-standing host adapter once its
  callers have migrated to scoped CONTROL.
- Authenticate actual compiler-generated supervisor CONTROL operations,
  and lower real `state { ... }` field accesses/projections through the
  same borrow checker/physical region; no raw aliases can bypass it.
- Prove cancellation, STOP, actor restart, carrier migration, and physical
  quiescence against real guest continuations.
- Require independent approval and LLVM/.NET NativeAOT/Wasm/Forge parity.

**Private source cannot be exported to another GitHub organization** for
its Actions minutes without independent review and exact destination/tree
authorization under conformance incidents #22/#77 and Java #963.
