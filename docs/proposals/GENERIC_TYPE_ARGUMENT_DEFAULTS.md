# Generic defaults, named type arguments, and partial inference

Status: **Phase 1 implementation on a draft branch** (call-site named/partial
type arguments and inference holes). **Generic parameter defaults are not yet
implemented**. Do not treat this document as proof of compiler, native-image,
formatter, or LLVM support.

## Surface syntax

```oreslang
pub fnc foo<A, B = Unknown, C = Unknown, D = Unknown, E = Unknown>() : void {
}

foo<int>()                        // defaults B-E
foo<int, string>()                // B is string; C-E default
foo<A=int, D=bool, C=bool>()      // named arguments can appear in any order
foo<int, D=bool>()                // positional arguments precede named
foo<int, _, bool>()               // _ means INFER B; it does not use B's default
```

An unadorned declaration `A` is **required unless it can be inferred**.
`A = T` is optional and supplies the *type* `T`, not a runtime value.
Omitting a parameter is not shorthand for `Any`. Defaults must be
explicitly declared.

All named type arguments are case-sensitive declaration names, and generic
parameter names are part of public source-level API compatibility.
Spelling `foo<a=int>()` does not select `A`.

## Binding semantics

1. Bind explicit positional arguments to declaration slots from left to right.
2. Bind named arguments to exact remaining parameter names, irrespective of
   named argument order. Duplicate or unknown names are errors.
3. Infer every remaining parameter from call value arguments and other
   explicitly supported inference contexts. Explicit bindings are immutable.
4. In declaration order, apply defaults to *still unbound* parameters, with
   substitutions of already resolved earlier parameters.
5. Unresolved required parameters are compilation errors. Inference holes
   (`_`) **never** select defaults; they must be inferred or produce errors.
6. Validate all bounds, capabilities, borrow/ownership constraints and
   type-level well-formedness after substitution, before lowering.

The default is a fallback; it is **not** an equality constraint. For example,
`B = A` does not forbid inferred `B = string` when `A = int`.
Defaults reference only preceding type parameters, eliminating cycles and
forward-reference ambiguity. Explicit and inferred actual types are never
silently coerced to fit a default.

Allow required parameters after optional parameters, because named arguments
or inference can satisfy the later requirements. A call is legal only once
**all** required parameters resolve.

Expected-return-context inference is **not** automatically promised by this
contract: each inference source must be separately checked for soundness.
Phase 1 uses the existing value-argument inference implemented in Java.

## Grammar and disambiguation

```text
generic_parameter      := IDENT ('=' type_ref)?
generic_parameters     := '<' generic_parameter (',' generic_parameter)* '>'
generic_call_arguments := '<' call_type_argument (',' call_type_argument)* '>'
call_type_argument     := type_ref | IDENT '=' type_ref | '_'
```

A bare `_` is inference-only in this syntactic position.
Positional arguments must precede named arguments.
`foo<D=bool, int>()` is a syntax error.

The call grammar distinguishes `foo<T>()` from relational `<` / `>`
using a matching closing angle followed by an invocation, preserving
the existing comparison disambiguation. Do not reuse
`canonicalizeNamedParameters` for call arguments: that routine sorts
named **type metadata**, but generic call names have declaration-slot
identity and must retain association with the original AST argument.

`foo<>` means infer unsupplied arguments, then use defaults where allowed.
`foo<_>` requires inference of the first parameter. Generic type
constructors in **type position** (e.g. `Box<T=int>`), class construction
(e.g. `new Box<T=int>()`), and type aliases require a separate
declaration-aware canonicalization path. Phase 1 does NOT implement them.

## Type security, ABI, and backend requirements

- `Unknown` is a safe opaque default, not a way to fabricate methods,
  mutation authority, sendability, or traits. `Any` retains the language's
  explicit dynamic semantics; neither bypasses ownership or actor gates.
- Static generics remain compile-time bindings; syntax does not reify or
  deserialize arbitrary runtime type descriptors.
- Runtime and LLVM erasure/specialization decisions use canonical ordered
  bindings after resolution; names must not change execution or layout.
- Exported defaults and parameter names belong in incremental API metadata.
  Renaming a public parameter or changing its default can affect clients.
- Generic method/class/trait/alias declarations, static inherited callables,
  async actor operations and generic overload selection must use the same
  semantic contract without structural overload dispatch.
- Reject a default with unresolved names, future references, recursive type
  expansion, invalid bounds, or unsafe ownership even if no call exercises it.

## Delivery checklist

Phase 1 (Java): named and partial explicit **function/method call**
arguments; inference hole; rejection of duplicate, unknown and positional
after named arguments; fail-closed unresolved generic parameters;
targeted JUnit regression suite. This is draft/unverified until CI executes.

Phase 2: add structured `GenericParameter(name, defaultType?)` AST rather
than encoding defaults inside `List<String>`; update signatures and
resolution for functions, methods, classes, interfaces, type aliases,
constructors, inheritance, type refs, incremental ABI, and ownership checks;
add dependent-default and illegal-default regressions.

Phase 3: provide the same specialization contract in LLVM, preserve source
semantics in oreslang-format, publish cross-backend fixtures, and run JVM,
GraalVM native-image, LLVM and differential conformance at exact tested SHAs.

No green CI or LLVM parity is claimed by this document.
