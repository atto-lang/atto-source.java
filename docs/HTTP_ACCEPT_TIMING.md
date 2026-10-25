# HTTP acceptance timing

`HttpExchange.http_queued_ns` and `accept_ready_ns` are read-only monotonic
nanosecond timestamps in the same clock domain as `admitted_ns` and
`process.monotonic_ns()`. They retain normal exchange ownership checks and
survive ownership transfer with the exchange state. Capture values before moving
or responding from the supervisor.

`http_queued_ns` marks arrival at the pending-accept handoff, after admission
bookkeeping. `accept_ready_ns` is set when a pending exchange is removed for
`accept()`, immediately before returning its completed Future, or immediately
before completing an already-waiting accept Future. On direct delivery both
values are equal: no time was spent in the HTTP pending queue.

The core recorder adds `http.accept.queue`, the difference between these two
timestamps, for both paths. It keeps the existing bounded, opt-in buffering and
shutdown JSONL export; no synchronous logging is added to request processing.
Requests rejected before admission or never delivered to an accept consumer do
not produce an accept-queue completion event.

Record `resumed = process.monotonic_ns()` as the first source statement after
`await server.accept()`. Subtracting `accept_ready_ns` measures delivery-to-source
resumption, including Future publication, scheduler waiting, interpreter resumption
and the timestamp call. It is not a pure executor queue measurement. The ready
boundary precedes Future completion deliberately; an immediately resumed consumer
cannot observe an unset timestamp.

For a dispatch timestamp D, admission A, queue entry Q, accept readiness R and
first source observation S, the disjoint intervals are Q-A, R-Q, S-R and D-S.
Their sum equals D-A. HTTP executor wait before admission is outside this total.
