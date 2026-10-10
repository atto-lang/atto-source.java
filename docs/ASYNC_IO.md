# Async standard-library I/O

The async APIs return native Oreslang `Future<T>` values. `await` suspends the
source continuation and releases its actor/root execution carrier. Completion
resumes through the owning scheduler; I/O workers never execute guest callbacks.
Existing synchronous APIs remain available for compatibility, but bare
synchronous I/O names are **legacy**: canonical source-visible native Oreslang
blocking I/O operations **must** end in `_blocking`, while async I/O operations
**may** end in `_async`. Java/Graal supports an opt-in strict compiler naming gate via
`-Dores.compiler.strictNativeIo=true`; legacy bare names remain accepted
by default until downstream migration completes. See the [shared native I/O naming contract](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/main/docs/NATIVE_IO_NAMING.md).

This migration must add canonical blocking entry points, migrate callers,
check method/alias paths and test actual blocking/async behavior and capability
denials before removing compatibility names. The `_blocking` suffix means synchronous *source-level* return semantics, not OS-thread blocking. Its implementation **must** submit native I/O through an async I/O engine, suspend and later resume the guest continuation on the owning scheduler, and immediately free the actor carrier. Bare synchronous Java I/O methods must not be renamed without implementing this lowering.

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
capabilities, not mailbox-sendable values. Await each operation before starting
the next operation in the same direction; TCP reads and writes may run in parallel.

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
resource permission checks, DNS/connect, and filesystem operations execute
outside the actor carrier. HTTP client requests now use HttpClient.sendAsync
instead of spending a virtual thread waiting for the HTTP response. Only immutable argument values are captured.
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
blocking host filesystem and TCP calls; the HTTP client path uses asynchronous
sendAsync completion and does not occupy an offload worker while waiting for
a response. The HTTP implementation still uses JDK-managed internal threads;
this change does not establish an entirely nonblocking kernel I/O stack, and
notably does not promise asynchronous filesystem I/O or zero blocked OS threads.
HTTP client connections are currently per call and asynchronously shut down
after completion. No throughput improvement is
claimed for tiny cached files.

Cancellation of an individual I/O future is declined (`cancel` returns false):
filesystem writes cannot safely promise rollback. On context close, outstanding
HTTP transport stages are cancelled and their clients are shut down; any late
completion must not resume a closed source task. Ending an actor does not undo
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

## Async-backed `_blocking` I/O migration (Java/Graal PR #522)

The feature branch now introduces `*_blocking` entry points for filesystem reads/writes, filesystem metadata, TCP connect/socket read/write/close, HTTP client requests, console output, and stdin. These calls are **synchronous only to Oreslang source**: they yield a SourceTask continuation and submit host I/O to `AsyncNativeIo`. The I/O worker is not a scheduler carrier. Java/Graal's continuation path detects `_blocking` calls and automatically awaits the returned host future without exposing it to Oreslang source.

Canonical shape:

```ores
pub routine main(): void {
  fs.write_text_atomic_blocking("./message.txt", "hello");
  val String text = fs.read_text_blocking("./message.txt");
  stdio.println_blocking(text);
  return;
}
```

`*_async` retains `Future<T>` return semantics. Naming and Java implementation are not evidence of LLVM support. Existing bare entry points remain **legacy compatibility aliases**. Set `-Dores.compiler.strictNativeIo=true` during Java/Graal compilation to reject registered bare filesystem, TCP/network, HTTP-client and stdio I/O names. Strict mode is not yet the default. Native `_blocking` method extraction as a first-class callable is now rejected even without strict mode: the compiler does not yet support implicit suspension effects on indirect calls. The final release gate remains caller conversion, default strict enforcement, and full cross-backend verification.

Native `_blocking` calls must not park an actor carrier, call `Future.get()/join()` on that carrier, or execute guest callbacks on I/O workers. All capability checks and bounded I/O admission remain mandatory.



Existing API names below describe the **currently implemented** Java/Graal
behavior and are not canonical names under the new language rule. Required
canonical replacements include `fs.list_dir_blocking`,
`fs.is_dir_blocking`, `fs.is_symlink_blocking` and
`fs.write_text_atomic_blocking`. `stdio.println` and any other synchronous
file/console/network I/O builtins must also be audited and either renamed with
`_blocking` or implemented with a verified async contract. The already
documented `*_async` functions remain correctly named; the suffix is optional
for genuinely async calls.

Do not switch the documentation to claim those replacement functions work
until the builtin resolver, compiler conformance tests and dependent CLI
programs have been migrated. Migration must check qualified methods and
aliases, explicit capability gating, diagnostics for legacy bare calls,
and two-actor **single-carrier** tests proving `_blocking` calls do not occupy the carrier, plus the existing explicit-`await` async tests. `_blocking` returns `T` directly in source; `*_async` returns `Future<T>`. Neither may use host `join()`/`get()` on the actor carrier.

## Synchronous directory operations (Oreslang stack CLI)

The Java/Graal interpreter exposes capability-gated `fs.list_dir(path): List<String>`,
`fs.is_dir(path): bool` and `fs.is_symlink(path): bool`. `list_dir` returns only
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

## Async-only I/O architecture target

Core and standard-library I/O should be exclusively based on asynchronous
transport operations. The user-visible `*_blocking` spelling is **not** a
request to use a blocking syscall: it returns a plain `T` to the caller by
implicitly suspending its continuation while the same async operation settles.
The `*_async` spelling returns `Future<T>`; `await` explicitly suspends.
Both paths share capability admission, bounded queues, failure propagation and
scheduler-owned resumption. An OS thread must never be occupied waiting on
behalf of an Oreslang logical task when a native async transport exists.

Transport readiness must be tracked separately from carrier-freedom:

| Transport | Status on this branch |
| --- | --- |
| HTTP client GET/POST | `HttpClient.sendAsync` and bounded callback-driven admission; both source styles use it |
| Filesystem / filesystem metadata | Host offload; true async or platform-specific design pending |
| TCP connect/read/write | Host blocking Socket offload; NIO state machine pending |
| Stdio/stdin/stdout | Host offload or legacy synchronous facades; nonblocking transport pending |
| Native LLVM backend | Not implemented; native compiler must fail closed |

The HTTP path is a first migration step, **not** evidence of complete async-only
I/O, no OS-blocked threads, zero-copy I/O, or Java/LLVM parity. In particular,
`sendAsync` may still rely on implementation-managed threads for DNS or other
operations; it avoids `HttpClient.send` on the Oreslang I/O worker.
