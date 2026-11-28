# Actor native memory lowering

## Target contract

Each logical actor has its own native arena, including ordinary/shared actors.
The main trusted AOT isolate and the one pooled untrusted JIT isolate are trust
domains, not per-actor allocation units. Actor migration between carrier threads
must not change the allocation owner. Actors share code and immutable layout
metadata, not private mutable payloads.

`new Foo()` in an actor must construct the source object's state directly in
native memory. It must not build a Java object graph and serialize that graph
afterward. Field reads/writes address native slots. This representation is
process-local memory, not the stable MessagePack/wire representation.

## Implemented foundation

* `ActorRuntime.currentNativeArena()` resolves the executing logical actor.
* Source actor fields and both explicit and synthesized ordinary class
  constructors use arena-backed field maps inside actor execution. Ordinary
  construction outside an actor retains the existing managed representation.
* Native slots contain scalar values, native string/numeric payloads, and
  **actor-relative logical offsets** for nested native values. Offsets belong
  to exactly one arena, are never raw host pointers, and are not recycled
  during that actor lifetime. Nested native objects retain reference identity.
  No arbitrary Java object is accepted as a fallback field payload.
* Each arena charges physical bytes before allocating, checks actor identity on
  access, and explicitly scrubs/frees native memory after physical actor/task
  quiescence. It does not depend on a direct-buffer Cleaner for native release.
* Dense `ActorShardColumns` stores use native regions for every actor kind.
  Primitive kernels read/write directly without per-element boxing or an
  intermediate Java array. Explicit shard close returns its byte budget.
* The JDK 21 baseline is preserved: the existing JNI library owns native regions.
  Replacing that allocator with stable FFM requires a deliberate JDK 22+ baseline
  change, not reflective access or preview APIs in this patch.

## GraalVM actor worker-pool domains

`ActorRuntime` eagerly constructs exactly three execution pool domains:
`sharedDispatcher` for SHARED actors, `privateDispatcher` for PRIVATE/isolated
actors, and `untrustedDispatcher` for UNTRUSTED actors. Each domain may be
split into CPU-affinity lanes, but it is still one logical pool. Pool
construction starts **zero** physical workers. Java workers start lazily on the
first admitted turn. Native pthread carriers are now initialized on first
accepted dispatch rather than during `NativeCarrierExecutor` construction;
closing an unused native pool creates no pthread and no reaper. Use the host-only
`ActorRuntime.dispatcherWorkerCounts()` snapshot to audit the live shared,
isolated, and untrusted carrier counts.

Worker pools belong to actor *classes*, not individual actors. An actor's
`ActorNativeArena` is independently owned and persists across its mailbox
turns and physical worker migration. A Future completion only enqueues an
actor-owned continuation; the producer's thread must never enter the actor
arena. At physical actor finalization, quiescence and native memory reclamation
must happen after all entered turns and pending actor-scoped operations retire.

The existing `OresScheduler.TaskRunner`, Future waiter registrations, and
compiler-generated lexical-environment structure are still JVM objects.
Actor-owned `SourceTask` instances carry a compact 40-byte
`ActorNativeArena.TaskFrame` in the same actor-relative address space. That
native header is the lifecycle authority for NEW/RUNNING/AWAITING/COOPERATING/
DONE/FAILED transitions, a monotonic resume count, and a monotonic suspension
ticket bound to the exact AWAIT/COOPERATE reason. A managed continuation cannot
resume an actor task unless its ticket and suspension kind match the live
arena-owned frame; stale, duplicate, wrong-kind, and post-terminal resumes fail
closed.

Supported lexical scalar payloads are opportunistically lowered through
`ActorNativeArena.ValueCell`: primitive values, strings/numeric text and symbols
inherit the same actor owner/lifetime checks as `state {}`. Actor-object locals
keep a managed, non-authoritative facade while their actual object fields remain
native in the actor arena; this avoids pinning facades in task-local native slots
until captured-scope/drop lifetime lowering exists. Collections/ADTs/callables
also remain explicit managed local fallbacks. Privileged `NativeCapability`
values deliberately stay out of lexical `ValueCell` storage until compiler-
generated scope-drop lowering can retire their handle references at lexical
exit. Foreign-arena native objects/capabilities are still rejected rather than
downgraded to managed aliases. Reads may still materialize boxed/String evaluator
values.

