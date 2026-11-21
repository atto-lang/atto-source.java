# Java/GraalVM moved source chronology regression contract

Status: DRAFT, parser/checker tests authored but NOT EXECUTED in source-owned CI.

OwnershipMoveUseOrderingTest.java adds ten JUnit dynamic checks. Five positive programs copy a detached scalar snapshot *before* rt take consumes its lexical owner, then return the moved scalar plus the detached snapshot. Five semantic-negative programs take the lexical owner and then attempt rt copy of the moved owner; they must reach the production OwnershipChecker and be rejected. They are not a proof of general non-lexical-lifetime support or actor-safe suspension.

The source-owned ownership-wave1-exact-head.yml workflow includes this class in both the path filter and the Maven -Dtest selection. No parser errors should count as a valid semantic-negative outcome. Future typed MIR/SSA loan last-use analysis must not resurrect consumed storage or turn an expired borrow into independent ownership. LLVM implements separate candidate-only last-use annotations and moved-field reinitialization contracts; those do not change Java guest acceptance.

Require immutable source SHA and tree, nonzero runner steps, executed JUnit report, compiler-backed guest receipts where applicable, and independent review before merging. No cross-org private source mirror.
