# Strict asynchronous standard-library I/O

Status: **strict release contract**.

Oreslang core/stdlib I/O is asynchronous by default and must be backed by
readiness/completion mechanisms. A blocking filesystem/socket/HTTP/terminal call
moved onto a platform thread, worker pool, or virtual thread is still blocking
I/O and is not a compliant stdlib implementation.

A bounded reactor may sleep in a multiplexed readiness/completion wait such as
`epoll_wait`, `kevent`, or an IOCP wait. That is not a per-operation blocking
data-plane call.

## Source API rule

Canonical I/O names return `Future<T>`; there is no duplicate `*_async`
surface in core/std:

| API | Type |
| --- | --- |
| `fs.read_text(path)` | `Future<String>` |
| `fs.list_dir(path)` | `Future<List<String>>` |
| `fs.is_dir(path)` / `fs.is_symlink(path)` / `fs.exists(path)` | `Future<bool>` |
| `fs.write_text(path, text)` / `write_text_atomic(...)` / `append_text(...)` | `Future<void>` |
| `fs.remove(path)` / `mkdir_all(path)` | `Future<void>` |
| `network.connect(host, port)` | `Future<TcpConnection>` |
| `socket.read_line()` | `Future<Option<String>>` |
| `socket.write_text(text)` / `socket.close()` | `Future<void>` |
| `http.get_text(url)` | `Future<String>` |
| `http.post_text(url, text)` | `Future<String>` |
| `http.listen(host, port, capacity, body_limit, deadline_ms)` | `Future<HttpServer>` |
| `stdio.print(value)` / `stdio.println(value)` | `Future<void>` |
| `stdio.stdout.write(value)` / `println(value)` / `log(...)` / `log_list(values)` | `Future<void>` |
| `stdio.stdin.read_line()` | `Future<Option<String>>` |

Normal sequential source uses `await` on those Futures. The names themselves do
not imply a synchronous host call.

Core/std intentionally does **not** expose `*_async`, `*_sync`, or
`*_blocking` aliases. If an explicitly blocking tool is ever retained for
compiler/build tooling, it must live outside core/std, be visibly named
`*_sync` or `*_blocking`, and must never be substituted for the canonical
guest API.

Ambient global `print` is not a guest I/O escape hatch.

## Java/Graal state

The Java backend currently fails closed for stdlib I/O until strict providers
are installed:

- canonical I/O calls typecheck as Futures but settle as failed Futures with an
  unsupported-provider error before host I/O;
- legacy `*_async`, `*_sync`, and `*_blocking` spellings are not admitted;
- filesystem/TCP/HTTP/stdio dispatch has no edge to legacy blocking
  `NativeIo`, worker-offloaded `AsyncNativeIo`, JDK `HttpServer`,
  `BufferedReader`, or `PrintWriter` I/O paths;
- injected legacy TCP/HTTP handles cannot use transport read/write/respond/
  accept/shutdown operations through guest dispatch;
- `stdlib/testing.ores` is pure and performs no terminal I/O.

The legacy `NativeIo`, `AsyncNativeIo`, and `NativeHttp` classes remain
migration/reference code. They are **not stdlib backends** and must stay
unreachable from strict guest dispatch.

## Backend requirements

A compliant backend must satisfy all of the following:

1. **TCP/network:** nonblocking descriptors plus readiness/completion
   notification; no blocking connect/read/write worker fallback.
2. **Filesystem:** a target-specific asynchronous completion provider. If the
   target cannot provide one, filesystem stdlib I/O is unsupported. Ordinary
   blocking file reads on a worker are not a fallback.
3. **HTTP/TLS:** a transport constructed over compliant nonblocking network/TLS
   primitives. A synchronous client/server moved to worker threads is not
   compliant.
4. **Terminal/pipes/stdio:** nonblocking descriptor/reactor integration or an
   explicitly nonblocking host sink. Unsupported targets fail closed.
5. **DNS:** asynchronous resolver integration or fail closed; blocking
   `getaddrinfo` offload is not compliant.
6. **Cancellation/backpressure:** bounded admissions and cancellation must not
   require a parked thread per operation.
7. **Completion/lifetime:** Futures settle from readiness/completion
   notification and resume through the Oreslang scheduler. Guest callbacks never
   execute on the reactor, and actor teardown cannot reclaim an operation until
   its kernel registration and callbacks have physically retired.

## LLVM/native state

The native Linux TCP substrate uses nonblocking sockets, `MSG_DONTWAIT`,
nonblocking `accept4`, eventfd wakeups, and one-shot epoll readiness. The
operation-lifecycle layer separately tracks logical Future settlement and
physical registration/callback retirement. Source-level native stdlib I/O
remains fail-closed until typed I/O registry and continuation lowering are
connected to that substrate.

## Other backends

Backends that do not yet expose guest stdlib I/O must continue to reject it.
WASM currently denies guest host imports entirely. Host compiler/launcher I/O
is outside this stdlib contract and must not be repurposed as a guest I/O
backend.

## Release gates

Java strict releases require:

- `tools/stdlib-boundary-audit` unit tests and a zero-violation source/runtime
  boundary audit;
- `StrictNonblockingStdlibIoTest` proving canonical operations fail before
  host I/O while providers are absent and legacy suffixes are rejected;
- exact-head JDK 25 and Native Image AOT/hybrid jobs that actually execute;
- when providers land, real progress/cancellation/backpressure/EOF/timeout tests
  proving operations complete without a per-operation blocked thread.

Passing historical `AsyncNativeIoTest` or legacy JDK HTTP transport tests is
not evidence of strict stdlib conformance.
