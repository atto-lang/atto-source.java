# Structural field views do not perform implicit conversions

Status: targeted Java hardening stacked on draft PR #819; **unverified**.
This file documents a semantic requirement, not an LLVM parity claim.

A structural readonly contract is a zero-copy view of the original value. A
required `int` field can read a stored `int` field, and a required `int|string`
field can safely read that same stored `int`. Extra fields are permitted.

**Counterexample:** a stored `int` does not become a `float` merely by
passing `record{foo: float}` or `struct{foo: float}`. Ordinary scalar
assignment/call promotion still works, because there a conversion is actually
performed on a value.

Similarly, the presence of an `Unknown` or unconstrained generic source
field does not prove a concrete required field type. Reading a concrete field
as **opaque** `Unknown` is permitted; the reverse direction is not.

The low-level `Types.isAssignable` and context-aware `TypeChecker` both
enforce coercion-free field reads, including nested records, tuples and callable
values. Named class subtype references remain a special case only when the
type checker can prove nominal inheritance (same reference representation).

Compiler-owned method slots are distinct from data slots and retain the
existing callable variance checks. Those semantics should eventually be
audited for **call adapters** when signatures require a numeric conversion;
this patch does not assert general effect-aware method parity.

Required release checks: actual Maven/JVM tests, matching runtime tests,
Graal AOT/hybrid, LLVM fail-closed/parity fixtures, and regression coverage
for nested readonly views and ownership/borrow aliases. No passing runtime
tests have been demonstrated for this patch.

### Method ABI is also zero-adapter

An existing implementation method returning `int` cannot silently satisfy
an expected `float` return, even if ordinary scalar return conversion is
allowed in source. Structural dispatch does not synthesize a wrapper and
cannot change the underlying method ABI. Parameter contravariance/result
covariance therefore require *representational* compatibility, with proven
nominal object subtyping and union admission still supported. Once explicit
adapter code is available, this restriction could be reconsidered.

### Whole-value proof, not just member compatibility

The general type checker previously admitted `Unknown -> Record` through a
shortcut ahead of member comparison. An unknown/unbound generic **entire
value** does not prove any record fields. `Types.isAssignable` now rejects
this case before its other inference shortcuts; this guard also rejects union
sources containing an unproven alternative when a record shape is required.
