# Checked call-binding plan v1 — current-main staging

Status: **data model, parser preservation and fail-closed admission only**. This document does not enable named or omittable calls at runtime.

## Two orders, one checked proof

A call has two different orders which must never be conflated:

1. **Source/evaluation order**: expressions execute exactly once from left to right in their written order.
2. **Formal/ABI order**: each supplied value is placed in the parameter slot selected by the checked plan.

The immutable `CheckedCallPlan` holds `parameterNames`, each formal's `omittable` bit, `sourceOrder` entries, and `abiToSource`. `abiToSource[i] == -1` means formal slot `i` has no source expression. Such a slot is legal only when its formal is declared omittable.

The plan binder rejects empty/duplicate formal names, unknown or duplicate labels, duplicate slot claims, missing required formals, positional arguments following a named argument, invalid projection provenance, mixed tuple/record projection forms for one call item, and inconsistent source-to-ABI maps. Nontrailing omissions are possible only when later supplied formals are addressed by name; positional arguments never skip a slot.

## Source representation and spread provenance

`Ast.NamedArgExpr(name, value)` preserves the source label and its value expression at the original location inside `CallExpr.arguments()`. The parser accepts positional arguments/spreads before named arguments, then named arguments. It rejects duplicate labels, positional arguments after named arguments, and positional spread arguments after named arguments.

`CheckedCallItem` models one raw source call item. A direct argument has one value. A tuple or record spread stores one source expression and one or more ordered projections. Runtime lowering must evaluate a spread source once, then project the saved value. It must not re-evaluate the source per projection.

## Proof identity and invalidation

A checked plan is attached to the exact `CallExpr` or `NewExpr` object identity using a weak identity-keyed table. Rechecking first invalidates the old proof. Failed nested type/ownership validation scopes revoke plans for only the call sites visited in that attempt. `Ast.copyCheckedMetadata` transfers a plan only when callee identity, call origin, type-argument identity and every raw argument expression identity remain unchanged. A changed label, argument, callee or spread shape requires rechecking.

## Runtime admission boundary

The current-main slice intentionally does **not** publish executable named calls. The type checker rejects named call nodes with `NAMED_CALL_BINDING_UNSUPPORTED`; constructor named arguments are also guarded. Ownership and sync/suspendable runtime paths independently fail closed if a named-argument node reaches them without a completed plan consumer.

This keeps parser admission separate from executable support. No existing positional-call semantics change.

## Next integration requirements

Before enabling any call surface:
- resolve the target callable before binding; do not use source argument count alone when omitted slots are possible;
- compute and record a plan only after name/arity/overload selection;
- type-check each supplied expression in source order against its selected formal type, including generic inference;
- run ownership analysis over source expressions in source order and ensure moves/borrows map to the same formal slots;
- materialize omitted Option formals as typed `None`, or evaluate an explicit checked default exactly once only when its slot is omitted;
- preserve the entire formal ABI at sync, async/suspendable and JIT/AOT boundaries;
- make both synchronous and suspendable evaluators consume the same proof and evaluate each raw source expression once;
- add executable guests for free functions, routines, instance/static methods and constructors before extending the enabled surface.

Canonical language authority: [attolang-language-spec.md #51](https://github.com/ores-truffle-oreslang/attolang-language-spec.md/pull/51).
