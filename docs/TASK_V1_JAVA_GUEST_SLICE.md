# Java/GraalVM source Task guest vertical slice — candidate

Real `.atto` fixtures `tests/fixtures/task-v1-java/typed-int.atto` and `eager-before-await.atto` are implemented in the **existing Java-supported `Future<T>` Task syntax**. They compile using `OresMain --check` and run via the real JVM/Truffle guest launcher in `scripts/test-source-task-v1-java.sh`, with bounded timeouts, exact source blob checks, typed result 42, and eager channel delivery before await.

```bash
mvn -B -ntp -DskipTests package
mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/conformance-classpath.txt
EXPECTED_HEAD="$(git rev-parse HEAD)" bash scripts/test-source-task-v1-java.sh
```

**Current status: NOT_EXECUTED** until these commands have run on an authorized source-local runner. This is only a narrow Java guest proof, **not** byte-identical cross-backend `Task<T>` conformance. LLVM currently expects `Task<int>` with a synchronous main while Java currently expects `Future<int>` with an async main. Do not claim the two language programs are equivalent or pass shared conformance #465. Generic `HasFuture<T>`, Task cancellation, physical retirement and actor-state isolation remain separate acceptance conditions.

## Compiler-negative controls

The runner also submits reject-live-guard.atto and reject-moved-capture.atto to the real Java source checker, but never executes them. These are adapted from current CpuTaskLanguageTest fixtures. Each must fail with a relevant guard/moved-capture diagnostic, and a successful compiler admission or timeout fails the script. Execution not yet verified.
