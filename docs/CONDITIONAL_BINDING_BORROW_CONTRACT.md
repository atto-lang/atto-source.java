# Conditional optional binding and borrow-checker admission

Status: **design contract / not yet implemented**. Do not accept the surface
syntax in Java, LLVM, Wasm, or interpreters until the admission tests below pass.
This document does not claim backend support.

## Separate Boolean predicates from bindings

Every ordinary `if`, `elif`, `for`, ternary, and match guard requires a
`bool` predicate. No integer, object, nullable sentinel, `Option<T>`, or
`Result<T,E>` implicitly converts to `bool`. Assignment `=` is not
comparison (`eq` / compatibility `==`) and cannot occur anywhere inside a
Boolean control-flow predicate.

Proposed guarded binding syntax:

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
- Initially only `Option<T>` is eligible. The carrier is `Some(payload)`
  or `None` (including `Some(false)`, `Some(0)`). `Result<T,E>`, nullable
  foreign values, and bare `Any` remain unsupported without explicit contracts.

A proposed shorthand is `if (option as let value) eq 15 then ... fi`; this is
a *guarded comparison construct*, not an expression that unconditionally
evaluates to `T`. A failed extraction yields false and never executes `eq`.
`if (option as let value) is type int then ... fi` is likewise a guarded
type test, not reference identity `is`. Reject these forms until their
lowering proves that exact behavior. The eager ordinary call
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
that optional binding is ownership safe on all targets.
