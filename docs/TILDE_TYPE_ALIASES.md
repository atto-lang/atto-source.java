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
