# Second five-module AttoLang-native stdlib migration

**Stack:** Java PR #2543 (and #2542). Avoids the separate active testing
and actor-sync implementation PRs.

Five pre-existing portable library sources are moved byte-for-byte, without
new guest behavior or Java implementations:
- compute.ores to compute.atto
- trace.ores to trace.atto
- db_pool.ores to db_pool.atto
- http_wire.ores to http_wire.atto
- actor_persistence.ores to actor_persistence.atto

Original Git source blob SHAs are preserved under the new physical names;
the .ores physical files are removed so there is one compiler-owned definition.
Extensionless, .atto and legacy .ores imports all resolve to one AttoLang source
and preserve the existing @std/<module>.ores code-unit identity. New modules
such as bounds.atto remain .atto-only.

Java remains responsible for source admission, compiler, Truffle, security and
runtime primitives; no Java guest semantics or host privileges were added.

JUnit covers canonical/legacy source and identity, no host interop, shadowing,
fail-closed unknown imports, and five .atto guest fixtures. Four fixtures call
library algorithms; compute currently verifies import/link/execution only and
does not prove native device semantics. The existing Java guest regression
suite, Rust boundary audit and Native Image resource packaging are further gates.

No claims of actual compiler-backed success or LLVM/.NET/Wasm/Forge parity
until exact-head CI shows nonzero executed steps, real guest results, source
receipts and independent review. steps=[] or steps=null denotes runner
admission failure, not compiler failure.
