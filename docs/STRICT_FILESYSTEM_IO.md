# Strict filesystem completion I/O

This document describes the first strict nonblocking filesystem provider for the
Java/GraalVM backend. It is intentionally narrower than the eventual strict
stdlib I/O profile.

## Current contract

| Operation | Java/GraalVM behavior |
| --- | --- |
| `fs.read_text_async(path)` | Linux: `io_uring` completion provider; other/unsupported kernels: fail closed |
| `fs.exists_async(path)` | Linux: `io_uring` completion provider; other/unsupported kernels: fail closed |
| `fs.write_text_async(path, text)` | Linux: `io_uring` create/truncate + write completion; fail closed if unavailable |
| `fs.append_text_async(path, text)` | Linux: `io_uring` create/append + write completion; fail closed if unavailable |
| `fs.remove_async` | fail closed until a completion provider exists |
| `fs.mkdir_all_async` | fail closed until a completion provider exists |
| legacy synchronous filesystem calls | still compatibility-only; **not strict** |

The legacy synchronous calls remain because stack CLI and compatibility tests
still rely on directory/stat/atomic-write operations that do not yet have
completion providers. Their existence must not be interpreted as satisfying the
strict-I/O profile. The stale all-or-nothing boundary work is split from this
provider PR so each operation can be migrated without hiding functional
regressions.

## Linux backend

Each Oreslang context owns a **lazy** strict filesystem provider. The first
strict filesystem operation attempts to create one bounded `io_uring` and one
reactor thread. There is never one platform or virtual thread per operation.

The native bridge uses:

- `IORING_OP_OPENAT2` for path admission,
- `IORING_OP_READ` for file contents,
- `IORING_OP_WRITE` for text writes and appends,
- `IORING_OP_CLOSE` for descriptors,
- `IORING_OP_ASYNC_CANCEL` for cancellation.

The reactor thread only waits on the shared completion ring and publishes
terminal completions back into `OresFuture`. It never executes guest code.

Admission is bounded to 128 pending operations on a 512-entry submission ring.
The extra SQ capacity is deliberate headroom for cancellation, close and reactor
wake operations rather than additional guest admission.

Read/write buffering has a shared conservative 64 MiB per-context reservation
budget. Each admitted text read reserves its full 16 MiB growth ceiling before
native submission; strict writes reserve their actual encoded UTF-8 payload
bytes. This prevents concurrent reads and writes from bypassing each other's
memory bound. Reservations are released only when submission is rejected,
physical completion arrives, or provider shutdown removes the pending owner.
Native read buffers themselves still start at 64 KiB and grow geometrically
only as data arrives; the reservation is admission accounting, not eager native
allocation.

Provider construction includes a JVM-attach startup barrier: a native handle is
not returned to Java until the reactor can deliver JNI callbacks. The callback
methods are explicitly rooted in Native Image reachability metadata.

## Path and permission boundary

Strict runtime permission admission is deliberately lexical and does **not**
call `Files.exists`, `toRealPath`, or another host filesystem operation.
It converts the guest path to an absolute normalized path and checks it against
the context's already-normalized read/write scopes. Deny scopes win.

The native open then applies:

- `RESOLVE_BENEATH`,
- `RESOLVE_NO_MAGICLINKS`,
- `RESOLVE_NO_SYMLINKS`.

That division is intentional: permission evaluation itself cannot block the
guest carrier, while the kernel refuses symlink traversal or path escape before
opening the file. A symlink is not silently canonicalized into another target.

## Cancellation and shutdown

A cancellable read/exists future keeps its native operation owned by the
provider until a terminal completion arrives. Cancellation first submits
`IORING_OP_ASYNC_CANCEL`; the Java future is allowed to enter the cancelled
state only if that native revocation SQE was actually admitted. If enqueueing
the cancel fails, `Future.cancel` returns false and the original completion
remains authoritative. Late or duplicate native completions are ignored after
removing the pending ownership record.

Every published SQE is submitted immediately. If `io_uring_enter` reports that
the just-published entry was not submitted, the provider retracts that SQ tail
entry before its owner can be freed, preventing stale `user_data` from becoming
reachable through a later submit.

Write/append futures deliberately decline guest `Future.cancel`. Once a
mutation has begun, some bytes may already have reached the file and the runtime
cannot promise rollback. Context shutdown can still request native cancellation
to bound physical lifetime, but callers must treat a failed/aborted write as
potentially partial. Normal write completion loops over partial
`IORING_OP_WRITE` results until all UTF-8 bytes have been accepted, then closes
the descriptor through the ring.

Create/truncate opens use `O_CREAT|O_TRUNC`; append opens use
`O_CREAT|O_APPEND`. Both use mode `0666` subject to the process umask and the
same no-symlink/no-escape `openat2` resolution contract as reads.

Context/provider shutdown:

1. prevents new native submissions,
2. requests cancellation of each pending operation,
3. settles still-owned Java futures,
4. closes the native ring once the reactor has drained physical operations.

The Java submission/cancel/close boundary is serialized so no JNI operation can
race a ring handle after close.

## Failure behavior

There is no worker fallback. If Linux `io_uring`, JNI loading, or provider
initialization is unavailable, strict reads/exists return failed futures through
the unsupported provider. `remove_async` and `mkdir_all_async` still return failed futures until
their secure parent-handle completion implementations land.

Invalid UTF-8 is an error; bytes are not replacement-decoded.

## Validation

`LinuxUringFileIoProviderTest` covers bounded admission, rejected submission,
cancellation, late/duplicate completion, close races, invalid UTF-8,
write/append cancellation policy, and real read/exists/write/append/symlink/FIFO
cancellation behavior when the kernel permits `io_uring`.

`RuntimePermissionsTest#strictFilesystemAdmissionIsLexicalScopedAndDenyFirst`
covers the nonblocking scope boundary.

`AsyncNativeIoTest#strictFilesystemReadWriteAppendAndExistsCompleteWithoutWorkerFallback`
executes the Oreslang async surface across write → append → read while asserting
unfinished remove/mkdir operations do not fall back to the worker adapter.

`tools/strict-filesystem-io-audit.py` statically rejects worker fallback from
the strict filesystem path and verifies the native primitives, build linkage,
permission boundary, and Native Image JNI roots.
