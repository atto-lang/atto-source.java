# JSON value runtime descriptor foundation

This slice is infrastructure for the `alloc/json` contract in
`oreslang-common-source#111`. It does **not** make JSON a core language
builtin and does not expose source `JsonValue` yet.

## Boundary

`core` remains allocator-free. JSON remains an allocator-backed portable
library feature. Until the canonical `alloc/json` module/resolver exists,
source code spelling `JsonValue` is rejected.

The Java runtime nevertheless needs a closed recursive carrier so later
`alloc/json` lowering can use the canonical typed Map without falling back to
host `Object`, `java.util.Map`, reflection, or guest-visible `ANY`.

## Runtime shape

`OresJsonValue` is a sealed runtime class with exactly six value domains:

- null;
- bool;
- exact number (`BigDecimal`, with hard precision/scale ceilings);
- Unicode-scalar String;
- immutable array snapshot;
- object backed by `OresKeyedMap<String, OresJsonValue>`.

The Map runtime has a dedicated `ValueDomain.JSON`. It accepts only
`OresJsonValue` and recursively validates the tree before publishing a write.
`ValueDomain.ANY` remains a separate host/dynamic domain and is not used as a
JSON shortcut.

## Cycle safety

JSON is a tree. Dynamic JSON object Maps are mutable, so ordinary recursive
validation alone is insufficient: two Maps could concurrently add opposite
edges after both observed acyclic pre-state.

JSON Map `put` and `putAll` therefore take a private JSON graph-admission
lock **before** the destination Map monitor. Every edge-creating mutation
rejects a direct/indirect cycle before publication.

Depth and total-node budgets are different: they are properties of a selected
root, not of one new edge in isolation. A retained alias to a nested mutable
object can later make an older root deeper/larger without mutating that root
Map. Therefore recursive externalization boundaries must revalidate the
**complete root** under the same graph-stability gate immediately before
encoding, freezing, transport, or equivalent traversal.

`OresJsonValue.validateCompleteTree` provides that whole-root check and
`OresKeyedMap.withJsonGraphLock` provides the private compiler/runtime-owned
stability guard without exposing the lock object. Future codecs must either
perform validation and traversal in one guarded operation or traverse a
validated immutable snapshot. Guest callbacks are forbidden while the guard is
held.

All JSON-domain object mutations—including `put`, `putAll`, `take`,
`remove`, and `clear`—use the same global-guard-before-Map-monitor order.
Only edge creation needs cycle admission, but removals must also participate so
a guarded encoder/freeze/transport traversal observes one stable root graph.

The graph lock is private to `OresKeyedMap`; it is not published through the
JSON value API or host interop. Non-JSON Map mutations retain the existing
per-instance synchronization and do not use this lock.

## Exactness and hostile-host behavior

- integer values above 2^53 remain exact;
- NaN and infinities are rejected;
- numeric precision and absolute scale have runtime ceilings;
- malformed surrogate strings are rejected before becoming JSON strings;
- array input is copied instead of retained;
- object keys remain governed by the canonical String-key Map key validator and
  quotas;
- raw host writes of arbitrary objects or strings into a JSON-domain Map are
  rejected;
- `putAll` remains all-or-nothing when one JSON value is invalid.

Parser-specific token length, allocation budget, unknown-field work accounting,
and configurable decode quotas remain responsibilities of the later
`alloc/json` parser/codec layer.

## Source staging

This PR deliberately leaves these source spellings unavailable:

```ores
Map<String, JsonValue>
Json.decode<T>(...)
Json.stringify(...)
```

They become legal only when imported/resolved through `alloc/json`. This
prevents an allocator-backed type from accidentally becoming an ambient
`core` builtin merely because the runtime descriptor landed first.

The next implementation slice should introduce the alloc module/resolver
boundary and compiler-generated/static JSON constructors/codecs, then bind
source `JsonValue` to this runtime descriptor.

Source `Map<String, JsonValue>` mutation remains gated on
commit-aware ownership transfer. A recursive JSON insertion can fail on graph,
quota, or policy admission, so ordinary eager argument consumption is not
sound for a move-only value.

The runtime foundation now provides
`OresKeyedMap.attemptOwnedPut(key, value)`. Its result is **linear** and has
exactly two states:

- **committed** — the offered value is published and
  `takePrevious()` transfers the previous mapping exactly once;
- **rejected** — the live Map is unchanged and
  `takeUncommittedValue()` transfers the exact offered value by identity
  exactly once.

The rejection is not exposed as ordinary data. On a rejected result,
`rethrowAfterRecovery()` refuses to propagate the original admission failure
until the uncommitted value has been taken back. This prevents runtime lowering
from throwing away the only owner while reporting an otherwise recoverable
cycle/quota/policy error.

The implementation has an explicit preflight/commit boundary. Key/value-domain
checks, JSON graph admission, key/count/byte quotas, and compiler-owned policy
validation all run before publication and are the only RuntimeException paths
converted into a rejected ownership result. The final canonical Map publication
occurs outside that rejection catch; an internal failure after publication must
never masquerade as a rejection that returns the candidate.

This is intentionally a compiler/runtime lowering primitive rather than the
guest API. Source lowering must evaluate the owned RHS into a temporary without
committing the source move, perform `attemptOwnedPut`, and:

1. on **committed**, mark the source moved and consume `takePrevious()`;
2. on **rejected**, consume `takeUncommittedValue()`, restore/retain the
   original owner, then call `rethrowAfterRecovery()` or translate the error.

The compiler must not implement this by calling ordinary `put`, eagerly
marking the source moved, and then attempting ownership recovery from an
exception.

Source `Map<String, JsonValue>` is still deliberately gated. The current
ownership checker conservatively treats every move observed anywhere inside a
`try` as moved on the catch edge, because a later operation may throw after a
successful move. The next compiler slice must model this insertion's explicit
commit/rejection edges (or lower it to a non-throwing internal result branch)
before a catch body may safely observe the restored source owner.
