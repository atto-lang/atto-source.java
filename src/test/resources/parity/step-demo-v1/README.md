# Exact-byte native workflow guest tests (same organization)

This directory contains nine Oreslang source fixture files copied byte-for-byte
from private repository `ores-truffle-oreslang/oreslang-step-function-demo`
at commit `0ba32f019a0c1dfcf9aaa2f91accb030178f5a45` into another private repository of the **same
organization** for Java/Graal compiler-backed JUnit execution.

The Java test calculates the canonical Git blob SHA-1 of each source and
rejects drift from the expected immutable Git blob. Successful JUnit assertions
then prove the Oreslang guest actually ran, provided the CI runner executed
real steps. A **zero-step Actions failure is NOT_EXECUTED**.

These inputs are in-memory actor message fixtures, not durable actor
persistence, authenticated approvals, workflow graph parsing, or sealed
`state { ... }` critical loans. They are not production step-function parity.

No source was sent to any cross-organization test carrier. Cross-org private
source transfer remains blocked by platform-conformance issue #22.

| Source | Expected stdout | Git blob SHA-1 |
| --- | --- | --- |
| `order-workflow.ores` | `order:complete` | `beb1e74abdfdcf06371d00e432e0a6af4e2052b0` |
| `order-rejected.ores` | `order:rejected` | `4319ec422a864feddf531eab7a80ade20f36fb5a` |
| `agent-memory-workflow.ores` | `memory:fixture-indexed` | `970302d6de46d05b2402059f201ddcb532010992` |
| `agent-memory-quarantine.ores` | `memory:quarantined` | `f11f77b3216cfb2a05d6ea9d8a8b21c3bff56b62` |
| `release-review-workflow.ores` | `release:preview-only` | `cafb2332e30d3289869687d9ca0cbffdd7a59663` |
| `release-waiting.ores` | `release:waiting` | `2f99ce9bf194689659d49dbac57496cee022a759` |
| `bounded-retry.ores` | `retry:complete-after-two` | `606f40654c35da572f8ef35fd71f53ed8a9e2508` |
| `cancel-before-work.ores` | `cancel:stopped-before-work` | `b774babccb52ce4d51eff2d8262f6a5e6eeab0de` |
| `coordinator-state.ores` | `workflow:state-guarded` | `d91bfa28323d198c0001ece58747e68d9980597f` |

## October 29: 13/13 exact source blob parity (NOT executable acceptance)

The canonical demo saga draft source is `ores-truffle-oreslang/oreslang-step-function-demo` PR #6 at commit `c4c025af750e08abdb0197d84d660e277eddaea8`. All **13/13** tracked demo `workflows/*.ores` files were compared independently against the same-named private Java test-resource blobs in this PR, and **all 13 canonical Git blob SHA-1 values match** at compiler PR commit `a3ec8deedac45b02142bbf5639b378b88ba7b981`. Both repos are private and inside the same GitHub organization; there was **no cross-organization source mirror** in this recovery.

Key repaired safety cases: `order-saga-compensated.ores` blob `79bdbddf049297b9f6692c59ca670a2604fdcfa2` refuses to release inventory if payment void is unconfirmed; `order-saga-void-unknown.ores` blob `7137fcfaba3782e357ffe433a8b05798cd811849` expects `saga:compensation-incomplete`. The coordinator compatibility sample now also matches at blob `d91bfa28323d198c0001ece58747e68d9980597f`; it is NOT compiler-sealed state.

JUnit `SourceActorDemoWorkflowExecutionTest` now defines **13** guest tests, and the dedicated Graal Actions workflow requires exactly **13** demo + **3** actor baseline = **16** real executed tests, including no errors/failures/skips. These are source and workflow **configuration** facts, not test results. **No real JDK25/GraalVM source runner has executed these current heads**. Until a successful exact-head run records runner ID, nonzero steps, real `Context.eval`, output and Surefire receipts, the verdict is **NOT_EXECUTED**, not PASS. The compiled sealed-state loan and cross-backend parity remain separate requirements.
