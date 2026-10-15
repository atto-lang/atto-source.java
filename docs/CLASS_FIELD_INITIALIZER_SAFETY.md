# Ordinary class initialization safety contract

## Pre-publication stage

For a class with an explicit constructor, eager declaration-site field initializers
run before the body with **no** `self` binding. The permitted constructor parameter
frame contains only statically recognized owned/copyable `int`, `bool`, `float`
and `string` values. It does not contain `arguments`, borrows, references,
mutable object handles or capability-bearing parameters. A field's initializer
is checked by both the type checker and the ownership checker.

A declaration-initialized final/const/val member is never reassignable by a
constructor, positional fallback or member write. The legacy constructor-only
fill of a *previously uninitialized* fixed field remains bounded by the
existing single-write ledger. `final mut` changes referent mutability, never
slot rebindability.

## Eager effect admission

Reject direct and nested awaits, selects, channel operations, assignments,
runtime intrinsics (`rt fail`, `rt take`, `rt borrow`, `rt copy`,
`rt share`, `rt cooperate`, `spawn`), and AST node types not explicitly
proven effect-free. Indexing is rejected even when its operands are pure,
because bounds failure could unwind an incompletely constructed object and
partial-construction resource cleanup remains unproven. Arithmetic division
and remainder are likewise excluded. Bit shifts (`<<`, `>>`, `>>>`) are
also excluded: the current runtime throws on shift counts outside 0..63,
even when both operands are well-typed integers, and evaluating an earlier
field may already have installed an owned value. A future proven range and
failure-cleanup analysis may narrow these exclusions.

Member reads and all nonlocal runtime value names are rejected in eager
initializers. Resolving a module export, imported namespace, host member or
global value can execute code outside the local initializer's effect proof;
visiting only a member receiver is not sufficient. The only admitted direct
value-name leaves are the constructor's checked scalar parameters. An eager
unary borrow (`&` or `&mut`) cannot create a loan across construction.

A verified zero-argument scalar helper may still be called, but its callable
name must resolve to that function in the runtime lexical environment. A
constructor parameter shadowing the callable, a same-named module that
takes precedence over a class namespace at runtime, or a foreign/imported
class namespace with unproven effects, cannot satisfy this exception.

The synchronous scalar-helper exception requires a statically resolved
zero-argument, non-generic, non-async, non-suspending, non-generator,
non-trapped, non-actor-dispatched callable. Its entire body must be one
scalar-return expression formed from literals and the supported unary and
arithmetic operations. Abstract static methods are not admissible.

The zero-argument built-in `Array` exception cannot be applied to a
shadowing local class, an imported binding of the same name, or an
inferred-type marker without an explicit constructor effect proof. Runtime
dispatch treats `Array` as a built-in before ordinary class lookup; the
checker must not silently conflate this case with arbitrary guest imports.
Partial-construction resource failure cleanup and full transitive effect
summaries remain **unproven**, so these exceptions must not be widened
without executable regressions.

## Constructor self

The constructor body may perform validated legacy `self.field` filling,
but cannot publish, move, copy, share, borrow, pass or dispatch a method on
partially initialized `self`. A constructor cannot return the object before
all required field slots are initialized.

## Verification gate

Every source change requires exact-head Maven/JDK25, GraalVM native AOT
and hybrid guest execution, Graal conformance, and the source-identical
Java/LLVM scalar-constructor guest returning `40`. Record the source and
mirror Git tree SHAs before reporting a pass. A workflow failure without
executed steps is not evidence of compiler failure; a historical green run
cannot verify a newly modified Git tree.
