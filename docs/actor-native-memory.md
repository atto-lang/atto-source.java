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
