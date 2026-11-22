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
`tests/fixtures/path_sync_telemetry_v1.atto` contains 67 guest output
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

## Follow-up audit and exact-source runner status (2026-11-22)
A new source audit verified that every fixture method exists in its backend-owned `.atto` module and no host-language source islands occur in those modules. Path scans reject unpaired Unicode surrogate scalars and values outside Unicode's maximum scalar range. The Java/.NET path views now scan one bounded Unicode snapshot per call and avoid temporary component arrays. Sync release and upgrade policy require an authenticated, unconsumed owner/generation receipt from a trusted runtime, not guest-created boolean authority.

**Verification boundary:** Guest fixtures are authored, not executed. Exact-current-head Actions jobs were queued or pending with no recorded runner steps when checked. Private-source export is not permitted merely by naming a carrier: Java has no active reviewed destination; .NET's candidate remains inactive; LLVM's stale StreemPilot allowlist entry is explicitly under the source-security hold described by LLVM #726/#736/#778. No private-source mirror was performed. Existing draft PRs must remain draft until stepful native guest results and all required reviews are available.

### Immutable native Path value (follow-up)
The backend now exposes a `define pub class Path` Atto value and `relative_path(spelling, windows)` factory. The instance provides the surviving lexical components, filename/extension/stem offsets, parent depth, canonical/portable tests and safe join preflight. The original `String` spelling is retained for codepoint views; no canonical serialized output, symlink confinement, or filesystem grant is produced. The 67-output guest fixture includes 16 new Path-instance observations. No exact-head compiled JVM guest result is available yet.


## Source-local JDK25 negative-constructor gate (2026-11-22)

The new `Path` facade uses the existing `pub define class` syntax, consistent with compiler-owned `Trace.Buffer` and `Telemetry.SpanBatch`. The JUnit guest suite now includes `invalidPathInstancesFailInGuestBeforeReturning`: four noncanonical or reserved paths must throw **after** a real guest entry marker, preventing a mere parser/import rejection from counting as constructor validation. The exact-head JVM workflow demands this method's unskipped Surefire report.

The original 67-output guest oracle is unchanged. Runner-backed JVM/JDK25 and Native Image results remain NOT_EXECUTED on the current head. No privately owned source was exported.
