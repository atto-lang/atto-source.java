# PR #1708 — semantic integration of Entry host-carrier safety

Historical head: `19deb6b05bc6a7f7bc251c9639f50beeba10b9db`; modern Java main: `d16793fd4209504bb8fd85f959d01370ba37fa77`.

This commit ports the historical nominal `OresEntry` **host-value carrier** unchanged and adds three focused host identity/opacity tests. A guest Entry is not a Java record equality/hash contract: two equal-looking carriers stay distinct Java keys; cyclic/hostile payload `equals/hashCode/toString` may not be invoked. Recursive guest equality and formatting still belong to bounded compiler/evaluator logic, not to record-generated methods. `SharedState` exposes key/value for future runtime authorities.

**Important incomplete feature:** main has no Entry evaluator/type/import/ActorRuntime.freeze integration yet. We do **not** port old evaluator/parser/actor transport changes over newer Task/borrow and isolation invariants. These three tests prove only host carrier behavior, not guest Entry expressions or actor-safe freezing. Original `OresEntryHostCarrierBoundaryTest` fourth test (freeze identity) and `OresEntryRuntimeTest` remain historical, pending actual integration.

Run exact-head Java tests under the supported JDK and add guest parsing/freeze/cycle tests with codegen before release; do not claim all of PR #1708 has been implemented by conflict resolution.
