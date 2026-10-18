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
