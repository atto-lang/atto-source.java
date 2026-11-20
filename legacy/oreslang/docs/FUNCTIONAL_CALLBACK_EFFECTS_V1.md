# Functional callback effects v1 — compiler foundation

**Status:** Java effect graph and a narrow **real source-checked callback analysis**
slice. Java/Graal guest execution and five-backend native parity remain **NOT EXECUTED**
by the repository's available CI jobs; authored JUnit tests are not passing evidence.

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

## Checked .atto source integration (v1 follow-up)

`OresCompiler.analyzeFunctionalCallbacksV1(source)` runs the real production
parser, type checker, **and ownership checker**, then inspects a deliberately
small subset of inline `Option` / `Result` higher-order calls. Only direct
Option/Result parameter receivers, with no detected same-name top-level
rebinding, qualify for the v1 report. Unsupported control-flow/expressions
mark `Report.complete=false`. That flag means scanner coverage for the
narrow surface, **not a proof of whole-program purity**.

- Inline callbacks using literal, parameter, scalar binary/unary or
  conditional expressions are analyzed conservatively. Checked operators
  may contribute `THROW`; combinators contribute `ALLOC`.
- Unknown or named callees, captures, dynamic dispatch, imported values,
  or unsupported expressions contribute `UNKNOWN_CALL`; they are *not*
  automatically marked pure. This is intentional until overload-resolved
  symbol/effect metadata flows from TypeChecker into HIR/MIR.
- The host-side effect graph uses **one specialization per call site**.
  `eligibleForPure` means the inferred row fits observational purity's
  allowance, not that a source `pure` annotation has been accepted or
  that the compiler has emitted a certified AOT artifact.
- The analysis cannot authorize a callback or bypass a failed type/ownership
  check. `analyzeChecked` is package-private; consumers enter through the
  checked `OresCompiler` API. No runtime capabilities are granted.

New exact-source fixtures under
`tests/fixtures/functional-callback-effects-v1/` include a pure inline
callback (expected guest stdout `42`), a named-helper callback (typechecked,
but `UNKNOWN_CALL` until transitive resolution), and a wrong-output-type
semantic rejection. `FunctionalCallbackEffectAnalysisV1Test` includes
an actual Graal `Context.eval` JUnit test; it must execute before any
guest-support claim.

### Current limitations / next compiler work

1. Move from conservative inline expression inspection to overload-specific
   callable identities, imported method effects and checked closure captures.
2. Feed resolved effect rows into formal source `pure` admission and
   typed HIR/MIR; the source checker does **not** currently reject an impure
   callback merely because its report is not `eligibleForPure`.
3. Walk all control-flow variants and actor/method bodies, preserving ownership
   and critical-state boundaries. Current `complete=false` is a hard stop
   for any claim that the scanner covered the whole program.
4. Run these exact `.atto` bytes on Java, LLVM, .NET Native AOT, Wasm and
   Forge; pin each backend source/compiler head and required test steps.

### Composition and coverage hardening

Two additional `.atto` cases pin the existing eager `|>` and exhaustive
value-match contract: `pipeline-match.atto` expects guest output
`42` followed by `0`, and `reject-incomplete-match.atto` must be rejected
by the semantic checker (not the lexer). The JUnit suite now has nine new
cases in `FunctionalCallbackEffectAnalysisV1Test`, alongside the six
existing effect-graph regression cases.

The v1 scanner is deliberately bounded at depth 64, 8,192 inspected
nodes per function and 1,024 reported call sites. Incomplete/over-budget
analysis returns `complete=false` and must never be interpreted as a full
purity proof. Unknown or non-parameter combinator receivers also mark
coverage incomplete. No actor, method body, user-defined collection type,
imported graph or arbitrary closure has yet been certified.
