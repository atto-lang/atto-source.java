# Per-actor physical garbage-collector heaps (opt-in, native isolate backend)

## What actually runs in its own physical heap

`DedicatedUntrustedActorIsolates` executes a **source-defined UNTRUSTED
actor** inside its own Graal polyglot `Engine` with `spawnIsolate(true)`.
Each actor gets a fresh engine, a fresh adversarial Truffle context and a
distinct native-image heap/collector. There is **no fallback** to the
host/JVM `ActorRuntime` if the Oreslang language-isolate artifact is absent.

A host supervisor can use:

```java
try (var actors = new DedicatedUntrustedActorIsolates(256L << 20, 16);
     var actor = actors.spawn(source, "worker-v1", "IsolatedWorker")) {
    actor.send("work"); // only copied scalars may cross the boundary
}
```

Unlike `SharedUntrustedIsolatePool`, one actor is admitted to one isolated
engine, and the adapter rejects second admissions. Both implementations
share the same safe `UntrustedJitCodeUnit` scalar-only gateway but NOT the
physical collector.

The host-facing actor proxy exposes only identity, scalar send, status,
cancel and close. It never exposes a guest Java object, `Value`, or raw
`Context`. The source code is parsed, linked and instantiated **inside** the
guest isolate rather than the parent JVM. Closing the actor cancels the
code unit, closes its context and its own engine; the supervisor can still
use its main heap and sibling isolates.

Native support must be provisioned with Oreslang's Graal language-isolate
image, which is built separately. GraalVM 25.3 APIs require that an engine
be explicitly configured to spawn a polyglot isolate; sharing one engine
between contexts shares its physical heap and collector.

## What is NOT independently collected yet

Ordinary `ActorRuntime.spawnPrivate`, `spawnShared` and
`spawnUntrusted` still run in the parent Truffle context. They have
owner-checked direct-memory regions, quotas, and explicit GC-control
containment, but their Java objects, async continuations, mailboxes,
compiler data structures and host object wrappers are subject to JVM-wide
garbage collection. **Do not silently redirect actor API calls** through
this dedicated bridge: a host `BehaviorFactory` closure cannot be safely
serialized as untrusted source and the source-level `spawn` lowering has
not been rewired.

This implementation deliberately supports source UNTRUSTED actors only.
A SHARED actor needs independent guest heap for *private actor state* plus
an explicitly separately owned cross-isolate shared-value service
(synchronized shared regions or serialized messages). A PRIVATE/isoactor
needs compilation and rooting of its state in a dedicated isolate/arena.
Neither should be advertised as having its own physical collector until
source spawn and actor message routing uses a proven backend.

For hostile tenants, use Graal external isolate mode or a supervisor-
managed OS worker process for stronger address-space isolation than
an in-process isolate. Do not confuse language sandbox policy with OS
memory protection.

## Correctness and safeguards

- No `Engine` is shared by two dedicated actor leases.
- Actor creation and destruction are serialized per supervisor pool;
  capacity is bounded and failure to construct guest code cleans up
  engine and context immediately.
- Each guest lease accepts **exactly one** remote actor command.
- A second actor admission is rejected even after a failed first
  admission, preventing partially initialized guest-state reuse.
- `UntrustedJitCodeUnit` copies bounded scalars and rejects arbitrary
  objects, byte buffers and actor references.
- The engine identity is checked against the context. A fallback ordinary
  JVM context is never treated as proof of a separate collector.
- The per-actor untrusted policy remains distinct from the enclosing
  context's cumulative CPU-time limit.
- Actor `gc()` still must not issue process-wide `System.gc()` from
  the host API; separate isolates use their own physical collector.

## Validation levels

Unconditional unit tests cover configuration limits, zero-actor start,
closure, argument validation and (on a runner without the native image)
fail-closed startup. The *real* source-execution proof has an opt-in test:

`ORES_TEST_DEDICATED_ACTOR_ISOLATE=true`

On a Graal runner **with the native Oreslang language-isolate image**, this
test must execute two source actors, verify their native engine identity
is different, send/cancel through copied payloads, and observe teardown.
The test is not passed unless the opted-in native-image job actually
executes. Then a second phase should measure actor A's isolated GC and
sibling/host latency, and verify ASAN, memory quota and root-liveness for
continuations, hot loading and cancellation.

The LLVM native actor-owned arena implementation is a distinct, currently
unintegrated backend and must not be counted as validation of the Graal
polyglot isolate adapter.
