# Compiler-side Data Layout Advisor (Java)

This host-side Java library is intentionally a **typed and immutable advisory
primitive**, not a Java-source regex scanner and not an automatic rewrite pass.

`dev.oreslang.types.DataLayoutAdvisor.analyze(Region)` accepts evidence produced
by a compiler or profiler: field widths, accessed indexes, row count, hotness,
access order, layout, relocation/stable-address obligations and optional
measured gather/tile/ECS mutation evidence. It returns `ORES-LAYOUT*`
diagnostic codes consistent with the Rust layout advisor.

Current Java codes: 101 (AoS selective scan), 131 (SoA whole-record scan),
132 (random gathers), 133 (tile mismatch), 134 (dense ECS query),
135 (structural churn). Invalid indexes, over-budget field lists and
impossible tile data fail closed before issuing recommendations.

The authoritative multi-backend JSON evidence contract remains in
`oreslang-static-analysis.rs/docs/LAYOUT-IR-V1.md`; this class currently
does **not** walk Oreslang source by itself. To use it in the compiler,
attach typed field accesses, actual loop iteration order and source spans
from HIR/CFG and profile data, and run the same golden cases against the
Rust implementation. Unverified loop hotness or unsafe relocatability
must not be invented.

For Oreslang `define inverted class` and layout-selectable
`InvertedArray<T>` / eventual `LayoutStore<T>`, avoid synchronized copies
of all physical layouts. A new layout should require explicit safe relocation,
outstanding-loan barriers and generation-checked stable handles. Static
optimization does not confer actor isolation or ownership capability.

Unit tests: `mvn -Dtest=DataLayoutAdvisorTest test`.
No JUnit/CI execution has yet been observed on this draft PR.

## Oreslang-native rules (initial source, not yet self-hosted)

The optional `stdlib/analysis/layout_advice.ores` defines pure, bounded
semantic predicates for AoS field-selectivity, SoA whole-row access,
AoSoA tile sizing, dense ECS iterations and archetype mutation churn.
They return the same stable diagnostic codes as the host analyzer.
This is an **unwired foundation**: the Java/LLVM compilers do not yet
invoke these Oreslang functions on source/HIR, and this draft has not
passed an end-to-end Oreslang parse/typecheck/runtime test. Therefore
it must not be advertised as an operational self-hosted analyzer.

No optional stdlib analysis module is a dependency of freestanding `core`.
A future self-hosted pass can consume immutable, compiler-verified layout
IR while retaining Rust and host backends for cross-checking and bootstrap.
