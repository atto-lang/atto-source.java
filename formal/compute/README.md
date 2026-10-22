# Checked physical byte layout, distinct from structural validity

This draft **adds** `compute.valid_exact_buffer_layout(&BufferLayout)` without changing `valid_buffer_layout` or the compiler/runtime allocator. The existing function is portable *structural* prevalidation only; it may accept a descriptor whose declared byte count differs from the physical size.

For positive signed-i64 `element_bytes` and positive `length`, the new predicate rejects multiplication overflow **before** multiplying, using `element_bytes > INT64_MAX / length`; then checks `byte_length == element_bytes * length`. For `length == 0` it requires exactly zero bytes. Nonpositive element size/alignment, negative length or negative byte length reject. The quotient is never evaluated with a zero denominator. If Oreslang `int` is not signed i64 in some profile, the guard's semantics need target-specific rejection and proof.

**This is not an allocation authorization or memory-safety guarantee.** A positive alignment alone does not prove a power-of-two alignment or device support; device limits, capacity, address space, handle provenance, generation, ownership, transfer rights and quiescence still require runtime enforcement. The method is intended as an additional portable preflight, not a substitute.

## Bounded formal model

`formal/compute/exact-layout-bv8.smt2` proves 3 UNSAT properties and exercises 2 SAT rejection controls **for an 8-bit signed arithmetic analogue** of the source branch logic. The model was checked locally using Z3's shared library: `unsat, unsat, unsat, sat, sat` (around 15 ms). This is exhaustive in BV8 but **not a full signed-i64 proof**, not source-to-SMT translation, and cannot establish that the actual guest compiler uses these semantics. An attempted full symbolic BV64 quotient/widened-product theorem exceeded a local timeout and must remain unresolved rather than be reported as proven.

`examples/native-compute-exact-layout.ores` supplies **real guest execution** cases for inconsistent physical byte lengths, 0-byte layouts, i64 overflow, i64 maximum boundary, negative lengths and invalid alignment. The exact-head GitHub Actions workflow requires the solver output and guest sentinel. Neither can pass until a runner executes actual steps.

## Promotion gates

1. Run workflow at exact PR head with host Z3 and Graal guest compiler; require zero skipped cases.
2. Independently review `valid_exact_buffer_layout`'s syntax/semantics and the ABI allocator's own checked-size enforcement.
3. Produce a full-domain BV64 proof, or a mechanized verified lifting argument from BV8 to signed-i64; current bounds do not justify that claim.
4. Add guest and allocation-refinement negatives to LLVM/.NET/Wasm/JS where implemented; prove alignment and authorization as separate gates.
5. Preserve the current structural API while rolling out `valid_exact_buffer_layout` separately; do not silently change caller admission.

Related: [Java issue #1876](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1876) and [cross-backend issue #150](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/150).

## Differential arithmetic evidence (host only)

`formal/compute/checked-layout-reference.test.mjs` runs the signed-i64 quotient preflight against an independent unbounded-BigInt product specification. It checks 18 edge vectors, 102,966 bounded cases (131×131×6), 12,000 deterministic full-width samples, and two intentionally unsafe variants that must disagree with the specification. This file was run locally with Node 22 and all four tests passed. It models the arithmetic rule only; it neither calls Oreslang nor proves the source is semantically identical to this host model, and random testing is not an SMT proof.
