# Actor-local I/O contract

Actor execution uses the implicit runtime capability `io`. It is not a field
on `self`, and process `stdio` never changes meaning based on execution
context.

Canonical source split:

- root/process: `stdio.*` and root `print(...)`;
- actor: `io.in`, `io.out`, `io.err`, `io.events`, `io.meta`.

Actor code cannot access process `stdio` or root `print`. The names `io`,
`stdio`, and `print` are reserved throughout actor lexical scopes so a local
binding cannot redirect the execution capability.

## Java/Graal v1

`io.out.write(value) -> Future<void>` is direct-call-only and routes through
the existing bounded, actor-generation-owned `ActorOutputStream`. It does not
own or write a host stdout descriptor. The V1 Future acknowledges bounded
logical admission, not OS flush or durable storage.

The output stream is data-only. Live `ActorRef`, actor-group, shared-memory and
mutex capabilities cannot be published through `self.send` or
`io.out.write`; mailbox `ActorRef.send` remains a separate explicit
capability transport and retains its existing Send rules.

The Future from `io.out.write` must be awaited or explicitly retained.
Capturing `io.out.write` as an ordinary function value is rejected.

`io.in`, `io.err`, `io.events`, and `io.meta` are reserved on the Java
source surface and fail closed until their bounded routers are connected.
They must never fall back to process stdin/stderr/stdout.

## Cross-backend runtime contract

Each actor generation receives independent bounded logical channels:

- input/output: owned byte/data records with explicit backpressure;
- errors: typed diagnostics, distinct from process stderr;
- events: typed bounded notifications;
- metadata: immutable/versioned bounded snapshots.

Every operation is fenced by actor identity and generation (plus backend-local
unforgeable authority where applicable). Restart/retirement revokes stale
handles and must not expose queued data from an old generation.

LLVM, .NET and Wasm may land this runtime substrate before their source
frontends admit `io.*`. Cranelift must reject `io.*` before machine-code
emission until native actor IR/ABI exists. A rejection or host-only runtime
test is not source-level parity.

No backend may introduce a second physical stdin/stdout/stderr owner. Process
stdin requires an explicit reader lease or bounded fan-out policy.

Promotion requires exact-head, nonzero-step execution for the backend's real
compiler/runtime. Parser-only, manifest-only, host reference-model, zero-step,
or stub compiler results do not establish source-level parity.
