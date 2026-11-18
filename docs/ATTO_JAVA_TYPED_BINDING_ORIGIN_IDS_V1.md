# AttoLang Java typed lexical binding IDs (v1)

**Status: DRAFT; no source-org JUnit/GraalVM execution proven.**

Each `Scope.define` now mints a typed `BindingId` from the root lexical scope's monotonic allocation counter. Shadowed names receive different binding IDs, while a compiler-linked alias retains the existing `VarState` owner identity. Repeated source spelling, different function scopes or arbitrary imported names do **not** establish different heap storage.

A typed `OriginId` record exists but defaults to zero (**unknown allocation origin**) on Java. This patch does not invent a heap-origin certificate or encode user-supplied source names as allocation proofs. `resolveOwnershipPlace` follows a bounded aliasSource chain (cycle guarded), requires a minted source binding, and carries the binding ID, unknown origin ID, checked root state, and bounded field/index projections. Root-borrow Share admission remains fail-closed. The existing partial-move and ownership capability gates are preserved.

`OwnershipMintedBindingIdTest.java` adds **20 parse-first production-checker JUnit cases**: 10 detached scalar Copy-to-Share positives and 10 loan-to-Share semantic-negative cases. The exact-head Java ownership workflow includes them in its Maven test selector and PR paths. None of these cases is counted as compiler-backed evidence until a source-owned runner executes the actual Java checker and guest tests.

**Out of scope:** typed allocation-origin minting on Java, globally stable actor IDs, full region inference, loop fixed points, non-lexical lifetimes and cross-isolate Send authority. They require a separately reviewed compiler MIR implementation and real negative/positive guest receipts. No external organization source mirror or zero-step green claim.