**Opaque capability issuance is separate from Java interface implementation.**
The arena's `NativeCapability` interface is a *description*, not an admission
credential. The package-internal, actor-owner-checked
`issueNativeCapability` records provenance by object identity before any
capability can be persisted into a guest-native field. The trusted HTTP core's
`NativeHttp.Exchange.nativeArena()` validates its owner and generation,
releases the HTTP `State` monitor, and only then issues the current exchange
to that actor. This avoids an arena/State monitor lock-order inversion with
concurrent actor handlers; the generation/owner is revalidated on subsequent
storage reads and moves. Arbitrary host wrappers that merely
return the same `ActorNativeArena` are rejected in both preflight and storage.
Opaque handle reference counts still drop the strong host handle after the last
slot overwrite; issuance uses weak keys and is cleared at actor teardown.
Future core/stdlib resources must have their own trusted issuance path.
This is not an operating-system sandbox for Java host code with privileged
runtime-package access; untrusted Oreslang guest code still has no general Java
FFI/host-class capability.

**Verification status:** the new issuance commits and negative JUnit cases must
run against their exact PR head. Previously reported JDK21/25 test totals below
do not certify these later changes. The latest inspected source-org Actions
native-memory job failed before starting any test steps.

Native facade provenance uses weak identity keys rather than structural equality
or strong actor-lifetime references. A separate reference-counted lookup retains
a facade only while one or more native slots contain its actor-relative address;
overwriting the last such slot removes that strong lookup entry. This reduces
managed-heap retention for long-lived actors without recycling native addresses
or weakening forged-facade checks.

**Full native async frame lowering is therefore not yet complete**:
managed environment topology, collection/ADT payloads, callback/cleanup
metadata, runtime-capability handles, and lowered program counters still need
typed native representations. These changes establish native continuation and
local-value authority, not strict zero-managed-allocation execution.

## Per-actor native linear address space (GraalVM/JNI)

Every logical actor already owns one `ActorNativeArena` in its `ActorCell`,
independent of carrier threads and distinct from the other actors in the same
trusted or pooled untrusted GraalVM isolate. `ActorRuntime.currentActorLinearMemory(initialPages, maxPages)`
now resolves **one lazily backed `ActorLinearMemory` per actor arena**.
The same native guest address space survives turns and carrier migration;
foreign actors and host code cannot read or write it. Physical actor shutdown
revokes all pages and refunds their native-byte reservations.

* Guest addresses are unsigned-like, bounds-checked **logical byte offsets**
  `[0, size)` with 64-KiB pages, little-endian primitive access and no exported
  raw pointer. Each page is an independent zero-filled native allocation from
  the actor arena's JNI allocator; host virtual addresses are not necessarily
  contiguous. This is WASM-style linear addressing, **not** a claim of one
  contiguous host allocation or an extra isolate per actor.
* Growth commits additional full native pages within the caller's maximum page
  count and shared actor quota. Existing offsets stay valid. Failed multi-page
  growth revokes its partial new pages and leaves the old length unchanged.
  `maxPages` is a logical upper bound, not a native memory reservation.
* `fill(destination, length, value)` and
  `copy(destination, source, length)` are native-offset operations using
  actor-owned pages, no copied Java byte array, and memmove-safe overlapping
  ranges. Both validate the full destination span and every page's borrow
  status **before** modifying any bytes. `copy` also checks the full source
  range. These are baseline scalar kernels, not vectorized bulk copies.
* Native `Region.ReadOnlyLease` now supports checked `readByte` and
  `readUnsignedByte`, in addition to eight-byte word/double reads. Every
  byte is validated against the borrowed slice and owning actor; no mutable
  array or `ByteBuffer` escapes. The native linear-memory tests include
  a deterministic 600-operation differential oracle for `fill` and
  overlapping `copy` around page boundaries.
* `leaseReadOnlyPage(pageIndex, offset, length)` borrows native page storage
  within the owning actor. It blocks all writes to that page while borrowed;
  a primitive write crossing two pages preflights both before mutating either.
  No cross-actor borrow/export, raw pointer or mutable `ByteBuffer` escapes.
