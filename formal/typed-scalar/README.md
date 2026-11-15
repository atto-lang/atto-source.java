# Experimental typed-AST BV64 scalar verification

This pilot lowers **the actual Java/Graal Oreslang compiler's checked AST** into SMT-LIB bit-vectors for exactly three scalar `pub fnc` declarations: `core.min_int`, `core.max_int`, and `core.clamp_int`. It is a stricter source-linking step than the earlier common-source text-parser prototype. `TypedScalarSmt.fromSource` first invokes `OresCompiler.parseAndTypeCheck` (parser, type checker and ownership checker), then admits only effects-free, nongeneric, nonactor `int` parameters/returns, direct parameter comparisons, branches and returns. Unrecognized statements, effects, expressions, missing return paths, or unsupported source forms must fail closed.

The independent contract encodes signed-64-bit min, max and current clamp behavior for reversed bounds. Each generated SMT document asks for the existence of a counterexample to the checked AST and separately requires a SAT result from an intentionally wrong mathematical implementation. This proves an abstract source-linked AST formula **only**. It does not prove the frontend or type checker correct, source-to-Truffle lowering semantics, runtime integer bounds, LLVM parity, native-image behavior, GC freedom, race freedom, or arbitrary Oreslang programs.

## Exact-head execution gate

`.github/workflows/typed-scalar-smt.yml` pins the reviewed `stdlib/core.ores` Git blob and GitHub PR head, installs Z3, compiles Java/Graal source and invokes `TypedScalarSmtTest`. The JUnit tests require actual solver output `unsat\nsat` for all three real-checked-AST routines; intentionally swapped min and clamp comparisons must yield `sat\nsat`. Unsupported literal-return code and targets must reject rather than being misrepresented in the SMT model.

GitHub Actions runners for this private repository have recently failed **before executing a single step**, with `runner_id:0`, zero billed milliseconds and no logs. Exact-head Java JUnit/Z3 results are therefore **pending**, not passing. The local common-source mini-parser proof and host BigInt tests do not substitute for this runtime. Keep draft until an assigned runner executes and logs the exact proof outputs.

## Future integration

1. Export a versioned proof IR from checked Java and LLVM frontends, including origin SHA, source-span and type/effect evidence. The current AST lacks an independently verified correspondence to target codegen.
2. Generalize beyond this small control-flow grammar to comparisons, checked arithmetic, Option/Result and stable ownership/borrow lifetimes.
3. Compare both compiler frontends on identical private source fixtures, and execute the same inputs on real backends.
4. Audit SMT encoding against compiler integer semantics (i64 signedness, arithmetic overflow, exceptional control flow) and pin the solver distribution.
5. Attach exact-head execution receipts; never count 0-step CI or silently skipped unsupported paths as proof.

Related [common-source draft #126](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/126), [hand-reviewed SMT pilot #1873](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1873), and [conformance plan #150](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/150).

## Hardening and proof transcripts

The proof boundary rejects unmodeled module annotations/contracts, unexpected
callable effects, malformed or missing proof identities, and null or oversized
source. Typed-AST traversal also has explicit limits: at most 64 branch levels,
512 visited statements, and 65,536 emitted SMT characters. These are *admission
limits*, not compiler-wide denial-of-service guarantees; the frontend parses
and type-checks inputs independently.

JUnit writes `target/formal-typed-scalar/` proof receipts for each accepted
and deliberately mutated source example. Each receipt includes the input
source's SHA-256, generated SMT SHA-256, exact SMT-LIB2, and Z3 stdout. The
workflow records the Git head, source Git blob, Java version, Z3 version and
binary SHA-256, and archives the results with a SHA-pinned artifact action.
A transcript is useful for independent replay but is **not a signed
attestation**. GitHub runner id, successful job execution and independent
review are required separately. Solver binary provenance is recorded, not
yet pinned to a reviewed package digest; artifact integrity across the
hosted CI/storage trust boundary remains an assumption.

If the initial job never starts, no artifact exists, and the result is **not
verified**. Partial artifacts from failed executions must not be treated as
successful proofs.

## Real Graal guest core-math matrix (separate CI job)

The new `examples/native-core-math-24.ores` guest imports the actual
Oreslang `std/core` implementation and emits 24 signed-i64 numeric
observations under exact `CORE-MATH-CASE|id|value` markers.
`scripts/check_core_math_guest.mjs` consumes the **captured Maven guest
stdout**, not predictions, and rejects missing/duplicate/unknown/malformed
cases, wrong signed-i64 results or absent completion. Its
`tests/core_math_guest_validator.test.mjs` examples are synthetic parser
self-tests and **not guest runtime evidence**. The guest fixture and reviewed
`formal/typed-scalar/core-math-i64-v1.json` are both Git-blob guarded.

The semantic oracle copy is pinned to the common-source signed-i64 Git blob
`58d2b64a137ede4700593f855e1f0504ff744f90`. Synchronization is manual
and requires explicit review. Java and LLVM guest source shapes differ,
so comparison is of the same **24 mathematical contracts**, not identical
source bytes or a proof that both code generators are equivalent.

The separate `graal-guest-core-math` job records real stdout, a normalized
non-attested result JSON, guest Git blob and Java version. It is a distinct
gate from the typed-AST Z3 job. If Maven fails, the pipe fails; no result
JSON can count as a pass without the captured guest output and exact-head
runner provenance.

**Current state:** [Java workflow #39744843339](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39744843339)
failed with **zero executed steps on both jobs**, `runner_id:0`.
Neither 24-case guest matrix nor compiler-backed SMT executed. This remains
a draft unverified implementation.

## Exact guest-source receipt (cross-backend parity)

The guest stdout validator also consumes the raw
`examples/native-core-math-24.ores` bytes, verifies Git blob
`2d12b8632ad77c8dee34235ecfacfca6a0fa553f` and embeds
`guestSourceGitBlob` alongside `contractGitBlob` in its normalized
receipt. A changed, truncated, or stale guest source is rejected, even
if a supplied guest stdout log happens to contain matching values.
Numeric log parsing rejects noncanonical `-0` in addition to missing,
malformed, duplicated, out-of-range, and incorrect signed-i64 cases.

The source and contract receipt is consumed by the strict offline
validator in [common-source draft #126](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/126),
which also requires the LLVM source and real GitHub workflow/job identities.
That offline structural validator **does not authenticate files or GitHub
data supplied to it** and cannot substitute for real runner execution.
