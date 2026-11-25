# JVM runtime diagnostics (not guest benchmarks)
 
This isolated JMH 1.37 module measures canonical runtime JSON parse, stringify, owned lookup hit/miss, strict UTF-8 encode/decode, and native Bytes versus boxed-list body assembly/decode. No production dependencies or compiler API changes. Fixtures are synthetic and checked in trial setup. Run only on a clean authorized checkout with GraalVM JDK25 and Maven. The script installs the exact compiler into a fresh isolated Maven repository so the SNAPSHOT cannot silently resolve to an older local build.

```sh
bash benchmarks/jmh/run.sh '.*RuntimeDiagnostics.*' -prof gc -rf json -rff /absolute/path/outside-checkout/runtime.json
```

Default: 3 forks, 3 warmups, 5 measurements; use a dedicated pinned CPU host and identical JVM settings for baseline and candidate. Capture raw JMH JSON, allocation bytes/op, GC, CPU/OS/JDK identity, exact commit/tree and fixture parameters. Compare lookup scaling from 16 through 512 keys and assembly scaling across payload/chunk sizes. Use identical harness blob on both revisions; do not compare CI smoke scores, different semantics or host measurements against Rust guest/web throughput. Native Bytes includes cleanup and the boxed path is a diagnostic approximation of the guest loop, not identical guest lowering. This is not the official SharkBench workload. Integrate with actual guest microbenchmarks separately.

Local environment lacks Maven/JDK25; module compilation and JMH execution are NOT_EXECUTED. No performance numbers claimed.
