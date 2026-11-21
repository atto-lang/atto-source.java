# AttoLang virtual actor logical call ABI v1 — compiler-only draft

**Status: source-authored, NOT_EXECUTED.** Stacked on checked operations MIR #2843
→ protected MIR #2834 → Cart guest transitions #2826 → directory #2821 →
terminal CONTROL #2817 → generation fence #2759. No parent branch modified.

## Compiler stage

`VirtualActorLogicalCallMirV1.lower` rechecks the complete virtual-actor
AST through `VirtualActorProtectedOpsMirV1.lower`, which in turn uses the exact
lexeme-and-token-kind gate of `VirtualActorProtectedMirV1.analyze`. The output
is immutable compiler metadata (private constructors), not a dispatch handle.

For the supported, **closed single-module** Cart-style declaration, this stage:

- Derives a nominal actor identifier from versioned, length-framed SHA-256
  of namespace, lexical module and declared actor name.
- Derives an independent, fixed-width SHA-256 method selector from a separate
  versioned domain, actor nominal ID, method name and exact `int → void`
  signature. Method source ordering does not affect selector identity.
- Publishes a compile-time-only `VirtualActorRef[namespace.Cart]` description.
  Exact method name, arity and argument types are checked. Unsupported
  parameter types, aliases, methods and supervisor operations are rejected.
- Produces `CallPlan` *requirements*, not runnable instructions:
  logical-address resolution, nominal code-image verification, generation-fenced
  mailbox admission, checked i64 argument encoding and an asynchronous receipt.
- Hooks this compiler stage into `TypeChecker` before the unchanged production
  `VIRTUAL_ACTOR_MIR_UNSUPPORTED` barrier.

Method selectors are **not authorization tokens**, not a mechanism to access
the actor heap or stack, not storage epochs and not proof of runtime code-image
compatibility. A backend must verify the published image's nominal and method
ABI and enforce generation fencing independently; static hashes alone do not
grant actor authority. No guest `resolve` or `invoke` expression has been
made executable. No host managed Cart logic is reclassified as native lowering.

## Security guarantees and negative tests

`VirtualActorLogicalCallMirV1Test` authors seven JUnit tests:

1. The metadata-only resolve/invoke obligations and immutable collections.
2. Identical selector identities across declaration reordering.
3. Nominal namespace separation and deterministic full SHA-256 identifiers.
4. Static rejection of wrong arities/types, unknown and reserved methods.
5. Rejection of unsafe state aliasing, suspension and extra operations.
6. Rejection of malformed namespaces, extra declarations or incomplete units.
7. Private-only compiler constructors and continued production rejection.

The focused exact-head workflow now includes this suite, the v1 protected
MIR/ops suites and the existing Cart guest/generation/terminal CONTROL/directory
suites. It checks the exact PR head/root tree and Git blobs, requires actual
Maven/Surefire runs and forbids failures, skipped suites and zero-tests.

## Not yet implemented

Actual typed guest AST expressions for `VirtualActorRef[T]`, code-image
resolution, compiler-generated runtime dispatch, lazy activation and physical
actor-owned heap/stack, exception-safe exclusive state loans, overflow-to-CONTROL
physical termination, asynchronous acknowledgement, restore-before-publish,
provider-issued durable persistence epochs and cross-backend execution.

The existing operations MIR's `ADD_I64_TRAP_ON_OVERFLOW` instruction is only a
candidate: backend lowering must prove cleanup of the exclusive write loan on
**every** exceptional exit before enabling production execution.

**CI blocker:** private source-org GitHub-hosted jobs have failed before
runner assignment with `steps=[]` (issue #963). The tests authored here are
NOT_EXECUTED until an actual runner executes them. No private-source
cross-organization mirror is authorized solely because another organization
has available minutes. Independent security approval and the repository's
export policy are separate requirements.

**Merge gate:** hold as draft until exact-head executed tests, negative mutation
proof, dependency stack gates and independent review from ORESoftware.
