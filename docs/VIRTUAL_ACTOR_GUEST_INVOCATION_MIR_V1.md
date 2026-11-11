# AttoLang guest virtual-actor resolve/invoke MIR v1

**Status: compiler-only AST admission and typed invocation candidate. NOT_EXECUTED.**

Dependency stack: this branch → #2850 nominal call ABI → #2843 checked
protected operations MIR → #2834 protected declaration MIR → #2826 Cart
compiled transition tests → #2821 directory → #2817 terminal CONTROL →
#2759 generation fencing. Parent branches remain separate drafts.

## Real guest-source parsing; bounded source language

`contracts/virtual-actor-guest-invoke-v1/positive/cart_resolve_and_invoke.atto`
contains a genuine guest `define virtual actor Cart`, plus:

```atto
pub async fnc main(): int {
  const VirtualActorRef<Cart> cart = VirtualActorRef.resolve<Cart>("cart-123");
  await cart.add(2);
  await cart.add(5);
  return 0;
}
```

The production `Parser.parse` produces the AST for the two declarations,
typed reference binding, generic resolve call, `AwaitExpr`, member calls
and return. `VirtualActorGuestInvocationMirV1.lower` proves a narrowly
bounded **closed** subset against exact AST types and the already-checked
protected-state/typed-method MIR. It produces immutable compiler-owned:

- `RESOLVE_LOGICAL_IDENTITY_ONLY`, not physical actor construction.
- `INVOKE_GENERATION_FENCED_I64` keyed by nominal method selector.
- `AWAIT_ASYNC_RECEIPT` immediately after each invocation.
- `RETURN_I64` for terminal `return 0`.

The key is currently a bounded ASCII literal, and method arguments must be
signed i64 literal AST values. This is **not** general method invocation:
dynamic identity expressions, state-reference arguments, implicit conversions,
extra statements, aliases, unawaited calls, runtime reflection, imports,
control-flow branches and more than 32 calls are rejected.

`TypeChecker` performs this preflight for a virtual actor + `main` source
unit; after validation it **still fails closed** with
`VIRTUAL_ACTOR_MIR_UNSUPPORTED`. There is no production runtime dispatch,
new actor creation, state access, native guest execution, code-image permit,
persistent actor state, generation-fence authority or receipt completion
granted by this pass. Source-derived operation metadata does not prove that
any backend actually implements it.

## Security and test acceptance

Ten source-authored JUnit tests check: parsed guest AST and typed linear
ops; immutable non-forgeable compiler metadata; wrong nominal types and
resolve factories; unknown/privileged methods, argument and arity mismatch;
unawaited calls and reference escape; invalid logical keys; control-flow
and extra declaration rejection; namespace/selector separation; three
standalone negative .atto fixtures checked against the production rejection;
and continued production execution denial.

A focused exact-head workflow includes this new suite plus both preceding
MIR suites, the existing Cart compiled transition tests, and the
generation-fencing, terminal CONTROL and directory suites. Passing requires
actual assigned runner, steps, blob identities, Surefire XML and nonzero,
non-skipped tests. A GitHub Actions run showing `steps=[]` is NOT_EXECUTED.

## Next backend implementation, not bypass

To enable this guest source as a real `main`, the compiler/runtime still
must emit the scalar protected MIR as executable AOT-compatible operations
with exceptional loan cleanup, compile the logical resolve and typed mail
into the single generation-fenced actor directory and mailbox, and publish
completion only after physical turn settlement. Overflow must not commit a
state write: unwind the exclusive loan, fence the generation, send sanitized
terminal CONTROL, wait for asynchronous supervisor acknowledgement, and
only then permit generation N+1. Actor heap/stack, cancellation and durable
restore-before-publication require separate proofs. Do not infer runtime
correctness from a validated AST or deterministic method ABI hashes.

Keep the PR draft until execution on an authorized source runner proves
actual positive and negative behavior. This patch does not authorize
private-source transfer to another GitHub organization; the repository's
independent security/CODEOWNER approvals remain required.
