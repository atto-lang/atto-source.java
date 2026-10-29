# Type extension methods and Zorde constraint interoperability (v1 draft)

## Syntax and status

```oreslang
declare type Code = int;

// `define type Code = int` has identical type identity.
define mtd get_list on Code(): List<int> {
  return new List<int>{self};
}
define mtd to_string on Code(): string {
  return `${self}`;
}
```

`define mtd name on Type(args): Return { ... }` is a **static extension**
attached to the named alias, not to all values with the same representation.
The Java/GraalVM parser lowers an extension declaration to a compiler-owned
function with the receiver `self` as the first parameter and a reserved
function symbol `$mtd$Type$name`. The typechecker authorizes method syntax
by retaining the named alias at explicit type-binding sites. A raw `int`
cannot acquire `Code` methods by accident. Instance-call dispatch is
compile-time; no virtual table, object allocation or implicit conversion
is implied. The native LLVM companion PR now contains source-level static extension-call
lowering with receiver-first ABI reuse and an LLVM/Clang executable gate. That
work remains **unverified** because the latest hosted workflow created zero
jobs, so it is not yet parity evidence.

`declare` and `define` are synonymous **only for complete type
declarations**: `declare type Code = int;` and `define type Code = int;`.
Neither introduces an opaque forward type, and neither changes the extension
method receiver identity.

## Zorde and `with` refinements: compiler descriptor v1

The compiler now accepts the first bounded descriptor slice:

```oreslang
declare type Code = int with min 0 and max 5;
```

`TypeChecker.checkTypesWithDiagnostics(...).constraintDescriptors()`
returns a compiler-owned `TypeConstraintDescriptors.Catalog`. Schema v1 is
`ores.type-constraint.v1` and currently admits only inclusive signed-i64
`min` / `max` predicates on non-generic direct `int` / `i64` aliases.
The compiler canonicalizes predicate order, emits deterministic error IDs,
records the stable qualified type name and `i64` representation, and computes
a SHA-256 compatibility fingerprint over the canonical descriptor.

Zorde should consume this catalog directly rather than parsing or
reinterpreting source `with` clauses. The compiler now also has a narrow
`ConstraintValidationBridge` substrate: it accepts only a descriptor from
the compiler-owned catalog, evaluates every v1 predicate, reports the
descriptor-owned deterministic error ID on failure, and records successful
evidence in a weak identity side table keyed by the exact AST expression.
There is intentionally no raw "set proof" API and generic AST metadata copying
does not transfer this evidence.

### Static compiler-provenance hardening (2026-10-29)

The experimental host-only `ConstraintValidationBridge` now records
evidence **only** for a canonical signed-i64 `Ast.LiteralExpr` whose actual
boxed integral value equals the proposed predicate operand. Passing a
different numeric value for an ineligible AST node, or passing a name,
floating-point literal, string, Boolean or arbitrary numeric wrapper, fails
before any evidence is recorded. Evidence holder construction is private,
and proof consumption requires both the exact original expression node
**and exact catalog object identity**, in addition to descriptor fingerprint,
qualified name, schema version and required validation status. A second
compilation with an equivalent fingerprint cannot replay the first
compilation's AST ledger.

The host API remains **experimental**: public source AST and catalog helpers
do not authenticate external source provenance, a Java reflection/plugin
attacker is outside this API's current trust model, and the global weak
identity ledger is not a runtime security capability. In particular the
host-only static literal path does not establish a guest checked-conversion
operation for a dynamically evaluated value, nor does it freeze mutable
storage, bridge actor/FFI data, or authorize an `i64` alias. Those facilities
require a compiler-owned construction intrinsic and trusted catalog/generation
scope, with exact-head source-local executable negative testing.

This is **not yet guest checked-conversion syntax or runtime admission**.
`CONSTRAINT_VALIDATION_BRIDGE_REQUIRED` remains in the normal value-type
resolution path until every construction/import/serialization/extension route
is integrated and exact-head tests execute real steps. Descriptor and catalog
constructors now reject schema/representation/identity/fingerprint drift so a
stale descriptor cannot be treated as current validation evidence.

**Security invariant:** constrained aliases cannot enter a runtime value path
yet. Type checking fails with `CONSTRAINT_VALIDATION_BRIDGE_REQUIRED` rather
than treating an unchecked `int` as validated `Code`. This includes
extension receiver paths, so an extension method cannot assume `min/max`
until checked conversion or compiler proof supplies validation provenance.
Future mutable constrained values must revalidate post-mutation invariants.

## Admission boundaries and backend work

- Canonical syntax in Java: `define mtd name on Alias(params): Return { ... }`.
- Receiver must name a same-module declared alias; raw primitives, unknown
  types and parameterized receiver syntax are currently unsupported.
- `self` is reserved for the implicit receiver and is not an explicit formal.
- The existing overload key remains method-name plus explicit argument arity.
  Two extensions with the same receiver/name/arity must fail.
- Runtime lowering must use the **typechecked call identity**, not an
  unverified receiver or raw primitive member-name match.
- Import coherence, cross-module extension discovery, trait method
  interaction, constrained generic receivers, mutation and extension
  propagation across containers remain unimplemented in this v1 profile.
- LLVM parity requires: preserving named alias metadata in typed expressions
  until extension dispatch; checking receiver identity and uniqueness;
  emitting static receiver-first calls using the same native scalar ABI;
  rejecting calls on raw primitives; and differential positive/negative
  source tests against Java/GraalVM.
- Java/GraalVM runtime/JUnit execution and LLVM CTest are not yet evidenced
  by source-organization GitHub Actions; zero-step failed jobs are not
  compiler failures or compiler passes.

## Minimal cross-backend verification fixtures

1. Same method lookup for `declare type Code = int` and
   `define type Code = int`.
2. `Code.get_list` returns the correct receiver singleton value.
3. `Code.to_string` is statically dispatched without runtime boxing.
4. `int.describe()` and `OtherAlias.describe()` do not inherit extensions
   declared only on `Code`.
5. Duplicate extension receiver/method/arity is rejected deterministically.
6. No program-defined callable can spoof a compiler-reserved `$mtd$` symbol.
7. Future: validated `Code` creation rejects values below 0 or above 5
   and every extension call preserves the validated-type invariant.
