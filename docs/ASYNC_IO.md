# Async standard-library I/O

Core and standard-library I/O is asynchronous-only. Plain guest APIs return
native Oreslang `Future<T>` values; `await` suspends the source continuation
and releases its actor/root execution carrier. Completion resumes through the
owning scheduler; I/O workers never execute guest callbacks. Blocking JDK calls
are host implementation details admitted only on bounded context-owned I/O
workers, never on an actor/root/CONTROL execution carrier.

Legacy `*_async` spellings remain temporary aliases for source compatibility;
they do not select a different implementation. New code should use the plain
names and `await` them.

| API | Awaited result |
| --- | --- |
| `fs.read_text(path)` | `String` (UTF-8) |
| `fs.exists(path)` | `bool` |
| `fs.write_text(path, text)` | `void` |
| `fs.append_text(path, text)` | `void` |
| `fs.remove(path)` | `void` |
| `fs.mkdir_all(path)` | `void` |
| `network.connect(host, port)` | `TcpConnection` |
| `socket.read_line()` | `Option<String>` (`None` at EOF) |
| `socket.write_text(text)` | `void` (flushes the text) |
| `socket.close()` | `void` |
| `http.get_text(url)` | `String` (response body) |
| `http.post_text(url, text)` | `String` (response body) |

`File` aliases `fs`; `net` aliases `network`. Async TCP connections are local
capabilities, not mailbox-sendable values. Await each operation before starting
the next operation in the same direction; TCP reads and writes may run in parallel.

```ores
pub async routine main(): void {
  await fs.write_text("./message.txt", "hello");
  val String text = await fs.read_text("./message.txt");
  await stdio.println(text);
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

The server-side HTTP API returns futures for listener creation, accept, body
reads, response writes and graceful shutdown. Stdin and stdout are also submitted
through the bounded native-I/O worker boundary; callers should await writes when
ordering or completion matters. Synchronous abort remains a control operation,
not a data-I/O path. This implementation does not add physical actor heap
isolation.

## Validation

`AsyncNativeIoTest` covers real filesystem operations, permission denial and
symlink escape, untrusted actor authority, HTTP GET/POST and redirect suppression,
TCP write/read/EOF, bounded admission and context shutdown. A delayed TCP peer
withholds its response until a second actor runs on the **same single actor
carrier**, proving that the first actor's pending read does not occupy that carrier.
EOF, errors and future results travel through normal continuation lowering.

Run `mvn -Dtest=AsyncNativeIoTest test` with JDK 25.

## Asynchronous directory operations (Oreslang stack CLI)

The Java/Graal interpreter exposes capability-gated `fs.list_dir(path): Future<List<String>>`,
`fs.is_dir(path): Future<bool>` and `fs.is_symlink(path): Future<bool>`. `list_dir` returns only
the immediate child **names**, sorted lexically, and refuses directories with
more than 20,000 children. It rejects a symlink at the input path; callers
must check children before descending. Each operation requires scoped filesystem
read permission. These metadata operations are not a race-free openat-style
directory handle and are not sufficient for processing hostile mutable trees.
The language also exposes `String.split_literal`, `trim`, `starts_with`,
`ends_with`, and `contains_literal` for source-level code generators.

## Audited Oreslang Stack CLI source-generation primitives

`fs.write_text_atomic(path, value)` uses a temporary UTF-8 file adjacent to
the destination and an atomic move/replace. It requires write permission on
both the lexical path and canonical parent target, refuses an existing symlink
destination, and does not fall back to a non-atomic move. Atomic replacement
is **one file at a time**, not a transaction over all RPC/Lambda outputs.

Metadata operations `fs.is_dir` and `fs.is_symlink` resolve and authorize
the parent before inspecting a child entry without following its terminal
symbolic link. They can inspect an exactly granted project root even when
its parent directory is not granted; symlinked ancestors cannot extend the
granted read scope. `fs.list_dir` returns immediate names in sorted order,
rejects symlink inputs, and caps enumeration at 20,000 names.

All path-based checks remain subject to time-of-check/time-of-use races when
a different process can concurrently change directory entries. This API is
not a directory-handle sandbox for actively hostile mutable trees. Use it
for locally trusted project source generation; a production hostile-tenant
builder must add race-free directory capabilities and multi-file transaction
admission.

**Validation:** The private mirror of the Java/Graal reference runtime
executed the `NativeCliIoTest` suite (5 passing tests) including the complete
`oreslang-stack-cli/src/main.ores` source, symlink escape denial and atomic
write operations:
https://github.com/ores-stack/ores-stack-cli/actions/runs/37884619591

The downstream generator additionally passed its full real-runtime command
integration:
https://github.com/ores-stack/ores-stack-cli/actions/runs/37884619670
