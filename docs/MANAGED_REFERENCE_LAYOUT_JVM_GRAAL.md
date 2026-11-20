# JVM/Graal managed references and data-oriented layouts

## Cross-backend rule

Oreslang source types do not expose a physical pointer width. A logical managed
reference remains a logical reference through parsing, type checking and
ownership analysis. Physical storage is chosen by the backend and layout pass.

The Java/Graal backend uses two different strategies deliberately:

1. ordinary Java object references remain VM-managed;
2. Oreslang-controlled actor/DOD storage uses primitive 32-bit indices or
   offsets whenever object identity is naturally table- or arena-relative.

This mirrors the LLVM policy without pretending that a Java language runtime
can rewrite HotSpot's internal oop representation.

## JVM / HotSpot

On a normal JVM, `NativeBuildMode.managedReferenceMode()` reports
`JVM_MANAGED`. Oreslang does not claim that a Java object field has a specific
physical width because HotSpot owns that decision.

HotSpot can use compressed ordinary object pointers on 64-bit VMs. That remains
a VM/runtime deployment setting, not an Oreslang source-language ABI.

Oreslang-controlled data-oriented storage does not depend on that setting.
`ActorDenseIndexColumns`, graph adjacency slot columns, ECS relations and
future SoA/AoSoA reference columns use primitive Java `int` storage, which is
exactly four bytes per element.

## GraalVM Native Image

GraalVM Native Image supports 32-bit compressed Java object references and
documents them as enabled by default. Oreslang now pins that choice explicitly
for Maven Native Image builds.

Default:

```sh
mvn -Pnative-aot package
mvn -Pnative-hybrid package
mvn -Pnative-isolate package
```

passes:

```text
-H:+UseCompressedReferences
-Dores.native.ref-mode=compressed32
```

A full-width build is an explicit profile:

```sh
mvn -Pnative-aot,native-ref64 package
```

which passes:

```text
-H:-UseCompressedReferences
-Dores.native.ref-mode=native64
```

The Oreslang property is captured during image generation by
`NativeBuildMode`; the native-image profiles explicitly initialize that class
at build time and require an explicit `ores.native.ref-mode`. Missing or
unknown native reference-mode metadata fails the image build rather than
silently defaulting. It is not a launch-time switch that can reinterpret an
already-built heap layout.

Reference documentation:

- https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/
- https://www.graalvm.org/jdk25/reference-manual/native-image/optimizations-and-performance/ObjectHeaderSize/
- https://wiki.openjdk.org/display/HotSpot/CompressedOops

## DOD / ECS representation

Pointer compression is not the preferred representation when a relationship is
already naturally a row, entity, component or graph slot.

`ManagedReferencePolicy.encodeDenseIndex32` uses all non-zero 32-bit patterns:

```text
0x00000000 -> null
0x00000001 -> logical index 0
...
0xffffffff -> logical index 2^32 - 2
```

Java's signed `int` interpretation is intentionally irrelevant; encoded words
are treated as unsigned storage bits.

`ActorDenseIndexColumns` stores those words in AoS, SoA or AoSoA layouts. For
shared actors the backing is `int[]`; for private actors the existing
owner-checked direct-memory arena is used through 32-bit `readInt/writeInt`
operations. Both are charged to the actor's existing shard quota and revoked by
the same physical-lifetime barrier. Hot kernels can use
`readEncodedWord`/`writeEncodedWord` to preserve primitive 32-bit index words
without constructing nullable wrappers or materializing Java object references.

This gives the Java backend the same important property as LLVM:

```text
logical relation
    |
    +-- ordinary object relation -> host managed Java reference
    |
    +-- actor arena relation     -> compact offset/index when lowered
    |
    +-- ECS/table relation       -> 32-bit dense index
    |
    +-- SoA/AoSoA relation       -> 32-bit column element
    |
    +-- FFI/OS/native pointer    -> native-width boundary pointer
```

## Generational graph storage

`GenerationalGraphArena` already used a data-oriented split representation:

- node values: `Object[]`
- liveness: `boolean[]`
- node generations: `long[]`
- adjacency targets: `int[]`
- adjacency generations: `long[]`
- edge counts: `int[]`

The target slot column is therefore already four bytes per edge. Generation
metadata remains separate so stale-handle/ABA protection is not sacrificed for
pointer density. Future profiling may justify compacting generation metadata
independently, but it must remain a safety decision rather than an implicit
pointer-width optimization.

## Zero-GC boundary

Compressed references do not mean zero GC.

HotSpot compressed oops and GraalVM compressed references are still managed
Java references. Likewise, converting selected relationships to primitive
indices removes references from those tables but does not prove every guest
allocation has escaped the traced Java heap.

`ActorHeapAdmission` therefore keeps zero-guest-tracing admission separate
from this feature.

## Lowering direction

The compiler should eventually classify each logical reference after ownership
and layout analysis instead of choosing representation during parsing:

```text
Ref<T>
  |
  +-- ordinary JVM object ------> Java reference (VM chooses physical oop width)
  +-- actor-region object ------> compact arena offset/index when supported
  +-- ECS/component row --------> dense32 index
  +-- SoA/AoSoA kernel ---------> preserve index until final load/store
  +-- cross-actor identity -----> ActorRef/capability
  +-- native boundary ----------> native-width pointer
```

Hot loops should preserve indices rather than eagerly materializing object
references. This keeps vectorization, cache locality and future GPU/accelerator
lowering compatible with the same logical Oreslang program.

## Native tagged REFERENCE slots use the compressed32 word (2026-11-16)

Actor-native tagged slots (`ActorNativeArena`, 24-byte `tag | bits | length`)
store a `REFERENCE` as the **cross-backend compressed32 word**, not as a raw
64-bit actor offset or host pointer:

- `0` is null; otherwise `word = (actor_offset / 8) + 1`, decoded unsigned
  (`ActorCompressed32Reference`). This is bit-identical to LLVM
  `ManagedRefLowering` (`(offset >> 3) + 1`): both pin the same deterministic
  100,000-word corpus digest `FNV64 = 0x46402ba55da7cde5`
  (`ActorCompressed32ReferenceTest`, LLVM `tests/managed_ref_test.cpp`).
- The word is encoded **before** either reference registry entry is
  published, so an unaligned or unrepresentable offset never leaves a
  half-published alias. Loads and overwrites reject any nonzero upper half,
  word `0`, and well-formed words the arena never issued.
- Words are actor-relative: two actors may hold the same word for different
  objects; decoding is only meaningful through the owning arena's registry,
  and a foreign actor's value is never storable.
- Every issued native value starts at an 8-aligned actor offset inside the
  per-actor unsigned 32-bit offset space (`reserveActorOffset`), so every
  reference is encodable. Physical arena memory is allocated with explicit
  natural 16-byte alignment (`posix_memalign` in `src/main/c/oresarena.c`),
  never `calloc`'s ABI-dependent alignment, because on 32-bit hosts
  `alignof(int64_t)`/`alignof(double)` can be 4.

Not yet claimed: the slot's physical bits field is still 8 bytes (the word is
zero-extended); a packed 4-byte reference column, compiler-selected packed
layouts, a 32-bit JVM/i686 execution receipt, and Native Image guest evidence
need their own gates. Supersedes draft #1849 (stacked on the stale October #1822 arena
branch) by porting its codec and tests onto current `main`.
