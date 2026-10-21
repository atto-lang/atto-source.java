# Strict asynchronous standard-library I/O

Status: **strict release contract**.

Oreslang stdlib I/O must be backed by readiness/completion mechanisms. A
blocking filesystem/socket/HTTP/terminal call moved onto a platform thread,
worker pool, or virtual thread is still blocking I/O and is not a compliant
stdlib implementation.

A bounded reactor is allowed to sleep in a multiplexed readiness/completion
wait such as `epoll_wait`, `kevent`, or an IOCP wait. That is not a
per-operation blocking data-plane call.

## Source API rule

The strict surface currently admits async I/O spellings only:

| API | Type |
| --- | --- |
| `fs.read_text_async(path)` | `Future<String>` |
| `fs.exists_async(path)` | `Future<bool>` |
| `fs.write_text_async(path, text)` | `Future<void>` |
| `fs.append_text_async(path, text)` | `Future<void>` |
| `fs.remove_async(path)` | `Future<void>` |
| `fs.mkdir_all_async(path)` | `Future<void>` |
| `network.connect_async(host, port)` | `Future<TcpConnection>` |
| `socket.read_line_async()` | `Future<Option<String>>` |
| `socket.write_text_async(text)` | `Future<void>` |
| `socket.close_async()` | `Future<void>` |
| `http.get_text_async(url)` | `Future<String>` |
| `http.post_text_async(url, text)` | `Future<String>` |
| `http.listen_async(host, port, capacity, body_limit, deadline_ms)` | `Future<HttpServer>` |
| `stdio.print_async(value)` / `println_async(value)` | `Future<void>` |
| `stdio.stdout.write_async(value)` / `println_async(value)` | `Future<void>` |
| `stdio.stdout.log_async(...values)` | `Future<void>` |
| `stdio.stdout.log_list_async(values)` | `Future<void>` |
| `stdio.stdin.read_line_async()` | `Future<Option<String>>` |

Bare synchronous names such as `fs.read_text`, `network.connect`,
`http.listen`, `stdio.stdout.write`, and ambient global `print` are not
part of the strict typechecked stdlib surface.

The canonical `*_blocking` spelling is reserved for future
synchronous-looking APIs. It must mean **implicit continuation suspension over
the exact same nonblocking provider**. Java does not admit those spellings yet,
because that lowering is not implemented. A `*_blocking` implementation may
never call a blocking host syscall directly or by worker offload.

## Java/Graal state

The Java backend currently fails closed for stdlib I/O until strict providers
are installed:

- async calls typecheck but settle as failed Futures with an unsupported-provider
  error before host I/O;
- old bare synchronous names are compiler errors;
- filesystem/TCP/HTTP/stdio dispatch has no edge to the legacy blocking
  `NativeIo`, `AsyncNativeIo`, JDK `HttpServer`, `BufferedReader`, or
  `PrintWriter` I/O paths;
- injected legacy TCP/HTTP handles cannot use transport read/write/respond/
  accept/shutdown operations through guest dispatch;
- `stdlib/testing.ores` is pure and performs no terminal I/O.

The legacy `NativeIo`, `AsyncNativeIo`, and `NativeHttp` classes remain
temporarily for migration/reference tests. They are **not stdlib backends** and
must stay unreachable from strict guest dispatch.

## Backend requirements

A compliant backend must satisfy all of the following:

1. **TCP/network:** nonblocking descriptors plus readiness/completion
   notification; no blocking connect/read/write worker fallback.
2. **Filesystem:** a target-specific asynchronous completion provider. If the
   target cannot provide one, filesystem stdlib I/O is unsupported. Ordinary
   blocking file reads on a worker are not a fallback.
3. **HTTP:** a transport constructed over compliant nonblocking network/TLS
   primitives. A synchronous HTTP client/server moved to worker threads is not
   compliant.
4. **Terminal/pipes/stdio:** nonblocking descriptor/reactor integration or an
   explicitly nonblocking host sink. Unsupported targets fail closed.
5. **DNS:** asynchronous resolver integration or fail closed; blocking
   `getaddrinfo` offload is not compliant.
6. **Cancellation/backpressure:** bounded admissions and cancellation must not
   require a parked thread per operation.
7. **Completion:** Futures settle from readiness/completion notification and
   resume through the Oreslang scheduler. Guest callbacks never execute on the
   reactor.

## LLVM/native state

The native Linux TCP substrate already uses nonblocking sockets,
`MSG_DONTWAIT`, nonblocking `accept4`, eventfd wakeups, and epoll readiness.
Source-level native stdlib I/O remains fail-closed until typed I/O registry and
continuation lowering are complete. No thread-pool blocking fallback is
permitted.

## Other backends

Backends that do not yet expose guest stdlib I/O must continue to reject it.
WASM currently denies guest host imports entirely. Host build/launcher I/O
(e.g. compiler artifact reads or launcher diagnostics) is outside this stdlib
contract and must not be repurposed as a guest I/O backend.

## Release gates

Java strict releases require:

- `tools/strict-nonblocking-io-audit.py` with zero violations;
- `tools/stdlib-boundary-audit` with no bare synchronous/legacy transport
  calls in packaged `.ores` modules;
- `StrictNonblockingStdlibIoTest` proving sync spellings are rejected and
  admitted async operations fail before host I/O while providers are absent;
- later, when providers land, real progress/cancellation/backpressure/EOF/
  timeout tests proving operations complete without a per-operation blocked
  thread.

Passing historical `AsyncNativeIoTest` or JDK HTTP transport tests is not
evidence of strict stdlib conformance. Those tests exercise quarantined legacy
adapters.
