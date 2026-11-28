# AttoLang Java affine Map reconciliation — V1 integration

Status: implementation candidate; exact-head JVM/JIT and Native Image execution
must be verified independently. Normative cross-backend authority remains
[atto-spec](https://github.com/atto-lang/atto-spec).

## Reconciled legacy decisions

- Oreslang Java [#1709](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1709)
  used `.put()`, denied indexed assignment, and supported affine function transfers.
- Oreslang Java [#1772](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1772)
  admitted indexed assignment, but rejected callable parameter/return transfers.

AttoLang V1 admits both `map[key] = value` and `map.put(key,value)`.
Both pass the same compiler-verified writable-place and ownership gates;
the runtime uses a single synchronized `OresKeyedMap.put` with preflighted
key/value domains, quota, reentrancy and atomic update. Index assignment yields
the assigned value in its expression context; `.put` returns `Option<V>`
containing the previous value.

An affine Map has one logical owner. `rt take map` transfers the exact
lexical slot and poisons the original runtime binding. Owned callable parameters,
returns and new lexical owners may receive transferred Maps, but an existing
named Map requires explicit `rt take`. Immutable/exclusive lexical borrows
follow ordinary borrow checking. `rt copy` and `rt share` are forbidden.
Actor messages, detached tasks, unrelated TenantVMs, and cross-tenant transport
cannot implicitly inherit a mutable Map or its JVM reference.

All current admitted Map keys and values must meet the pre-existing narrow
reified scalar witness, including the compiler's distinct Symbol/String domains.
Non-Copy entries, nested graphs, generic aliases, untrusted provenance,
borrowed/captured Map escapes, and remote serialization remain excluded until
the necessary runtime and compiler ownership proofs land.

## Verification requirements

At an immutable PR head commit/tree, require at least one nonzero stepful
GraalVM JDK25 Maven run of `AttoAffineMapOwnershipV1Test` and existing
`AttoRedisScalarMapMutationGuestTest`, `TypedEmptyMapGuestTest`,
`OresKeyedMapTest` and `PointerlessFncOwnershipTest`. Then execute the full
Maven suite and Native Image AOT/hybrid guest fixtures where configured.
Failures and zero-step Actions must be reported as not verified, never PASS.
Cross-backend or untrusted TenantVM support is NOT demonstrated by host tests.
