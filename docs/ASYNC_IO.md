# Strict asynchronous standard-library I/O

Status: **release-blocking core/std contract**.

Oreslang core/stdlib I/O is asynchronous by default. Canonical names return
`Future<T>`; callers use `await` when sequential source semantics are wanted.
Core/std does not expose duplicate `*_async` methods and does not expose
`*_sync` or `*_blocking` escape hatches.

A blocking filesystem, socket, DNS, HTTP, pipe, terminal or process-stream call
does **not** become compliant merely because it runs on a worker, platform
thread, or virtual thread. A compliant provider must use readiness/completion
mechanisms without one parked native thread per guest operation. A bounded
reactor may sleep in a multiplexed wait such as epoll, kqueue, IOCP or another
verified completion primitive.

## Canonical source surface

| API | Type |
| --- | --- |
| `fs.read_text(path)` | `Future<String>` |
| `fs.list_dir(path)` | `Future<List<String>>` |
| `fs.is_dir(path)`, `fs.is_symlink(path)`, `fs.exists(path)` | `Future<bool>` |
| `fs.write_text(path, text)`, `write_text_atomic(...)`, `append_text(...)` | `Future<void>` |
| `fs.remove(path)`, `mkdir_all(path)` | `Future<void>` |
| `network.connect(host, port)` | `Future<TcpConnection>` |
| `socket.read_line()` | `Future<Option<String>>` |
| `socket.write_text(text)`, `socket.close()` | `Future<void>` |
| `http.get_text(url)` | `Future<String>` |
| `http.post_text(url, text)` | `Future<String>` |
| `http.listen(host, port, capacity, body_limit, deadline_ms)` | `Future<HttpServer>` |
| `stdio.print(...)`, `stdio.println(...)` | `Future<void>` |
| `stdio.stdout.write(value)`, `println(value)`, `log(...)`, `logList(values)` | `Future<void>` |
| `stdio.stdin.read_line()` | `Future<Option<String>>` |

Ambient global `print` is not an I/O escape hatch.

Legacy `*_async`, `*_sync`, and `*_blocking` spellings are not part of the
core/std API. If explicitly synchronous compiler/build tooling is ever retained,
it must remain outside guest core/std and be visibly named `*_sync` or
`*_blocking`; it must never be an implicit fallback for a canonical guest call.

## Current Java/Graal state

Until a strict readiness/completion provider is connected, the Java reference
backend is intentionally **fail closed**:

- canonical I/O calls typecheck as Futures;
- evaluating those guest members returns already-failed Futures before host I/O;
- legacy worker-offloaded `AsyncNativeIo`, blocking `NativeIo`, and legacy
  `NativeHttp` transport code may remain as migration/reference code but are
  unreachable from strict guest I/O dispatch;
- `http.handler` and `http.claim` are not admitted through the strict
  core/std boundary;
- server accept/shutdown and exchange data-plane operations fail closed through
  Future results rather than invoking legacy transport methods;
- unknown members on `fs`, `File`, `network`, `net`, `http`, and
  `env` are compile errors rather than dynamic/unknown escape routes.

Fail-closed is a supported safety state, not provider parity.

## Provider requirements

A real provider may replace fail-closed behavior only after it demonstrates:

1. nonblocking/readiness or completion-driven transport;
2. bounded admission and backpressure;
3. partial read/write progress;
4. EOF and peer-reset semantics;
5. cancellation before readiness;
6. cancellation after readiness but before continuation resume;
7. timeout/completion single-winner arbitration;
8. stale generation rejection;
9. no busy polling;
10. scheduler progress while I/O is pending;
11. logical Future settlement separated from physical kernel/callback retirement;
12. actor `get_done()` cannot complete until that physical retirement occurs.

Filesystem and DNS remain unsupported on a target if the target cannot provide
a compliant completion mechanism. Blocking file or resolver calls hidden behind
a worker pool are not fallback implementations.

## Validation

The focused release gate is
`.github/workflows/strict-async-stdlib-io.yml`. It:

- verifies the exact checked-out commit and emits its Git tree SHA;
- runs the Rust `tools/strict-async-io-audit` unit tests;
- audits guest dispatch and packaged `stdlib/**/*.ores` for blocking/legacy
  edges and suffixes;
- runs `StrictAsyncStdlibIoCurrentMainTest` on GraalVM JDK 25.

A workflow that fails before any job step executes is runner-admission evidence
only. It is neither a pass nor an implementation failure.

The broader JDK/GraalVM and Native Image AOT/hybrid suites remain mandatory
before this contract can be promoted on `main`.
