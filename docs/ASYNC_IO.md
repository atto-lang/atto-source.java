# Awaitable standard-library I/O

Oreslang I/O is **awaitable by default**. Canonical I/O method names do not use
an `_async` suffix: they return native `Future<T>` values and are consumed with
`await`. Await suspends the source continuation and releases its actor/root
execution carrier; I/O workers never execute guest callbacks.

The method name describes the operation, not the scheduling mechanism:

| Canonical API | Awaited result |
| --- | --- |
| `fs.read_text(path)` | `String` (UTF-8) |
| `fs.exists(path)` | `bool` |
| `fs.list_dir(path)` | `List<String>` |
| `fs.is_dir(path)` / `fs.is_symlink(path)` | `bool` |
| `fs.write_text(path, text)` | `void` |
| `fs.write_text_atomic(path, text)` | `void` |
| `fs.append_text(path, text)` | `void` |
| `fs.remove(path)` / `fs.mkdir_all(path)` | `void` |
| `network.connect(host, port)` | `TcpConnection` |
| `socket.read_line()` | `Option<String>` (`None` at EOF) |
| `socket.write_text(text)` | `void` |
| `socket.close()` | `void` |
| `http.get_text(url)` | `String` (response body) |
| `http.post_text(url, text)` | `String` (response body) |
| `stdio.stdin.read_line()` | `Option<String>` (`None` at EOF) |

`File` aliases `fs`; `net` aliases `network`. TCP connections are local
capabilities, not mailbox-sendable values. Await each operation before starting
the next operation in the same direction; TCP reads and writes may run in
parallel when the ownership rules permit it.

```ores
pub async routine main(): void {
  await fs.write_text("./message.txt", "hello");
  val String text = await fs.read_text("./message.txt");
  stdio.println(text);
  return;
}
```

## Explicit synchronous/blocking implementation names

When an API needs to expose that its **underlying host implementation** is
synchronous/blocking, use the `*_sync` suffix. The guest call is still
awaitable and still returns `Future<T>`:

```ores
val String text = await fs.read_text_sync("./message.txt");
val TcpConnection socket = await network.connect_sync("127.0.0.1", 9000);
```

`stdio.stdin.read_line_sync()` follows the same convention as the other explicit
sync spellings.

`_sync` means “this implementation performs synchronous/blocking host I/O on
a bounded native-I/O worker”; it does **not** mean “park the actor carrier”.
A future `*_blocking` spelling, if ever needed for a distinct API contract,
must follow the same rule: it remains awaitable unless a separate language
proposal explicitly introduces carrier-blocking execution.

Historical `*_async` spellings that already shipped remain compatibility
aliases during the migration window. New APIs must not add an `_async` suffix.
For example, the outbound proxy API is `await http.request(...)`, not
`await http.request_async(...)`.

Scoped filesystem/network permissions are checked before or during native-I/O
admission as appropriate. Canonical path resolution, resource permission checks,
DNS/connect, file operations and blocking client HTTP work execute off the actor
carrier. Only immutable argument values are captured. HTTP redirects remain
disabled so a response cannot bypass the destination grant. Failures retain
their cause and a bounded `native:io` boundary annotation.

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
remain separate operations. `stdio.stdin.read_line()` is migrated to the bounded
native-I/O scheduler; stdout/console export is not changed by this proposal.
This implementation does not add physical actor heap isolation.

## Validation

`AsyncNativeIoTest` covers real filesystem operations, permission denial and
symlink escape, untrusted actor authority, HTTP GET/POST and redirect suppression,
TCP write/read/EOF, bounded admission and context shutdown. A delayed TCP peer
withholds its response until a second actor runs on the **same single actor
carrier**, proving that the first actor's pending read does not occupy that carrier.
EOF, errors and future results travel through normal continuation lowering.

Run `mvn -Dtest=AsyncNativeIoTest test` with JDK 25.

## Directory operations (Oreslang stack CLI)

The Java/Graal interpreter exposes capability-gated, awaitable
`fs.list_dir(path): Future<List<String>>`,
`fs.is_dir(path): Future<bool>` and
`fs.is_symlink(path): Future<bool>`. `list_dir` returns only the immediate
child **names**, sorted lexically, and refuses directories with more than
20,000 children. It rejects a symlink at the input path; callers must check
children before descending. Each operation requires scoped filesystem read
permission. The `*_sync` forms expose the same Future-returning guest contract
while documenting the synchronous host implementation. These metadata
operations are not a race-free openat-style
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
