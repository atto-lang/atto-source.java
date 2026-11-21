# OresVM GenServer correlated Future bridge (host-only integration stage)

This branch incorporates the internal bounded `GenServerReplyLedger` from
Java PR #1210 plus a typed `GenServerCallPort<T>` that binds opaque tickets
to `OresFuture<T>`, with atomic **ledger** arbitration between reply, timeout,
cancellation, actor-generation failure and close.

* A package-private runtime caller registers a target and receives a ticket
  and a future. Tickets do not cross the Oreslang guest DATA boundary.
* Cancellation must revoke the correlation in the admission predicate before
  Future cancellation becomes observable. Completion occurs after dropping
  the short port lock; no guest callback runs under its lock.
* Stale/wrong-actor reply fails; actor crash settles only the exact incarnation.
  Timeout settles the future but cannot retract already admitted server work.
* Failure to admit/send the actual message **must** call `fail()` with the
  original ticket, because this bridge itself does NOT enqueue to ActorMail.
  Actor/supervisor authority must be minted by OresVM, not guessed from a UUID.
* `close` settles outstanding futures and prevents further registration.

The existing `GenServerReplyLedger` and this bridge are Java runtime plumbing.
This is **not** guest-visible `call_async`, nor source actor handler dispatch,
callback ABI enforcement, scheduled deadline timing, or a complete GenServer.
No fabricated reply tokens, magic guest imports, or data-only strings substitute
for the runtime capability. The next stage is `ActorRef<GenServer>` typed
admission, dispatch and actual guest handler source tests; followed by CONTROL
restart, AOT/native and LLVM parity.

Acceptance unit test: `GenServerCallPortTest`, including 250 reply/cancel races.
As with other guest tests, source-org Actions must execute actual steps before
claiming whole-repository validation.


## UUID-free ledger integration note (2026-10-20)

This branch's `GenServerReplyLedger` now uses bounded lazy generational slots
and exact live ticket identity rather than per-call `UUID.randomUUID()` plus a
`HashMap<UUID, Ticket>`. Package-private `Ticket.target()` and
`Ticket.matchesTarget()` are maintained for `GenServerCallPort<T>` compatibility.
The correlation ledger retains no completed ticket in its slot; once exhausted,
a slot is permanently retired instead of wrapping its 32-bit generation.

The ledger is still **not** a transport protocol. Never serialize Ticket or
permit an untrusted numeric locator to complete an OresFuture. The existing
call port's Future completion may notify host waiters off the actor lane;
that must be reworked for any actual guest callback/continuation path to ensure
scheduler-owned dispatch (no inline guest callback / Zalgo). The existing
`pendingByTarget` index and the ledger are independent structures which must
continue to retire atomically under the call-port gate. Failure to publish a
mailbox request must compensate the reply reservation without moving/dropping
the caller's payload incorrectly.

Standalone JDK21 `GenServerReplyLedgerSmoke` and
`GenServerReplyLedgerGenerationalSmoke` passed against blob
`261a853b344a40452c3750977cf8c2a455f6c73d` (new test blob
`50cfea41c696b5368b52dc922cb324d2227da4ed`). The existing
`GenServerCallPortTest`, actual `OresFuture`, guest-source, supervisor,
cross-process and native tests **were not executed in this audit**.
