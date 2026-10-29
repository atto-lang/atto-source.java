# Native Oreslang saga guest acceptance — Java/Graal candidate (2026-10-29)

**Implementation language: pure Oreslang.** These are source-guest programs copied
**within ores-truffle-oreslang** from the private native step-function demo, not
a Java or Node workflow simulator.

## Exact guest source identity

| Native demo fixture | Git blob SHA-1 | Expected stdout |
|---|---|---|
| `workflows/order-saga-success.ores` | `1d47ce2cfb929d43a7501fbc4bf2363ec2195fde` | `saga:complete` |
| `workflows/order-saga-compensated.ores` | `d4782060ff0f53588b00e8c2eb9b7f87f5824e3d` | `saga:compensated` |
| `workflows/order-saga-ambiguous.ores` | `51ca94e1cbf983a495ed89f1531e9e740d50c34d` | `saga:reconciliation-required` |

Each target Git blob was independently created from the same source file
bytes in `oreslang-source.java` and verified equal to its demo blob SHA.
The code is under `src/test/resources/parity/step-demo-v1/`.

## Exact execution requirement

`.github/workflows/actor-demo-native-guest.yml` now requires **12**
`step-demo-v1` `.ores` files, plus the **3** earlier actor-step fixtures.
The Graal/JUnit suite must run **15 actual Oreslang guest cases**, using
`Context.eval(Source.newBuilder(OresLanguage.ID, ...))`, exact stdout and
Git blob assertions. Every guest has a 30-second JUnit timeout.

```sh
mvn -B -ntp -Dtest=SourceActorStepWorkflowExecutionTest,SourceActorDemoWorkflowExecutionTest test
```

**Current status: SOURCE INTEGRATED, NOT_EXECUTED.** A Java syntax/static
check or an Actions run with `jobs[].steps=null` is not a native Graal guest
result. Exact compiler and Java test commit SHAs, executed runner job steps,
JUnit XML and failed/green guest verdicts must be retained before promotion.
The three saga fixtures simulate a known business rejection and unknown
outcome; they do not perform actual payments, refunds or storage.

**Private-source transfer boundary:** no cross-organization mirror is
authorized while platform-conformance security issue #22 remains open.