* Object fields, nested objects, continuation headers, native regions, and
  `ActorLinearMemory` pages now draw addresses from **one monotonic
  actor-relative logical offset authority** in `ActorNativeArena`. Compact
  object/string/task allocations still use 4-KiB physical slabs and linear
  guest pages remain independent 64-KiB physical regions, so tiny actors do not
  pay a 64-KiB page per object. `ActorLinearMemory.actorOffset(...)` maps a
  guest-page byte offset into this canonical actor address space. This unifies
  ownership/address identity without pretending the host allocation is
  contiguous. General object fields are not yet directly readable through the
  public page-indexed `ActorLinearMemory.read*` API; typed compiler lowering
  should target canonical actor offsets rather than raw native pointers.

These are native guest payload guarantees on the existing JVM/JNI runtime; Java
metadata, wrappers, reference tracking, scheduler state and evaluator frames
still live on the managed heap. Neither hard realtime nor strict zero-managed-
allocation is enabled. Native Image compilation and optimized Truffle/Graal
execution require their own CI execution evidence.

## Data-oriented layouts

One physical layout is authoritative per dense store. There are no synchronized
AoS/SoA copies. Layout changes must eventually use an explicit relocation with
no live borrows.

| Layout | Physical arrangement | Intended use |
| --- | --- | --- |
| AoS | Consecutive complete rows | Small objects, whole-row access |
| SoA | A contiguous column per field | Streaming one/few fields |
| AoSoA | Blocks of field columns with a fixed lane count | Vector-friendly blocked kernels |
| SoAoS | Separate arrays of fixed-width field groups | Keep related fields together while separating groups |

`ActorShardColumns(Layout.SOAOS, rows, fields, 1, groupWidth)` groups consecutive
fields. The width must divide the field count. AoSoA requires a power-of-two lane
count and pads the last block; logical bounds never expose padded rows. The
current shard is a homogeneous double store, not a heterogeneous ECS engine or
source-language `LayoutStore<T>` implementation.

## Read-only pointers and resource transfers

A read-only pointer is an acceptable low-level implementation of a borrow when
the allocation domain permits access, bounds/layout are known, and a lease keeps
the allocation alive. An address alone provides neither immutability nor
lifetime safety. Mutable actor-private memory cannot be exported by merely
casting its pointer to read-only. Mutable aliases must be excluded for the
borrowed interval, or the allocation must be frozen. Sharing with an untrusted
domain requires explicit authorization. No raw native pointer API is introduced
by this foundation.

HTTP exchanges, TCP connections and file descriptors are move-only capabilities,
not serializable copies of an OS descriptor integer. A supervisor-to-actor move
must invalidate all sender aliases, preserve permissions, fence in-flight I/O,
and bind cleanup to the receiving actor. Generation checks must reject stale
handles even after the OS reuses a descriptor. Failed delivery and shutdown must
close exactly once or explicitly roll back ownership before publication.

`NativeHttp.Exchange` already implements generation-bound `move_to`/claim and
actor-exit cleanup. It is a `NativeCapability`, deliberately distinct from
`NativeValue`: native object/local slots store a monotonic arena-local opaque
handle ID, never an actor-memory offset or OS descriptor. The arena keeps the
control-plane facade only while at least one native slot references that handle;
overwriting the last slot retires the registry entry. Access revalidates the
generation and execution owner, and generic mailbox/shared-data paths reject
`NativeCapability` just like actor-native memory aliases. Its existing
restriction on untrusted transfers is preserved. TCP/FD movement still needs a
corresponding checked handle path; this patch does not assert that those adapters
are implemented.

## Guest code, surrounding code, and FFI authority

Source-file/module proximity does not grant memory authority. Shared helper
functions and immutable code metadata may be used by every actor, but allocation
inside an actor resolves through `ActorRuntime.currentNativeArena()`, so a
helper's `new Foo()` still belongs to the calling actor.

While an actor is executing, access to a public module field is rejected when
that binding is rebindable (`let`) or exposes a mutable referent. Compile-time
module constants remain usable. The check is based on the active actor
execution context, so it applies transitively when an actor calls an ordinary
helper declared in the same file or another linked unit.

Actor-native values are also forbidden from crossing mailboxes, output streams,
readonly-sharing wrappers, `SyncCell`, or `SharedMutex` by reference. A
future source serializer/move operation must produce an explicit detached copy
or transfer capability; generic transport may never preserve an arena alias.

