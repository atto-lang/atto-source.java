# ML workload evidence admission — reference-only first slice (Java #749)

This change is a fail-closed, dependency-free **receipt shape validator**. It does not execute benchmarks, assert source parity, independently verify hashes or certify any claimed CI run. Its positive test uses synthetic data; NEVER present it as measured Oreslang performance.

Receipts must name a real compiled Oreslang source execution mode and an immutable source/compiler SHA, artifact/fixture/reference/budget SHA-256, hardware context, nonpublic source-mirror statement, canonical CI run location, numerical reference tolerances, measurement quantiles and sample counts. Missing or unsupported fields are rejected; no accepted receipt may claim that a host-only microbenchmark was compiled source execution. The trusted evaluator must independently verify all self-reported provenance before moving a receipt from structurally admissible to verified.

## Still required before closing #749

- Freeze workload-specific SLO/capacity budgets using real baselines **before** running comparisons.
- Run real compiled .ores inference, CPU/HPC, feeder and MPA workloads with trusted reference outputs.
- Independently authenticate source Git blobs, compiler identity, full CI job trace, executable hashes and private mirror access controls.
- Quantile/throughput/queue age/GC/VRAM/GPU-utilization/recovery measurement; run matched Ray/native baselines.
- Fault injection: GPU OOM, crash, partition, slow sink, redelivery, resource exhaustion, cancellation and stale fencing.
- Source-level Java/GraalVM, LLVM, portable and privileged host evidence with explicit unsupported status and independent reviews.

Smoke: node --test tools/ml/verify-evidence.test.mjs. This is **not** a benchmark run. Do not copy private source to any public Actions repo.

## Audit hardening (2026-10-12)

- Receipts must be plain JSON records with own enumerable data properties only; reject getters, inherited prototypes, symbols and hidden fields at every modeled nested record boundary.
- Reject noncanonical negative zero, non-finite/fractional integer measurements, misleading comma-spoofed properties, multiline hardware descriptors, and noncanonical GitHub Actions URL paths.
- This remains format-only admission: even a syntactically valid GitHub Actions URL, SHA and `source_mirror_is_public: false` are **unverified self-assertions**. No trust decision may use this validator by itself.
- 24/24 pure JavaScript checks passed under a V8 synchronous harness; no actual .ores benchmark or source-provenance attestation was run.
