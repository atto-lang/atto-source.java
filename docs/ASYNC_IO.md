# Async standard-library I/O

Async APIs return native Oreslang `Future<T>` values. `await` suspends the
source continuation and releases its actor/root execution carrier. Completion
resumes through the owning scheduler.

Filesystem async I/O is now being migrated away from the legacy worker adapter
operation by operation. See [STRICT_FILESYSTEM_IO.md](STRICT_FILESYSTEM_IO.md)
for the exact strict-provider contract.

| API | Awaited result | Current Java/GraalVM implementation |
| --- | --- | --- |
| `fs.read_text_async(path)` | `String` (UTF-8) | strict Linux `io_uring`; fail closed if unavailable |
| `fs.exists_async(path)` | `bool` | strict Linux `io_uring`; fail closed if unavailable |
| `fs.write_text_async(path, text)` | `void` | strict Linux `io_uring`; fail closed if unavailable |
| `fs.append_text_async(path, text)` | `void` | strict Linux `io_uring`; fail closed if unavailable |
| `fs.remove_async(path)` | `void` | fail closed; completion provider pending |
| `fs.mkdir_all_async(path)` | `void` | fail closed; completion provider pending |
| `network.connect_async(host, port)` | `TcpConnection` | legacy worker adapter |
| `socket.read_line_async()` | `Option<String>` | legacy worker adapter |
| `socket.write_text_async(text)` | `void` | legacy worker adapter |
| `socket.close_async()` | `void` | legacy control adapter |
| `http.get_text_async(url)` | `String` | legacy worker adapter |
| `http.post_text_async(url, text)` | `String` | legacy worker adapter |

`File` aliases `fs`; `net` aliases `network`. Existing synchronous
filesystem APIs remain available for compatibility while their strict completion
equivalents are built. They are explicitly **not** part of the strict I/O
profile and will eventually move to the `*_blocking` suspend/non-parking
contract rather than performing blocking work on an actor carrier.

A strict read can be written as:

```ores
pub async routine main(): void {
  if await fs.exists_async("./message.txt") then
    val String text = await fs.read_text_async("./message.txt");
    stdio.println(text);
  end
  return;
}
```

### Strict filesystem execution

`fs.read_text_async`, `fs.exists_async`, `fs.write_text_async` and
`fs.append_text_async` do not delegate to `AsyncNativeIo`. On Linux, one
lazy bounded `io_uring` reactor per Oreslang context multiplexes
open/read/write/close/cancel completion. Unsupported kernels or
platforms fail closed rather than falling back to blocking workers.

Runtime path permission checks for these operations are lexical and
nonblocking. The native open uses `openat2` with beneath/no-magic-link/no-symlink
resolution, preventing a lexical permission decision from being redirected
through a symlink. Strict reads reject malformed UTF-8.

Read/exists futures support cancellation. Cancellation requests
`IORING_OP_ASYNC_CANCEL`; native ownership is retained until physical terminal
completion so a late callback cannot resurrect or double-settle a cancelled
future. Context shutdown prevents new admission, cancels pending native
operations, and closes the ring after native work drains.

`remove_async` and `mkdir_all_async` currently return failed futures because
their secure parent-directory completion providers are not implemented yet.
Write and append are strict completion operations, but their futures deliberately
decline guest cancellation because partial filesystem mutation cannot be rolled
back.

### Remaining portable worker adapter

TCP and HTTP client operations still use the bounded `AsyncNativeIo`
worker adapter. That adapter releases Oreslang actor carriers but may park host
virtual/platform threads in blocking syscalls, so those operations are **not**
yet strict kernel-nonblocking I/O. Their existing bounded admission, error
sanitization, redirect restrictions, and socket-control reservation remain in
force while readiness/completion transports are implemented.

The server-side HTTP API already returns futures for accept, body reads,
response writes and graceful shutdown, but its transport is not certified by
the strict filesystem work. Stdout/console export and stdin are likewise
outside this slice.

### Validation

The strict filesystem gate runs:

- `tools/strict-filesystem-io-audit.py`,
- the native bridge build under strict compiler warnings,
- `LinuxUringFileIoProviderTest`,
- the strict path-permission regression,
- the Oreslang async write/append/read/exists integration regression,
- Native Image packaging for JNI reachability validation.

The broader `AsyncNativeIoTest` continues to cover the legacy TCP/HTTP adapter
until those transports move to their own completion providers.

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
