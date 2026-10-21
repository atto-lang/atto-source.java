# Strict nonblocking standard-library I/O

Status: **release-blocking invariant**.

Oreslang standard-library I/O must never depend on a data-plane host call that
parks an actor carrier, ROOT carrier, or one platform/pooled/virtual worker per
operation. A worker-offloaded blocking syscall is not a compliant nonblocking
backend. A bounded reactor may sleep only in a multiplexed readiness/completion
wait such as epoll/kqueue/IOCP.

## Required architecture

All stdlib I/O is submitted to a readiness/completion provider and settles an
Oreslang `Future<T>` (or implicitly suspends a `*_blocking` continuation)
from completion notification. The source-level `_blocking` suffix, where
supported, means sequential continuation semantics only. It never authorizes a
blocking host operation.

Compliant examples include epoll/kqueue/IOCP readiness, io_uring completion, or
another backend with the same no-thread-parking contract. If a target cannot
provide a compliant primitive for filesystem, DNS, console, pipe, TCP, HTTP, or
another I/O operation, that operation must fail closed as unsupported.

## Java strict-boundary state

The Java/Graal branch now treats the legacy blocking host adapters as
**quarantined migration code, not a stdlib backend**. Bare synchronous FS/TCP/HTTP/terminal spellings are compiler-rejected. Admitted
async forms return failed Futures until a strict readiness/completion provider
is installed. Canonical `*_blocking` spellings remain reserved until implicit
continuation-suspension lowering is implemented. `stdlib/testing.ores` is
pure and no longer writes to stdout.

The release gate `tools/strict-nonblocking-io-audit.py` checks that stdlib
dispatch cannot reach legacy `NativeIo`, worker-offloaded `AsyncNativeIo`,
blocking stdin/stdout, blocking socket methods, or the legacy JDK HttpServer
listener. `tools/stdlib-boundary-audit` separately rejects bare synchronous I/O
calls from packaged `.ores` modules.

Legacy runtime classes still contain blocking APIs and must not be presented as
strict providers. Completion is not achieved until those adapters are replaced
and the disabled stdlib operations are backed by real nonblocking providers.

## Migration order

1. Replace quarantined worker-offloaded network/TCP/HTTP adapters with
   completion-driven transports, then reconnect only those verified providers
   to the stdlib surface.
2. Add a target-specific nonblocking filesystem provider; do not substitute a
   thread pool for missing kernel async support.
3. Add nonblocking terminal/stdin/stdout descriptor/reactor providers on
   supported targets; keep operations unsupported elsewhere.
4. Implement canonical `*_blocking` lowering, if retained, as implicit
   continuation suspension over the exact same async provider.
5. Require the strict audit plus single-carrier progress, cancellation,
   timeout, backpressure, close, EOF, and stale-completion tests before release.

No compatibility mode may silently re-enable blocking I/O in a profile that
claims strict stdlib conformance.
