# Native Image PGO: evidence-first implementation plan

Status: **draft design only**. No trained profile, measured speedup or newly enabled optimization is claimed.

## Existing execution contract
`docs/NATIVE_DEPLOYMENT.md` describes `mvn -Pnative-aot -DskipTests package` and `mvn -Pnative-hybrid -DskipTests package`. Native AOT compiles the Java/Truffle host and guest interpreter; it **does not AOT-compile each Oreslang guest function**. JIT and hybrid have distinct execution characteristics. All three must remain separate rows of evidence.

## Experimental matrix
| Variant | Build | Training | Expected guest execution |
| --- | --- | --- | --- |
| JVM/JIT reference | pinned Maven/Graal | JIT warmup only | optimized Truffle guest |
| Native AOT baseline | native-aot | none | guest interpreter |
| Native AOT PGO | same source/toolchain; instrument, train and rebuild | real `.iprof` from approved toolchain | guest interpreter, optimized host |
| Native hybrid | native-hybrid | distinct JIT warmup | mixed host/guest JIT |

## Phase 1 — versioned preflight
- Probe Native Image edition/toolchain PGO availability. Fail as `UNSUPPORTED` if the required capabilities or accepted license are missing; never silently use an ordinary `-O2` image.
- Capture source SHA, `java -version`, `native-image --version`, operating system, arch, build flags, CPU model and Maven profile.
- Build exact base and instrumented variants from identical source. Store compiler log, binary SHA-256 and training input hashes.

## Phase 2 — controlled training and evaluation
- Training exercises admitted actor dispatch, queue send/receive, guest numeric operations, and real route handling where supported.
- Use a disjoint held-out workload and one interpreter-only negative-control workload; no synthetic/fake compiler may count as a runtime result.
- Collect 5+ independent process runs, per-run p50/p95/p99, ops/s, RSS, build time, image bytes, process CPU and allocations/op when instrumentation supports it.
- Report median and dispersion; identify regressions and temperature/frequency drift. Native profile benefits cannot be extrapolated to guest compiler AOT.

## Phase 3 — correctness/security gates
- Run identical guest output and error tests before perf evaluation.
- Validate fail-closed runtime mode selection, host capability denial, actor ownership, and hot-reload generation semantics.
- Keep trained profiles and raw reports private when source/workload artifacts contain private data.

## Release checklist
- [ ] Opt-in PGO build/train/rebuild script with real toolchain feature detection
- [ ] Profile integrity/provenance manifest with source/toolchain/executable hashes
- [ ] Held-out workload, multiple independent runs and trustworthy raw logs
- [ ] Deterministic parity, actor and capability denial regressions
- [ ] Reviewed performance claim per OS/arch/toolchain, if actually measured

Tracking: https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1254
