# GraalVM actor-owned heterogeneous native storage

Status: **host runtime/native arena substrate** stacked on Java draft #1822.
This is not source-compiled `define inverted class`, a complete actor-local
heap allocator, generated async frames, or proof of GraalVM's minimum-GC goal.

`ActorMixedRegion` stores all guest scalar payload bytes inside one
actor-private `ActorNativeArena.Region`. Immutable schema, name indexes and
layout geometry remain Java-managed metadata; **no Java object is allocated
per scalar read/write or ECS integration iteration**. The public
`Address` record exists only for diagnostics and geometry tests and is not
used on scalar hot paths. The `forCurrentActor` factory binds to
`ActorRuntime.currentNativeArena()`; actual actor ID/physical turn
authorization, read-loan fencing and native reclamation are delegated to
the existing actor-owned arena. These are not independently managed Java GC
heaps; the enclosing JVM may still collect runtime metadata.

Mixed-width fixture: `id:u32`, `x:f64`, `flag:bool`, `next:ref32`.

| Layout | Rows | Payload bytes | Physical actor allocation |
| --- | ---: | ---: | ---: |
| AoS | 3 | 72 | 72 |
| SoA | 3 | 51 | **56** |
| AoSoA (tile 4) | 5 | 144 | 144 |
| SoAoS ([id, flag], [x, next]) | 3 | 72 | 72 |

SoA fields' naturally aligned segment bases are 0, 16, 40, 44; row 2
`x` has absolute byte offset 32. AoSoA's row 4 `id` is at byte 72,
and SoAoS row 2 `next` at byte 64. The total-physical-quota admission
counts inter-segment alignment gaps and tile padding **before** reserving
native storage. `ref32` is an encoded unsigned 32-bit word, **never a
dereference right or host pointer**. `bool` uses canonical 0/1 bytes and
rejects invalid encodings at read.

`ActorMixedRegionTest` covers the four layouts, exact offsets,
little-endian unsigned scalars and IEEE signed-zero/NaN, physical-byte
nonaliasing, malformed layouts, budget failure atomicity, deterministic
native refunds, and a real `ActorRuntime.spawnSharedTrusted` turn that
denies host access after dispatch. The GraalVM 25 native-memory test
workflow includes this class. A hosted check is a real pass **only when
its exact-head test steps execute successfully**.

Zero-copy `leaseSoaColumnReadOnly(field)` returns a checked bounded
native slice of a physically contiguous SoA field without exposing a
mutable buffer. The underlying `Region.ReadOnlyLease` currently **freezes
writes and release of the whole region**, not just the borrowed field.
`integrate` preflights that write fence before the first row mutation.
The regression deliberately attempts both an ordinary field write and
an ECS kernel while the loan is alive, verifies no partial update,
then closes the loan and retries successfully. This is deliberately
conservative until field-sensitive ownership is proven by the compiler.

Known limitations: Java-managed geometry/name maps remain; input data
structures are constructor-snapshot but metadata caches still use the
JVM heap; zero-copy borrower lifetimes need compiler-generated lowering;
cross-backend row generation/ownership proofs and source guest ECS output
are not implemented; the current region is not yet merged into
`ActorLinearMemory` page allocation. Native `Region` is not a sandbox
for untrusted code.

Next: align the typed compiler IR and byte layout to the versioned
56-cell ABI vector in platform conformance draft #149; emit guest native
object-field accesses into this actor region; prove alias-sensitive borrow
enforcement and post-termination quiescence; execute emitted Graal and LLVM
guest programs before any five-backend parity claims.
