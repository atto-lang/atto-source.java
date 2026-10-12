# Fixed actor scalar record ABI on GraalVM

This implementation adds a compiler-facing typed actor object layout
without allocating guest field payloads in ordinary Java objects.

`ActorMemorySlice.newNativeI64Record(typeName, fields)` validates the
complete fixed `i64` schema before native allocation. Fields have
unique identifiers and deterministic declaration order; duplicate
fields, invalid names, empty schemas and excessive field counts fail
without charging the actor's native heap budget.

The backing is the actor's `ExplicitNativeI64Region`, using Java 25
FFM native memory with explicit physical `Arena.close()`.
`ActorNativeI64Record.read(field)` checks the semantic actor owner
through the existing native region guard and rejects foreign actors.
The two-argument `Field(name, value)` constructor remains immutable by
default. `Field(name, value, true)` opts into mutable native fields,
which can be updated with `record.write(name, value)` only in the owner
actor's admitted turn. Writes to immutable fields or native storage
with outstanding loans are rejected, even when the actor is resumed
on a new carrier thread.
`borrow(field)` returns a host-managed loan that can survive a
suspended actor turn but may not be dereferenced outside the owner turn.
Actor retirement fences reads immediately; native bytes and
aggregate quota remain physically charged until the last loan releases.

This is a matching *field-layout semantics* prototype for LLVM's
source-level `define actor` i64 compiler path. It is not yet
Oreslang Truffle AST allocation lowering or a generic struct/class
layout compiler. Host wrapper/control plane remains JVM GC-managed,
and untrusted actors still require their physical JIT isolate router
before a separate collector/security boundary can be claimed.

The funded GraalVM 25 tests exercise the same field values across
mailbox turns, declaration order, invalid layouts with quota rollback,
foreign actor denial, pinned loans during shutdown, and stateless
UNTRUSTED actor admission. Native Image AOT and the actual guest
language-isolate package require separate validation.
