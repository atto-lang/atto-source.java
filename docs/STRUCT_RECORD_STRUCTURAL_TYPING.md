# Struct, record, and structural shapes (Java phase 1)

Status: implementation proposal on Java PR #819, **not verified/merged**.
This describes *source-level type syntax*, not a new runtime value kind. LLVM
parity is not claimed. The rest of PR #819 is also draft.

## Two independent axes

1. **Value/storage:** `infer struct{foo: 3, bar: "seven"}` constructs a closed,
   readonly, statically inferred structural value. The existing
   `struct{foo: int, bar: string}{foo: 3, bar: "seven"}` constructs an explicit
   closed struct value with the existing definite-initialization and mutability
   rules. Neither can acquire new field names after construction.
2. **Required shape / admission:** In *type position*, `struct{foo: int}`
   and `record{foo: int}` both normalize to the same **readonly structural
   record view**. A structural view requires the specified fields, accepts
   additional fields in the argument, and exposes only the named fields. No
   copying, runtime shape truncation, new object identity, or default
   serialization happens as part of the conversion.

`fnc f(structural {foo: int} v)` is the existing explicit structural
parameter spelling. It continues to allow wider shapes and readonly inferred
structs, subject to the existing borrowing/ownership restrictions.

The legacy **bare** `{foo: int}` is a record type with the pre-existing
writable-capability semantics. Unlike the new readonly aliases, it must not
be treated as an automatic write-permission upgrade for `infer struct`
values. This distinction preserves source compatibility pending a broader
design for per-field readonly contracts.

## Supported examples

```ores
pub fnc a(struct{foo: int} v): int { return v.foo; }
pub fnc b(structural {foo: int} v): int { return v.foo; }
pub fnc c(record{foo: int} v): int { return v.foo; }

fnc run(): int {
    const wider = infer struct{foo: 3, bar: "seven"};
    return a(wider) + b(wider) + c(wider);
}
```

All three calls must compile. The argument still has `bar` at its original
storage location, but none of these narrowed parameter views exposes it.
`record{foo: int}` is not a `record{foo: 3}` value initializer, a nominal
record declaration, or a separate runtime layout.

## Soundness gates

- **Direction:** `S` satisfies `R` if every required public/readable field
  in `R` is present and type-compatible in `S`. Extra fields are fine.
  Missing, inaccessible or incompatible fields fail compilation.
- **Readonly:** these new inline type spellings grant *no write authority*,
  even when backed by a mutable explicit struct. Do not infer a mutable
  receiver, setter, or unrestricted borrow from a readonly view.
- **Types:** writable fields remain invariant; readonly field variance is
  allowed only where compiler/runtime representation and ownership agree.
  Conversion-aware numeric widening is not automatically a valid field-view
  subtype without a materialized conversion. Unknown and unconstrained generic
  placeholders are not evidence of a runtime field type.
- **Methods:** function-valued fields and instance methods are different
  categories. Structural callable conformance checks arity, parameter modes,
  effects, contravariant arguments and covariant results. A method name does
  not silently substitute for a data field with the same name.
- **Nominal barriers:** ordinary named classes, traits, interfaces and actor
  references remain nominal unless an explicit structural boundary exists.
  Actor identity and mailbox isolation must never be erased by a shape match.
- **Nested aliases:** readonly and ownership restrictions must survive
  projection, assignment, closure capture, generics, unions/intersections,
  destructuring, return values, suspension and actor boundaries.
- **Compile time:** a shape contract is not a dynamic object or a
  `Map<String, Any>`. It is statically known and AOT-checkable.
- **Future work:** separately decide whether a *nominal* `define record`
  declaration should exist. Do not imply it exists or change wire/ABI
  representation merely by adding the `record{...}` type spelling.

## Verification and backend work

Java regression coverage: parsing/canonical type equality, positive width
subtyping (both inferred and explicit structs), field-mismatch rejection,
write denial, duplicate keys and contextual keyword compatibility.

Still required before promotion: exact-head JVM compiler/runtime suite;
native AOT/hybrid checks; a targeted audit of readonly numeric widening,
Unknown/Generic, nested mutable projections and methods; explicit
LLVM rejection or matching lowering for syntax/shape checks, and shared
positive/negative conformance cases. No parity or green build is claimed.

### Suspension and actor admission

Type-position `struct{...}` / `record{...}` (and aliases that resolve to
readonly shape views) must not provide a `mut` parameter or cross an actor
mailbox. These borrowed parameter views are prohibited for `async`,
`suspend` / `suspends`, and `generator` callables. The same prohibition
applies to explicit `structural {...}` parameters. This is a capability and
lifetime rule, not a statement that an owned immutable struct can never be
copied/serialized for async or actor use.

An eventual explicit owned/copy conversion may relax this restriction only
with ownership, serialization, and cancellation-safe lifetime proof.
