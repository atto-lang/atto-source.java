# Async standard-library I/O

The async APIs return native Oreslang `Future<T>` values. `await` suspends the
source continuation and releases its actor/root execution carrier. Completion
resumes through the owning scheduler; I/O workers never execute guest callbacks.
Existing synchronous APIs remain available for compatibility.

| API | Awaited result |
| --- | --- |
| `fs.read_text_async(path)` | `String` (UTF-8) |
| `fs.exists_async(path)` | `bool` |
| `fs.write_text_async(path, text)` | `void` |
| `fs.append_text_async(path, text)` | `void` |
| `fs.remove_async(path)` | `void` |
| `fs.mkdir_all_async(path)` | `void` |
| `network.connect_async(host, port)` | `TcpConnection` |
| `socket.read_line_async()` | `Option<String>` (`None` at EOF) |
| `socket.write_text_async(text)` | `void` (flushes the text) |
| `socket.close_async()` | `void` |
| `http.get_text_async(url)` | `String` (response body) |
| `http.post_text_async(url, text)` | `String` (response body) |

`File` aliases `fs`; `net` aliases `network`. Async TCP connections are local
capabilities, not mailbox-sendable values. Only one socket read and one socket write may be active in each direction.
Overlapping same-direction operations fail fast instead of parking extra I/O
workers. TCP reads and writes may run in parallel.

```ores
pub async routine main(): void {
  await fs.write_text_async("./message.txt", "hello");
  val String text = await fs.read_text_async("./message.txt");
  stdio.println(text);
  return;
}
```

Grant the same filesystem/network permissions required by synchronous calls.
Actor-local capabilities are checked before submission. Canonical path resolution,
resource permission checks, DNS/connect, file operations and blocking client HTTP
work execute off the actor carrier. Only immutable argument values are captured.
HTTP redirects remain disabled so a response cannot bypass the destination grant.
Failures retain their cause and a bounded `native:io` boundary annotation.

## Execution and lifecycle

Each context owns a virtual-thread executor with a hard maximum of 128 in-flight
standard-library operations. Submission above that bound returns a failed future;
there is no unbounded backlog or caller-runs fallback. Socket close has 16
separately reserved in-flight control slots, so saturated reads cannot consume
all admission needed to close their sockets. These are executor admission
counters, not additional actor channels. One future is created per
operation, not one channel. Await uses the actor's existing continuation transport.

This is **non-blocking for guest execution**. The portable implementation offloads
blocking host filesystem, TCP and HTTP client calls; it does not promise kernel
asynchronous filesystem I/O or zero blocked OS threads. HTTP client connections
are currently per call and closed after completion. No throughput improvement is
claimed for tiny cached files.

Cancellation of an individual I/O future is declined (`cancel` returns false):
filesystem writes cannot safely promise rollback. Ending an actor does not undo
an already submitted operation. Context closure stops new admission, interrupts
workers, closes tracked sockets and settles pending futures. A filesystem syscall
that ignores interruption may still finish after context closure. Await outstanding
writes when their completion matters; closing a socket interrupts its pending read.
Close TCP sockets explicitly when finished; context shutdown is a fallback.

The server-side HTTP API already returns futures for accept, body reads, response
writes and graceful shutdown. Listener setup and synchronous abort/close control
remain separate operations. Stdout/console export and stdin are not migrated by
this change. This implementation does not add physical actor heap isolation.

## Validation

`AsyncNativeIoTest` covers real filesystem operations, permission denial and
symlink escape, untrusted actor authority, HTTP GET/POST and redirect suppression,
TCP write/read/EOF, bounded admission and context shutdown. A delayed TCP peer
withholds its response until a second actor runs on the **same single actor
carrier**, proving that the first actor's pending read does not occupy that carrier.
EOF, errors and future results travel through normal continuation lowering.

Run `mvn -Dtest=AsyncNativeIoTest test` with JDK 25.


## Bounded network text APIs

Whole-text HTTP calls, including `http.get_text_async` and
`http.post_text_async`, admit at most **4 MiB UTF-8 bytes** per request
body and response body. A declared oversized response is rejected immediately,
and chunked/unknown-length responses are independently capped while reading.
The response stream is closed on overflow; HTTP redirects remain disabled.
Large responses need separately designed chunked/backpressured streaming APIs.

`socket.read_line_async()` admits at most **65,536 UTF-8 bytes** in
one line (before UTF-8 decoding). A line exceeding that bound fails and closes its connection rather
than silently returning a truncated record. LF, CRLF, CR, and a trailing EOF
line are accepted. `socket.write_text_async()` admits at most **65,536 UTF-8
bytes** per write call. The underlying compatibility methods share these
limits, even when dispatched through a bounded I/O worker.

A synchronous network call from an **UNTRUSTED** actor turn now fails closed
rather than blocking its assigned carrier. The `*_async` family moves native
blocking calls onto bounded I/O workers so awaiting network data can release
the actor carrier. This is defensive hardening, **not** a claim that untrusted
actors currently have network authority: `untrustedActor()` still defaults
to zero capabilities and effective untrusted actor policies strip requested
NETWORK grants. Scoped opt-in network grants, per-actor byte and concurrency
budgets, and full compiler-driven preemption for untrusted loops remain in
#485, #484 and #402.