Guest/user source has no FFI or Java-host escape hatch. `java:` imports,
mixed `java { ... }` / `do java { ... }` source islands, and the guest
`ffi.*` namespace are denied even when the host runtime carries legacy
interop capabilities. `JAVA_INTEROP`, `JAVA_SOURCE_INTEROP`, and `FFI`
are host/core/stdlib implementation authorities only. Core/stdlib native
services should be exposed to guest code through typed Ores APIs and
compiler/runtime intrinsics, never by handing guest code raw JNI/native
addresses.

## Scoped native read leases and retained text capacity

`ActorNativeArena.Region.leaseReadOnly(offset, length)` provides an owner-checked,
bounds-checked native slice. While any read lease is live, the backing region
rejects writes and explicit release. The lease has no exported memory address,
cannot be used by a foreign actor, and becomes invalid on physical actor
finalization; a closed lease cannot read again.

`ActorShardColumns.leaseColumnReadOnly(field)` applies this to a physically
contiguous SoA column without a copy. AoS, AoSoA and grouped SoAoS do not
silently create a contiguous column pointer: callers must explicitly request a
copy or a future strided borrow. Current freezing is coarse-grained for the
entire region, not merely the selected column. Explicit shard close fails
without refunding its reservation if an immutable lease remains outstanding.
These host-side handles are still managed facades and do not establish
cross-actor sharing, raw-pointer validity, or allocation-free guest execution.

Arena-backed object fields and lexical value cells retain the original native
capacity of supported string/numeric-text/symbol payloads. Replacements may
shrink and later regrow up to that capacity without another allocation, while
preserving the same non-recycled actor-relative address. Growth beyond retained
capacity allocates transactionally and leaves the previous value untouched if
the actor quota cannot satisfy it. General lifetime-based reclamation of
variable-size payloads remains unfinished.

## Remaining work before strict no-managed-allocation admission

This patch does **not** make the Java evaluator allocation-free. It still uses
Java object facades, layout metadata, environments, argument lists, boxed
expression values, and async/runtime machinery. Strings/numerics materialize
managed values when read through the generic evaluator. The native field adapter
is an executable lowering foundation, not the final specialized primitive IR.

Unlowered guest payload kinds (collections, closures, ADTs and other complex
values) fail when stored into a native actor field rather than silently becoming
managed field payloads. Actor-local temporaries of those kinds still require
lowering. Supported lexical locals use arena-backed `ValueCell` storage. Arena-issued
`NativeValue` references (including actor objects and native lists) are persisted
in those lexical cells as actor-relative offsets rather than retained as the
authoritative Java payload; foreign/forged arena references still fail closed.
After the first `SourceTask` resume binds arguments/`self` into `Env`, the task
clears its original Java argument list and receiver root so an async suspension
does not keep duplicate guest roots outside the arena. Async argument/result
detachment of an `OresObject` from inside an actor also rebuilds the detached
object's fields in the same actor arena instead of a Java `LinkedHashMap`.
Nested unsupported payloads therefore fail the native store rather than silently
escaping to managed object state. Native field/string
storage still has actor-region lifetime: growth beyond retained capacity
consumes the bounded actor budget until actor exit. Compiler-generated
drops/reuse are required for long-lived allocating actors.

Next admission requirements are primitive/handle evaluator frames, native
collections and ADTs, full lexical-local spill/restore into native continuation
frames, compiler-generated lifetime/drop operations, and ownership-fenced
I/O/shared-data handles. Runtime control objects such as Future/channel handles
must be represented separately from guest frame data rather than blindly copied
into the arena. Heap-allocation evidence
must cover both normal and exceptional paths. `strict-no-gc` stays unavailable;
native guest payloads alone do not eliminate runtime GC or establish hard
real-time execution.

## Standalone native ABI preflight

Run `bash scripts/check-native-arena-abi.sh` from a POSIX shell with
a JDK, a C compiler, and Linux JNI headers installed. The script compiles
`src/main/c/oresarena.c` with warnings treated as errors, generates a
temporary **source-free** Java JNI ABI probe in a private temporary directory,
checks invalid sizes, allocation zeroing, independence, little-endian
native word storage, and 1,000 explicit native-buffer allocations/releases.
Temporary probe sources, classes, and shared-library objects are removed
on exit.

