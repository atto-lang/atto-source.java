# AttoLang canonical core and stdlib migration: three modules

Stacked on Java PR #2542; this is a separate PR/branch so other agents
can continue the new native `bounds.atto` work uninterrupted.

`ores.core/numeric`, `ores.std/core`, and `ores.std/assert` now load
byte-identical AttoLang source from physical `.atto` files. The old
physical `.ores` files are deleted; `.ores` **import spellings** remain
aliases to those same compiler-owned source objects, never duplicate
modules. Existing `@ores.core/numeric.ores` and `@std/{core,assert}.ores`
linked identities remain stable pending a separate versioned change.

New `.atto` modules such as `bounds` still reject explicit `.ores`
imports. Java retains only the loader/compile/Truffle/security boundary;
library functions are authored in AttoLang. No new host capabilities.

**Evidence:** the new and deleted source paths use the exact same Git
blobs (no textual changes); new `.atto` guest and legacy-shadow JUnit
cases are authored but must be run at exact PR head with real executed
steps. A source-only check or `steps=[]` does not prove guest parity.
GraalVM Native Image resources, Rust stdlib audit, LLVM/.NET/Wasm/Forge
guest execution and independent review remain explicit gates.
