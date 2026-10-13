# Runtime-owned actor failure receipts and error type descriptors

Status: **staged Java/GraalVM source and JUnit tests, draft PR #811**.
GitHub Actions still fails at runner admission with zero executed steps; the
following has not passed JVM/GraalVM AOT or LLVM conformance. Do not
represent a failure receipt as fully delivered supervisor CONTROL.

## ActorFailureNotice (host-only data)

`ActorRuntime.ActorFailureNotice` is an immutable, runtime-created object
with a **private constructor**:

- `actorId` — runtime-owned UUID identity; never parsed from guest input
- `generation` — monotonic, runtime-local incarnation number, greater than 0
- `actorKind` — runtime-owned actor execution category
- `category` — `HOST_FAILURE` or `STRUCTURED_CANCELLATION`
- `guestError` — optional immutable diagnostic data, currently empty for
  host exceptions and cancellation; source guest typed errors are not lowered

A `ActorRef` retains at most **one** failure receipt for its generation.
The first terminal cause wins under its `lifecycleLock`: publication uses
atomic compare-and-set and never replaces an earlier notice. **Failures
during final cleanup use the same publication routine**, rather than
writing the terminal Throwable separately and omitting the notice.
Graceful termination publishes no failure receipt. A structured cancellation is not
a catchable `std/error.Error`.

`ActorRef.failureNotice()` requires host/supervisor context. Actor code is
denied; it cannot use its own ref to inspect supervisor diagnostics or
fabricate a control event. The notice does not retain the underlying
`Throwable`, `OresObject`, guest heap, function, method, callback, actor
reference, variable bindings, stack trace, or message text. Creation
does not call `Throwable.getMessage()`, `getCause()`, `toString()`, or
`getStackTrace()`. It is a **failure receipt**, not a serializer for
host exceptions and not a source-level `panic`.

**Not yet implemented:** enqueueing authenticated per-generation receipts
on the priority CONTROL path for a supervisor to consume exactly once.
The host-only `failureNotice()` accessor offers durable per-ref observation
without relying on user-mailbox capacity, but it does not itself deliver
the event. Future delivery must be bounded, scheduler-controlled, fenced,
and loss-aware when no supervisor is online.

## CoreErrorDescriptors (runtime-owned v1 metadata)

`CoreErrorDescriptors` is generated from the std/error manifest:
99 unique nominal names, stable numeric codes, and abstract flags.
Descriptors are private-constructed and published as immutable map
values. `requireKnown(name)` and `requireKnownCode(code)` return the same
catalog object, or reject an unknown code/name.

`GuestErrorWireCodec.supervisorCompactCoreSnapshot(descriptor, guestFields)`
takes a known descriptor, verifies it is the canonical registry instance
and concrete, and produces only `schema/code/name`. It never reads,
enumerates or invokes anything in `guestFields` (including guest code(),
serialization hooks, closures or arbitrary Java accessors). An abstract
class category cannot be sent as a concrete failure value.

A source-provided **name is not sufficient nominal proof**: real dispatch
must bind an actual `Ast.ClassDecl`/code-image identity to the canonical
descriptor during compiler linking, and register versioned custom error
types separately. Guest `code()` methods must never authenticate identity.
The descriptor map is only the core catalog trust anchor.

## Remaining release gates

1. Get Java 21 JVM/Native Image Actions **executing jobs**, not failing at
   GitHub runner admission. Verify JUnit `ActorFailureNoticeTest`,
   `CoreErrorDescriptorsTest`, `StdErrorCatalogTest`, and
   `ErrorTraceSerializationTest` and all affected actor lifecycle tests.
2. Implement authenticated type descriptors at import/link time, including
   custom `Error` subclasses and portable stable codes. Do not accept
   structurally identical impostors or guest-assigned metadata.
3. Integrate the data-only failure receipt with the real supervisor's
   priority CONTROL channel, with generation-fenced at-most-once creation
   and explicit acknowledgement/retry semantics for **exactly-once
   processing**. Preserve events under queue pressure or supervisor restart;
   do not silently drop notices.
4. Implement typed `throw`/catch, `raise`, `panic` with distinct effects.
   Capture a guest stack at the throw site, not construction, and preserve
   traces across suspension; panic bypasses actor-local interception.
5. Enforce redaction independently at all remote/cross-tenant boundaries.
   Verify JSON/MessagePack/Protobuf against independent decoders.
6. Mirror the behavior on native LLVM/wasm and GraalVM Native Image. The
   versioned wire schema is in `contracts/v1/guest-error.proto`.

The compact error snapshot is **data**; CONTROL remains a separate
runtime-authenticated channel with no executable guest payload.
