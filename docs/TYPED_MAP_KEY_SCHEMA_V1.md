# Typed open Map key schema v1

This document defines the first executable Java/Graal slice for contextual
`key` declarations on typed open Maps.

## Source form

```ores
define class Settings extends Map<string, unknown> as
  key name: Option<string>;
  key enabled: Option<bool>;
  key pending: Option<Option<bool>>;
end
```

A `key` declaration is storage schema metadata. It is not an object field,
constructor parameter, method slot, visibility boundary, or dot-property.
`key` remains an ordinary identifier outside the unambiguous
`key IDENT : Type;` class-member form.

The outer `Option<T>` on a key declaration describes key presence. The stored
value is exactly `T`. Therefore:

- `key name: Option<string>` stores `string`;
- `key pending: Option<Option<bool>>` stores `Option<bool>`;
- `get("pending")` and `take("pending")` return
  `Option<Option<bool>>`, preserving missing, stored `None`, and stored
  `Some(value)` as distinct states.

## Operations

For `Map<K,V>` and typed open Map schema instances:

- `m[k]`: required read. Returns `V`; a missing key raises an error.
- `m[k] = v`: mutation through an exclusively writable map binding.
- `m.has(k)`: one-operation membership check returning `bool`.
- `m.get(k)`: one-operation non-consuming lookup returning `Option<V>`.
- `m.take(k)`: one-operation removal/ownership transfer returning
  `Option<V>`.
- `m.remove(k)`: one-operation delete returning whether an entry existed.

The runtime never implements `get` or `take` as a public
`containsKey/get/remove` sequence. `OresKeyedMap.lookup` and
`OresKeyedMap.take` resolve presence and value while holding one map monitor.

## Open keys

A literal key declared by the schema is statically narrowed to its stored type.
A literal key not declared by the schema is open and reads as `unknown`.
A dynamic String read/take also returns an open value.

Dynamic writes are deliberately rejected in v1 because the runtime String may
name a schema-constrained key. Unknown literal extra-key writes are admitted
only for recursively Copy scalar/Option/Result values.

The runtime repeats schema validation before committing a write. The generic
map value domain remains `ANY` for an open-schema instance, but a key-aware
entry validator rejects malformed known-key values and non-Copy open-key
values. Transactional `putAll` applies the same validation before modifying
the destination.

## Ownership and isolation

Typed Maps reuse the existing affine Map restrictions:

- fresh construction may establish the single writable owner;
- rebinding, aliases, closure/deferred capture, call-argument escape, and
  returns fail closed until lifetime-aware moves/loans are available;
- `take` and `remove` require a directly owned mutable binding;
- typed Map allocation inside actor execution remains denied until actor-owned
  arena/quota support is implemented;
- open `unknown` is a source-level top type, distinct from the compiler's
  internal inference `Unknown`, and is not shared/sendable by default.

Schema payloads in v1 are restricted to recursively Copy `int`, `bool`,
`String`, `Symbol`, `Option`, and `Result`.

## Limits

This slice intentionally keeps schema classes free of physical fields,
constructors, interfaces, generic parameters, and user methods. Historical
`on_get` / `on_put` / `on_remove` callbacks from the stale prototype are
not revived yet; map operations are sealed runtime behavior.

The current hardened runtime foundation still names the concrete guest
container `Map<K,V>`. `HashMap<K,V>` subclassing is rejected with an
explicit diagnostic. The long-term hierarchy may make `Map` the read-only
protocol and `HashMap` the mutable concrete implementation, but that rename
must land as a coordinated backend/API change rather than an alias that hides
different ownership semantics.

Typed-open-map class identity is not currently reified in the runtime object;
`is`, `as`, and type patterns targeting a schema class therefore fail
closed rather than returning misleading results.

## Verification gate

Source code must pass focused parser/type/ownership/runtime tests and full
Maven verification on the exact Git tree. Cross-repository private source
mirrors are deny-by-default under
`.github/private-ci-approved-repositories.txt`; no destination may be used
until a maintainer/security-reviewed protected-branch change approves it.
Zero-step GitHub Actions runner-allocation failures are infrastructure results,
not test evidence.
