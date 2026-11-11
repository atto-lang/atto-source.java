# AttoLang strict compiler front end: first AOT-compatible increment

**Status:** draft implementation, experimental. No native guest AOT proof has
been completed. Legacy source and existing compiler entry points remain
unchanged.

## Explicit source profile

The strict profile is **opt-in**, never silently selected from a source
filename. `.atto` is canonical for new AttoLang source; `.ores` remains a
legacy-compatible extension. A build tool must select the profile expressly.

| Expression | Strict AttoLang | Legacy parser |
| --- | --- | --- |
| `left eq right` | admit after type check | admit |
| `left neq right` | admit after type check | admit |
| `left !eq right` | admit contiguous only | admit |
| `left == right` | reject at lexer-token boundary | admit |
| `left != right` | reject at lexer-token boundary | admit |
| `int eq float` | reject implicit numeric conversion | historical behavior |
| template interpolation | reject pending propagated profile | existing behavior |

Strict numeric equality requires identical statically resolved numeric types.
It does not invent coercions. Unknown equality domains fail closed; comments
and ordinary strings do not count as equality operators. Function-head clause
matching remains **deferred**.

## Public Java compiler APIs

- `Parser.parseStrictAtto(source)` performs strict token admission and parses
  the source as an AST.
- `OresCompiler.parseAndTypeCheckStrictAtto(source)` additionally runs
  strict type checking and ownership admission. This single-unit API **rejects
  imports**, because it has no source graph.
- `IncrementalCompiler.compileStrictAtto(sourceUnits, resolutions)` checks
  the complete supplied source graph. Every linked dependency must itself
  be supplied as strict source and parsed under the same profile.
- `OresCompiler.parseAndTypeCheckStrictAttoGraph(sourceUnits, resolutions)`
  exposes the checked linked graph through the trusted public front end.
  Strict and legacy incremental caches are separate. No unchecked external
  package or Java host source is implicitly linked into the strict graph.
- `OresCompiler.compileRealtimePrimitiveStrictAtto(source, entry, arity,
  budget, outputDirectory)` emits **Java 8 classfile bytecode** for the
  existing closed-world bounded `i32`/`bool` primitive subset.
  `eq`/`neq`/`!eq` are lowered to Java primitive comparison operators
  *during compilation*. No runtime source compilation or reflection is
  generated in the emitted class.

The last API is a real static classfile emitter but only a narrow guest
subset. `javac` runs at **build time**, not in the emitted guest program.
Executing the class on a JVM may use the JVM's own JIT. A classfile, a JVM
execution test, and a precompiled Truffle interpreter are **not** equivalent
to a program-specific Graal Native Image artifact.

## Remaining strict-AOT release gates

1. Integrate profile selection into the production CLI, resolved package
   loader, and all compilation/link entry points without bypass routes.
2. Either provide provenance-verified strict checked interfaces for imported
   packages, or keep external/host imports rejected. Do not widen based on
   filenames, conventions or a stale cached AST.
3. Statically lower typed binary patterns and selective receive. Their
   actor-message semantics, resource limits and generation fencing must be
   independently verified; the bounded host bit cursor is not a guest lowering.
4. Execute the JUnit source-graph checks and produced classfiles at the exact
   PR head; attach negative diagnostic receipts and check emitted artifacts.
5. Produce and execute a **native compiled guest program** with confirmed
   no-JIT/no-interpreter fallback; then repeat for LLVM, .NET NativeAOT, Wasm
   and Forge. Include hard negative mutation tests and independent review.

A GitHub Actions run with `steps=[]`, `steps=null` or an unassigned runner
proves no test execution. Success of a source-free canary or of a **different**
mirror tree is also not evidence of this source head. For any private mirror,
obtain authorization to export, confirm a private destination, and establish
byte-for-byte tree identity before treating a rerun as an exact-head result.
