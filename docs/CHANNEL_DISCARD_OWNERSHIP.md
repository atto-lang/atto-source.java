# Discarding channel results and borrow lifetimes

Canonical source forms:

```ores
do readch ch;            // blocking read; discard the value
do writech ch, value;    // blocking write; discard the void result
do nb readch ch;         // actor-scoped async read; discard delivered value
do nb writech ch, value; // actor-scoped async write; discard completion
```

The `do` marker means the **result** is discarded, never that registration,
backpressure, cancellation, payload ownership, or scheduler safety can be skipped.
These are statement-only forms, lowered to existing channel operations with an
explicit AST marker. The blocking operations suspend the current source
continuation while waiting and reject any live ordinary lexical borrow/MutexGuard.

A `do nb` operation registers immediately without suspending the caller. Its
channel future is owned by the current actor; the runtime cancels actor-owned
pending operations on actor stop. `do nb` is rejected in ordinary (non-actor)
functions because detached operations would otherwise lack a defined lifecycle.

Writing a move-only payload transfers ownership **at registration**, even if
the future is discarded. Neither blocking nor nonblocking channels may send
borrowed references. There is no implicit ownership transfer of the channel
handle to another actor. A direct actor `self` capability may survive a mailbox
turn; an ordinary borrowed alias of `self` may not.

Important limitations: an unobserved asynchronous completion failure needs
an explicit supervision/error-reporting policy before discarding the returned
future can be treated as reliable fire-and-forget delivery. `do nb` gives
actor-lifetime cancellation, **not** delivery confirmation. Use an awaited
`nb readch`/`nb writech`, or a callback-enabled operation, whenever failure
or delivery matters. A future runtime implementation must not silently turn
this into a cross-actor shared-memory alias.

`do select` and `do nb select` retain their existing arm-specific return
discard semantics; they do not permit a deferred callback to capture a
borrowed lexical view, mutable loan, or MutexGuard.
