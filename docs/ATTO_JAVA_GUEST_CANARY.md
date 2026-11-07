# AttoLang Java/GraalVM actor-critical guest canary

This change is an executable **compiler-backed test declaration**, not an executed CI result. It is a precursor to the separate two-reader ownership and `rt take` acceptance program described in [platform-conformance issue #522](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/522).

## Real guest metamorphic control

The JUnit method `CriticalActorGuestExecutionTest.canonicalAttoGuestOutputTracksARealSourceMutation` loads the canonical `examples/critical-actor-checked-v1.atto` source into the real Graal/Truffle language Context through `OresCompiler.parseAndTypeCheck` and `context.eval`.

It compares two separate evaluations:

| Variant | Source initialization | Expected observed stdout |
| --- | --- | --- |
| Canonical | `let int value = 40;` | `42` |
| Mutated | `let int value = 41;` | `43` |

The test requires one and only one occurrence of the source replacement target and uses a distinct polyglot Source filename for the mutated variant. A source-insensitive stub that always prints `42` cannot pass this test.

This does **not** prove actor isolation, simultaneous readers, borrow exclusivity, generation transfer, or Native Image/AOT behavior. It is a restricted actor-critical JVM guest source-sensitivity canary.

## How to run (once an authorized runner is admitted)

```sh
mvn -B -ntp -Dores.runtime.carriers=java -DfailIfNoTests=true \
  -DskipTests=false -Dmaven.test.skip=false \
  -Dtest=CriticalActorGuestExecutionTest test
```

The existing workflow `.github/workflows/critical-suspends-java-admission.yml` pins original PR-head checkout, records the Git root tree, and requires the new test name inside a nonempty Surefire XML report with no failures, skips or errors. A separate artifact carries the original source SHA, tree, canonical fixture digests, and compiled `OresCompiler.class` hash when present.

These artifacts are explicitly candidate-only until another verifier binds their exact run/job/attempt, step execution, source tree, relevant blob hashes and test output. An artifact uploaded by `if: always()` is never by itself proof that tests ran.

If GitHub reports `steps=null` or `steps=[]`, classify it as runner-admission failure, **not** a compiler failure or a passed guest test.

## Follow-up formal refinement

Complete ACT-BORROW-001..005 on the actual canonical compiler with positive read/read overlap, compile-negative writer/read overlap, `rt take` generation fencing and cancellation. Apply the same canonical .atto fixture blob to LLVM, .NET NativeAOT, Wasm and Forge. These requirements are **not implemented or satisfied** by this restricted critical-actor canary.
