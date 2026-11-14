# Functional callback effects v1 — compiler foundation

**Status:** a Java compiler-library building block. Source-language parsing, call-site
integration, guest execution and cross-backend parity are **NOT EXECUTED** by this patch.

## Why

A higher-order operation such as `map`, `filter`, `fold` or `and_then` should not
assume its callback is pure. Equally, it should not become permanently impure when
invoked with a statically proven pure callback. The compiler must infer the effect
row of the **actual resolved callback at each invocation**. AttoLang's `pure`
contract is observational: allocation, throw/panic/raise and cancellation have
separate semantics; it does not promise totality, no allocation or bounded work.

## Compiler-library API (Java host infrastructure, not guest APIs)

- `EffectContracts.Call.direct(resolvedSymbol)`: direct, overload-resolved call.
- `EffectContracts.Call.callback(parameterName)`: invocation of a formal
  higher-order callback. This is distinct from a function with the same name.
- `Graph.infer()`: an unspecialized callback slot contributes `UNKNOWN_CALL`,
  even when another function has the same lexical name.
- `Graph.inferInvocation(callableSymbol, callbackBindings)`: binds **only the
  indicated callable's** callback slots to statically resolved targets and
  returns the induced effects.
- `Graph.validateInvocation(...)`: checks the indicated callable's declared
  upper bound using that specialized effect row. Missing, unresolved, untrusted
  or indirect targets must not silently gain `pure` authorization.
- Recursive direct/callback dependencies use the existing finite fixed point;
  a trapped callee erases only ordinary `THROW` at the invocation edge, not
  panic, raise, cancellation, I/O or unknown calls.

The binding map is a compiler-internal artifact and **must not** come from user
source strings, runtime reflection, a source macro's assertion or a forged
imported ABI. The eventual frontend must first resolve overloads, generic
instances, method references, captures and imported effect metadata. The
specialization API is not a substitute for type checking and ownership checking.

## Required frontend/lowering integration (not yet implemented)

1. Parse source-level callback values and partially applied methods using the
   existing lambda and `mtd` receiver grammar; preserve the chosen function's
   type, captures, capabilities and effect bound.
2. Carry typed callable effect rows through HIR/MIR, and bind callback slots
   **at each higher-order call site**. Do not merge the same `map` invocation
   across different callback types or permission contexts.
3. Reject unknown/impure callbacks at pure call sites; ensure callbacks in
   match guards meet the separate no-effects-under-guard contract.
4. Preserve actor-state and borrow invariants; no escaping critical-state
   references, no suspending callback under a critical lock, no closure or
   capability transport through channels just because it was bound.
5. Keep local eager expression semantics. Lazy `Iterator` transforms may be
   fused only after callback effect, evaluation order, overflow and drop-order
   equivalence is proven. No hidden host allocation or interpreter fallback
   in sealed AOT.
6. Add positive and negative `.atto` cases, real compiler/type-checker
   assertions, actual guest execution, and native artifact evidence before
   claiming Java/LLVM/.NET/Wasm/Forge parity. Record immutable source/compiler
   blob IDs and nonempty job-step receipts.

## Current testing boundary

`EffectContractsReconciliationTest` exercises:
- an unbound callback failing closed despite a same-named function;
- pure and I/O callbacks yielding distinct specialized effect rows;
- recursive/transitive I/O and diagnostic ancestry;
- missing, foreign and undeclared callback bindings;
- `trap` suppressing only ordinary throw; and
- no accidental specialization of unrelated higher-order definitions.

Run when a compatible JDK/Maven builder is available:

```sh
mvn -B -ntp -Dtest=EffectContractsReconciliationTest test
mvn -B -ntp verify
```

**These commands are verification requirements, not a statement that they
have passed on this branch.** Java is used solely for compiler graph analysis;
any user-visible functional library belongs in native `.atto` source.
