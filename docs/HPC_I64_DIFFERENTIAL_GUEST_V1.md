# AttoLang HPC i64 differential guest acceptance (Java/Graal)

**Status:** source-local JUnit candidate, **not compiler-executed evidence until a stepful exact-head run passes**. This is not Java-to-LLVM parity certification, multicore execution, SIMD, or an HPC performance benchmark.

## Actual guest execution

`src/test/java/dev/oreslang/AttoHpcTileGuestTest.java` contains:
- A fixed common `.atto` tile-sum guest source result of signed i64 42.
- Two binary64 numerical cancellation characterization tests (only this Java/Graal backend).
- A **107-case dynamic JUnit** signed-i64 corpus using `Context.eval("ores", program)` to compile and execute AttoLang guest `tile_reduce8`. Inputs are materialized as signed integer source literals in each generated guest main; the AttoLang guest performs the arithmetic. The checked Java oracle uses `Math.addExact`, never host-side re-execution in place of the guest.
- Exact Git blob checks of both shared sources: fixed `9deb9935b4f65b4d53de92f200291a28cdb02a79`; parameterized `88795ff752006d020ea08c6585a8b3b03bf641c7`.

The parameterized input generator is byte-for-byte/algorithmically aligned with the LLVM PR #1702 Rust oracle: 11 named adversarial inputs and 96 xorshift64 generated inputs from `0x5EED_F00D_BADC_0FFE`, with unsigned remainder by 1,000,001 and a -500,000 shift. AttoLang `tile_reduce8` computes `((a+b)+c)+d` for each four-element tile before their ordered sum. JVM performance, numerical outputs and completion must be independently verified in source-local JUnit receipts.

Execute after installing the repository's supported GraalVM/JDK dependencies:

```sh
mvn -B -ntp -Dtest=AttoHpcTileGuestTest -DfailIfNoTests=true test
```

The dedicated `.github/workflows/hpc-graal-reduction-v1.yml` workflow pins the PR head SHA and asserts the resource blob before running JUnit, retaining surefire artifacts.

LLVM's native companion intentionally uses a *different* execution route: dynamic host C ABI calls to compiler-generated `tile_reduce8` at both O0/O2. Java's injected literals guarantee guest evaluation but do not prove a parameterized native ABI, parallel execution, cancellation or speedup. A source-only conformance test (PR #704) establishes fixture identities but never claims backend execution. All three gates must report nonzero executed steps and independent review before merging.

Never promote queued or `steps=null` workflow results into guest-pass evidence.
