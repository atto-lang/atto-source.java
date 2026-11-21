# alloc/json Map source surface

Status: **staged, fail-closed foundation**

This slice exposes the first guest-source use of the allocator-backed JSON
runtime without making JSON ambient in allocator-free core.

## Admission gate

The JSON Map domain is available only after the exact canonical module import:

```ores
import module Json from "alloc/json";
```

The import must bind the local module name `Json`. Merely spelling
`JsonValue`, importing a differently named module, or aliasing the canonical
module does not grant the JSON runtime type capability.

## Enabled surface

```ores
val Map<String, JsonValue> object = new Map<String, JsonValue>();
object["name"] = "Ada";
object["age"] = 37;
object["active"] = true;
object["score"] = 1.25;
object.put("age", 38);
```

Only String keys are legal. Source String/Signed-integer/finite-float/decimal
and bool values are lifted into the closed `OresJsonValue` runtime carrier at
the Map mutation boundary. Both indexed assignment and named `put` commit
through the canonical checked Map runtime.

## Deliberately not enabled yet

- standalone declared `JsonValue` bindings;
- `JsonValue<T>` or any generic JsonValue spelling;
- implicit Symbol-to-String JSON keys;
- arrays/records/classes/Maps implicitly lifted into JsonValue;
- moving an already-owned JsonValue into another JSON Map;
- `Json.parse`, `Json.stringify`, or typed `Json.decode<T>`.

Existing owned JsonValue transfer remains blocked until compiler lowering uses
`OresKeyedMap.attemptOwnedPut` and can restore the exact offered value on a
failed commit. This avoids marking a move committed when graph/quota/domain
admission rejects the insertion.

## Runtime defense

The evaluator independently checks the canonical alloc/json import before it
will allocate a JSON-domain Map. This is a defense against malformed or
prechecked ASTs; static type admission alone is not treated as runtime
authority.

The recursive JSON Map runtime continues to reject cycles, malformed value
domains, quota violations, and hostile bulk mutation behavior under the
existing #1787 hardening.
