# Experimental executable `define state container` and `define virtual actor` (i64 slice)

**Status:** JVM VM-only and opt-in restricted **production AST → typed i64 MIR** admission (`ProtectedScalarMirV1`). It uses the normal `Parser.parse` declarations, but **does not run the general `TypeChecker`/`OwnershipChecker` or normal backend code generation**, and is not available on LLVM, Wasm, Forge, or .NET. No general guest language, actor runtime, or AOT parity is claimed.

## Supported syntax

- One `define state container Name as ... end` **or** one `define virtual actor Name as ... end` per source unit; `define pub` and `pub define` are parsed.
- Exactly one owned `state { let int field: signed_i64; }` scalar field.
- Exactly one single-`int`-parameter method with a checked addition guarded by `handle_state(critical-write |s| -> ...)`. A state-container method returns `int`; a virtual actor method returns `void`.
- No other statements, imports, nesting, declarations, unsafe references, async work, or trait inheritance are admitted. All token sequences must be consumed; unsupported guest syntax fails closed at parsing or with `PROTECTED_MIR_UNSUPPORTED`.
- The method field, parameter and critical guard variables must match exactly. Adds use `Math.addExact`, so overflow raises `ArithmeticException` without changing the scalar state.
- Virtual actors are local and ephemeral, keyed by normalized logical string identity; their serial mailbox admission is bounded. Each message is a signed i64. A failure notifies an injected callback and explicitly settles accepted tickets; this is not yet the real actor supervisor priority CONTROL dispatcher.

## Running the examples

Compile with the project build or with these runtime/compiler sources on a supported JDK, then invoke:

```bash
java -cp <classes> dev.oreslang.compiler.ScalarDeclarationI64Cli \
  examples/experimental/state-container-i64.atto 5 7
# 12

java -cp <classes> dev.oreslang.compiler.ScalarDeclarationI64Cli \
  examples/experimental/virtual-actor-i64.atto 5 7
# processed=2
```

The standalone CLI prints the final scalar for the state container and admitted/completed message count for the virtual actor. It does not print or persist virtual actor internal state. Source budget is 8 KiB; the opt-in MIR requires one method containing at most 96 tokens, and the CLI accepts 1–64 i64 arguments.

## Execution boundaries

- `ProtectedScalarMirV1` reads the **production `Parser` AST** and type-checks one i64 state field and a single fully consumed guarded addition body, producing `ACQUIRE_WRITE`, `LOAD_STATE_I64`, `CHECKED_ADD_I64`, `STORE_STATE_I64`, `RELEASE_WRITE`, and return instructions. Its JVM implementation uses the checked atomic `ScalarStateContainer.tryAddChecked`, which releases its write lease on all exits. The old `ScalarDeclarationI64Compiler` remains a compatibility-only experimental component.
- `ScalarStateContainer` stores only one native Java `long`, so there are no nested object aliases. The `StateContainerAccessGate` enforces compatible read leases and exclusive write leases; shutdown revokes authority before any new mutation.
- `LocalVirtualActorI64` executes one mailbox turn at a time when the embedding runtime explicitly calls `run_one`. It requires two-phase activation publication, logical identity fencing and explicit completion disposition. No separate carrier pool is spawned.
- **Quiescent handler disposal:** after a successful final admission disposition and an incarnation fence, the VM invokes each resource-owning `Turn.close()` exactly once, outside directory/slot monitors, during explicit deactivation, acknowledged panic recovery, and successful runtime shutdown. A `retiring` fence rejects activation while disposal is in progress. Disposal failures quarantine the slot and block silent shutdown. Existing stateless `Turn` lambdas use a no-op default hook; the opt-in protected scalar compiler provides an explicit hook that closes its scalar state and verifies access-gate quiescence.
- **Not a native allocator receipt:** a scalar Java `long` is still a managed Java field, and Java object/collection allocations remain GC-managed. The verified property requested here is *logical guest resource lifetime and admission fencing*, not native-memory deallocation, zero-GC, GraalVM Native Image, or independent backend conformance. Bounded-native-arena allocation and final physical reclamation remain separate future evidence gates.

- Handler failures emit a notice through an injected host callback; if notice publication fails, the accepted work remains unsettled and shutdown refuses to forget it. These are provisional local control semantics, not validated supervisor CONTROL priority.
- No remote placement, durable state, guest-written arbitrary methods, generalized borrow checking or native AOT codegen.

## Tests and promotion gate

- `ProtectedDeclarationAstAdmissionTest`, `ProtectedScalarMirV1Test`, `ScalarDeclarationI64CompilerTest`, and `ExecutableVirtualActorStateContainerTest` cover normal AST structure, restricted MIR, checked scalar state, key reactivation, failures and fail-closed negative cases.
- `LocalVirtualActorQuiescentDisposalTest` adds lifecycle cases for settle-before-dispose, single disposal per incarnation, acknowledged-panic retirement, failed-CONTROL no-disposal, disposal-failure quarantine, and reactivation fencing during an in-progress disposer.
- Exact-blob private-runner receipts exercise the production AST, typed scalar MIR, guarded VM classes, and both `.atto` CLI examples under JDK 21; these remain narrow proofs, not a complete project Maven/JUnit or AOT run.
- Before general language promotion: lower arbitrary guarded method AST into typed HIR/MIR, prove nested heap ownership/borrow lifetimes, integrate production actor scheduling and priority CONTROL, add source-module callable binding and execute the same conformance cases on Java, LLVM, Wasm, Forge, and .NET. The **general TypeChecker rejection remains active** until those proofs and backends exist.
