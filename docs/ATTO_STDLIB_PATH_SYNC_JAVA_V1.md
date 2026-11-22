# Java/Graal native Atto std/path and std/sync v1

## Implementation
- `stdlib/path.atto` provides lexical component classification and checked
  normalized relative depth for POSIX and conservative Windows path spellings.
  It rejects absolute paths, root traversal, NUL/control characters, empty
  components, overlong paths (4096 scalars, 256 components, 255 scalars each),
  Windows DOS device names, drive prefixes, alternate streams, and trailing
  dots. `is_canonical_relative` admits paths without dot components.
  `normalized_component_start`, `normalized_component_length`, and
  `normalized_component_scalar` expose the surviving normalized components
  as offsets into the original codepoint stream, with `.. ` cancellation.
  `is_portable_relative` requires POSIX/Windows agreement and explicitly
  denies backslashes, which otherwise have different semantics by OS.
- `stdlib/sync.atto` provides pure state-admission logic for mutex queues,
  writer-priority RW lock decisions, semaphore take/release with checked
  subtraction, barriers, cancellation and epoch advancement. A new mutex
  claimant cannot bypass existing waiters. Releasing an *owned* permit during
  shutdown is allowed; acquiring new permits after close is not.
- `stdlib/telemetry.atto` retains its separately developed span-value and
  bounded-batch model, now including strict W3C-v00 traceparent header grammar.

All three modules use Atto source only. They are separately registered in the
Java closed compiler-owned stdlib source table, with **no legacy .ores alias**.

## Trust boundary
These functions do **not** create an authenticated capability or access a
filesystem, operating-system lock, shared-memory atomics, or exporter.
Lexical path admission is not symlink-safe root resolution. Lock admission
predicates are not lock acquisition or scheduler ownership. W3C parsing does
not authenticate span provenance. The trusted runtime must enforce these
properties, including cancellation, exact-once release and nonblocking wakeup.

`normalized_depth` returns only a count; component-view APIs expose
surviving scalar offsets. Neither yields a canonical serialized native path. A true `Path`/`PathBuf` join/parent/extension API and native I/O provider
remain further implementation milestones.

## Tests and current evidence
`tests/fixtures/path_sync_telemetry_v1.atto` contains 26 guest output
vectors, including root escapes, Windows aliases, path ambiguity, waiter
fairness, permit release on closed semaphore, 32-digit trace IDs and invalid
headers. `StdPathSyncTelemetryGuestTest.java` tests closed registry/source
admission and a linked guest execution oracle. They are **authored tests**,
not proof that a JVM/Graal guest compiled and executed on the exact head.
If a GitHub Actions job reports zero steps, queued or pending, report
`NOT_EXECUTED`. The source repository currently has no authorized private
CI export destination in `.github/private-ci-approved-repositories.txt`;
DO NOT mirror private source to any alternate org without approved export
authorization. Remaining: stepful JDK25/Native Image tests and runtime
mutex/FS/OTLP integration.

Overlaps: Java PR #1377 is actor synchronization *metadata* (different
from trusted runtime Mutex/RWLock); Java #1661/#1662/#1660 and #1756 contain
telemetry scope/provider work; .NET #209 and LLVM #883 are independently owned
backend source candidates. Resolve semantic overlaps before merge.
