# Provider-neutral Lambda invocation v1

Oreslang's first executable function-host boundary is intentionally smaller
than a cloud deployment system.

## ABI

A v1 function is a public synchronous Oreslang function with exactly one
`String` argument and a `String` result. A module-qualified export is
recommended:

```ores
define module handler as
  pub fnc invoke(String event): String {
    return event;
  }
end
```

The Java/Graal host invokes that export without requiring a `main()`
entrypoint:

```sh
printf '%s' 'ping' | oreslang --lambda-invoke=handler.invoke handler.ores
```

The response is written to host stdout. Guest stdout/stderr are captured
separately and forwarded to host stderr, so function logs cannot corrupt the
response channel.

## Isolation

`--lambda-invoke` always uses `IsolatePolicy.strictFaas()` and
`RuntimePermissions.denyAll()`.

The v1 command therefore rejects `--allow=...`, Deno-style
`--allow-*`/`--deny-*` permission overrides, and Java host-class grants.
Provider adapters must not widen guest authority implicitly. Future explicit
capability profiles require a separately reviewed contract.

Host input and returned response are each capped at 1 MiB of UTF-8 data before
they cross the function ABI. The Graal adversarial sandbox additionally applies
the strict FaaS heap, wall-time, thread, stdout and stderr bounds.

## What this proves

This is a real Oreslang execution boundary: the normal linked-program loader
collects imports, compiles and type-checks the source graph, applies capability
checks, links and initializes all code units, then invokes the requested public
function in the selected entry code unit.

It does **not** yet prove:

- AWS Lambda `bootstrap` or OCI packaging;
- an `oreslang-faas` network service;
- warm-isolate pooling or multi-invocation reuse;
- async function ABI support;
- typed JSON/MessagePack provider events;
- LLVM backend parity;
- deploy, rollback, cold-start, concurrency or provider receipts.

Those remain separate promotion gates. Provider adapters should consume this
ABI rather than implementing a second evaluator or bypassing Oreslang policy.