This can run without Maven or GraalVM and checks **only** the native
allocator boundary. It does **not** prove actor ownership, memory lifetime
under concurrent actor turns, native image compatibility, or zero-GC guest
allocation. The full GraalVM JUnit suite remains the release gate.

## Validation for this change

The focused native-storage, actor lifecycle, constructor, imported-class and
lexical-boundary suite passes 72 tests on OpenJDK 21 and OpenJDK 25. Native C is
built with the existing `-Wall -Wextra -Werror` configuration. The wider selected
suite runs 514 tests: 512 pass; the actor-mail mutability case and one HTTP
response-draft parser fixture also fail on the unchanged base commit
`ea46aa1d8a5459f3469c5f1deb5c08855a92b991`.

Local OpenJDK runs use Truffle's interpreter fallback, including the attempt with
JVMCI enabled: the optimizing runtime is unavailable in this distribution. This
is not optimized-JIT or Native Image evidence. The dedicated workflow runs the
focused suite with the repository's pinned GraalVM setup. No allocation-free,
zero-GC, hard-real-time or native-image compatibility claim is admitted by these
tests.


### Dense kernel critical-section amortization

`ActorShardColumns.integrate` now lowers each complete AoS/SoA/AoSoA/SoAoS pass to one owner-authenticated `ActorNativeArena.Region` bulk operation. The region validates writability, finite scalar parameters, and the highest byte touched by every block **before** the first store, then keeps one arena critical section for the complete pass. This removes per-element actor-owner checks and monitor entry/exit from the dense loop while preserving the actor's single-flight execution authority and read-lease freeze semantics.

The physical traversal remains layout-specific: AoS uses a record stride, SoA uses contiguous scalar columns, SoAoS uses the inner-group stride, and AoSoA uses contiguous lanes plus an explicit tail block. No raw `ByteBuffer` or native address escapes. This is a structural hot-path improvement, not a SIMD or cache-miss performance claim; optimized Graal/Native Image code generation and hardware counters still require exact-head execution evidence.

## Compact 32-bit actor-relative managed references (integration hardening)

The actor-native tagged `REFERENCE` payload now uses an **unsigned 32-bit
actor-relative word**, zero-extended into the existing 8-byte tagged-value
cell. `0` is null; all nonzero words decode using
`(unsigned(word) - 1) * 8`. This is the same 8-byte-aligned logical
scheme as the LLVM compressed32 actor heap. The maximum encoded *starting
offset* is 32 GiB minus 16 bytes; an 8-byte object at that address ends at
32 GiB minus 8 bytes. Values outside this range fail closed, not truncate.

This does **not yet reduce the 24-byte generic native tagged-value cell**
to 20 or fewer bytes: the cell still provides a 64-bit payload to also hold
arbitrary scalar values. Future typed field-layout/codegen passes must pack
reference-only columns/fields as 4-byte words to realize the full footprint
benefit, preserving alignment and mixed-width scalar storage.

`ActorNativeArena` maintains the authoritative actor-local registry keyed
by canonical 64-bit logical offsets. Stores encode only *after* validating
arena issuance and owner; loads reject nonzero upper 32 bits and must resolve
through the current live registry. The compressed word is not a capability.
Cross-actor aliasing remains forbidden, even when separate arenas happen to
encode their first allocation to the same 32-bit word. Actor offsets never
recycle within an arena lifetime, avoiding stale-offset ABA.

`ActorLinearMemory`'s page-local guest offset and the *canonical*
actor-relative offset are distinct. Its 64-KiB pages can be physically
noncontiguous and interleaved with small actor object slabs; compress the
result of `actorOffset`, not the guest page number or the JNI/host pointer.
The existing 4-GiB WASM-like guest page indexing limit is separate from
the compressed actor offset encoding's roughly 32-GiB span. No 32-GiB native
allocation or virtual reservation is needed.

**Remaining integration gates:** packed typed field layouts, object-start
and type metadata in the compiler, all arena allocations under the selected
reference-width profile, native64 fallback for workloads exceeding compressed
span, and Native Image/LLVM compiled-guest differential tests. Normal Java
managed object references remain VM-owned and must not be confused with
Oreslang-controlled actor-relative words. Host/core/stdlib may use native
pointers internally; guest code never receives them.
