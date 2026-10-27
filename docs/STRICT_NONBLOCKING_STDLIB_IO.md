# Strict nonblocking standard-library I/O

Status: **release-blocking invariant**.

Oreslang standard-library I/O must never depend on a data-plane host call that
parks an actor carrier, ROOT carrier, or one platform/pooled/virtual worker per
operation. A worker-offloaded blocking syscall is not a compliant nonblocking
backend. A bounded reactor may sleep only in a multiplexed
readiness/completion wait such as epoll/kqueue/IOCP.

## Required architecture

All core/std I/O uses canonical names and returns `Future<T>` (or is consumed
with `await`). Core/std does not provide duplicate `*_async` names and does
not provide `*_sync`/`*_blocking` escape hatches.

If explicitly blocking compiler/build tooling is ever retained, it must remain
outside core/std and be visibly named `*_sync` or `*_blocking`. It must never
be an implicit fallback for guest I/O.

Compliant providers include epoll/kqueue/IOCP readiness, io_uring completion, or
another backend with the same no-per-operation-thread-parking contract. If a
target cannot provide a compliant primitive for filesystem, DNS, console, pipe,
TCP, HTTP, or another I/O operation, that operation must fail closed as
unsupported.

## Java strict-boundary state

The Java/Graal branch treats legacy blocking host adapters as quarantined
migration code, not stdlib backends. Canonical FS/TCP/HTTP/terminal names
produce Futures. Until a strict provider exists they settle as failed Futures
before host I/O. Legacy `*_async`, `*_sync`, and `*_blocking` source names
are rejected. `stdlib/testing.ores` is pure and does not write to stdout.

The Rust release gate `tools/stdlib-boundary-audit` checks both sides of the
boundary: packaged `.ores` modules may not use legacy/blocking I/O spellings,
and Java guest dispatch may not reach `NativeIo`, worker-offloaded
`AsyncNativeIo`, blocking stdin/stdout, blocking socket methods, or the legacy
JDK HttpServer listener.

Legacy runtime classes may still contain blocking APIs for migration tests; they
must not be presented as strict providers or become reachable from guest
dispatch.

## Lifecycle rule

Logical Future settlement is not sufficient for reclamation. Cancellation,
timeout, completion, or close may win while a native readiness registration or
callback is still physically alive. Actor `get_done()` and operation-frame
reclamation must wait for both terminal settlement **and** physical retirement.

## Migration order

1. Connect completion-driven TCP to scheduler continuations and prove
   cancellation/readiness races, partial I/O, backpressure, EOF, and close.
2. Add nonblocking terminal/stdin/stdout and asynchronous DNS.
3. Build HTTP/TLS on those providers.
4. Add a target-specific asynchronous filesystem provider; do not substitute a
   blocking worker pool.
5. Run a shared cross-backend conformance fixture plus exact-head native/AOT
   execution before declaring parity.

No compatibility mode may silently re-enable blocking I/O in a profile that
claims strict stdlib conformance.
