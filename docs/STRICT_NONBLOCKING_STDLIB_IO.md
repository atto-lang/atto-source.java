# Strict nonblocking standard-library I/O

Status: **release-blocking invariant**.

Oreslang standard-library I/O must never depend on a host call that parks an
actor carrier, ROOT carrier, platform worker, pooled worker, or virtual thread.
A worker-offloaded blocking syscall is not a compliant nonblocking backend.

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

## Java audit result at introduction

The current Java/Graal backend is **not yet compliant**. The release gate
`tools/strict-nonblocking-io-audit.py` intentionally rejects the existing
blocking/worker-offloaded paths, including:

- synchronous `java.nio.file.Files` operations;
- synchronous `HttpClient.send`;
- `java.net.Socket` connect/read/write;
- JDK `HttpServer` response streams;
- blocking stdin/stdout adapters;
- `AsyncNativeIo` virtual-thread worker offload;
- source-visible bare synchronous FS/TCP/HTTP I/O entries.

This document must not be used to claim completion while that audit reports a
nonzero violation count.

## Migration order

1. Replace worker-offloaded network/TCP/HTTP paths with completion-driven
   transports.
2. Add a target-specific nonblocking filesystem provider; do not substitute a
   thread pool for missing kernel async support.
3. Replace terminal/stdin/stdout paths with a nonblocking descriptor/reactor
   provider or reject them on targets that cannot provide one.
4. Remove bare synchronous stdlib I/O entrypoints. `*_blocking`, if retained,
   must lower to implicit continuation suspension over the same async provider.
5. Require the strict audit plus single-carrier progress, cancellation,
   timeout, backpressure, close, EOF, and stale-completion tests before release.

No compatibility mode may silently re-enable blocking I/O in a profile that
claims strict stdlib conformance.
