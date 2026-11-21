# Certified scalar Task access claims — stage 2

**Scope:** compiler-only incremental work stacked on [PR #1448](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1448). This is *not* source-level Task execution, an unforgeable runtime capability, or a complete compiler-minted lifetime proof.

## What is implemented

`TaskCertifiedLayout` is package-private. Its `Emitter` allocates an issuer-unique owner ID and monotonically increasing allocation IDs: callers cannot pass numeric IDs, owner IDs, field-disjointness booleans or unchecked range spans to the certified admission path.

The emitter recognizes **only** flat, nonempty records with direct INT/FLOAT/BOOL scalar fields, or bounded FIXED_ARRAY tuples of these scalar types.

A new `freshRecordLiteral(Ast.ObjectExpr, Types.Record)` gateway checks **actual source AST** on the straight-line compiler analysis path: exact statically named fields, matching scalar literals, inferred-struct readonly semantics and no NameExpr aliases. An AST evaluation node cannot be certified twice as two independent allocations in one emitter scope. This is a narrow source-shape gate; the Task ownership checker still must prove the resulting binding and any reborrows, and loop re-evaluation must use correctly separated runtime allocation instances. The older `modelFreshRecordUnchecked(Types.Record)` and `modelFreshFixedArrayUnchecked(Types.Tuple)` remain trusted **internal fixture/lowering hooks**, not a guest admission API. Anything generic, borrowed, pointer-bearing, nominal, dynamic, opaque, nested, String, Decimal, or structurally unknown rejects. Unknown field names, read-only writes and out-of-range partitions reject. For conservative fixed-array layout proofs, a range must contain identically typed scalar elements; heterogeneous ranges require real lowered layout evidence.

Certified `Access` objects are constructed exclusively by `RecordStorage`/`FixedArrayStorage` and bound to the issuing emitter. Atomic typed admission verifies Send/Sync via the existing `ThreadSafetyTraits` and delegates conflict, cancellation-not-quiescence, scope-retirement and stale-generation checks to `TaskAccessVerifier`. Concurrent compiler-side proof issuance is serialized to prevent numeric allocation identity reuse.

## Explicit source integration blocker

**A structural `Types.Record` or `Types.Tuple` does not by itself prove that a source expression is an owned fresh allocation, that a host/LLVM backend materializes separate inline physical fields, or that no FFI/unsafe aliases exist.** The `Emitter.modelFreshRecordUnchecked/modelFreshFixedArrayUnchecked` methods are *trusted compiler hooks*, not source-generated permissions. A future integration must feed them only from checked fresh-owned allocation sites and pinned code generations, using real MIR storage layout and provenance. Do not expose these hooks to guest code.

The Task AST/ownership checker source work is currently in Java draft PR #1377, while Send/Sync is in #1439 and the loan ledger in #1448. Preserve their **fail-closed source admission** until the branches are integrated and:
1. The ownership checker ties each issuer allocation to a proven owned fresh region and owns its lifetime across all child continuations. Ordinary reborrows and alias bindings must reuse root identity rather than mint fresh allocation IDs.
2. Checked read/write field and slice uses are extracted from Task bodies/interprocedural calls and matched against certificate shapes. Missing effect information is a compiler error, not READ by default.
3. The native/Graal lowerers prove actual inline field layout and nonoverlapping fixed-array backing storage; bounds and generation revisions must be enforced across all structural mutations and hot reload.
4. The scheduler binds each ticket to a trusted physical completion/quiescence receipt, including cancellation, cleanup and FFI pins. `Emitter.quiescent` currently models only analysis and **must not be called by guest or by a result-Future cancellation callback**.
5. The compiler enforces `Send`/`Sync` and actor/tenant isolation, not only the certificate emitter's conservative scalar restrictions. Actor-private storage must not be smuggled into this PROCESS-only scaffold.

## Tests and verification

`TaskCertifiedLayoutTest` contains 20 source-controlled JUnit cases: disjoint scalar fields, adjacent array partitions, forged/foreign issuer, readonly record, unsupported pointer/generic types, bounds, heterogeneous ranges, cancellation vs quiescence, closed issuer, atomic partial admission, unsupported large shapes a 64-allocation parallel issuance race, plus five AST literal field/shape/freshness rejection cases.

All are **source committed, not proven passed**. Full private checkout/JDK 21 Maven execution and GraalVM tests have not yet run; the previous source Actions jobs had zero executed steps. Do not claim source Task borrowing enabled or LLVM parity from these static certificates.
