# Sendable and Serializable boundaries — Oreslang v1

**Status (2026-10-15):** Normative security and design contract for Java/GraalVM and LLVM implementation work. This document does **not** claim that generic trait derivation, Protobuf/JSON network codecs, or native actor-mailbox parity have been implemented. Existing runtime transport and the compiler's current admission gates remain authoritative until executable conformance proves this design.

## 1. Separate questions: movement, encoding, authority

The properties are independent:

- **`Sendable<T>`** is compiler-proven *ownership/lifetime* evidence that an owned `T` can cross a specified actor DATA boundary without mutable aliasing, dangling borrows, foreign runtime handles, or changes to the observable value. A type is not made Sendable by declaring an interface or implementing methods with that name. The compiler seals evidence and checks recursively after resolving imports, generic substitutions and nominal identity. `Sendable` is not synonymous with `SharedSafe`: a uniquely moved mutable buffer may be Sendable but cannot be concurrently shared.
- **`Serializable<T, Format>`** is codec evidence that a logically represented `T` may be encoded to and decoded from a validated, versioned, bounded data representation for that `Format`. This property alone does **not** grant actor ownership, networking permissions, shared memory, or supervisor privileges. Codecs are not themselves Sendable data.
- **`ActorMessageCodec<T>`** is a compiler/runtime-approved *DATA* schema binding for an actor mailbox. It has a fixed message domain and receiver schema revision; the runtime owns the identity, byte budget, staging, and type selector. Even if a user type has a network codec, it cannot self-register as an actor mailbox codec.
- **`NetworkCodec<T, Format>`** is a user- or compiler-provided codec for external protocol payloads. Its encode/decode functions are data transformations. Socket/file permissions are separately checked on every I/O operation.
- **Supervisor CONTROL capabilities** (stop, kill, supervise, join, spawn, revoke, rotate, restart, privileged ActorRef, lifecycle and scheduling rights) are runtime-minted opaque authorities. Neither `Sendable` nor `Serializable`, nor a userland schema declaration, may mint, serialize, clone or decode this authority. CONTROL never enters ordinary actor DATA, even nested in records, Option/Result, maps, error payloads, or generics.

A correctly implemented network serializer can produce a DTO snapshot from a *non*-Sendable object, but the underlying host handle itself does not become serializable or sendable. Conversely a uniquely owned in-process arena region might eventually be safely movable between trusted actors without having any external network representation.

## 2. Boundary matrix

| Source → destination | Admission | Transport | Authority |
| --- | --- | --- | --- |
| Supervisor → actor DATA | Owned, recursively Sendable, receiver DATA contract | Current source implementation uses actor mailbox; portable domains require compiler-approved ActorMessageCodec | No CONTROL rights in payload |
| Actor → actor DATA, same trusted runtime | Same as above; snapshot before admission or proven exclusive move | Copy/freeze, approved MessagePack frame, or a proven-equivalent zero-copy fast path | No ambient supervisor rights |
| Trusted actor → pooled untrusted JIT isolate | Owned, fully data-only and codec-validated; no host pointer or shared capability | Bounded, owned bytes and isolated decoding | No handle/proxy injection |
| Actor → remote actor DATA | Sendable **and** portable, versioned serialization; authenticated sender/recipient/generation | Authenticated framed protocol with codec agreement | Remote CONTROL requires a separate protocol, not a DATA field |
| Application → network socket/file | Serializable for the explicitly selected format; valid ownership of the encoded snapshot | Bounded JSON/MessagePack/Protobuf bytes | Runtime network/file permission checked independently |
| Supervisor → actor CONTROL | Supervisor runtime capability policy, incarnation/epoch and explicit target | Separate sealed runtime-controlled mailbox | One-use/revocable, not a general-purpose codec |

Existing *host* `ActorRuntime` APIs accept selected runtime-affine `ActorRef` and other audited handles through a distinct trusted capability path. This is **not** permission to accept the same objects in source-level portable DATA or in external network codecs. Until CONTROL/DATA splitting is end-to-end, reject unsupported paths rather than interpreting host API exceptions as general `Sendable` evidence.

## 3. Source-level direction (ILLUSTRATIVE, not presently accepted syntax)

```ores
// Compiler-verified marker, NOT a user-implementable escape hatch.
define trait Sendable<T> as end

// Format-indexed data codecs, not transport capabilities:
define interface Serializable<T, Format> as
  fnc encode(borrow T value): Result<OwnedBytes, EncodeError>;
  fnc decode(borrow Bytes bytes): Result<T, DecodeError>;
end

// A protocol-specific network codec can additionally validate envelope/schema.
define interface NetworkCodec<T, Format> as
  fnc encode_network(borrow T value): Result<OwnedBytes, EncodeError>;
  fnc decode_network(borrow Bytes bytes): Result<T, DecodeError>;
end

// Deliberately NOT user-controllable: actor schema IDs, origin, recipient,
// permissions, and DATA/CONTROL envelope headers.
```

`OwnedBytes`, `Bytes`, `Format`, `derive`, and the trait syntax above are conceptual ABI directions. Do not treat these snippets as executable compiler input or authorize `@Serializable` / `@Sendable` by ignoring annotations. Parser reservations must fail closed until derivation and validation are implemented.

The conceptual compiler obligations are:

1. Resolve fully qualified nominal identities before proof; never trust a user-defined `Sendable` type name or a textual `impl` to confer authority.
2. Derive value-only Sendable recursively, respecting immutability, unique moves, loan lifetime, region ownership, generic substitutions, class fields, field visibility, FFI, unsafe host values, capability types, cycles, and closure captures. No blanket instance for `Any`, unbound generics or arbitrary nominal classes.
3. Distinguish `rt take` and `rt copy`: for a move, invalidate the sender only on **successful** linearized mailbox admission; on validation/quota/cancellation failure the sender retains its value. If an implementation cannot guarantee that atomicity, reject the move.
4. A `Serializable` implementation must be a type- and schema-bound value codec with explicit failure and resource limits; arbitrary constructors, reflection, class-name dispatch and runtime-selected deserializers are prohibited.
5. Explicit `ActorMail<T>` or `ActorRef.send(T)` must require the *registered* receiving DATA contract and runtime proof. Generic `Serializable<T, JSON>` does not satisfy `ActorMessageCodec<T>`.
6. Separate Sendable data from runtime-defined capability transport. No user-defined codec or error payload may smuggle an ActorRef, ActorGroupJoinCapability, child process, file/socket, pointer, iterator, guard, future, shared mutable cell, GPU/Region handle or supervisor command.

## 4. Codec profile contracts

### MessagePack (default portable actor DATA)

Retain MessagePack v2's compiler-committed recursive shape/schema identity, minimal encodings, strict UTF-8, bounded nesting/nodes/bytes and explicit nominal variants. `[wire_version, schema_id, shape_hash, typed_payload]` is a **data schema envelope**: its SHA-256 shape commitment is not a MAC or origin authentication. Codec selection is pinned by the receiving mailbox contract, *never* chosen from untrusted payload type strings. Keep opaque CONTROL authorities out of the data frame. Actor runtime admission, memory quota, incarnation and cancellation accounting remain mandatory.

### JSON

Require strict UTF-8 and JSON token/string decoding, bounded nesting/bytes/collection length, rejection of duplicate object keys (or an explicitly pinned schema policy), exact integer/decimal semantics without silent precision loss, and deterministic field/default/null handling. Reject NaN/Infinity, invalid Unicode, unknown discriminators, implicit polymorphic class names, and undeclared capability fields. Any canonicalization profile must be explicitly versioned; ordinary JSON output order is not proof of canonical identity.

### Protobuf

Bind to a compiled descriptor identity and schema version (message full name alone is insufficient); preserve `oneof` discriminators and explicit presence, validate numeric ranges and length-delimited fields before allocation, and apply a specified unknown-field/extension policy for each trust boundary. Unsupported groups, unexpected extensions, schema conflicts, deeply nested messages and excessively repeated fields fail closed. Deterministic protobuf serialization is not automatically a unique canonical byte representation, a signature, or authorization.

### Common envelope/security requirements

A format-specific decoder receives a fixed, trusted descriptor from code/config and returns a fully validated, owned data value; it cannot instantiate runtime capability types, execute guest constructors with ambient authority, or select an arbitrary plugin by incoming bytes. Enforce preallocation limits, per-message and per-actor quotas, total decompression limits, deadlines, bounded error output, and cancellation rollback. Remote transport requires authenticated provenance, recipient incarnation/generation fencing, replay control and separate confidentiality/integrity protection.

## 5. Admission / lifecycle invariants

- DATA snapshots are fixed **before** queue admission. A mutable source graph concurrently modified during copying is not a proven snapshot; synchronize, exclusively own, or fail closed. Graph validation then copy must not leave validation-to-use races.
- The actor receives an independent owned graph. A move is not a shallow alias; a zero-copy region transfer must invalidate all sender writable handles, prevent live borrows, and account ownership in exactly one heap.
- Reserve queue capacity and bytes before publication; rollback once on every failure. Concurrent stop, cancel, send, pop, actor restart and await must not double-deliver, double-free, leak quota, or resurrect stale authority.
- Reply/error output travels through DATA only if its type and payload are admitted. A panic, supervision action or lifecycle authority is a separate runtime-generated CONTROL event.
- An actor `await` does not relax its state ownership or turn exclusivity. No codec callback may execute during privileged scheduler/CONTROL admission or import runtime authority.

## 6. Release and conformance gates

1. Negative compiler cases: unknown/generic/borrowed/identity-bearing values, nested capabilities, qualified names, malformed `Option`/`Result` arities, callbacks and codec interfaces as actor DATA; maintain legitimate scalar/list/tuple/record DATA.
2. Sendable vs SharedSafe distinction: exclusive region ownership move, use-after-move, live-borrow rejection, shared immutable value, and explicit trusted-only capability transfer.
3. Strict JSON: UTF-8, duplicate keys, Unicode escapes, numeric exactness, NaN, unknown type selector, oversized arrays/maps, recursion/cycles and malicious decoder constructors.
4. MessagePack: existing Java/native binary golden vectors, exact schema/digest, version downgrade, nonminimal integers/strings/arrays, malformed length, unknown variants and no-intern symbol policy.
5. Protobuf: `oneof`, presence/null/default semantics, unknown fields/extensions, packed/repeated size, version drift, integer overflow and malicious length prefixes.
6. Actual supervisor → actor DATA → supervisor output, private/shared/untrusted recipients, positive and negative nesting, concurrent source mutation, quotas, stop/cancel races and recipient generation.
7. Independent Java/GraalVM JUnit/Maven, AOT Native Image, LLVM CMake/CTest, sanitizer/fuzz corpus and exact-tree provenance. No claim of source-level LLVM parity until its compiler lowers actor DATA and ownership into the real scheduler.

**Known current gaps:** generic trait evidence and derive; compile-owned cross-module schema registry; network JSON/Protobuf codec code generation; LLVM source actor codec lowering; portable `Option/Result` mailbox representation and full ABI parity; complete Graal native-image and stress verification. Host `ActorRuntime` freeze/copy semantics and standalone MessagePack codecs are useful components, not equivalent evidence of the complete design.

Related: Java draft #1026, Java draft #623/#645, Java issue #899, LLVM messagepack transport audit and native codec tests.
