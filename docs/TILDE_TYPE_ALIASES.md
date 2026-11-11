# Additive tilde type aliases (draft compiler slice)

**Status:** draft Java parser/type-checker slice; not a value constructor,
factory, Native Image or five-backend conformance certification.

## Non-negotiable compatibility

The existing collection system remains authoritative:

- `[...] ` describes sequence shape and exact positional tuple shape.
- `<name=value>` holds compile-time storage/layout policy, **not** current
  `size`/`capacity` for runtime-extent collections.
- Repeated `[T][T]` describes a homogeneous multidimensional shape.
- `Vector[...(int, string, bool)]` requires whole repeated groups, while
  `Vector[int | string | bool]` allows arbitrary element order.
- `FixedArray[4 of int]` is fixed storage, distinct from `Tuple[int,int,int,int]`.
- `arr[...]`, `[...]`, `tuple {...}`, and `(...,...)` remain legacy value
  syntaxes; this patch does **not** rewrite or remove them.

## Type-position spellings

| Existing | New type alias |
| --- | --- |
| `Array[int]` / `Array<int>` | `~array<int>`, `~array[int]` |
| `Vector[int]` | `~vector<int>`, `~vector[int]` |
| `Tuple[int, string]` | `Tuple<int, string>`, `~tuple<int, string>`, `~tuple[int, string]` |
| `FixedArray[4 of int]` | `~fixed_array[4 of int]` |
| `Vector[...(int, string, bool)]` | `~vector[...(int, string, bool)]` |
| `Vector<allocator=Arena, align=64>[int]` | `~vector<allocator=Arena, align=64>[int]` |
| `Map<K,V>` | `~map<K,V>` (type spelling only) |
| `Entry<K,V>` | `~entry<K,V>` (type spelling only) |
| `Pair<A,B>` | `~pair<A,B>` (type spelling only) |
| `Record<...>` | `~record<...>` (type spelling only; *not* a structural `{...}` rewrite) |
| `Set<T>` | `~set<T>` (type spelling only) |

Alias resolution uses the existing capitalized name in the AST. No
`TildeArray`, `TildeTuple`, etc. is introduced.

**Intrinsics versus nominal aliases:** An explicitly bracketed built-in
sequence shape (including the normalized `~tuple<T1,T2>` form) is resolved by
`resolveCollectionType` *before* lexical named type aliases. A source
`type Tuple<T> = ...` or `type Vector<T> = ...` cannot replace
`Tuple[int,string]` or `Vector[...(int,string)]` with an arbitrary type.
Unshaped nominal references retain their existing lexical resolution policy.
The type-prefix provenance problem for all other (notably map/entry/record)
aliases remains a review gate until an explicit compiler-owned builtin type
registry and validated namespace identity exist.

**Typechecker evidence matters:** Equality of two parsed `Ast.TypeRef`s is
necessary for some spellings but does not prove runtime collection-family
identity. The focused tests must include positive and negative
`TypeChecker.check`/ownership tests, especially under name shadowing.

**For Tuple only:** generic positional arguments with no bracketed shape
are normalized to the already supported finite tuple shape. Named metadata
(`align`, `size`, `allocator`, etc.) is preserved and checked normally.
`Tuple<int, string>[int, string]` is an error rather than a silently
overridden shape.

**For nominal collections:** syntactic aliasing does **not** grant a missing
builtin implementation, bypass namespace lookup, or authorize access through a
host interop class. The Map/Entry/Record/Pair/Set factory and value contracts
remain independently gated.

## Deliberate non-goals

This change does not implement `~map(...)`, `~tuple(...)`, `~entry(...)`,
`~array(...)`, `Map.of(...)`, `Tuple.of(...)`, etc. The value constructor
equivalences are a separate language decision requiring compiler-owned,
owner-safe lowering and actual compiled guest tests.

No formatter migration is authorized: existing spellings remain round-trippable;
formatters must not replace bracket shapes with lossy generic `<...>` arguments.

## Required acceptance evidence

1. Compile/type-check positive and fail-closed negative cases in
   `TildeAggregateTypeAliasCompilerTest` at the exact branch SHA.
2. Execute the existing `CollectionShapeMetadataTest`,
   `TupleFixedArraySpreadTest` (if present), and relevant parser/ownership
   regression suites. Check the published counts and nonzero job steps.
3. Execute real Java/GraalVM guest fixtures for the existing `arr[]`,
   fixed-array and tuple values and their new type annotations.
4. Independently run native/NativeAOT conformance (LLVM, Wasm, Forge, .NET)
   before parity claims. Source-only, host-only, skipped or zero-step checks
   must be recorded as such.

See the shared draft constructor/empty contracts in
[`oreslang-common-source#254`](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/254).

## Canonical tuple return types (2026-11-10)

Use **`Tuple[T1, T2, ...]`** in new declarations, including public function
return types, parameters, binding annotations and nested types. The angle-bracket
spelling `Tuple<T1,T2>` remains an additive compatibility form when it does not
resolve to a lexically declared nominal `Tuple`; it is not the formatter's
canonical spelling. The tilde constructor is a **value** expression,
`~tuple(v1, v2)`, not a function return-type syntax.

```atto
pub fnc labels(): Tuple[string, string] {
  return ~tuple("left", "right");
}
```

Return checking uses the existing fixed positional tuple kind: each slot's type
and arity must match; a non-void function must return on every path. Explicit
`~array(v1, v2)` and legacy `arr[v1, v2]` remain array values even in a tuple-expected return
context, and must not be implicitly converted. The older ambiguous `[...]`
literal retains its existing contextual tuple compatibility until a separate
migration contract is approved. A user-declared `Tuple` class or alias cannot
hijack an explicitly bracketed intrinsic shape.

Zero-slot and singleton annotations have canonical spellings `Tuple[]` and
`Tuple[T]`, respectively; these match the distinct `~tuple()` and `~tuple(v)`
values. Only `Tuple[]` is allowed as an empty bracketed sequence type; `Array[]`
and other unshaped empty sequence notations still fail. In any type position,
`~tuple(value, ...)` fails with a clear diagnostic: it is a value constructor,
not a type. Typed construction `~tuple<T...>(...)` is intentionally rejected
until checked value-witness lowering is implemented and proven across backends.

A dedicated `TupleReturnTypeContractTest` and the real
`src/test/resources/atto/tuple-returns/canonical-return.atto` fixture provide
positive compiled guest and negative return-type cases. **These checks are
authored, not executed**, until JUnit and Truffle guest execution run at the
exact source head, with nonzero runner steps. The original tuple drafts remain
separately gated for native and five-backend parity.
