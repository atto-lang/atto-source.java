# Experimental executable `define state container` and `define virtual actor` (i64 slice)

**Status:** JVM VM-only + restricted source compiler. This is **not** the standard `Parser.parse`/typed HIR/MIR path, not source-module linking, and not supported by LLVM, Wasm, Forge, or .NET. It does not imply general guest language or AOT parity.

## Supported syntax

- One `define state container Name as ... end` **or** one `define virtual actor Name as ... end` per source unit; optional `pub` immediately after `define`.
- Exactly one owned `state { let int field: signed_i64; }` scalar field.
- Exactly one single-`int`-parameter method with a checked addition guarded by `handle_state(critical-write |s| -> ...)`. A state-container method returns `int`; a virtual actor method returns `void`.
- No other statements, imports, nesting, declarations, unsafe references, async work, or trait inheritance are admitted. All token sequences must be consumed; unsupported guest syntax fails closed with `SCALAR_DECLARATION_UNSUPPORTED`.
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

The standalone CLI prints the final scalar for the state container and admitted/completed message count for the virtual actor. It does not print or persist virtual actor internal state. Source budget is 8 KiB / 256 tokens and CLI accepts 1–64 i64 arguments.

## Execution boundaries

- `ScalarDeclarationI64Compiler` uses the production Java `Lexer` to parse a **separate experimental grammar**; it never skips unknown guest statements.
- `ScalarStateContainer` stores only one native Java `long`, so there are no nested object aliases. The `StateContainerAccessGate` enforces compatible read leases and exclusive write leases; shutdown revokes authority before any new mutation.
- `LocalVirtualActorI64` executes one mailbox turn at a time when the embedding runtime explicitly calls `run_one`. It requires two-phase activation publication, logical identity fencing and explicit completion disposition. No separate carrier pool is spawned.
- Handler failures emit a notice through an injected host callback; if notice publication fails, the accepted work remains unsettled and shutdown refuses to forget it. These are provisional local control semantics, not validated supervisor CONTROL priority.
- No remote placement, durable state, guest-written arbitrary methods, generalized borrow checking or native AOT codegen.

## Tests and promotion gate

- `ScalarDeclarationI64CompilerTest` and `ExecutableVirtualActorStateContainerTest` cover source execution, checked scalar state, key reactivation, failures and fail-closed negative cases.
- [Private exact-blob Actions run 41586353997](https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/41586353997) passed on JDK 21 and JDK 25: 12 source/fixture SHA matches, 322 harness checks per JDK, 3,200 concurrent gate grants, 8 concurrent actor admissions, and 120 close-race trials. The isolated carrier uses minimal AST/parser stubs only for unused general template-interpolation paths; this is **not** a full Java Maven/JUnit or GraalVM Native Image build.
- Before general language promotion: add first-class AST/HIR/MIR declaration nodes, type checker/borrow-checker guarded heap access, actor scheduling and priority CONTROL integration, a source-module callable binder, and executable conformance evidence on Java, LLVM, Wasm, Forge and .NET. The existing compiler `Parser.parse` admission rejection must remain in place until those gates pass.
