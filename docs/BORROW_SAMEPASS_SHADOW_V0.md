# Same-pass ownership shadow hook V0 (Java)

Draft stack on Java #2691. `OwnershipChecker.checkWithBorrowShadowV0(program, sink)` invokes the existing checker exactly once; the sink is called after successful move/persistent-borrow and suspension checks. Normal production calls `OwnershipChecker.check()` do not allocate trace events.

The opt-in events are **partial evidence only**, not a complete typed CFG, lifetime, loan-release or unwind trace. Diagnostic names are not canonical owner IDs. A sink exception aborts the check before `Ast.CheckedTaskCaptures.publish` is called; the sink must not be used as a reentrant compiler callback or as authority to issue Task/actor capabilities.

Before matching against `atto-borrow-checker.rs`, a future adapter must encode resolved per-function owner identities, CFG false edges and join states, explicit borrow release, trusted Copy/Send/Share type witnesses and effect provenance. It must never infer Copy merely from Send or make a permission/guest safety claim based on matching logs.

Local build gate (not run at authoring): `mvn -Dtest=BorrowSamePassShadowV0Test,GuardedMatchOwnershipFlowTest test`. Positive compiled .atto guests and negative semantic checks remain required. CI zero-step jobs count as NOT_EXECUTED.
