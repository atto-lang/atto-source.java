# Atto guest microbenchmark admission

Three bounded real .atto workloads exercise canonical JSON parse/lookup/stringify, strict UTF-8 round trips, and native Bytes concatenation/decode. Each runs 1000 iterations and emits one checksum; no host code implements guest operations. This is a correctness gate, not a timing experiment. Run `bash tests/fixtures/sharkbench-microbench/run.sh` on an exact clean compiler checkout with JDK25/Maven.

Expected checksums: JSON 22000; UTF-8 8000; Bytes 8000. Future timing must execute the admitted source with configurable iteration counts, separate compilation/startup, measure multiple warmups/forks and capture raw receipts; do not compare these checksums or CI wall-clock time to JMH or Rust web results. No compiler execution has occurred yet. The syntax is drawn from existing canonical guest regression fixtures, not yet compiler-verified on this candidate. Stacked on Java #1816.
