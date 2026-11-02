# Durable activity and workflow declarations v1

Status: distinct class-shaped AST and parser syntax; execution remains fail-closed.

## Canonical declarations

```atto
define activity PaymentProvider as
    charge(PaymentId payment_id, Money amount) -> PaymentResult {
        // External provider operation
    }
end

define workflow OrderFulfillment as
    run(OrderId order_id) -> Shipment {
        // Deterministic orchestration of durable steps
    }

    // Future workflow message endpoints:
    // signal approve(...), query status(...), update cancel(...)
end
```

`activity` and `workflow` are class-like declaration kinds, not callable modifiers. Declaration-level annotations are retained verbatim in the dedicated AST wrapper; they are not expanded or interpreted until durable declaration metadata has a checked contract.
Each gets a distinct AST node containing a class-shaped member body. They are not
ordinary classes and cannot be instantiated/executed through ordinary class paths.

## Intended semantics

- An activity is a typed external-effect boundary. Its member operations perform
  external work and require stable operation identity, retry/timeout/cancellation
  policy, idempotency, durable result recording, and optional heartbeat state.
- A workflow is a typed durable orchestration entity. Its methods operate over
  deterministic replay, durable history, checkpointed state, timers, event waits,
  signals, queries, updates, child workflows and explicit compensation.
- Workflow code may not use unrecorded system time, randomness, IO, or actor
  mutations. Such operations are admitted only through typed activity/command
  boundaries.
- `await` by itself is not durability. Coroutine suspension and persisted workflow
  checkpoints remain separate concepts.
- Activity and workflow identities, versions, member signatures, state schema,
  command order, and compatibility data belong in a canonical compiler manifest.
- No global exactly-once external-effect guarantee is implied. Use stable keys,
  receipts, deduplicating inboxes and transactional outboxes when storage supports
  the necessary atomicity.

## Current implementation boundary

The Java parser produces dedicated `Ast.ActivityDecl` and `Ast.WorkflowDecl` nodes
wrapping a class-shaped member body. The general TypeChecker rejects them with
explicit `ACTIVITY_EXECUTION_UNSUPPORTED` / `WORKFLOW_EXECUTION_UNSUPPORTED`
diagnostics until activity effect checking, workflow replay, persistence, timer
recovery, dispatch, idempotency and versioned-manifest lowering exist.

## Next compiler/runtime work

1. Define activity member effect sets and an activity dispatch ABI.
2. Define workflow command AST: step, timer, event wait, signal, query, update,
   child workflow, continue-as-new, compensation.
3. Type-check durable state as serializable, versioned data; prohibit live loans
   across durable suspension and process migration.
4. Emit canonical manifests with stable IDs and replay-compatible command order.
5. Implement Java history store, deterministic interpreter/replay, activity
   dispatcher, durable timers, idempotency and worker recovery.
6. Add crash/fault-injection guest tests, then carry identical fixtures through
   LLVM, .NET NativeAOT, Wasm and Cranelift.

Parser support is not runtime support; no ordinary-class fallback is permitted.
