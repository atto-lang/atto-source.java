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
is implied. The current native LLVM compiler has **not** implemented
this extension dispatch contract.

`declare` and `define` are synonymous **only for complete type
declarations**: `declare type Code = int;` and `define type Code = int;`.
Neither introduces an opaque forward type, and neither changes the extension
method receiver identity.

## Zorde and `with` refinements: required integration, not yet a claim

Proposed constrained example:

```oreslang
declare type Code = int with min 0 and max 5;
```

Zorde must consume the compiler's normalized checked constraint
descriptor, not independently parse or reinterpret `with` clauses.
No `Zorde.parse` implementation or schema format is claimed here.
A future shared descriptor should include at minimum:

- Stable nominal qualified type name and underlying primitive representation
- Normalized range/length/case/character predicates and their parameters
- Constraint-composition rules and deterministic validation error identities
- Provenance of validation: construction, checked conversion or compiler proof
- Schema/descriptor version and compatibility fingerprint across backends

**Security invariant:** extension methods on constrained `Code` may assume
that `self` has passed validation only if every route producing `Code`
checks or proves its constraints. Aliases must never allow an unchecked
`int -> Code` conversion or generic erasure that fabricates validated
type evidence. Extension receivers remain immutable by default;
future mutable methods must revalidate post-mutation invariants.

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
