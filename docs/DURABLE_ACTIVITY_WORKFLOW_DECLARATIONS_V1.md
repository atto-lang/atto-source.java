# Durable activity and workflow declarations v1

Status: class-shaped declarations, first member-semantic checking phase, typed command AST, and canonical declaration manifest. Dispatch/replay/history execution remains fail-closed.

## Canonical declarations

```atto
define activity PaymentProvider as
    charge(PaymentId payment_id, Money amount) -> PaymentResult {
        // External provider operation
    }
    refund(PaymentId payment_id) -> RefundResult {
        // Separate external provider operation
    }
end

define workflow OrderFulfillment as
    normalize(OrderId order_id) -> OrderId {
        return order_id;
    }
    run(OrderId order_id) -> Shipment {
        // Deterministic orchestration of durable commands
    }
end
```

`activity` and `workflow` are class-shaped declaration kinds, not callable modifiers. They have distinct `Ast.ActivityDecl` and `Ast.WorkflowDecl` identities. The parser retains declaration annotations, but they fail closed until an annotation schema/version contract exists.

## Member semantics admitted in this phase

- Activities are stateless operation containers in v1: no class fields, constructor, inheritance, interfaces, actor behavior or generic container. Each operation is a concrete non-static, non-generic member with a unique `name + arity` identity.
- Workflows require exactly one `run` entry point. This first source slice admits only concrete, non-generic, non-suspending, non-async members and rejects abstract methods, checked effects, fields, constructors, inheritance and generic containers.
- Every activity/workflow member body is checked by the existing class member type-checker and ownership checker using the exact member AST body; the durable declaration wrapper is not rewritten into or returned as an ordinary class.
- Workflow member expressions are additionally fail-closed for host/runtime calls, raw member dispatch, I/O/time/random/spawn/channel/task/await, allocation and unknown expression nodes. Calls are restricted to declared deterministic helpers in the same workflow.
- Activity method bodies are type/ownership checked as the external-effect implementation boundary. Activity dispatch, effect taxonomy, retry classification, timeout/heartbeat/cancellation policy, idempotency receipts and result persistence are not implemented yet.
- The restrictions are deliberately conservative. Unsupported durable declarations fail rather than falling through to ordinary class execution.

## Typed workflow command AST

`Ast.WorkflowDecl` retains an ordered immutable command list. The initial command IR defines:

- `ActivityStepCommand`: stable command ID, exact activity/operation target and typed argument AST.
- `TimerCommand` and `WaitForEventCommand`: persistent timer/event wait intents.
- `SignalCommand`, `QueryCommand`, and `UpdateCommand`: message endpoints. Queries are read-only and do not receive history command IDs.
- `ChildWorkflowCommand`, `ContinueAsNewCommand`, and `CompensationCommand`.

History-appending commands require a canonical ASCII command ID and duplicate IDs are rejected within a workflow. The semantic analyzer resolves fully-qualified/local activity and child-workflow targets and rejects ambiguous unqualified names; it binds activity operations and message endpoints by exact member name/arity. Command payload expressions are re-homed into checker-only synthetic methods with the exact `run` parameters and target operation parameter types, then pass the ordinary type and ownership checker. These synthetic methods are never retained in the program. `TimerCommand.delay` is currently an integer millisecond value. Query signatures must exactly match their read-only endpoint method. The manifest canonicalizes payload AST and keeps query endpoint descriptors separate from the history command list. Source grammar for emitting these nodes remains future work; current AST producers can construct them directly.

## Canonical manifest

`DurableExecutionManifest.analyze(program)` performs durable member analysis and returns immutable canonical UTF-8 JSON bytes plus a SHA-256 digest. The v1 format sorts activity/workflow declarations and operation signatures, preserves workflow command order, binds command IDs and canonical argument payloads, and marks each declaration version. Query entries intentionally omit a history ID.

The current state schema is explicitly empty. Adding persistent state requires a versioned serializable schema and ownership proof; the manifest must not infer state from ordinary class fields. Unknown or non-canonical command expression nodes are rejected.

## Execution boundary remains closed

The analyzer and manifest are compiler metadata, not a runtime:

- `TreeShaker` rejects durable declarations until command/state lowering can preserve replay-visible order.
- `CapabilityChecker` rejects until activity external capabilities and workflow replay-safe effects have a closed admission model.
- `IncrementalCompiler` rejects the declarations before ABI hashing until the versioned durable manifest is integrated into public ABI and dependency invalidation.
- No activity dispatcher, durable history store, deterministic interpreter, worker activation/fencing, persistent timer service, cancellation protocol, idempotency receipt store or crash recovery exists in this phase.
- A coroutine suspension is not a durable checkpoint. No physical exactly-once effect guarantee is implied.

## Next implementation work

1. Add source grammar and type/binding rules for workflow command nodes; type-check each command payload against the exact workflow member lexical environment.
2. Add a versioned serializable workflow state schema and reject live borrows/loans across durable suspension or process migration.
3. Define activity effect rows, checked error/result ABI, retry classes, timeouts, heartbeat and cancellation semantics.
4. Integrate the manifest digest into incremental ABI hashing, import invalidation, compatibility/version markers and code-upgrade checks.
5. Implement Java history/replay runtime, activity dispatch receipts, persistent timers, worker restart recovery and timer/event races.
6. Execute Java/Graal guest tests, then run identical fixtures on LLVM, .NET NativeAOT, Wasm and Cranelift.

Parser and semantic metadata support do not imply runtime support. No ordinary-class fallback is permitted.
