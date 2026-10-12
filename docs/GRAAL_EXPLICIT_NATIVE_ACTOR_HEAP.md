# Explicit FFM native actor heap slice (GraalVM Java 25)

OresVM can now allocate typed `i64` guest data in a per-actor
**explicitly closed native arena**, without using a DirectByteBuffer cleaner.

Inside a SHARED, PRIVATE or UNTRUSTED actor:
`ctx.actorOwnedMemory().orElseThrow().newExplicitNativeI64Region(slots)`
returns a bounded `ExplicitNativeI64Region`. The API provides
`allocate`, `read`, `write`, `drop`, `close` and checked generation
handles. Each region charges its byte count to the owning actor's
`ActorMemorySlice` quota and is automatically retired at actor finalization.

## Memory release and portability

The region resolves `java.lang.foreign.Arena.ofShared()` and
`MemorySegment.get/set` reflectively to retain the project's Java 21
compiler target while executing on Java 25. The FFM segment uses
little-endian 64-bit layout and is never exposed to guest code.

Calling `Arena.close()` invalidates the scope and releases the owned
native allocation through the FFM API. This provides a deterministic native
*data-lifetime* foundation even when the actor moves among physical carrier
threads: semantic `ActorId` governs access, not `Arena.ofConfined()`
thread identity. The API deliberately fails closed if supported FFM is
unavailable; it does not silently fall back to a managed DirectByteBuffer.

Java wrappers, actor state, guest interpreter and the Graal/Native Image Java
heap remain managed by the host collector. This is **not** independent
per-actor GC, zero JVM GC pauses, or a replacement for the separate untrusted
JIT isolate router. Native Image reflective FFM metadata and AOT packaging
must still be validated: passing hosted GraalVM JDK25 does not certify
Native Image operation.

## Ownership / security boundary

Typed native slot handles carry a private region identity, integer slot and
generation. A dropped slot is zeroed before reuse. Cross-actor dereferences,
expired slot handles, buffer over-capacity, and operations on a closed region
all fail. Ordinary mailbox transport must copy or transfer typed data; it
must never send a raw native MemorySegment.

On actor termination, the enclosing memory slice closes every still-live
FFM region before revoking the actor heap's final quota accounting. There
must be no outstanding async/FFI native leases when this happens. As source
objects, borrow checking, and native opaque handles are wired in later,
compiler-enforced actor quiescence will be required before physical release.

The equivalent LLVM branch uses a separate bounded native region per
compiled function activation, emitting explicit ownership drops and
rejecting tracing-GC calls. Shared compiler semantics and an actor-persistent
native heap remain future integration gates.
