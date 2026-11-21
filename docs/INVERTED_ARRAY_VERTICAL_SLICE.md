# InvertedArray one-level SoA lowering target (draft)

**Status:** Java host-only reference; Oreslang guest compilation still intentionally
fails closed. This document describes executable *compiler-target* primitives and
their current admissions, not an enabled end-user language feature.

## Concrete progress

- Parser and AST preserve `define inverted class` in root/module scope.
- A nominal `InvertedArray.Schema` can be derived from the parsed class and
  maps **only directly declared fields** to physical columns; never recursively
  splits the members of a nested value struct.
- `InvertedArray` owns the actual column memory, creates opaque generation-checked
  handles, provides transient row views, and permits bind-once fixed-column access.
- A restricted `InvertedRowMethod` compiles a source method's AST into a
  bounded, pure, non-suspending scalar plan. It cannot execute arbitrary calls,
  block, spawn actors, capture references, return values, or access other objects.
- `InvertedColumnStore.transformRow` executes that preverified plan under the
  store lock, validating and committing its scalar output atomically. Inserting
  a row stores independent scalars; no persistent class-object heap allocation
  is performed for that row.
- Stale/foreign/forged row handles, conflicting column loans, invalid field names,
  and closed stores are rejected by the underlying implementation.

## Current admitted host lowering example

This Oreslang **class declaration** is parsed; JUnit feeds the resulting AST to
the host lowering target. It is not yet admitted for `.ores` guest execution:

```oreslang
define inverted class Particle as
    x: f64;
    y: f64;
    vx: f64;
    vy: f64;

    move(f64 dt): void {
        self.x = self.x + self.vx * dt;
        self.y = self.y + self.vy * dt;
    }
end
```

In the Java host reference, `InvertedArray.fromClass(parsedClass, capacity)`
compiles `move` once. `array.invoke(handle, "move", 0.1)` runs it against one
stable row handle without making an `OresObject` for the row.

The initial method plan admits only `f64` immutable parameters, `f64` target
fields, pure `f64` literals, field reads (`self.field`), parameter reads,
unary minus, and `+`, `-`, `*`. Other AST effects and conversions are
rejected. This is intentionally narrower than the final language.

## One-level inversion contract (October 25, 2026)

`define inverted class` inverts the fields declared *at that class level
only*. A direct `position: struct{x: f64, y: f64}` field creates **one**
`position[]` column containing contiguous `{x,y}` values, **not** separate
`position.x[]` and `position.y[]` arrays. Other directly declared fields
get separate columns, regardless of whether their types are scalar or
fixed-layout value records. The one-column-per-declared-field invariant
applies to row insertion, field binding, borrowing, erasure and snapshots.

```oreslang
define inverted class Particle as
    position: struct{x: f64, y: f64};
    velocity: struct{x: f32, y: f32};
    mass: f64;
end
```

Host reference layout (conceptual, not end-user guest execution):

```text
Particle
  position: [ {x0,y0}, {x1,y1}, ... ]    // each element is packed AoS
  velocity: [ {x0,y0}, {x1,y1}, ... ]
  mass:     [ m0,      m1,      ...  ]   // separate scalar column
```

The Java host-only reference now supports inline closed structural record
types and explicitly supplied non-generic structural type aliases.
Supported record members are currently fixed-width scalar primitives
(`i32/i64/f32/f64/bool`) with no pointers, dynamic arrays, strings,
unverified widths, nested records, or recursive alias expansion. An
unsupported record layout must **fail closed**; the compiler must never
silently flatten grandchild fields, construct persistent row objects, or
fall back to ordinary AoS class storage.

All byte-width calculations use checked arithmetic; record writes,
reads and loan operations copy buffers and return immutable detached
record values. The reference encoding is packed little-endian; it is
**not yet an ABI commitment** for native LLVM, padding, alignment, GPU
or external interop. LLVM must explicitly agree on physical layout
before guest execution is admitted. Existing class-method admission
remains scalar-only and source-level inverted classes remain gated by
TypeChecker until ownership/effects/parity are proven.

## Layout and lifecycle

Current primitive physical kinds: `i32`, `i64`, `f32`, `f64`, `bool`.
An additional fixed-stride RECORD column stores packed, AoS-formatted structural
value elements, one per row, using an explicit verified codec. The record's
members are **not** promoted to sibling SoA columns.
The aliases `int` and `float` must be mapped only after the Java/LLVM
target ABI and signedness/width agreement. Generic classes, parent classes,
interfaces, constructors, annotations, and initialized/resource-owning fields
are not admitted to this lowering target. The existing type checker continues
to reject *all* executable inverted-class guest code, including classes whose
AST the host reference can model.

The backing Java store uses contiguous little-endian per-field byte arrays,
with record elements encoded at a fixed byte stride.
Handles use opaque store-issued issuer identity, slot and monotonically advanced
generation. Reuse after deletion cannot revive old handles; deleted physical
tail bytes and closed backing arrays are cleared. Bounded row transforms
serialize on the Java store monitor. They are not a claim of lock-free SIMD or
production performance.

## Proof currently available

- [Final exact-source targeted carrier run #39199996790](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39199996790):
  all parser, TypeChecker, column-store, typed-array and bounded method-lowering
  JUnit gates passed, including preventing a schema-only owner from silently
  dropping declared methods.
- [First passing source-method carrier run #39199603070](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39199603070):
  Java 21 compilation, parser and TypeChecker regression tests, SoA storage
  JUnit, AST-derived typed owner tests, restricted method-plan tests, concurrent
  atomic row-update tests, negative unsupported-effect/type tests.
- Exact source Git blob verification in a separate **private** repository.
  The carrier uses only its own read-only token; the original repository
  remains private and was not checked out across org boundaries.
- The failed preceding run [#39199421746](https://github.com/scintilla-run-test/gleam-runner-conformance/actions/runs/39199421746)
  caught admission of integer constants as f64 expressions; the fix rejects
  implicit conversion and the replacement run passes.

## Next gates (not yet satisfied)

1. Separate compiler-owned typed layout/method IR from this Java reference
   target and add an `alloc`-owned guest API. The `core` package must not
   gain a mandatory heap requirement.
2. Modify Java source type/ownership checking to admit only source programs
   whose row/column borrows are proven nonescaping, actor-domain safe and valid
   across `await`/tasks. Only then enable evaluator binding for
   `InvertedArray<T>` expressions; do **not** fall back to AoS or Java host
   interop to get apparently passing guest tests.
3. Implement the matching native LLVM typed IR, ABI and error-lowering path,
   plus cross-backend executable guest conformance.
4. Run the full GraalVM repository build, native CMake/CTest, sanitizers and
   memory/performance measurements. The current focused tests do not qualify
   the entire source repository.
