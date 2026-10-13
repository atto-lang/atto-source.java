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

### Suspension, actor transport, and owned vs borrowed values

`Record.readOnly` is a field-write capability, **not a loan**. Ordinary
parameters spelled `record{foo: int}` and `struct{foo: int}` are readonly
**owned value contracts**: ownership may transfer across async/suspends/actor
boundaries when the payload passes the existing static sendability checker,
and the transport provides value isolation. Nested readonly records are
likewise permitted if they contain no unowned or unsafe capabilities.

An explicitly qualified `structural T` parameter is different: it is a
borrowed, read-only *view*, valid only for the synchronous call. Actor
mailboxes, async, suspend/suspends, and generators must reject this
view. Explicit `&T` or `&mut T` references, even when nested inside
records, tuples, lists, unions, callbacks or generic wrappers, are also
borrowed and must not escape via suspension or mailbox transport.

The static `Types.Borrow` and `Ast.Param.structural` establish loan
provenance. Neither the type checker nor the ownership checker may infer
a borrow solely from `Record.readOnly`. This prevents immutable owned
structs from becoming accidentally unsendable. Actor boundary checks
must still reject data containing mutable identity-bearing class
instances, functions, host resources, unresolved generics, or other
unsendable capabilities. Passing an owned readonly shape never grants
mutation permission to the recipient.

`mut record{...}` as a function parameter remains rejected to prevent
confusion between rebinding ownership and granting write access to a
readonly structural field contract. A separate source-level rebinding
feature could relax this without permitting record field mutation.

### Regression gates

Run Java/JUnit and both ownership + type-checker passes against
owned readonly parameters in async and actor functions, actual explicit
borrows in direct/nested positions, and explicit structural views.
Validate execution, AOT/hybrid, and native LLVM parity before merging.
