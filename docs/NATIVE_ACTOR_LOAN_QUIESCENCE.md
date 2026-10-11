# Actor-native FFM loans and physical-memory accounting

This experimental OresVM/Graal runtime branch strengthens
`ExplicitNativeI64Region` from PR #563.

## Loan-gated deterministic native release

`region.borrow(slot)` creates an opaque host-side loan. An actor may use
`loan.read()` only while it is the currently executing semantic owner of
the region and the loan has not been released. The host completion worker may
call `loan.close()` on any thread without dereferencing native memory.
A loan is idempotent to close. A slot cannot be dropped while loans refer
to it.

`region.close()` and actor termination immediately **fence further access**.
If loans remain, the native FFM Arena is not physically released. Closing
the last loan performs `Arena.close()`, invalidates all slots and releases
the region's quota. `region.physicallyClosed()` is separate from the
logical `closed()` state so callers cannot confuse revocation with release.

## Quota hardening

A pending loan may retain physical native memory beyond actor shutdown.
Previously the encompassing `ActorMemorySlice.close()` would reset the
aggregate runtime memory budget even though the FFM memory was still alive.
That would allow another actor to consume the same aggregate bytes twice.

Native FFM reservations are now marked `retainPhysicalNativeBytes`.
At actor exit, the slice releases nonpinned reservations but **keeps
still-live FFM bytes charged to the runtime-wide heap ceiling**. Completion
of the final loan releases the aggregate charge, even if the original
actor and its memory slice have already closed. Tests inspect
`ActorRuntime.privateMemoryBytes()` before and after this transition.

## Remaining isolation and compiler gates

- This is host-FFM native data memory; JVM/Graal Java wrappers are still
  garbage collected. No independent actor tracing heap or zero JVM GC is
  claimed.
- No raw MemorySegment leaves the host runtime API, but a future source
  compiler must ensure that guest-language borrows cannot escape as
  references to mutable Java state. Host code holding an opaque loan remains
  trusted; the public host API is not itself an unforgeable compiler witness.
- Cross-isolate untrusted JIT dispatch is not implemented by these changes.
- Full scheduler quiescence, real native actor classes, async/FFI cancellation
  fencing, source-level object layout lowering, Native Image reflection and
  physical isolation tests remain release gates.
- FFM scope-close failures are fatal to the cleanup path and must not
  masquerade as successfully released physical memory or quota refunds.

The funded GraalVM JDK 25 exact-blob CI validates actor shutdown with a
pending loan, foreign actor confinement, multiple-loan completion, aggregate
native reservation persistence and release, alongside previous actor GC
boundary and isolate admission tests.


## Shared JVM/LLVM source-field logical descriptor (v1)

`NativeActorFieldLayoutV1` implements the versioned i64 logical
actor-field contract in `contracts/v1/ACTOR_FIELD_LAYOUT_V1.md`:
source-ordered fields, zero-based indexes, `let` mutability versus
`const`/`val` readonly status, bounded field counts, identifier
validation and a deterministic FNV64 fingerprint. Java's `Counter`
and `Readonly` reference hashes are checked against the corresponding
LLVM-generated layout hashes. A standalone executable validates this
same code on a funded JDK runner.

This is a **descriptor and admission contract**, not a claim that the
Java compiler now routes all source actor fields into
`ExplicitNativeI64Region`, nor that JVM host control objects avoid
tracing GC. Java's existing FFM loan/physical-close/quota retention
checks remain separate, necessary lifecycle primitives. Further work
must attach this descriptor to actual source class/struct allocation,
ActorGroup exclusive turns, and end-to-end await/FFI cancellation.


## Idiomatic source lifecycle observers

The runtime's `ActorRef.get_ready()` and `ActorRef.get_done()` are
zero-argument methods returning the **same runtime-owned**
`OresFuture<Void>` tickets as the compatibility `ready()` and
`done()` host observer APIs. They do not create stop or cancellation
authority; `get_done()` still resolves only after the actor runtime
completes physical finalization.

The guest-source member dispatcher in `OresEvalRootNode` explicitly
resolves `worker.get_ready()` and `worker.get_done()` as zero-arity
invocations. The compiler's existing TypeChecker already rejects legacy property-style
`await worker.ready` and `await worker.done`; source fixtures have now
been migrated to canonical method-call syntax. The Java host's
`ready()` and `done()` APIs remain supported for native host callers.

The companion tests are
`ActorReadinessLifecycleTest` (host observer identity, cancellation
denial, readiness and physical done ordering) and
`SourceActorSpawnExecutionTest` (two independent source workers
awaiting lifecycle methods). This is lifecycle spelling/semantics
parity, **not** a claim that Java/Graal native FFM fields have been
connected to the LLVM typed memory-layout descriptor in every actor.


## Typed actor mailbox contract

Actors declare accepted message payload types in their actor-local
`receive(ActorMail<T> mail): void` handler. For instance,
`ActorMail<{delta: int} | {reset: int}>` declares two structurally typed
message variants. The public ActorRef exposes **`send(message)`** for
user mail delivery; the TypeChecker determines `T` from the target actor
and statically requires the send payload to conform to that type.
`send(infer struct{delta: 20})` and
`send(infer struct{reset: 1})` are accepted; missing keys or mistyped
required fields are rejected at compile time. Structural record
subtyping allows additional fields when required fields match; this is
a deliberate difference from exact-closed-message schema validation
and must be revisited for wire-format/schema enforcement.

The `receive` hook itself is reserved/actor-local and direct guest
`worker.increment(...)` is not permitted. Native runtime may execute
compiler-generated receive code only after admission to that actor's
single-flight mailbox lane. Lifecycle methods `get_ready()` and
`get_done()` are not message transports.

The `ActorMailTypeContractTest` verifies positive and negative typed
inferred-record delivery, union acceptance, direct-method-call
rejection and mailbox hook access constraints. The LLVM companion
currently supports only a single typed i64 field, not Java's union
record message grammar.
