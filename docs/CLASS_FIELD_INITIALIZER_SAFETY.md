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
partial-construction resource cleanup remains unproven.

The synchronous scalar-helper exception requires a statically resolved
zero-argument, non-generic, non-async, non-suspending, non-generator,
non-trapped, non-actor-dispatched callable. Its entire body must be one
scalar-return expression formed from literals and the supported unary and
arithmetic operations. Abstract static methods are not admissible.

The zero-argument built-in `Array` exception cannot be applied to a
shadowing guest class of the same name. Partial-construction resource failure
cleanup and full transitive effect summaries remain **unproven**, so these
exceptions must not be widened without executable regressions.

## Constructor self

The constructor body may perform validated legacy `self.field` filling,
but cannot publish, move, copy, share, borrow, pass or dispatch a method on
partially initialized `self`. A constructor cannot return the object before
all required field slots are initialized.

## Verification gate

This branch is a **draft integration candidate**, not a release artifact.
Required before merge: exact-head Maven/JDK25, GraalVM AOT and hybrid,
Graal conformance, scheduler deadline permit retirement tests, and real
execution of the source-identical Java/LLVM scalar-constructor guest returning
`40`. Workflow failures with no executed steps do not prove any of these.
