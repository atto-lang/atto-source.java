# Computed `rt take` semantic admission matrix — Java/GraalVM

**Candidate compiler test inventory, not executed source-org CI proof.**

`src/test/java/dev/oreslang/OwnershipComputedPlaceMatrixTest.java` adds **15 independent JUnit Jupiter dynamic tests**. Every test first requires `Parser.parse` to succeed for a fully formed AttoLang function. It then calls the actual production `OwnershipChecker.check` and requires `E_TAKE_UNTRACKED_PLACE` for an arithmetic or unary `rt take` operand.

The cases cover literal/named addition, subtraction, multiplication and division, unary negation, nested sums/subtractions, and arithmetic subexpressions on both sides of multiplication. This tests semantic admission, not parser-only failure. Existing suites separately exercise legal named owner transfers and fresh constructor-owned temporary transfers.

**Semantic scope:** the current source AST checker conservatively rejects computed `rt take` values without an invalidatable tracked owner slot. A fully typed HIR/MIR implementation may later distinguish *fresh owned temporary* transfers from an illicit re-labeling of live source storage; this test suite must be revised with the proven compiler semantics, not blindly treated as a permanent ban on rvalues.

**Evidence:** no Java/Truffle guest, JUnit, GraalVM or native backend tests were executed as part of authoring this source. Parent PR #2607 hosted jobs had `steps=null`. Do not merge as compiler-proven without nonzero-step exact-head source-owned JUnit execution and independent review.
