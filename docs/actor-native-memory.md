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
  arena-local reference IDs. Nested native objects retain reference identity.
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
compiler-generated async frames are still JVM objects. **Native async frame
lowering is not yet complete**: generated continuation program counters, local
slots, reference IDs, and cleanup maps must eventually live in the owning
actor's arena, with generation-fenced handles and safe terminal reclamation.
These pool changes establish scheduling and initialization guarantees, not
strict zero-managed-allocation execution.

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
* `leaseReadOnlyPage(pageIndex, offset, length)` borrows native page storage
  within the owning actor. It blocks all writes to that page while borrowed;
  a primitive write crossing two pages preflights both before mutating either.
  No cross-actor borrow/export, raw pointer or mutable `ByteBuffer` escapes.
* Object fields, nested objects and variable-size strings remain in that
  **same actor-owned arena's separate native slab allocator**, not yet in the
  `ActorLinearMemory` logical offsets. Native dense shards currently use
  `ActorNativeArena.Region` allocations. Unifying guest object/collection
  allocation into a typed linear-memory allocator is the next lowering step.

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
actor-exit cleanup. Native object fields can retain its checked control-plane
handle ID; transport state remains runtime-managed. Access revalidates the
generation and execution owner. Its existing restriction on untrusted transfers
is preserved. TCP/FD movement still needs a corresponding checked handle path;
this patch does not assert that those adapters are implemented.

## Scoped native read leases and same-size payload reuse

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

Arena-backed object fields now overwrite supported string/numeric-text/symbol
payloads in place when the new payload has precisely the same UTF-16 length.
This prevents repeated fixed-width text writes from steadily consuming native
arena bytes. Payloads that grow or change size still use actor-lifetime slab
allocation, so general lifetime-based reclaim/reuse remains unfinished.

## Remaining work before strict no-managed-allocation admission

This patch does **not** make the Java evaluator allocation-free. It still uses
Java object facades, layout metadata, environments, argument lists, boxed
expression values, and async/runtime machinery. Strings/numerics materialize
managed values when read through the generic evaluator. The native field adapter
is an executable lowering foundation, not the final specialized primitive IR.

Unlowered guest payload kinds (collections, closures, ADTs and other complex
values) fail when stored into a native actor field rather than silently becoming
managed field payloads. Actor-local temporaries of those kinds still require
lowering. Native field/string storage currently has region lifetime: repeated
variable-sized allocations consume the bounded actor budget until actor exit.
Compiler-generated drops/reuse are required for long-lived allocating actors.

Next admission requirements are primitive/handle evaluator frames, native
collections and ADTs, native async frames, compiler-generated lifetime/drop
operations, and ownership-fenced I/O/shared-data handles. Heap-allocation evidence
must cover both normal and exceptional paths. `strict-no-gc` stays unavailable;
native guest payloads alone do not eliminate runtime GC or establish hard
real-time execution.

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
