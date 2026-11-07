# Java lexical BindingId issuance and scoped ownership hardening

Status: DRAFT; real Java JUnit and GraalVM/AttoLang guest tests NOT_EXECUTED.

Scope.define now maintains a root-scope compiler-issued BindingId ledger. A new VarState mints an ID, and a previously minted state may present that ID only when it is the exact same compiler-managed object in that source checker. Foreign or duplicated BindingIds reject. Java OriginId is still unknown (zero): this does not infer separate heap allocations from names. The bounded resolveOwnershipPlace alias walk must end at a currently visible compiler-minted VarState, reject stale/escaped alias roots, and reject forged allocation origins.

tests/OwnershipScopedBindingIdentityTest.java adds 15 source-driven production Parser.parse + OwnershipChecker.check dynamic JUnit cases: five lexical shadowing positives, five if/else scoped local positives, five one-arm rt take negative CFG-join cases. They use the real parser's block {...} and if ... fi constructs. The exact-head ownership workflow selects these tests by filename path and Maven test class.

These tests are authored but not yet executed. This is not full NLL, partial-move lowering, actor Send isolation, or compiler-backed backend parity. Source-owned runner admission, nonzero executed steps, accurate diagnostics and independent review are required before merging.
