# Boolean actor-owned state admission (static-only)

Add `bool` true/false initializers alongside checked signed i64 in
`define state on actor Foo as { ... }`. This is essential for true Atto
actor-owned private `closing`, `sealed`, and `draining` flags without
inventing numeric flags in application source. Booleans and integers remain
nominally distinct: no `0`/`1` coercion to bool; bool cannot initialize int.
Compound expressions, calls, free variables, borrowed types, and visibility
on the state schema remain rejected. The binder checks both declared type and
AST literal class, preventing forged mismatched AST from minting state.

Tests use AttoRMQ's namespace actor shape, including the private runtime
`external mtd` hooks. **TypeChecker still deliberately rejects executable
attached state/ingress** until compiler-owned generation-fenced ActorRefs,
critical state loans, actor isolation, and mailbox dispatch are lowered.
No real AttoRMQ broker, old inline actor substitute, or state runtime is
claimed in this PR. Independent review and exact-head Java JUnit evidence
are required before merging.
