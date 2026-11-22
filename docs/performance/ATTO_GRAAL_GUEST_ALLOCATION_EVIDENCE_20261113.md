# AttoLang Java/GraalVM guest allocation evidence — 2026-11-13

Status: executable test candidates stacked on `RuntimeMemoryProbe` PR #1278, **not** yet an executed whole-project JVM or Native Image result.

## Real native guest workloads

- `src/test/resources/atto/memory/scalar-loop-control.atto`: 256 iterations summing integers.
- `src/test/resources/atto/memory/owned-vector-churn.atto`: 256 Vector[int] appends followed by a read/sum. Both must emit checksum `32640`.
- Java JUnit `AttoAllocationChurnEvidenceTest` loads each `.atto` source, calls the production compiler/parser typechecker, and verifies a real Polyglot guest context emits `32640`. No Java vector loop substitutes for the guest algorithms.
- Each guest workload is then run 32 warm-up and 128 measured `Context.eval` invocations within one context, with a null output stream to avoid growing guest stdout buffers.
- `RuntimeMemoryProbe` samples current host thread allocation bytes, global JVM collection count and global collection time outside the measured loop. `UNAVAILABLE` remains a first-class result.

## Interpretation (strict)

This is **host JVM memory per Context.eval invocation containing guest code**, not isolated guest-instruction allocations or a per-actor heap metric. It includes runtime dispatch, frontend/cache behavior, Truffle objects and any Java stdlib work on the sampling thread; other carrier threads may allocate uncounted bytes. The two workloads have different guest operations; any measured difference is descriptive, not causal proof of an optimization.

A Java `Vector[int]` may still use traced managed Java storage. A long-lived, memory-flat native actor arena does not establish that all guest values are lowered off-heap. A measured GC count of zero does not prove absence of future collections, lack of managed allocations or bounded process lifetime. Epsilon collects nothing and must not be used as a production leak workaround.

The exact-head JDK25 workflow is **NOT_EXECUTED** when GitHub reports `steps=[]` or `steps=null`. A successful native source test needs actual compiler and guest execution, nonzero job steps, verified JUnit XML, immutable fixture blobs and owner-reviewed security provenance.

## Next acceptance matrix

1. Determine distinct compiler-lowered guest allocation sites and classify them as stack/register, unique owner, per-actor arena, shared immutable region or foreign handle; reject unknown allocation profiles.
2. Inject compiler-generated one-shot drop glue for return/throw/raise/cancel/STOP/async continuation unwind and prove no alias/loan escape.
3. Run one million deterministic actor mailbox turns and at least 10,000 restart/upgrade cycles with a fixed retained-state budget; assert native outstanding bytes return to the survivor baseline after each retirement and never depend on GC timing.
4. Measure thread/process allocated bytes/op, process RSS high-water, GC pause event distributions including p99/p99.9, lost callback or native leases, and latency tails on isolated fixed hardware.
5. Build and execute a **real GraalVM Native Image** with the exact same `.atto` guest source while distinguishing Native Image of interpreter code from compiled guest machine code. Compare Java/Graal with LLVM and .NET in the same source contract.

No new `.ores` test fixture, Python test tooling or new guest host privilege is introduced by this patch.
