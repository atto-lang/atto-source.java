# JSON value runtime descriptor foundation

This slice implements the first source-facing `alloc/json` contract from
`oreslang-common-source#111`. It does **not** make JSON a core language
builtin. The source surface exposes the opaque compiler-owned `JsonValue`
type, `Map<String, JsonValue>` allocation, compiler-owned construction
factories, and a bounded stable stringify boundary through the reserved
`alloc/json` import. Strict parsing and generated typed codecs remain later
slices.

## Boundary

`core` remains allocator-free. JSON remains an allocator-backed portable
library feature. The compiler-owned source boundary is now:

```ores
import class JsonValue from "alloc/json";

val Map<String, JsonValue> values = new Map<String, JsonValue>();
```

The `alloc/*` namespace resolves before project roots or `ORESLANG_PATH`,
so project files cannot shadow `alloc/json`. The imported type is opaque:
`new JsonValue()` is rejected by both the type checker and evaluator runtime
construction paths. An aliased import does not unlock the canonical
`JsonValue` Map domain, and a user class with the same name has no JSON
authority.

The Java runtime still uses the closed recursive `OresJsonValue` carrier so
later `Json.*` lowering can use the canonical typed Map without falling back
to host `Object`, `java.util.Map`, reflection, or guest-visible `ANY`.

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
- binary floating-point input, including finite doubles, is rejected until an
  exact decimal carrier is supplied; NaN and infinities are therefore rejected
  at the same boundary;
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

The resolver/type-admission slice is now present. This exact unaliased import:

```ores
import class JsonValue from "alloc/json";
```

admits the opaque nominal `JsonValue` type and a fresh
`Map<String, JsonValue>`. The runtime constructs that Map with
`OresKeyedMap.ValueDomain.JSON`; Symbol or String|Symbol key domains reject.

Construction and stable externalization are now available only through the
exact compiler-owned `Json` namespace:

```ores
import class JsonValue from "alloc/json";
import class Json from "alloc/json";

val JsonValue scalar = Json.of("value");
val JsonValue nothing = Json.null;
val JsonValue list = Json.array([Json.of(1), Json.of(2)]);

val Map<String, JsonValue> fields = new Map<String, JsonValue>();
fields["name"] = Json.of("Ada");
val String encoded = Json.stringify(fields);
val JsonValue object = Json.object(fields);
```

`Json.of` currently admits bool, integer and String source scalars. Floating
source values fail closed until the source runtime carries an exact decimal
scalar; the runtime carrier itself already preserves exact `BigDecimal`
numbers. `Json.array` and `Json.object` consume their owned containers,
while `Json.stringify` is a non-consuming read.

`new JsonValue()` and `new Json()` remain forbidden. Strict
`Json.parse(...)`, `Json.decode<T>(...)`, and generated typed codecs are
still unavailable.

The runtime/evaluator half of commit-aware ownership transfer is now present.
`OresKeyedMap.attemptOwnedPut` returns a linear internal result:

- on rejection, `takeRejectedValue()` returns the exact offered object by
  identity exactly once and live Map storage is unchanged;
- on commit, `takePrevious()` returns the displaced value exactly once;
- future JSON-domain indexed writes and named `put` lower through that path,
  recover the rejected candidate before rethrowing admission failure, and
  consume the previous-value token after commit.

The ownership checker now models the conditional exceptional edge only for
the compiler-owned `JsonValue` Map value descriptor, and only for the narrow
source shapes we can prove today. A same-named user class or unrelated future
move-only Map domain does not inherit rollback semantics.

The admitted source shapes are:

- terminal direct indexed assignment `m[key] = value;`;
- terminal direct named `m.put(key, value);`;
- terminal direct binding from `m.put(key, value)`.

For those forms, the normal path consumes the move-only candidate while the
innermost admission-failure catch sees the candidate restored. A non-terminal
write remains conservative, as do top-level-defer/live-MutexGuard cases.
Nested tries suppress recovery only for the innermost catch; every enclosing
try still observes the committed move so a later outer failure cannot reuse it.

Source `Map<String, JsonValue>` is admitted only through the exact
compiler-owned `alloc/json` import. `Json.stringify` revalidates the complete
selected root and traverses it under the same private JSON graph-stability
guard used by `put`, `putAll`, `take/remove`, and `clear`, so concurrent
mutation cannot produce a torn encoding. Output is bounded by a UTF-8 byte
quota and traversal invokes no guest callbacks.

The remaining source work is strict parsing followed by generated typed codecs
(record, struct, `Option<T>`/collections, then explicit class factories).
Scalar Map domains remain unchanged.
