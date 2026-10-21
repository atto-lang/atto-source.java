# Map.from_entries — integration contract (not yet implemented)

Status: **design and fail-closed merge gate**, not an implemented guest API.
This contract connects the nominal `Entry<K,V>` slice in #1668 to the typed
guest Map foundation in #1104 after that foundation is forward-ported to a
fresh current-`main` base. Do not expose `Map.from_entries` before all
requirements below have executable Java/Graal and LLVM parity evidence (or an
explicit LLVM unsupported diagnostic).

## Type and admission

```oreslang
const entries: Array<Entry<string,int>> = [
    Entry.of("a", 1),
    Entry.of("b", 2)
];
const map: Map<string,int> = Map.from_entries(entries);
```

- Accept a finite iterable of **nominal** `Entry<K,V>`, never infer entries
  from two-element arrays, tuples, structural records or host `Map.Entry`.
- Infer or context-check exact `K,V` and reject unresolved/unchecked key
  domains, unsafe widening, capability leaks and borrowed/affine escapes.
- Key domains must use the typed guest Map's canonical key codec. Distinguish
  `"x"` from `#x`, and process Symbols from stable Symbols.
- No user-defined equality/hash callbacks, host `equals/hashCode`, string
  coercion or identity-based hashing may decide duplicate keys.
- For v1, reject duplicate **logical** keys. Never silently last-write-wins.
  If a future explicit conflict resolver is added, it must be a separate API
  with an explicit deterministic evaluation order.

## Atomic construction

1. Validate the input shape and its finite size under a bounded traversal
   budget before exposing any partially populated Map.
2. Validate each key/value and canonicalize each key exactly once; reserve
   byte/entry quotas for the **whole** candidate snapshot.
3. Reject collisions **after** canonicalization, including Unicode-normalized
   or tagged Symbol keys that become logically identical.
4. Build into private, non-observable temporary storage. On error, release
   temporary resources and leave all input ownership unchanged unless a
   compiler-proven move transaction is available.
5. Publish the completed Map atomically only after all validation succeeds.
   Do not invoke guest callbacks while holding a Map lock.
6. Do not admit actor transport or shared/untrusted use until the Map's
   ownership domain, detachment, graph budget, and capability checks are
   independently proven. An immutable Entry wrapper does not make a mutable
   Map or its nested values Sendable.

## Required adversarial tests

- empty input, one entry, repeated distinct keys, duplicate first/last keys;
- duplicate normalized Unicode String keys and duplicate tagged Symbols;
- String/Symbol lookalikes remain separate keys;
- wrong key/value type, Option.None versus missing, nested Option values;
- maximum entry count, maximum aggregate key bytes, oversized individual key;
- failure at the final entry leaves no partially observable Map;
- mutable payloads, nested Entries, cyclic inputs and non-Sendable capabilities;
- concurrent readers cannot observe a half-built Map;
- both synchronous and continuation evaluators produce identical results;
- actor/shared/untrusted admission fails closed until explicitly enabled;
- LLVM parser/codegen either implements the same contract or rejects it
  before emitting partial IR.

## Merge order

1. Obtain executable exact-head tests for #1668 and #1688.
2. Forward-port the typed Map foundation to the current Java main and
   reconcile the Symbol/key-domain and actor-copy hardening already on main.
3. Implement `Map.from_entries` as a typed guest operation using the actual
   `OresKeyedMap` storage and key codec, not `java.util.Map`.
4. Execute the adversarial suite, Maven/JDK25/Graal, and native parity gates.
5. Merge only in dependency order after the relevant CI/security reviews.