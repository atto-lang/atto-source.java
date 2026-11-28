# Inverted SoA host storage boundary — AttoLang

## Why this file exists

`InvertedColumnStore` is an **internal Java/GraalVM allocation and
capability primitive**, not a guest-visible AttoLang collection or compiler
implementation. This intentionally small, independently compilable mainline
port separates host backing bytes and row identity from the older
inverted-class source/compiler integration stack (see #2073 and #2146).
It must not be interpreted as `.atto` compilation, native AOT parity, or
portable DOP semantics.

## Physical layout and invariants

- Each directly declared field has a separate, contiguous, little-endian
  primitive column. Fixed-width RECORD elements retain an opaque AoS encoding
  *within* their single column; there is no recursive field flattening.
- One opaque, store-issued owner+slot+generation capability identifies each
  logical row. A dense row index is not persistent identity; swap-delete
  changes row-to-slot mapping but preserves surviving issued capabilities.
- All public store operations check owner state. Structural changes reject
  any live column loan; read/write loans use explicit accounting.
- Reserve allocates replacement columns and slot metadata before publication.
  On successful publication, previous columns and previous slot/generation
  arrays are overwritten with zero values under the synchronized owner.
  A failed allocation must leave existing backing and live handles intact.
- An abandoned Java heap array may have copies elsewhere in the VM or may
  be optimized by the JIT. Clearing it is **defense in depth**, not a
  cryptographic zeroization guarantee or a claim of zero-GC semantics.
- This primitive is **not** admitted into guest channels, actors, closures,
  lexical capture, or `rt borrow/share/take/copy` without compiler-mediated
  ownership, serialization and lifetime proofs.

## What must remain in canonical `.atto`

Guest-visible methods, reusable data-oriented algorithms, AoS/SoA/AoSoA
policies, migrations, nominal schema definitions, and LRU-cache semantics
belong in canonical `.atto` source. The Java host must only supply the
minimal backing, checked memory/borrow and ABI capability needed by the
compiler/runtime. Until native source lowering exists, the port is **host-only**.

## Required qualification before merge or public use

1. Strict Java compilation and the `InvertedColumnStoreTest` JUnit suite on
   this exact source head, including generation reuse, stale/foreign handles,
   fixed RECORD copies, loan conflicts, atomic reserve rollback, retired-array
   clearing, and multi-threaded operation ordering.
2. Independent review, source branch protection, and real executed CI steps.
3. Subsequent *separate* compiler-backed `.atto` fixtures from Java/GraalVM,
   LLVM, .NET NativeAOT, Wasm, and Forge; compare the same logical oracle and
   reject illegal states before creating executable guest artifacts.

A `steps=[]` or `steps=null` workflow is runner admission evidence, not a
test pass or compiler failure. A Java-only JUnit pass would still not prove
guest-language parity. No private source should be mirrored to another
organization absent independent transfer authorization.
