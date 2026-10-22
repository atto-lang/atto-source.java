# Typed Map storage — current-main forward port (draft)

This branch is **runtime-only** and is stacked on the current-main
tuple/Entry integration candidate #1713. The older complete typed-Map
compiler/evaluator branch #1104 is more than 800 commits behind modern
`main` and must not be merged wholesale.

## Included

- Dedicated `OresKeyedMap` storage, preserving the distinction between
  String and nominal PROCESS/STABLE Symbol keys.
- Versioned tagged `SymbolPropertyKeyCodec` and its adversarial round-trip
  checks.
- Atomic lookup/take distinction between missing and a stored Option-like
  value, transactional `putAll`, strict key/value domain admission and
  per-map key-byte/entry quotas.
- Java host equality/hash are object-identity based and host `toString` is
  opaque. Mutable runtime storage cannot use structural host hashing as a
  surrogate for future Oreslang guest Map equality; independent tests reject
  hostile nested `equals/hashCode/toString` callbacks.
- Host/runtime foundation `fromNominalEntries(Iterable<?>, KeyDomain,
  ValueDomain)`, taking **actual** `OresEntry` carriers only.
  It bounds traversal to `MAX_ENTRIES`, rejects incompatible elements and
  duplicate logical keys, and returns a fully populated private candidate
  only on success. It deliberately throws a sanitized duplicate-key exception.
- Actor message graph preflight rejects `OresKeyedMap` even when nested in
  an Entry. No mutable typed Map can be transported until the ownership and
  actor-arena transport model is implemented.

## Deliberately not included

- No public Oreslang `Map<K,V>` constructor, `Map.from_entries` function,
  `Result<Map<K,V>,DuplicateKey<K>>`, map hook ABI, or schema declarations.
- No blanket actor Send/Sync implementation, shared-memory projection,
  serialization of values, numeric key domain or automatic deep freeze.
- No authoritative LLVM Map lowering; native code should reject unsupported
  Map products rather than manufacturing a host map.

## Integration gates

1. Build exact branch head with JDK25; run focused
   `OresKeyedMapTest`, `OresKeyedMapLookupOptionTest`,
   `SymbolPropertyKeyCodecTest` and
   `OresKeyedMapFromNominalEntriesTest`, plus actor-isolation tests.
2. Differentially test tagged key identity against current-main
   `OresSymbol`, not the stale copy from #1104.
3. Port parser/AST/typechecker and ownership checks in smaller slices:
   `Map<K,V>`, key/value type admission, direct mutation, lookup and
   capability-safe aliasing.
4. Expose `Map.from_entries` only after compiler-proven `Result` lowering,
   transactional move/borrow semantics, and full exact-head tests.
5. Keep current-main source transfer policies: no private cross-org mirroring
   without protected destination approval and tree-bound receipt validation.

The companion contract `docs/MAP_FROM_ENTRIES_CONTRACT.md` distinguishes
byte-exact String keys from normalized nominal Symbol identity. Do not silently
change String normalization for one Map entrypoint without changing all reads
and writes and adding differential tests.
