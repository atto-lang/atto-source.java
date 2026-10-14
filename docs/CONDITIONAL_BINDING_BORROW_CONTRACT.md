# Conditional optional binding and borrow-checker admission

Status: **Java draft implementation, unverified** for Copy payloads only in
`if` and `elif` conditional guards. The grammar, type checker, interpreter
and ownership checker have new paths for `Option<int>`, `Option<bool>`,
`Option<float>` and `Option<String>`. This is not a tested or released
contract: CI validation and behavior/ownership tests are still required.

Non-Copy payloads, borrow-bearing payloads, arbitrary unions, `Result<T,E>`,
and cross-backend parity remain **explicitly unsupported**. LLVM and Wasm
must continue to fail closed until independently implemented and verified.

## Separate Boolean predicates from bindings

Every ordinary `if`, `elif`, `for`, ternary, and match guard requires a
`bool` predicate. No integer, object, nullable sentinel, `Option<T>`, or
`Result<T,E>` implicitly converts to `bool`. Assignment `=` is not
comparison (`eq` / compatibility `==`) and cannot occur anywhere inside a
Boolean control-flow predicate.

Java draft guarded binding syntax (Copy payloads only):

```ores
if get_optional() as const value then
  use(value);
fi

if get_optional() as let value && value eq 15 then
  value = transform(value);
fi
```

This is **not** the ordinary checked cast `expr as T`. The grammar must
distinguish `as const IDENT` / `as let IDENT` from `as Type` with a separate
AST node, a distinct presence predicate and a success-edge-only binding.

- `const`: immutable runtime local binding; the producer need not be known at
  compile time. Reassignment is rejected.
- `let`: reassignable runtime local binding; this does not grant a mutable
  reference to immutable/borrowed storage.
- `final` is not a local binding keyword.
- Initially only `Option<Copy T>` is eligible in Java. The carrier is `Some(payload)`
  or `None` (including `Some(false)`, `Some(0)`). `Result<T,E>`, nullable
  foreign values, and bare `Any` remain unsupported without explicit contracts.

The Java draft shorthand `if (option as let value) eq 15 then ... fi` is
a *guarded comparison construct*, not an expression that unconditionally
evaluates to `T`. A failed extraction yields false and never executes `eq`.
`if (option as let value) is type int then ... fi` is likewise a guarded
type test, not reference identity `is`. These Copy-only lowering paths are
committed but not yet validated by CI. The eager ordinary call
`Eq(option as let value, 15)` must **not** acquire special binding semantics.

## Ownership admission

1. Evaluate the carrier expression exactly once.
2. Branch on the carrier tag without calling `unwrap()` or other potentially
   panicking extraction APIs.
3. On `None`, initialize no binding and do not run any downstream guard.
   Destroy/drop the carrier exactly once under normal and exceptional paths.
4. On `Some`, extract its payload without copying move-only objects. A Copy
   payload may copy; a move-only payload transfers exactly once. The carrier's
   moved field must never be dropped a second time.
5. For extraction from an existing named owner, require a proven consuming move
   (source becomes unusable), or an explicit, statically tracked borrow of the
   payload. Do not silently move out of borrowed `Option<T>`.
6. An extracted owned `let` local can be reassigned only under normal
   reinitialization and capability rules. `value = transform(value)` is legal
   only if the checker proves RHS evaluation, move, failure cleanup and
   reinitialization safe; if `transform` raises before replacement, no stale
   value may be read or double-dropped.
7. A borrowed binding is not magically owned or mutable. Track the original
   owner and exclusive/shared loan provenance across refinements.
8. A comparison must use a temporary read-only loan (unless payload is Copy)
   and end that loan before mutation in the successful branch. Equality must
   not silently consume `value`.
9. Scope the binding to the successful guard edge and successful body only.
   No visibility in `else`, following `elif`, after `fi`, or across an
   unsuccessful `&&` chain. A right-hand `&&` operand sees successful
   bindings from the left. `||`, negation and arbitrary user calls do not
   promote a binding into definite initialization.
10. Honor actor isolation, closure capture, `await`, `yield`, `rt cooperate`,
    and destructor/exception cleanup restrictions; a lexical loan cannot
    cross suspension or escape through a closure, message or return.
11. Loop back-edges must merge move/borrow/initialized states soundly.
    Backend lowering must preserve these CFG and ownership edges exactly.

An existing type-test/pattern refinement is an **alias**, not a new owner.
For `left && right`, the borrow checker must resolve right-hand refinements
in the left-success scope, preserving the ownership root across aliases.

The ownership checker must record a validated Copy payload type **during its
single guard-producer analysis**, then reuse that fact when creating the
success-edge local. Calling the producer's ownership analyzer again at alias
declaration time can register a second move of a consumed argument. The
recorded fact is keyed by the binding AST node and may never be inferred from
an unrelated name or an unverified expression.

Runtime conditional binding names must be unique across all successful
`&&` operands, including when two payload values compare equal or share
object identity. Both synchronous and suspendable evaluators reject duplicates.

Return/defer cleanup verification must distinguish the conditional binding
from any shadowed outer variable with the same spelling. Otherwise a returned
branch-local Copy value can be mistaken for an outer mutable binding whose
deferred reassignment should remain independent.

## Required verification before enabling

- Parser + AST: positive syntax for both mutability forms, rejected bare
  `Option<T>` condition, rejected assignment anywhere in a predicate, no
  ambiguity with `as T` / `as? T` or named arguments.
- Type checker: success-edge-only binding, `Option<T>` unwrapping,
  `Some(false)` and `Some(0)`, guards and type narrowing, rejection of
  reassigning `const`, `||`/negation/else scope and invalid implicit
  truthiness.
- Ownership: move-only payload extracted once; shared/exclusive loan alias
  conflicts; use-after-move, double move, borrow of a temporary, `let`
  reinitialization after consuming transform (including throwing transform),
  defer/destructor cleanup exactly once.
- Runtime/IR: carrier evaluated once; false path bypasses guard/body; equal
  comparisons do not consume; sanitizer tests for zero/double destruction and
  exceptional unwinding; actor and suspension fail-closed negatives.
- Differential Java/LLVM/Wasm tests. LLVM currently admits only limited
  nonescaping scalar borrows, so general Option payload bindings must fail
  closed until native provenance and lowering are implemented.

Do not treat parser acceptance alone, or a green JVM-only fixture, as proof
that optional binding is ownership safe on all targets. Keep the PR draft until
its tests and differential checks actually execute and pass.
